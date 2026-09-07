package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.jawata.core.IJdtService;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 11 Phase E — {@code move_class}: move a Java type to a different
 * package. Updates every {@code import} line and qualified-name reference
 * in the workspace.
 *
 * <p>The type is identified by file + line + column (zero-based). The
 * target package is given by FQN; if it doesn't yet exist in the same
 * source folder as the type, it's created before the move.</p>
 */
public class MoveClassTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /** Reached as {@code move kind=class}. */
    @Override
    public String kindName() {
        return "class";
    }

    /** The bullet a client reads under {@code move} — moved here from the door (M5). */
    @Override
    public String kindSummary() {
        return """
            move the type at a caret to another package.
            Needs: filePath, line, column, targetPackage (optional targetProjectKey).""";
    }

    /** Structural: the type's fully-qualified name changes, and every reference with it. */
    @Override
    public boolean isStructural() {
        return true;
    }


    public MoveClassTool(Supplier<IJdtService> serviceSupplier,
                        RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "move_class";
    }

    @Override
    public String getDescription() {
        return """
            Move a Java type to a different package, optionally to a different
            loaded project. Every import and qualified reference across the
            workspace is updated.

            USAGE:
              move_class(filePath="src/main/java/com/example/Foo.java",
                         line=10, column=14,
                         targetPackage="com.example.api")
              move_class(... targetPackage="com.other.api", targetProjectKey="other-proj")

            Inputs:
            - filePath / line / column — point anywhere inside the type to move
              (zero-based line/column).
            - targetPackage — FQN of destination package; created if missing.
            - targetProjectKey — optional. Destination project for cross-project
              moves. When omitted, the destination is auto-detected: if
              targetPackage already exists in a loaded sibling project, that
              project is used; otherwise the move stays in the source project
              (bugs.md #10).
            - updateReferences (default true) — when false, the file moves but
              external imports keep their old qualified name.

            Conflict (e.g. target package already has a same-named type) →
            REFACTORING_FAILED with no files modified.

            Known limitation (v1.8.0): when the moved type is referenced ONLY
            via Javadoc {@link} tags in some files, the import-rewrite path can
            add spurious `import` statements to those files (sub-defect 3 of
            bugs.md #10, deferred to v1.8.1).

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the type to move."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line number anywhere inside the type."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column number on the line."));
        properties.put("targetPackage", Map.of("type", "string",
            "description", "Fully-qualified destination package (e.g., 'com.example.api')."));
        properties.put("targetProjectKey", Map.of("type", "string",
            "description", "Optional. Destination project for cross-project moves. Omit to auto-detect (matches an existing sibling project that already has targetPackage) or stay in the source project."));
        properties.put("updateReferences", Map.of("type", "boolean",
            "description", "Update import lines and qualified references in callers (default true)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "targetPackage"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String targetPackage = getStringParam(arguments, "targetPackage");
        String targetProjectKey = getStringParam(arguments, "targetProjectKey");
        boolean updateReferences = arguments != null && arguments.has("updateReferences")
            ? arguments.get("updateReferences").asBoolean(true)
            : true;

        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        if (targetPackage == null) {
            return ToolResponse.invalidParameter("targetPackage", "targetPackage is required");
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IType type = service.getTypeAtPosition(filePath, line, column);
            if (type == null) {
                return ToolResponse.symbolNotFound(
                    "No type at " + filePathStr + ":" + line + ":" + column);
            }
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return ToolResponse.invalidParameter("filePath",
                    "Type is not from a source compilation unit (binary types can't be moved)");
            }
            if (!(cu.getResource() instanceof IFile file)) {
                return ToolResponse.invalidParameter("filePath",
                    "Compilation unit's resource is not an IFile");
            }
            // bugs.md #10 (Sprint 14): source project comes from the
            // compilation unit's own IJavaProject, NOT from
            // service.getJavaProject() (which is the default-scoped one and
            // misses cross-project moves in multi-project workspaces).
            IJavaProject sourceProject = cu.getJavaProject();
            IPackageFragment sourcePkg = (IPackageFragment) cu.getParent();
            if (sourcePkg.getElementName().equals(targetPackage)
                    && (targetProjectKey == null || targetProjectKey.isBlank()
                        || targetProjectKey.equals(sourceProject.getElementName()))) {
                return ToolResponse.invalidParameter("targetPackage",
                    "Target package equals source package in the source project; nothing to move");
            }

            // Determine target project for cross-project moves:
            //   1. explicit targetProjectKey → look it up
            //   2. auto-detect: scan loaded projects for one that already has
            //      targetPackage (excluding the source project)
            //   3. fallback: stay in source project (intra-project move)
            IJavaProject targetProject = resolveTargetProject(
                service, sourceProject, targetProjectKey, targetPackage);
            if (targetProject == null) {
                // bugs.md #11 (Sprint 14): distinguish dropped from typo
                // for the explicit-targetProjectKey path.
                Optional<Long> dropped = service.wasRecentlyDropped(targetProjectKey);
                if (dropped.isPresent()) {
                    return ToolResponse.projectKeyDropped(targetProjectKey, dropped.get());
                }
                return ToolResponse.invalidParameter("targetProjectKey",
                    "Unknown targetProjectKey '" + targetProjectKey + "'. Use list_projects.");
            }

            IPackageFragment destination = ensurePackageInProject(targetProject, targetPackage,
                (IPackageFragmentRoot) sourcePkg.getParent());
            if (destination == null) {
                return ToolResponse.invalidParameter("targetPackage",
                    "Could not resolve or create target package '" + targetPackage
                        + "' in project '" + targetProject.getElementName() + "'");
            }

            MoveDescriptor descriptor = (MoveDescriptor) RefactoringCore
                .getRefactoringContribution(IJavaRefactorings.MOVE)
                .createDescriptor();
            // LTK's MoveDescriptor wants the project that hosts the *source*
            // CU — that's where its initial conditions are evaluated.
            descriptor.setProject(sourceProject.getProject().getName());
            // Signature is (files, folders, compilationUnits) — pass the CU
            // we're moving in the third slot.
            descriptor.setMoveResources(new IFile[0], new IFolder[0], new ICompilationUnit[]{cu});
            descriptor.setDestination(destination);
            descriptor.setUpdateReferences(updateReferences);

            return runRefactoring(service, descriptor, "move_class", arguments);

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * bugs.md #10 (Sprint 14): resolve the destination IJavaProject. Order:
     * <ol>
     *   <li>Explicit {@code targetProjectKey} parameter wins. If the key is
     *       unknown, returns {@code null} so the caller can return
     *       {@code INVALID_PARAMETER}.</li>
     *   <li>Auto-detect: scan loaded projects for one whose source roots
     *       already contain {@code targetPackage} AND that isn't the source
     *       project. First match wins (deterministic in practice — same
     *       package across multiple projects is rare; when it happens, the
     *       caller should pass {@code targetProjectKey} explicitly).</li>
     *   <li>Fallback: source project (preserves the v1.7.0 intra-project
     *       behaviour when no cross-project hint is given).</li>
     * </ol>
     */
    /**
     * Returns the resolved target project, or {@code null} if an explicit
     * {@code targetProjectKey} doesn't match a loaded project. The caller
     * checks {@link IJdtService#wasRecentlyDropped(String)} on a null result
     * to surface PROJECT_KEY_DROPPED (bugs.md #11) vs INVALID_PARAMETER.
     */
    private static IJavaProject resolveTargetProject(IJdtService service,
                                                     IJavaProject sourceProject,
                                                     String targetProjectKey,
                                                     String targetPackage) throws Exception {
        if (targetProjectKey != null && !targetProjectKey.isBlank()) {
            Optional<LoadedProject> explicit = service.getProject(targetProjectKey);
            return explicit.map(LoadedProject::javaProject).orElse(null);
        }
        for (LoadedProject loaded : service.allProjects()) {
            IJavaProject candidate = loaded.javaProject();
            if (candidate.getElementName().equals(sourceProject.getElementName())) continue;
            if (projectContainsPackage(candidate, targetPackage)) {
                return candidate;
            }
        }
        return sourceProject;
    }

    private static boolean projectContainsPackage(IJavaProject jp, String packageName) throws Exception {
        for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            IPackageFragment fragment = root.getPackageFragment(packageName);
            if (fragment != null && fragment.exists()) return true;
        }
        return false;
    }

    /**
     * Find or create {@code targetPackage} in a source root of
     * {@code targetProject}.
     *
     * <p>{@code preferredRoot} — the root the moved class already lives in — WINS whenever
     * it belongs to {@code targetProject}, whether or not it already declares the package.
     * That is mcp#17: a package name in one root says nothing about the other.
     * {@code src/main/java} and {@code src/test/java} are separate compilation scopes that
     * routinely declare the same package, so "this root already has {@code com.example.service}"
     * is not evidence about where a TEST class belongs — and acting on it wrote the class into
     * the production root, reported success, and passed the compile gate, because production
     * code compiling against a moved test class is valid Java. The two roots exist to prevent
     * exactly that.</p>
     *
     * <p>A CROSS-project move has no such preference to honour — the source root is not in the
     * destination project at all — so it falls through to the scan below, which is what keeps
     * the auto-detect relocation in {@code MoveClassToolCrossProjectTest} working.</p>
     *
     * <p>Otherwise: prefer an existing fragment; if none exists, create the package in the
     * first source root.</p>
     */
    private static IPackageFragment ensurePackageInProject(IJavaProject targetProject,
                                                            String targetPackage,
                                                            IPackageFragmentRoot preferredRoot)
            throws Exception {
        if (preferredRoot != null && targetProject.equals(preferredRoot.getJavaProject())) {
            IPackageFragment inOwnRoot = preferredRoot.getPackageFragment(targetPackage);
            if (inOwnRoot != null && inOwnRoot.exists()) return inOwnRoot;
            return preferredRoot.createPackageFragment(targetPackage, true, new NullProgressMonitor());
        }
        IPackageFragmentRoot firstSourceRoot = null;
        for (IPackageFragmentRoot root : targetProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            if (firstSourceRoot == null) firstSourceRoot = root;
            IPackageFragment existing = root.getPackageFragment(targetPackage);
            if (existing != null && existing.exists()) return existing;
        }
        if (firstSourceRoot == null) return null;
        return firstSourceRoot.createPackageFragment(targetPackage, true, new NullProgressMonitor());
    }
}
