package org.jawata.core;

import java.nio.file.Path;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;

/**
 * Where a file is found in a Java model (Sprint 28k, E20): the one lookup of a
 * file's compilation unit within a project, used by the service that serves
 * every loaded project and by the view of a single one, so the two cannot
 * answer differently.
 *
 * <p>
 * It holds no state and asks nothing of the service: the project to look in is
 * an argument. That is what lets the single-project view stop naming the
 * service that owns the others.
 * </p>
 */
final class ModelLookup {

	private ModelLookup() {
	}

	/**
	 * The part of a PROJECT-RELATIVE path that lies under a source root, or
	 * {@code null} when it does not lie under this one.
	 *
	 * <p>
	 * Sprint 28 (v3.6.4): jawata emitted a path it would not accept. Responses
	 * format paths through {@code PathUtils.formatPath}, which returns them
	 * RELATIVE to the project root — {@code test/com/example/FooTest.java}. Handing
	 * that exact string back to any tool that resolves a file returned
	 * {@code FILE_NOT_FOUND}, because the lookup only ever matched ABSOLUTE paths
	 * against the source roots and gave up on anything else. Measured on a live
	 * project: the absolute form resolved and reported its own path back in the
	 * relative form, which then did not resolve — with or without
	 * {@code projectKey}. An agent that passes a path jawata just gave it was told
	 * the file does not exist, which is the same lie as an empty result on a failed
	 * lookup.
	 * </p>
	 *
	 * <p>
	 * Matching is by root TAIL: a source root {@code …/com.example/test} ends with
	 * the relative path's first segment {@code test}, and
	 * {@code …/proj/src/main/java} ends with its first three. The longest match
	 * wins, so a root nested inside another is preferred, and the caller still
	 * verifies the compilation unit exists — a coincidental tail match resolves to
	 * nothing and is discarded rather than returned.
	 * </p>
	 *
	 * <p>
	 * Output is unchanged. This only widens what the lookup ACCEPTS, so no response
	 * shape, path form or existing caller moves.
	 * </p>
	 */
	static Path underRoot(Path rootPath, Path projectRelative) {
		int segments = projectRelative.getNameCount();
		if (segments < 2)
			return null; // needs at least one root segment plus a file name
		int max = Math.min(rootPath.getNameCount(), segments - 1);
		for (int k = max; k >= 1; k--) {
			if (rootPath.endsWith(projectRelative.subpath(0, k))) {
				return projectRelative.subpath(k, segments);
			}
		}
		return null;
	}

	/**
	 * Resolve a file through the project's DECLARED source roots, whatever they are
	 * named.
	 *
	 * <p>
	 * Sprint 28 (v3.6.1). Each {@code K_SOURCE} package-fragment root knows its own
	 * filesystem location, and for a linked folder that location is the link TARGET
	 * — so a project mounted as {@code src-1-test -> <root>/test} resolves
	 * correctly without anyone hard-coding the name {@code test}. Longest matching
	 * root wins, so a nested source folder is preferred over its parent.
	 * </p>
	 *
	 * @return the compilation unit, or null when no declared root contains the file
	 *         (the caller then falls back to the convention guess)
	 */
	static ICompilationUnit lookupViaSourceRoots(IJavaProject jp, Path filePath) throws JavaModelException {
		Path absolute = filePath.isAbsolute() ? filePath.normalize() : null;
		Path projectRelative = absolute == null ? filePath.normalize() : null;

		IPackageFragmentRoot bestRoot = null;
		Path bestRelative = null;
		for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
			if (root.getKind() != IPackageFragmentRoot.K_SOURCE)
				continue;
			IResource resource = root.getResource();
			if (resource == null)
				continue;
			IPath location = resource.getLocation();
			if (location == null)
				continue;
			Path rootPath = Path.of(location.toOSString()).normalize();
			Path relative;
			if (absolute != null) {
				if (!absolute.startsWith(rootPath))
					continue;
				relative = rootPath.relativize(absolute);
			} else {
				relative = underRoot(rootPath, projectRelative);
				if (relative == null)
					continue;
			}
			// Longest root wins: prefer the most specific source folder.
			if (bestRelative == null || relative.getNameCount() < bestRelative.getNameCount()) {
				bestRoot = root;
				bestRelative = relative;
			}
		}
		if (bestRoot == null)
			return null;

		String relative = bestRelative.toString().replace('\\', '/');
		int lastSlash = relative.lastIndexOf('/');
		String packageName = lastSlash > 0 ? relative.substring(0, lastSlash).replace('/', '.') : "";
		String className = lastSlash > 0 ? relative.substring(lastSlash + 1) : relative;
		IPackageFragment pkg = bestRoot.getPackageFragment(packageName);
		if (pkg == null || !pkg.exists())
			return null;
		ICompilationUnit cu = pkg.getCompilationUnit(className);
		return cu != null && cu.exists() ? cu : null;
	}

	/**
	 * Resolve a file to its compilation unit within ONE project.
	 *
	 * <p>
	 * Package-private on purpose (Sprint 28, v3.6.2): {@link ScopedJdtService} —
	 * the view a tool gets when it passes {@code projectKey} — used to carry its
	 * own COPY of this logic. v3.6.1 fixed the source-folder handling here and the
	 * copy kept the old Maven-prefix guess, so an unscoped call resolved a plug-in
	 * project's {@code test/} folder and a scoped call did not. Measured on a live
	 * 1040-source project: {@code find_tests} answered 126 unscoped and 1 scoped,
	 * seconds apart, same resident. One implementation now, so the two cannot drift
	 * again.
	 * </p>
	 */
	static ICompilationUnit lookupCompilationUnit(IJavaProject jp, Path filePath) {
		if (jp == null)
			return null;
		try {
			// Sprint 28 (v3.6.1): ASK THE MODEL where the source roots are.
			//
			// This used to derive the type name by guessing the source folder
			// from a fixed list of Maven/Gradle path conventions
			// ("src/main/java/", ..., "src/"). Any project whose source folder
			// is named something else — an Eclipse plug-in project with a
			// top-level `test/`, `tests/` or `integration-tests/` — matched no
			// prefix, so the ENTIRE absolute path was turned into a "package
			// name" and every lookup failed. The caller (SourceScan) records
			// that as unresolvable, which is honest, but the effect was that
			// find_tests reported 1 of 20+ test classes and the quality scans
			// silently missed 142 of 1040 files on a real PDE project, while
			// the JDT search engine read the very same files without trouble.
			//
			// The source roots are a fact the Java model already holds, and
			// getResource().getLocation() resolves a LINKED folder to its real
			// filesystem target — which is exactly how jawata mounts a
			// project's source folders into its synthesized workspace. So match
			// the file against the actual roots and derive the name from the
			// one that contains it. The convention guess stays underneath as a
			// fallback for paths the model cannot place.
			ICompilationUnit fromRoots = lookupViaSourceRoots(jp, filePath);
			if (fromRoots != null)
				return fromRoots;

			String pathStr = filePath.toString().replace('\\', '/');
			String classPath = pathStr;
			String[] sourcePrefixes = { "src/main/java/", "src/test/java/", "src/main/kotlin/", "src/test/kotlin/",
					"src/" };
			for (String prefix : sourcePrefixes) {
				if (pathStr.contains(prefix)) {
					int idx = pathStr.indexOf(prefix);
					classPath = pathStr.substring(idx + prefix.length());
					break;
				}
			}
			String withoutExt = classPath.replace(".java", "");
			String qualifiedName = withoutExt.replace('/', '.');
			IType type = jp.findType(qualifiedName);
			if (type != null)
				return type.getCompilationUnit();

			for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
				if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
					int lastSlash = classPath.lastIndexOf('/');
					String packageName = lastSlash > 0 ? classPath.substring(0, lastSlash).replace('/', '.') : "";
					String className = lastSlash > 0 ? classPath.substring(lastSlash + 1) : classPath;
					IPackageFragment pkg = root.getPackageFragment(packageName);
					if (pkg != null && pkg.exists()) {
						ICompilationUnit cu = pkg.getCompilationUnit(className);
						if (cu != null && cu.exists())
							return cu;
					}
				}
			}
			return null;
		} catch (Exception e) {
			return null;
		}
	}
}
