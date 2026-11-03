package org.jawata.mcp.java.lsp;

import static org.jawata.mcp.java.lsp.LspEdits.add;
import static org.jawata.mcp.java.lsp.LspEdits.pathOf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.eclipse.jdt.core.ICompilationUnit;
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
 * The language server's rename requests (Sprint 28h, S16c): rename and the check before it, and the code changes a file rename or move
 * implies. Formatting is {@link LspFormatting}, code actions are {@link LspCodeActions}, and what the three share is {@link LspEdits}
 * (split out in S16e d). A rename is the rename the agent gets: the gate stages {@code rename_symbol} with {@code edits: true}, and the
 * answer's edits are translated into the protocol's workspace edit. Nothing is written by the server, and the editor applies the edits to
 * its own buffers.
 *
 * <p>A rename or file operation that would change a file the editor has open with unsaved text is refused naming that file: the staged
 * edits are positions in the file's saved text, so applying them over the typed text would land in the wrong places. The person saves
 * or closes the file first, the same rule the write guard applies when a tool writes disk.</p>
 */
final class LspEditing {

    private static final Logger log = LoggerFactory.getLogger(LspEditing.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Function<String, ICompilationUnit> unitOf;
    private final Predicate<String> unsaved;

    /**
     * @param unitOf  the unit a request reads for a document: its working copy when open, else the saved unit; null for a file the model
     *                does not hold
     * @param unsaved whether the document has text in the editor that is not saved
     */
    LspEditing(ToolCalls tools, CallerIdentity caller, Function<String, ICompilationUnit> unitOf, Predicate<String> unsaved) {
        this.tools = tools;
        this.caller = caller;
        this.unitOf = unitOf;
        this.unsaved = unsaved;
    }

    /**
     * The name under the cursor and its range, which is what the editor selects and then renames; null where the cursor is not on a
     * name the model knows.
     */
    Object prepareRename(JsonNode params) {
        String uri = params.path("textDocument").path("uri").asText();
        ICompilationUnit unit = unitOf.apply(uri);
        if (unit == null) {
            return null;
        }
        try {
            String text = unit.getSource();
            JavaRanges.Lines lines = JavaRanges.linesOf(text);
            int offset = lines.offsetOf(params.path("position").path("line").asInt(), params.path("position").path("character").asInt());
            int start = offset;
            while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
                start--;
            }
            int end = offset;
            while (end < text.length() && Character.isJavaIdentifierPart(text.charAt(end))) {
                end++;
            }
            if (start == end || !Character.isJavaIdentifierStart(text.charAt(start)) || unit.codeSelect(start, end - start).length == 0) {
                return null;
            }
            JavaRanges.Range span = lines.span(start, end - start);
            return Map.of("range", Map.of("start", Map.of("line", span.line(), "character", span.column()),
                    "end", Map.of("line", span.endLine(), "character", span.endColumn())),
                "placeholder", text.substring(start, end));
        } catch (Exception e) {
            throw new IllegalStateException("the name under the cursor could not be read: " + e.getMessage(), e);
        }
    }

    /** The rename as a workspace edit, from the edits the gate stages; a refused rename is answered with the gate's reason. */
    Object rename(JsonNode params) throws ToolNotFoundException {
        ObjectNode arguments = JSON.createObjectNode()
            .put("filePath", pathOf(params.path("textDocument").path("uri").asText()).toString())
            .put("line", params.path("position").path("line").asInt())
            .put("column", params.path("position").path("character").asInt())
            .put("newName", params.path("newName").asText())
            .put("auto_apply", false)
            .put("edits", true);
        ToolResponse response = tools.callTool("rename_symbol", arguments, caller);
        if (!response.isSuccess()) {
            throw new IllegalStateException("The rename was refused: " + response);
        }
        JsonNode data = JSON.valueToTree(response.getData());
        Map<String, List<Object>> changes = new LinkedHashMap<>();
        LspEdits.addStaged(unsaved, response, changes, "rename");
        List<Object> renames = new ArrayList<>();
        for (JsonNode rename : data.path("fileRenames")) {
            Path from = Path.of(rename.path("from").asText());
            Path to = Path.of(rename.path("to").asText());
            addDeclarationEdits(from, to, changes, "rename");
            renames.add(Map.of("kind", "rename", "oldUri", from.toUri().toString(), "newUri", to.toUri().toString()));
        }
        if (renames.isEmpty()) {
            return Map.of("changes", changes);
        }
        // a file renamed with its type: the text is edited where it is, then the file is renamed
        List<Object> documents = new ArrayList<>();
        changes.forEach((uri, edits) -> documents.add(Map.of("textDocument", Map.of("uri", uri), "edits", edits)));
        documents.addAll(renames);
        return Map.of("documentChanges", documents);
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
            Path from = pathOf(oldUri);
            Path to = pathOf(pair.path("newUri").asText());
            if (Files.isDirectory(from)) {
                addFolderChange(from, to, changes);
                continue;
            }
            ICompilationUnit unit = from.toString().endsWith(".java") && to.toString().endsWith(".java") ? unitOf.apply(oldUri) : null;
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
            org.eclipse.jdt.core.IType type = primaryTypeOf(unit);
            if (type == null) {
                log.warn("willRenameFiles: the model holds no type for {}, so no code is changed along with it", from);
                return;
            }
            org.eclipse.jdt.core.ISourceRange name = type.getNameRange();
            JavaRanges.Range at = JavaRanges.linesOf(unit.getSource()).span(name.getOffset(), name.getLength());
            ObjectNode arguments = JSON.createObjectNode().put("filePath", from.toString()).put("line", at.line()).put("column", at.column())
                .put("auto_apply", false).put("edits", true);
            if (from.getParent().equals(to.getParent())) {
                String newName = to.getFileName().toString().replaceFirst("\\.java$", "");
                if (newName.equals(type.getElementName())) {
                    return;
                }
                LspEdits.refuseIfUnsaved(unsaved, from.toUri().toString(), from, "file operation");
                LspEdits.addStaged(unsaved, stagedBy("rename_symbol", arguments.put("newName", newName), from), changes, "file operation");
                addDeclarationEdits(from, to, changes, "file operation");
            } else {
                String target = packageOf(unit, to.getParent());
                if (target == null) {
                    return;
                }
                LspEdits.refuseIfUnsaved(unsaved, from.toUri().toString(), from, "file operation");
                LspEdits.addStaged(unsaved, stagedBy("move", arguments.put("kind", "class").put("targetPackage", target), from), changes, "file operation");
                addPackageEdit(unit, from, target, changes);
                addOldNeighbourImports(unit, from, changes);
            }
        } catch (org.eclipse.jdt.core.JavaModelException e) {
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
        if (oldPackage == null || newPackage == null || oldPackage.equals(newPackage)) {
            return;
        }
        ObjectNode arguments = JSON.createObjectNode().put("kind", "package").put("packageName", oldPackage)
            .put("newPackageName", newPackage).put("auto_apply", false).put("edits", true);
        LspEdits.addStaged(unsaved, stagedBy("move", arguments, from), changes, "file operation");
        // the engine's staged edits change the files that use the package; the package line of each file in the folder is the editor's file
        // move to leave as it was, as for a single moved file
        inside.forEach((file, unit) -> {
            try {
                org.eclipse.jdt.core.IPackageDeclaration[] declared = unit.getPackageDeclarations();
                String name = declared.length == 0 ? "" : declared[0].getElementName();
                if (name.equals(oldPackage) || name.startsWith(oldPackage + ".")) {
                    addPackageEdit(unit, file, newPackage + name.substring(oldPackage.length()), changes);
                }
            } catch (org.eclipse.jdt.core.JavaModelException e) {
                throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
            }
        });
    }

    /** The units of the Java files below a folder that the model holds, by file. */
    private Map<Path, ICompilationUnit> unitsUnder(Path folder) {
        Map<Path, ICompilationUnit> units = new LinkedHashMap<>();
        try (Stream<Path> files = Files.walk(folder)) {
            files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                ICompilationUnit unit = unitOf.apply(file.toUri().toString());
                if (unit != null) {
                    units.put(file, unit);
                }
            });
        } catch (java.io.IOException e) {
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

    /**
     * A file renamed with its type: the type and its constructors take the new name. The engine's own rename leaves these to the file
     * rename, which renames the type itself; the editor's file rename does not, so the edits are made here, in the file as it is.
     */
    private void addDeclarationEdits(Path from, Path to, Map<String, List<Object>> changes, String what) {
        String uri = from.toUri().toString();
        ICompilationUnit unit = unitOf.apply(uri);
        if (unit == null) {
            return;
        }
        LspEdits.refuseIfUnsaved(unsaved, uri, from, what);
        try {
            org.eclipse.jdt.core.IType type = primaryTypeOf(unit);
            if (type == null) {
                return;
            }
            String newName = to.getFileName().toString().replaceFirst("\\.java$", "");
            String text = unit.getSource();
            JavaRanges.Lines lines = JavaRanges.linesOf(text);
            List<Object> edits = changes.computeIfAbsent(uri, key -> new ArrayList<>());
            org.eclipse.jdt.core.ISourceRange name = type.getNameRange();
            add(edits, text, lines, name.getOffset(), name.getLength(), newName);
            for (org.eclipse.jdt.core.IMethod method : type.getMethods()) {
                if (method.isConstructor()) {
                    org.eclipse.jdt.core.ISourceRange at = method.getNameRange();
                    add(edits, text, lines, at.getOffset(), at.getLength(), newName);
                }
            }
        } catch (org.eclipse.jdt.core.JavaModelException e) {
            throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
        }
    }

    /** A file moved to another package folder: its package declaration names the new package, which the editor's file move leaves as it was. */
    private void addPackageEdit(ICompilationUnit unit, Path from, String target, Map<String, List<Object>> changes) {
        String uri = from.toUri().toString();
        LspEdits.refuseIfUnsaved(unsaved, uri, from, "file operation");
        try {
            String text = unit.getSource();
            JavaRanges.Lines lines = JavaRanges.linesOf(text);
            List<Object> edits = changes.computeIfAbsent(uri, key -> new ArrayList<>());
            org.eclipse.jdt.core.IPackageDeclaration[] declared = unit.getPackageDeclarations();
            if (declared.length == 0) {
                add(edits, text, lines, 0, 0, "package " + target + ";\n\n");
                return;
            }
            org.eclipse.jdt.core.ISourceRange declaration = declared[0].getSourceRange();
            int name = text.indexOf(declared[0].getElementName(), declaration.getOffset() + "package".length());
            add(edits, text, lines, name, declared[0].getElementName().length(), target);
        } catch (org.eclipse.jdt.core.JavaModelException e) {
            throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
        }
    }

    /** A file moved to another package imports the types of its old package that it uses, which it saw before without an import. */
    private void addOldNeighbourImports(ICompilationUnit unit, Path from, Map<String, List<Object>> changes) {
        MovedFileImports.Insertion needed = MovedFileImports.neededBy(unit);
        if (needed == null) {
            return;
        }
        try {
            String text = unit.getSource();
            add(changes.computeIfAbsent(from.toUri().toString(), key -> new ArrayList<>()), text, JavaRanges.linesOf(text),
                needed.offset(), 0, needed.text());
        } catch (org.eclipse.jdt.core.JavaModelException e) {
            throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
        }
    }


    /**
     * The type the unit is named for. While the engine syncs or builds the project, the model can answer "none" for a unit that has one,
     * for a moment; the request waits for it up to two seconds rather than answer that nothing needs to change.
     */
    private static org.eclipse.jdt.core.IType primaryTypeOf(ICompilationUnit unit) throws org.eclipse.jdt.core.JavaModelException {
        for (int attempt = 0; attempt < 20; attempt++) {
            org.eclipse.jdt.core.IType type = unit.findPrimaryType();
            if (type != null) {
                return type;
            }
            unit.close(); // a request cancelled while JDT parsed can leave an empty structure behind; closing makes the next ask parse again
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    /** The package a folder names under the unit's source root; null when the folder is outside that root or is the root itself. */
    private static String packageOf(ICompilationUnit unit, Path folder) {
        org.eclipse.jdt.core.IJavaElement root = unit.getAncestor(org.eclipse.jdt.core.IJavaElement.PACKAGE_FRAGMENT_ROOT);
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
