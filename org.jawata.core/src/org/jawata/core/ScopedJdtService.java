package org.jawata.core;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.host.HostPaths;
import org.jawata.core.search.SearchService;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.CoreException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Wraps an {@link IJdtService} and re-points its single-project getters at a
 * specified {@link LoadedProject}, so tools that pass through a tool-side
 * {@code projectKey} parameter operate against just that project's classpath.
 *
 * <p>Sprint 10 introduces multi-project workspaces. By default tools see the
 * full workspace (cross-project search, cross-project file lookup). When an
 * agent wants to scope a query down to one project, it passes
 * {@code projectKey} on the tool call; {@link org.jawata.mcp.tools.AbstractTool}
 * resolves the project and wraps the service in this adapter before invoking
 * the tool body. Tool implementations are unaware of the wrapping — they keep
 * calling {@code service.getSearchService()}, {@code service.getJavaProject()},
 * {@code service.getCompilationUnit(filePath)} etc., and those calls now
 * return the per-project view.
 *
 * <p>Multi-project lookup methods ({@link #getProject}, {@link #allProjects},
 * {@link #defaultProjectKey}, {@link #projectKeys}) delegate to the underlying
 * service so tools that want to look beyond their scope still can.
 */
public class ScopedJdtService implements IJdtService {

    private final IJdtService delegate;
    private final LoadedProject scope;

    public ScopedJdtService(IJdtService delegate, LoadedProject scope) {
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        if (scope == null) throw new IllegalArgumentException("scope must not be null");
        this.delegate = delegate;
        this.scope = scope;
    }

    /** The project this view is scoped to. */
    public LoadedProject scope() {
        return scope;
    }

    // ===== scoped getters: re-point at the scoped project =====

    @Override
    public HostPaths getPathUtils() {
        return scope.pathUtils();
    }

    @Override
    public Path getProjectRoot() {
        return scope.projectRoot();
    }

    @Override
    public SearchService getSearchService() {
        return scope.searchService();
    }

    @Override
    public IJavaProject getJavaProject() {
        return scope.javaProject();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sprint 28 (v3.6.2): delegates to the ONE implementation in
     * {@link JdtServiceImpl#lookupCompilationUnit}, restricted to this scope's
     * project. This method used to hold a second copy of that logic, and when
     * v3.6.1 taught the original to read a project's DECLARED source folders
     * instead of guessing Maven layouts, the copy was left behind — so a tool
     * call carrying {@code projectKey} still could not resolve anything under a
     * source folder named {@code test/}. On a real 1040-source Eclipse plug-in
     * that meant {@code find_tests} answered 126 unscoped and 1 scoped, and the
     * quality scans reported 142 files "unresolvable" — one whole source root,
     * every time.</p>
     */
    @Override
    public ICompilationUnit getCompilationUnit(Path filePath) {
        IJavaProject jp = scope.javaProject();
        if (jp == null) return null;
        return JdtServiceImpl.lookupCompilationUnit(jp, filePath);
    }

    @Override
    public IJavaElement getElementAtPosition(Path filePath, int line, int column) {
        ICompilationUnit cu = getCompilationUnit(filePath);
        if (cu == null) return null;
        try {
            if (!cu.isOpen()) cu.open(null);
            cu.reconcile(ICompilationUnit.NO_AST, false, null, null);
            int offset = delegate.getOffset(cu, line, column);
            IJavaElement[] elements = cu.codeSelect(offset, 0);
            if (elements.length > 0) return elements[0];
            return cu.getElementAt(offset);
        } catch (CoreException e) {
            return null;
        }
    }

    @Override
    public IType getTypeAtPosition(Path filePath, int line, int column) {
        IJavaElement element = getElementAtPosition(filePath, line, column);
        if (element instanceof IType type) return type;
        if (element != null) return (IType) element.getAncestor(IJavaElement.TYPE);
        return null;
    }

    @Override
    public IType findType(String typeName) {
        IJavaProject jp = scope.javaProject();
        if (jp == null || typeName == null || typeName.isBlank()) return null;
        try {
            IType type = jp.findType(typeName);
            if (type != null) return type;
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    for (IJavaElement child : root.getChildren()) {
                        if (child instanceof IPackageFragment pkg) {
                            for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                                for (IType t : cu.getTypes()) {
                                    if (t.getElementName().equals(typeName)) return t;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        } catch (JavaModelException e) {
            return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Sprint 28 (v3.6.2): a listing failure is now LOUD, matching
     * {@code JdtServiceImpl.collectFilesFrom}. It used to be swallowed as
     * "best-effort", which returns a PARTIAL list that the caller cannot tell
     * from a complete one — and every scanning tool turns that into a "0
     * findings" verdict over files it never enumerated. That is the failure
     * mode the whole scan-honesty contract exists to prevent, and this scoped
     * view was quietly exempt from it.</p>
     */
    @Override
    public List<Path> getAllJavaFiles() {
        List<Path> files = new ArrayList<>();
        IJavaProject jp = scope.javaProject();
        if (jp == null) return files;
        try {
            for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
                if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
                    collectJavaFilesIn(root, files);
                }
            }
        } catch (JavaModelException e) {
            throw new SourceListingException(jp.getElementName(), e);
        }
        return files;
    }

    private static void collectJavaFilesIn(IPackageFragmentRoot root, List<Path> files) throws JavaModelException {
        for (IJavaElement child : root.getChildren()) {
            if (child instanceof IPackageFragment pkg) {
                for (ICompilationUnit cu : pkg.getCompilationUnits()) {
                    IResource resource = cu.getResource();
                    if (resource != null) {
                        IPath location = resource.getLocation();
                        if (location != null) {
                            files.add(Path.of(location.toOSString()));
                        }
                    }
                }
            }
        }
    }

    // ===== pure delegations (workspace queries, line/offset math, timeouts) =====

    @Override
    public int getTimeoutSeconds() {
        return delegate.getTimeoutSeconds();
    }

    @Override
    public <T> T executeWithTimeout(Callable<T> operation, String operationName) {
        return delegate.executeWithTimeout(operation, operationName);
    }

    @Override
    public String getContextLine(ICompilationUnit cu, int offset) {
        return delegate.getContextLine(cu, offset);
    }

    @Override
    public int getOffset(ICompilationUnit cu, int line, int column) {
        return delegate.getOffset(cu, line, column);
    }

    @Override
    public int getLineNumber(ICompilationUnit cu, int offset) {
        return delegate.getLineNumber(cu, offset);
    }

    @Override
    public int getColumnNumber(ICompilationUnit cu, int offset) {
        return delegate.getColumnNumber(cu, offset);
    }

    @Override
    public Optional<String> defaultProjectKey() {
        return delegate.defaultProjectKey();
    }

    @Override
    public Optional<LoadedProject> getProject(String projectKey) {
        return delegate.getProject(projectKey);
    }

    @Override
    public Collection<String> projectKeys() {
        return delegate.projectKeys();
    }

    @Override
    public Collection<LoadedProject> allProjects() {
        return delegate.allProjects();
    }

    @Override
    public LoadedProject addProject(Path projectPath) throws CoreException {
        return delegate.addProject(projectPath);
    }

    @Override
    public boolean removeProject(String projectKey) {
        return delegate.removeProject(projectKey);
    }

    @Override
    public Optional<Long> wasRecentlyDropped(String projectKey) {
        return delegate.wasRecentlyDropped(projectKey);
    }
}
