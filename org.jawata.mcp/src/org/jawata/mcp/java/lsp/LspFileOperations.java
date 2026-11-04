package org.jawata.mcp.java.lsp;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the editor changes before it renames or moves Java files, so the code still compiles afterwards (Sprint 28h, S16c and S16f 1): a
 * file renamed in its folder renames its type, a file moved to another folder of its source root moves its type to the package that folder
 * names, and a folder renamed or moved is a package change. The editor performs the file operation itself, so the answer carries the text
 * edits only. A change to a file with unsaved editor text is refused, naming that file.
 */
final class LspFileOperations {

    private static final Logger log = LoggerFactory.getLogger(LspFileOperations.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final LspEdits edits;
    private final MovedFileEdits moved;

    LspFileOperations(ToolCalls tools, CallerIdentity caller, LspEdits edits, MovedFileEdits moved) {
        this.tools = tools;
        this.caller = caller;
        this.edits = edits;
        this.moved = moved;
    }

    /**
     * What the editor changes before it renames or moves Java files, so the code still compiles afterwards: a file renamed in its folder
     * renames its type, and a file moved to another folder of its source root moves its type to the package that folder names. The
     * editor performs the file operation itself, so the answer carries the text edits only. A change to a file with unsaved editor text
     * is refused, naming that file.
     */
    Object willRenameFiles(JsonNode params) throws ToolNotFoundException {
        Map<String, List<Object>> changes = new LinkedHashMap<>();
        for (JsonNode pair : params.path("files")) {
            String oldUri = pair.path("oldUri").asText();
            Path from = Path.of(URI.create(oldUri));
            Path to = Path.of(URI.create(pair.path("newUri").asText()));
            if (Files.isDirectory(from)) {
                addFolderChange(from, to, changes);
                continue;
            }
            ICompilationUnit unit = from.toString().endsWith(".java") && to.toString().endsWith(".java") ? edits.unit(oldUri) : null;
            if (unit != null) {
                addChangeFor(unit, from, to, changes);
            } else if (from.toString().endsWith(".java")) {
                log.warn("willRenameFiles: the model holds no unit for {}, so no code is changed along with it", from);
            }
        }
        return changes.isEmpty() ? null : Map.of("changes", changes);
    }

    /**
     * Adds to {@code changes} what moving {@code from} to {@code to} does to the code: the gate's staged edits for the other files, and
     * the edits to the file itself that its own rename or move would make but the editor's does not.
     */
    private void addChangeFor(ICompilationUnit unit, Path from, Path to, Map<String, List<Object>> changes) throws ToolNotFoundException {
        try {
            IType type = MovedFileEdits.primaryTypeOf(unit);
            if (type == null) {
                log.warn("willRenameFiles: the model holds no type for {}, so no code is changed along with it", from);
                return;
            }
            ISourceRange name = type.getNameRange();
            JavaRanges.Range at = JavaRanges.linesOf(unit.getSource()).span(name.getOffset(), name.getLength());
            ObjectNode arguments = JSON.createObjectNode().put("filePath", from.toString()).put("line", at.line()).put("column", at.column())
                .put("auto_apply", false).put("edits", true);
            if (from.getParent().equals(to.getParent())) {
                String newName = to.getFileName().toString().replaceFirst("\\.java$", "");
                if (newName.equals(type.getElementName())) {
                    return;
                }
                edits.refuseIfUnsaved(from.toUri().toString(), from, "file operation");
                edits.addStaged(stagedBy("rename_symbol", arguments.put("newName", newName), from), changes, "file operation");
                moved.declaration(from, to, changes, "file operation");
            } else {
                String target = packageOf(unit, to.getParent());
                if (target == null) {
                    log.warn("willRenameFiles: {} is moved out of its source root, so no code is changed along with it", from);
                    return;
                }
                edits.refuseIfUnsaved(from.toUri().toString(), from, "file operation");
                edits.addStaged(stagedBy("move", arguments.put("kind", "class").put("targetPackage", target), from), changes, "file operation");
                moved.packageLine(unit, from, target, changes);
                moved.neighbourImports(unit, from, changes);
            }
        } catch (JavaModelException e) {
            throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * A folder renamed or moved in the editor: when it holds Java source it is a package, and the engine's package move, staged as edits,
     * is what changes the files in it and the files that use them. A folder with no Java below it changes no code.
     */
    private void addFolderChange(Path from, Path to, Map<String, List<Object>> changes) throws ToolNotFoundException {
        Map<Path, ICompilationUnit> inside = unitsUnder(from);
        if (inside.isEmpty()) {
            return;
        }
        ICompilationUnit any = inside.values().iterator().next();
        String oldPackage = packageOf(any, from);
        String newPackage = packageOf(any, to);
        if (oldPackage == null || newPackage == null) {
            log.warn("willRenameFiles: the folder {} is moved out of its source root or is the root itself, so no code is changed along with it", from);
            return;
        }
        if (oldPackage.equals(newPackage)) {
            return;
        }
        // the engine's package move renames the one package it is asked for, so it is staged for every package declared below the folder,
        // and the package line of each file in the folder, which the editor's folder move leaves as it was, is added as for a single moved file
        Set<String> packages = new TreeSet<>();
        inside.forEach((file, unit) -> {
            try {
                IPackageDeclaration[] declared = unit.getPackageDeclarations();
                String name = declared.length == 0 ? "" : declared[0].getElementName();
                if (name.equals(oldPackage) || name.startsWith(oldPackage + ".")) {
                    packages.add(name);
                    moved.packageLine(unit, file, newPackage + name.substring(oldPackage.length()), changes);
                }
            } catch (JavaModelException e) {
                throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
            }
        });
        for (String name : packages) {
            ObjectNode arguments = JSON.createObjectNode().put("kind", "package").put("packageName", name)
                .put("newPackageName", newPackage + name.substring(oldPackage.length())).put("auto_apply", false).put("edits", true);
            edits.addStaged(stagedBy("move", arguments, from), changes, "file operation");
        }
    }

    /** The units of the Java files below a folder that the model holds, by file. */
    private Map<Path, ICompilationUnit> unitsUnder(Path folder) {
        Map<Path, ICompilationUnit> units = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                ICompilationUnit unit = edits.unit(file.toUri().toString());
                if (unit != null) {
                    units.put(file, unit);
                } else {
                    log.warn("willRenameFiles: the model holds no unit for {}, so no code is changed along with it", file);
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException("the folder could not be read: " + e.getMessage(), e);
        }
        return units;
    }

    private ToolResponse stagedBy(String tool, ObjectNode arguments, Path file) throws ToolNotFoundException {
        ToolResponse staged = tools.callTool(tool, arguments, caller);
        if (!staged.isSuccess()) {
            throw new IllegalStateException("The code for " + file.getFileName() + " could not be changed along with it: " + staged);
        }
        return staged;
    }

    /** The package a folder names under the unit's source root; null when the folder is outside that root or is the root itself. */
    private static String packageOf(ICompilationUnit unit, Path folder) {
        IJavaElement root = unit.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
        if (root == null || root.getResource() == null || root.getResource().getLocation() == null) {
            return null;
        }
        Path rootPath = root.getResource().getLocation().toFile().toPath();
        if (!folder.startsWith(rootPath) || folder.equals(rootPath)) {
            return null;
        }
        List<String> names = new ArrayList<>();
        rootPath.relativize(folder).forEach(part -> names.add(part.toString()));
        return String.join(".", names);
    }
}
