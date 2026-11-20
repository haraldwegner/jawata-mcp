package org.jawata.mcp.tools.shared;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

import org.jawata.core.LoadedProject;
import org.jawata.mcp.models.ErrorInfo;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.lang.LoadedWorkspace;

/**
 * The answer for a path that no loaded Java project resolved to a file (28h-C27-27). It lives with the tools and not in the response envelope
 * because the answer is a decision about the workspace: whether the place is there, and whether a loaded project holds it.
 *
 * <p>Three answers, and only these three: the place is not there ({@code FILE_NOT_FOUND}); it is there inside a loaded project but is no Java
 * source of it ({@code NOT_A_JAVA_SOURCE}); it is there outside every loaded project ({@code NOT_A_JAVA_PROJECT}). A relative path is judged by
 * no folder at all, because the engine's working folder is no folder of the workspace, so it is never answered as present.</p>
 */
public final class UnresolvedFile {

    private UnresolvedFile() {
    }

    /**
     * The answer for a path a Java tool could not resolve, decided against the projects the tool's workspace service holds.
     *
     * @param projects the loaded projects, whose roots say what lies inside the workspace
     * @param path     the path as the caller gave it
     * @return the error response naming which of the three cases the path is
     */
    public static ToolResponse fileNotFound(Collection<LoadedProject> projects, String path) {
        return decide(projects.stream().map(LoadedProject::projectRoot).toList(), path);
    }

    /**
     * The same answer for a tool that holds the language-neutral workspace and not a Java service.
     *
     * @param workspace the loaded workspace, whose projects' roots say what lies inside it
     * @param path      the path as the caller gave it
     * @return the error response naming which of the three cases the path is
     */
    public static ToolResponse fileNotFound(LoadedWorkspace workspace, String path) {
        return decide(workspace.projects().stream().map(LoadedWorkspace.ProjectInfo::root).toList(), path);
    }

    /**
     * A front door's one check of the file its caller named, made before its kinds look for a symbol at a position: the answer for an unresolved file
     * when no loaded project holds a compilation unit of it, and null when it does, when no file was named (a kind that takes none), or when the name
     * cannot be read as a path, so the kind goes on as it did. Without it each kind says "no symbol at the position" or "the position does not resolve
     * to a method", which reads as a wrong position when the file is what is wrong.
     *
     * <p>A request that names a symbol ({@code symbol} or {@code typeName}) is about that symbol and not about any file it also carries, which the
     * symbol form has always ignored, so it is let through: a stale {@code filePath} beside a valid symbol must not be refused (the post-release audit). An
     * explicit position ({@code line} and {@code column} beside the file) wins over the symbol, so the file is then the thing the request is about.</p>
     *
     * @param service   the Java service of the loaded projects
     * @param arguments the request, whose {@code filePath} is the file the caller named
     * @return the unresolved-file answer, or null when the file resolves, none was named, or the request names a symbol
     */
    public static ToolResponse ofTheFileNamed(org.jawata.core.IJdtService service, com.fasterxml.jackson.databind.JsonNode arguments) {
        String filePath = arguments.path("filePath").asText("");
        boolean namesASymbol = !arguments.path("symbol").asText("").isBlank() || !arguments.path("typeName").asText("").isBlank();
        // an explicit position wins over the symbol (FqnTarget.materializePosition), so a file with a position is a file the request is about
        boolean explicitPosition = arguments.path("line").asInt(-1) >= 0 && arguments.path("column").asInt(-1) >= 0;
        if (filePath.isBlank() || (namesASymbol && !explicitPosition)) {
            return null;
        }
        try {
            return service.getCompilationUnit(Path.of(filePath)) == null ? fileNotFound(service.allProjects(), filePath) : null;
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static ToolResponse decide(List<Path> roots, String path) {
        Path place = absolutePlace(path);
        if (place == null || !Files.exists(place)) {
            return ToolResponse.error(ErrorInfo.fileNotFound(path));
        }
        boolean insideAProject = roots.stream().anyMatch(root -> place.startsWith(root.toAbsolutePath().normalize()));
        if (insideAProject) {
            return ToolResponse.error("NOT_A_JAVA_SOURCE",
                "'" + path + "' exists inside a loaded project but is not a Java source file of it: the Java tools answer for the Java sources a project declares, and this is a build file, a resource, a folder, or a source the project does not declare.",
                "Read or change it with the document and file tools; a Java tool answers for a Java source file of a loaded project.");
        }
        return ToolResponse.error("NOT_A_JAVA_PROJECT",
            "'" + path + "' exists on disk but is not a Java project file here: it lies outside every loaded Java project.",
            "Read or change it with the document and file tools; a Java tool answers for a file of a loaded Java project.");
    }

    /** The path in full and normalised, or null when it is not given, is invalid, or is relative (a relative path has no place of its own). */
    private static Path absolutePlace(String path) {
        if (path == null) {
            return null;
        }
        try {
            Path named = Path.of(path);
            return named.isAbsolute() ? named.normalize() : null;
        } catch (RuntimeException invalidPath) {
            return null;
        }
    }
}
