package org.jawata.mcp.java.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the editor's Java Projects view shows (Sprint 28h, D53 (o)): each project the engine has loaded with its packages, the classes of each
 * package and the libraries on its classpath. It is an engine fact asked for by name, because no language-server request answers it.
 */
final class LspProjects {

    private final Supplier<IJdtService> service;

    LspProjects(Supplier<IJdtService> service) {
        this.service = service;
    }

    /** The projects, in the order the engine holds them; none when no workspace is loaded yet. */
    Object projects(JsonNode params) throws JavaModelException {
        List<Map<String, Object>> projects = new ArrayList<>();
        IJdtService jdt = service.get();
        if (jdt == null) {
            return projects;
        }
        for (LoadedProject project : jdt.allProjects()) {
            projects.add(entryOf(project));
        }
        return projects;
    }

    private static Map<String, Object> entryOf(LoadedProject project) throws JavaModelException {
        Map<String, List<Map<String, Object>>> packages = new java.util.TreeMap<>();
        List<Map<String, Object>> libraries = new ArrayList<>();
        for (IPackageFragmentRoot root : project.javaProject().getPackageFragmentRoots()) {
            if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                collectClasses(root, packages);
            } else {
                libraries.add(Map.of("name", root.getElementName(), "path", root.getPath().toString()));
            }
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", project.projectKey());
        entry.put("root", project.projectRoot().toString());
        List<Map<String, Object>> shown = new ArrayList<>();
        packages.forEach((name, classes) -> shown.add(Map.of("name", name, "classes", classes)));
        entry.put("packages", shown);
        entry.put("libraries", libraries);
        return entry;
    }

    private static void collectClasses(IPackageFragmentRoot root, Map<String, List<Map<String, Object>>> packages) throws JavaModelException {
        for (IJavaElement child : root.getChildren()) {
            if (child instanceof IPackageFragment fragment && fragment.getCompilationUnits().length > 0) {
                String name = fragment.getElementName().isEmpty() ? "(default package)" : fragment.getElementName();
                List<Map<String, Object>> classes = packages.computeIfAbsent(name, key -> new ArrayList<>());
                for (ICompilationUnit unit : fragment.getCompilationUnits()) {
                    IResource resource = unit.getResource();
                    classes.add(Map.of("name", unit.getElementName().replaceFirst("\\.java$", ""),
                        "uri", resource == null || resource.getLocationURI() == null ? "" : resource.getLocationURI().toString()));
                }
            }
        }
    }
}
