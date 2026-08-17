package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.IClassFile;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.util.ClassFileBytesDisassembler;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Sprint 23 (D8) — readable source for ANY type by FQN: workspace source,
 * JDT-attached source (JDK src.zip), the sibling {@code …-sources.jar} in
 * the local repository (read directly, no classpath mutation, EXISTING jars
 * only — never a silent network fetch), or an honestly-labeled disassembled
 * stub as the last resort.
 */
public final class LibrarySource {

    /**
     * The engine's hard ceiling — never exceeded, whatever the caller asks for.
     *
     * <p>It is NOT a transport bound: at 120K characters a reply is roughly
     * 30K tokens, which the clients reject
     * ({@code java.util.stream.Collectors} came back at 98,064 characters and
     * Claude Code refused it — mcp#23, seen first on macOS v3.6.4 and
     * reproduced on Linux). So the DEFAULT is transport-realistic and this
     * stays only as the outer limit.
     */
    private static final int MAX_SOURCE_CHARS = 120_000;

    /**
     * What one page holds unless the caller says otherwise — about 6K tokens,
     * which every current client accepts.
     */
    public static final int DEFAULT_MAX_CHARS = 24_000;

    /** null = the FQN resolves in no loaded project. */
    public static Map<String, Object> sourceOf(IJdtService service, String typeName)
            throws Exception {
        return sourceOf(service, typeName, DEFAULT_MAX_CHARS, 0);
    }

    /**
     * One PAGE of a type's source, with the totals the caller needs to ask for
     * the next one (mcp#23).
     *
     * <p>The bound existed before this and was neither caller-controllable nor
     * reachable: a correct answer the client cannot receive is a failure, and
     * reporting "truncated" without a way to continue is half a fix — so this
     * follows the workspace's established idiom (limit/offset + true totals +
     * {@code truncated} + a {@code hint} naming the next call), the same shape
     * {@code compile_workspace} and {@code find_quality_issue} use.
     *
     * @param maxChars characters of source per page, clamped to
     *                 {@link #MAX_SOURCE_CHARS}
     * @param offset   character offset into the source, clamped to its length
     */
    public static Map<String, Object> sourceOf(IJdtService service, String typeName,
                                              int maxChars, int offset) throws Exception {
        return sourceOfPaged(service, typeName,
            Math.min(Math.max(maxChars, 1), MAX_SOURCE_CHARS), Math.max(offset, 0));
    }

    private static Map<String, Object> sourceOfPaged(IJdtService service, String typeName,
                                                     int maxChars, int offset)
            throws Exception {
        for (LoadedProject project : service.allProjects()) {
            IType type = project.javaProject().findType(typeName);
            if (type == null) continue;

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("typeName", typeName);
            result.put("projectKey", project.projectKey());

            if (type.getCompilationUnit() != null) {
                result.put("origin", "workspace-source");
                result.put("source", page(type.getCompilationUnit().getSource(), result, maxChars, offset));
                return result;
            }

            IClassFile classFile = type.getClassFile();
            if (classFile == null) continue;
            IPackageFragmentRoot root = (IPackageFragmentRoot)
                classFile.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
            if (root != null) {
                result.put("container", root.getPath().toOSString());
            }

            String attached = classFile.getSource();
            if (attached != null && !attached.isBlank()) {
                result.put("origin", "attached-source");
                result.put("source", page(attached, result, maxChars, offset));
                return result;
            }

            String fromSourcesJar = root == null ? null
                : readSiblingSourcesJar(root, type);
            if (fromSourcesJar != null) {
                result.put("origin", "sources-jar");
                result.put("source", page(fromSourcesJar, result, maxChars, offset));
                return result;
            }

            byte[] bytes = classFile.getBytes();
            ClassFileBytesDisassembler disassembler =
                ToolFactory.createDefaultClassFileBytesDisassembler();
            String stub = disassembler.disassemble(bytes, "\n",
                ClassFileBytesDisassembler.WORKING_COPY);
            result.put("origin", "disassembled-stub");
            result.put("source", "// DISASSEMBLED STUB — no source attachment and no "
                + "sibling -sources.jar exists for this type's container.\n"
                + "// Signatures are accurate; bodies are omitted. Fetching a sources jar "
                + "is an explicit user action (e.g. mvn dependency:sources), never done "
                + "silently.\n\n" + page(stub, result, maxChars, offset));
            return result;
        }
        return null;
    }

    /** {@code foo-1.0.jar} → {@code foo-1.0-sources.jar} beside it, existing only. */
    private static String readSiblingSourcesJar(IPackageFragmentRoot root, IType type) {
        try {
            Path jar = Path.of(root.getPath().toOSString());
            String name = jar.getFileName().toString();
            if (!name.endsWith(".jar")) return null;
            Path sourcesJar = jar.resolveSibling(
                name.substring(0, name.length() - 4) + "-sources.jar");
            if (!Files.isRegularFile(sourcesJar)) return null;
            String pkg = type.getPackageFragment().getElementName();
            String primary = type.getTypeQualifiedName();      // Outer$Nested
            int dollar = primary.indexOf('$');
            if (dollar > 0) primary = primary.substring(0, dollar);
            String entryName = (pkg.isEmpty() ? "" : pkg.replace('.', '/') + "/")
                + primary + ".java";
            try (ZipFile zip = new ZipFile(sourcesJar.toFile())) {
                ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) return null;
                return new String(zip.getInputStream(entry).readAllBytes(),
                    StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * One page of the source, and the facts a caller needs to read the rest
     * (mcp#23).
     *
     * <p>{@code sourceLength} is ALWAYS reported, truncated or not — a reply
     * that clips without saying how much it clipped reads as the whole type,
     * which is this project's recorded deepest bug class.
     */
    private static String page(String source, Map<String, Object> result,
                              int maxChars, int offset) {
        if (source == null) {
            result.put("sourceLength", 0);
            result.put("returnedChars", 0);
            result.put("offset", 0);
            return "";
        }
        int length = source.length();
        int from = Math.min(offset, length);
        int to = Math.min(from + maxChars, length);
        String slice = source.substring(from, to);

        result.put("sourceLength", length);
        result.put("offset", from);
        result.put("returnedChars", slice.length());
        if (to < length) {
            result.put("truncated", true);
            result.put("hint", "This is characters " + from + "–" + to + " of " + length
                + ". Call inspect(kind=source, typeName=…, offset=" + to
                + ") for the next page, or raise maxChars (ceiling "
                + MAX_SOURCE_CHARS + ") if your client accepts more.");
        }
        return slice;
    }

    private LibrarySource() {}
}
