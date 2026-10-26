package org.jawata.mcp.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineSettings;

/**
 * The folders of the engine that runs alone (Sprint 28k, E17; contract §5): a
 * folder named in the settings, else one under the workspace the launcher
 * published, else one in the temporary folder. The three stores each worked
 * this out for themselves, with the same order and different words.
 */
public class DefaultEngineDirectories implements EngineDirectories {

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DefaultEngineDirectories.class);

	private final EngineSettings settings;

	/**
	 * @param settings where the named folders and the workspace root are read from
	 */
	public DefaultEngineDirectories(EngineSettings settings) {
		this.settings = settings;
	}

	@Override
	public Path runtimeDir() {
		return folder(SettingKeys.RUNTIME_DIR, "runtime");
	}

	@Override
	public Path coverageDir() {
		return folder(SettingKeys.COVERAGE_DIR, "coverage");
	}

	@Override
	public Path toolsDir() {
		Optional<String> explicit = present(SettingKeys.TOOLS_DIR);
		if (explicit.isPresent()) {
			return Path.of(explicit.get());
		}
		// Blank is unset for the override above, and it was for the distribution folder
		// only when absent altogether.
		Optional<String> distRoot = settings.get(SettingKeys.DIST_ROOT.name());
		if (distRoot.isEmpty()) {
			throw new IllegalStateException("Neither " + SettingKeys.TOOLS_DIR.name() + " nor "
					+ SettingKeys.DIST_ROOT.name() + " is set — the distribution's tools/ folder cannot be located. "
					+ "(The boot sets " + SettingKeys.DIST_ROOT.name() + "; a dev embedding must set "
					+ SettingKeys.TOOLS_DIR.name() + ".)");
		}
		return Path.of(distRoot.get()).resolve("tools");
	}

	/**
	 * The named folder; else {@code <workspace>/.jawata/<leaf>}; else
	 * {@code jawata-<leaf>} in the temporary folder.
	 */
	private Path folder(SettingKey key, String leaf) {
		Optional<String> explicit = present(key);
		if (explicit.isPresent()) {
			return Path.of(explicit.get());
		}
		Optional<String> workspace = present(SettingKeys.WORKSPACE_ROOT);
		if (workspace.isPresent()) {
			return Path.of(workspace.get(), ".jawata", leaf);
		}
		return Path.of(System.getProperty("java.io.tmpdir"), "jawata-" + leaf);
	}

	private Optional<String> present(SettingKey key) {
		return settings.get(key.name()).filter(value -> !value.isBlank());
	}

	/**
	 * The named folder, used as given; else {@code jawata} in {@code $XDG_DATA_HOME}, else in {@code ~/.local/share}. Only the default
	 * location carries the one-time move of the pre-rebrand folder.
	 */
	@Override
	public Path sharedStoreDir() {
		Optional<String> explicit = present(SettingKeys.EXPERIENCE_SHARED_DIR);
		if (explicit.isPresent()) {
			return Path.of(explicit.get());
		}
		Optional<String> xdg = present(SettingKeys.DATA_HOME);
		Path base = xdg.isPresent() ? Path.of(xdg.get()) : Path.of(System.getProperty("user.home"), ".local", "share");
		return migrateLegacySharedDir(base);
	}

	/**
	 * Sprint 22b (the jawata rebrand): one-time move of the pre-rebrand user-shared store dir {@code <base>/goja} to
	 * {@code <base>/jawata} — the whole directory (store file, backups, exports) moves as-is, content untouched; the v4 schema
	 * migration then rewrites the anchors on first open. Never clobbers: if {@code <base>/jawata} already exists, the legacy dir is
	 * left alone. The {@code "goja"} literal is migration code (grep-contract exception class 3).
	 *
	 * @param base the folder the shared store's folder lives in
	 * @return {@code <base>/jawata}
	 */
	public static Path migrateLegacySharedDir(Path base) {
		Path dir = base.resolve("jawata");
		Path legacy = base.resolve("goja");
		if (Files.isDirectory(legacy) && !Files.exists(dir)) {
			try {
				Files.move(legacy, dir);
				log.info("Experience store: migrated legacy shared dir {} -> {}", legacy, dir);
			} catch (java.io.IOException e) {
				log.warn("Experience store: could not migrate legacy shared dir {} -> {}: {} (continuing with {})", legacy, dir,
						e.getMessage(), dir);
			}
		}
		return dir;
	}

	@Override
	public Optional<Path> dataDir() {
		return Optional.ofNullable(resolveDataDir());
	}

	@Override
	public Optional<Path> workspaceRoot() {
		return Optional.ofNullable(stableWorkspaceRoot(resolveDataDir()));
	}

	public Path resolveDataDir() {
		// Use the OSGi-defined system property rather than
		// Platform.getInstanceLocation().getURL()
		// — the latter is a Tycho-restricted API. osgi.instance.area is set by the
		// framework to
		// the URL of the -data dir and is the public way to read it.
		try {
			String area = settings.get("osgi.instance.area").orElse(null);
			if (area == null || area.isBlank())
				return null;
			return Path.of(java.net.URI.create(area));
		} catch (Exception e) {
			log.warn("Failed to resolve Eclipse instance area: {}", e.getMessage());
			return null;
		}
	}

	/**
	 * The project folders the memory the person keeps is looked for beside. The engine alone has none: which projects a workspace holds
	 * is a fact of the host, and a host that reads them from a file of its own says so by overriding this.
	 *
	 * @return the project folders, empty when the host names none
	 */
	protected List<Path> projectDirs() {
		return List.of();
	}

	/**
	 * Sprint 28b (D1/D3): where the field lane's pile and state live — under the
	 * WORKSPACE ROOT, the same resolution studio reads (C1 audit F3: a second
	 * convention for the same concept would have studio reading a location the pile
	 * is never written to). One seam, three users: the recorder writes the pile,
	 * {@code FieldTool} reads both, and studio's field view folds the same files.
	 */
	@Override
	public Path fieldDir() {
		return stableWorkspaceRoot(resolveDataDir()).resolve("field");
	}

	/**
	 * The default roots the no-path {@code experience(kind=load|reseed)} seeds
	 * from, in one of TWO mutually exclusive modes (Sprint 28e, mcp#58).
	 *
	 * <p>
	 * <b>Configured:</b> {@code -Djawata.memory.roots} (path-separator list — the
	 * studio config channel) names the substrate, and those roots are the WHOLE
	 * answer. Nothing is discovered beside them.
	 * </p>
	 *
	 * <p>
	 * <b>Unconfigured:</b> discovery — the layered {@code CLAUDE.md} set (global
	 * {@code ~/.claude} + each workspace project dir and its ancestors up to
	 * {@code $HOME}), the Claude per-project memory dirs
	 * ({@code ~/.claude/projects/<sanitized-path>/memory}), and the other agents'
	 * per-project conventions.
	 * </p>
	 *
	 * <p>
	 * The two were ADDITIVE until 28e, which is why a deployment that had cut over
	 * to a curated substrate went on re-importing the corpus it had retired.
	 * </p>
	 */
	@Override
	public List<Path> memoryRoots() {
		return DefaultEngineDirectories.defaultMemoryRoots(Path.of(System.getProperty("user.home")), projectDirs(),
				settings.get(SettingKeys.MEMORY_ROOTS.name()).orElse(null));
	}

	/**
	 * Package-private + static for tests (no OSGi). Only existing paths are
	 * returned.
	 */
	public static java.util.List<Path> defaultMemoryRoots(Path home, java.util.List<Path> projectDirs,
			String extraRoots) {
		java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
		if (extraRoots != null && !extraRoots.isBlank()) {
			for (String s : extraRoots.split(java.io.File.pathSeparator)) {
				if (!s.isBlank()) {
					addIfExists(roots, Path.of(s.strip()));
				}
			}
			// Sprint 28e (mcp#58): a CONFIGURED substrate is AUTHORITATIVE, not additive.
			// Sprint 28c made a curated directory the substrate the store is rebuilt from,
			// but this channel stayed an ADDITION to the layered CLAUDE.md discovery below
			// —
			// so every no-path load re-imported the legacy corpus the cutover retired, the
			// studio's auto-seed-on-deploy (studio#34) above all.
			//
			// The branch is on "was a substrate CONFIGURED", never on "did it resolve".
			// A configured root that does not exist yields NO roots, so load and reseed
			// refuse with the message they already publish ("needs configured default
			// memory roots (-Djawata.memory.roots) — none found"). Falling through to
			// discovery there would seed the legacy corpus while the operator believed
			// they had named a substrate: that is the defect this closes, not a safety net.
			return java.util.List.copyOf(roots);
		}
		addIfExists(roots, home.resolve(".claude").resolve("CLAUDE.md"));
		for (Path proj : projectDirs) {
			Path d = proj.toAbsolutePath().normalize();
			while (d != null) {
				addIfExists(roots, d.resolve("CLAUDE.md"));
				if (d.equals(home) || !d.startsWith(home)) {
					break; // layered up to $HOME; foreign roots: dir only
				}
				d = d.getParent();
			}
			addIfExists(roots,
					home.resolve(".claude").resolve("projects").resolve(sanitizeProjectDir(proj)).resolve("memory"));
			// Sprint 21b (item C2): the other agents' per-project conventions.
			addIfExists(roots, proj.resolve(".cursor").resolve("rules"));
			addIfExists(roots, proj.resolve(".cursorrules"));
			addIfExists(roots, proj.resolve("AGENTS.md"));
			addIfExists(roots, proj.resolve(".github").resolve("copilot-instructions.md"));
			addIfExists(roots, proj.resolve(".windsurfrules"));
		}
		// Sprint 21b (item C2): the store is user-level, so discovery is too — EVERY
		// Claude memory dir, not just this workspace's projects ("autofind").
		Path claudeProjects = home.resolve(".claude").resolve("projects");
		if (Files.isDirectory(claudeProjects)) {
			try (var dirs = Files.list(claudeProjects)) {
				dirs.sorted().forEach(p -> addIfExists(roots, p.resolve("memory")));
			} catch (java.io.IOException e) {
				log.warn("Cannot list {}: {}", claudeProjects, e.getMessage());
			}
		}
		return java.util.List.copyOf(roots);
	}

	/**
	 * The STABLE workspace root: the launcher-published original {@code -data}
	 * ({@code jawata.workspace.root}, read through the host's settings) wins; else
	 * the layout rule of {@link #resolveWorkspaceRoot}. Never the session subdir
	 * when the launcher is in play.
	 *
	 * @param dataDir the OSGi data folder, or null
	 * @return the root, or null when the launcher published none and there is no data folder
	 */
	public Path stableWorkspaceRoot(Path dataDir) {
		Optional<String> published = present(SettingKeys.WORKSPACE_ROOT);
		return published.isPresent() ? Path.of(published.get()) : resolveWorkspaceRoot(dataDir);
	}

	/**
	 * The workspace root by layout alone: walk up from the OSGi data dir to where
	 * {@code workspace.json} lives; else the data dir itself.
	 */
	public static Path resolveWorkspaceRoot(Path dataDir) {
		if (dataDir == null) {
			return null;
		}
		Path workspaceJson = findWorkspaceJson(dataDir);
		return workspaceJson != null ? workspaceJson.getParent() : dataDir;
	}

	/**
	 * Look for {@code workspace.json} starting at the OSGi data dir, then walking
	 * up one level to handle the JawataLauncher session-isolation subdir. Returns
	 * the path of the file if found, else {@code null}.
	 *
	 * <p>
	 * Public + static so unit tests in the {@code org.jawata.mcp.tests} bundle can
	 * exercise the walk-up logic without booting OSGi.
	 */
	public static Path findWorkspaceJson(Path dataDir) {
		if (dataDir == null) {
			return null;
		}
		Path candidate = dataDir.resolve("workspace.json");
		if (Files.isRegularFile(candidate)) {
			return candidate;
		}
		Path parent = dataDir.getParent();
		if (parent != null) {
			candidate = parent.resolve("workspace.json");
			if (Files.isRegularFile(candidate)) {
				log.info(
						"Found workspace.json in parent of OSGi data dir ({}), "
								+ "treating that as the workspace root (JawataLauncher session-isolation path)",
						parent);
				return candidate;
			}
		}
		return null;
	}

	/**
	 * The Claude Code project-dir convention: the absolute path with separators as
	 * dashes.
	 */
	public static String sanitizeProjectDir(Path proj) {
		return proj.toAbsolutePath().normalize().toString().replace(java.io.File.separatorChar, '-').replace('/', '-');
	}

	public static void addIfExists(java.util.Set<Path> set, Path p) {
		if (Files.exists(p)) {
			set.add(p);
		}
	}
}
