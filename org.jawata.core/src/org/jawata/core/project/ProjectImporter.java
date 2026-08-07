package org.jawata.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.launching.IVMInstall;
import org.eclipse.jdt.launching.IVMInstallType;
import org.eclipse.jdt.launching.JavaRuntime;
import org.gradle.tooling.GradleConnector;
import org.gradle.tooling.ProjectConnection;
import org.gradle.tooling.model.eclipse.EclipseExternalDependency;
import org.gradle.tooling.model.eclipse.EclipseProject;
import org.gradle.tooling.model.eclipse.EclipseSourceDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Imports external Java projects (Maven/Gradle/Bazel) into the Eclipse workspace
 * with proper classpath configuration for JDT analysis.
 *
 * Uses linked folders to keep all Eclipse metadata in the workspace,
 * not polluting the user's actual project directory.
 */
public class ProjectImporter {

    private static final Logger log = LoggerFactory.getLogger(ProjectImporter.class);

    public enum BuildSystem { MAVEN, GRADLE, BAZEL, ECLIPSE_PDE, ECLIPSE, UNKNOWN }

    /**
     * Tycho packaging values for which Maven's {@code dependency:build-classpath}
     * goal returns wrong/empty results: Tycho injects classpath via the target
     * platform + {@code MANIFEST.MF}, not via pom {@code <dependencies>}.
     * Detected projects bypass {@link #getMavenDependencies(java.nio.file.Path)}
     * and rely on {@code .classpath kind="lib"} entries plus (in v1.5.0+)
     * {@code Require-Bundle} resolution against the workspace bundle pool.
     */
    private static final Set<String> TYCHO_PACKAGINGS = Set.of(
        "eclipse-plugin",
        "eclipse-test-plugin",
        "eclipse-feature",
        "eclipse-repository",
        "eclipse-update-site",
        "eclipse-target-definition"
    );

    // Source folder mapping: external relative path -> linked folder name
    private static final String[][] SOURCE_MAPPINGS = {
        {"src/main/java", "src-main-java"},
        {"src/test/java", "src-test-java"},
        {"src/main/kotlin", "src-main-kotlin"},
        {"src/test/kotlin", "src-test-kotlin"},
        {"src", "src"}
    };

    // Directories to skip during recursive source scanning
    private static final List<String> IGNORED_DIRS = List.of(
        ".git", ".svn", ".mvn", ".gradle", ".settings", ".metadata",
        "node_modules", "target", "build", "bin", "out", "dist"
    );

    /**
     * Exclusion patterns applied to every linked source entry (Sprint 22a
     * P0-c). Editor / agent scratch copies land INSIDE a source root — e.g.
     * {@code src/.claude/.edit-baks/**} or {@code *.edit-bak} backups — and,
     * because they carry their original {@code package} declaration, JDT
     * indexes them as DUPLICATE types that pollute search + call-hierarchy
     * with phantom results. IGNORED_DIRS skips them during source-ROOT
     * scanning, but the entry that links a root still sees everything beneath
     * it; these patterns exclude the scratch copies from the entry itself.
     * {@code **}/ matches at any depth including the root.
     */
    private static final IPath[] SOURCE_EXCLUSIONS = {
        new Path("**/.claude/**"),
        new Path("**/*.edit-bak")
    };

    // Pattern to extract module names from pom.xml
    private static final Pattern MODULE_PATTERN = Pattern.compile("<module>([^<]+)</module>");

    /**
     * Configure an IProject as a Java project with proper classpath.
     * Creates linked folders for source directories to keep Eclipse metadata
     * in the workspace, not polluting the user's project directory.
     *
     * @param project The workspace project (must be created and open)
     * @param projectPath The filesystem path to the external project
     * @param workspaceManager WorkspaceManager for creating linked folders
     * @return Configured IJavaProject
     * @throws CoreException if configuration fails
     */
    public IJavaProject configureJavaProject(IProject project, java.nio.file.Path projectPath,
            org.jawata.core.workspace.WorkspaceManager workspaceManager) throws CoreException {
        IJavaProject javaProject = JavaCore.create(project);

        // jawata-mcp#3: a synthesized workspace can start with NO default VM
        // registered (observed on macOS: defaultVM=""). Then the JRE container
        // below is UNBOUND — "Unbound classpath container: Default System
        // Library" — and NO java.* type resolves anywhere, cascading into
        // build-path errors on every project. Bind the running JVM as the
        // default before building the classpath so the container can resolve.
        ensureDefaultVm();

        // Build classpath entries
        List<IClasspathEntry> entries = new ArrayList<>();

        // 1. Add JRE container (provides java.* classes)
        IPath jreContainerPath = JavaRuntime.getDefaultJREContainerEntry().getPath();
        entries.add(JavaCore.newContainerEntry(jreContainerPath));

        // 2. Create linked folders and add source entries
        addSourceEntries(entries, project, projectPath, workspaceManager);

        // 3. Add dependency JARs from build system + Require-Bundle siblings
        addDependencyEntries(entries, projectPath, workspaceManager);

        // 4. Add output location
        IPath outputPath = project.getFullPath().append("bin");

        // Set the classpath
        javaProject.setRawClasspath(
            entries.toArray(new IClasspathEntry[0]),
            outputPath,
            new NullProgressMonitor()
        );

        // 5. Apply the project's OWN declared Java language level.
        applyComplianceLevel(javaProject, projectPath);

        log.info("Configured Java project with {} classpath entries", entries.size());
        return javaProject;
    }

    /**
     * Set the compiler compliance from the level the project itself DECLARES.
     *
     * <p>Sprint 28 (D-IMPORTER). Nothing in the product set compliance at all —
     * every loaded project silently took the JDT default, whatever its build
     * file said. The v3.6.x macOS round recorded the symptom without the cause:
     * a project "compiles at the wrong language level … 77 errors requiring
     * Java 10+ ({@code var}) and Java 14+ (switch arrows) while the pom declares
     * source/target 15". That was never specific to one project — no project
     * ever received its declared level.</p>
     *
     * <p>Silently keeping the default is the worst outcome: the user's code is
     * legal and the errors look real, so the search goes to their source instead
     * of our classpath. When nothing is declared we keep JDT's default and say
     * so at debug level, rather than guessing a level.</p>
     */
    private void applyComplianceLevel(IJavaProject javaProject, java.nio.file.Path projectPath) {
        Optional<String> declared = readComplianceLevel(projectPath);
        if (declared.isEmpty()) {
            log.debug("No Java language level declared by {} — keeping the workspace default",
                projectPath);
            return;
        }
        String level = declared.get();
        javaProject.setOption(JavaCore.COMPILER_COMPLIANCE, level);
        javaProject.setOption(JavaCore.COMPILER_SOURCE, level);
        javaProject.setOption(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, level);
        log.info("Java language level {} (declared by the project) applied to {}", level, projectPath);
    }

    /**
     * The Java language level a project declares, per build system.
     *
     * <p>Each build system states it in its own vocabulary: Eclipse in
     * {@code .settings/org.eclipse.jdt.core.prefs}, Maven in
     * {@code maven.compiler.release/source}, Gradle in
     * {@code sourceCompatibility}, Bazel in a {@code javacopts} entry. Read
     * textually — this runs while the project is being configured, before any
     * model exists to ask.</p>
     *
     * <p>Textual reading has one failure mode worth naming: a build file may
     * declare a level it does not itself contain. {@code <maven.compiler.source>
     * ${java.version}</maven.compiler.source>} is legal Maven and extremely
     * common, and the property is resolved by Maven, not by us. Handing
     * {@code "${java.version}"} to JDT as a compliance level configures the
     * compiler with a value that is not a Java version at all. So every
     * candidate passes {@link #COMPLIANCE_LEVEL} before it leaves this method:
     * an unusable declaration is reported at WARN naming the raw text, and the
     * project keeps the default — the same outcome as declaring nothing, but
     * never the same silence.</p>
     */
    static Optional<String> readComplianceLevel(java.nio.file.Path projectPath) {
        // Each source in precedence order. An UNUSABLE declaration does not stop
        // the search (C1 audit round 3): a pom saying ${java.version} used to
        // suppress a perfectly good BREE or javacopts below it, so a project
        // that states its level twice — once unresolvably — got no level at all.
        // A usable declaration still wins immediately, so precedence is intact.
        Optional<String> level = Optional.empty();
        level = level.or(() -> usableLevel(readEclipseCompliance(projectPath), projectPath, ".settings"));
        level = level.or(() -> usableLevel(
            readMavenCompliance(projectPath.resolve("pom.xml")), projectPath, "pom.xml"));
        level = level.or(() -> usableLevel(readGradleCompliance(projectPath), projectPath, "build.gradle"));
        level = level.or(() -> usableLevel(readBreeCompliance(projectPath), projectPath, "MANIFEST.MF"));
        level = level.or(() -> usableLevel(readBazelCompliance(projectPath), projectPath, "javacopts"));
        return level;
    }

    /**
     * The levels JDT accepts, taken from what it DECLARES rather than from
     * what looks reasonable: {@code JavaCore} publishes {@code VERSION_1_1}
     * … {@code VERSION_1_8} and {@code VERSION_9} … {@code VERSION_27}.
     *
     * <p>Two corrections, both from audits, both in the direction of
     * inventing a rule instead of reading one (C1, rounds 4 and 5). First the
     * pattern was {@code [1-9][0-9]+}, which matched {@code 99} and
     * {@code 1234} — a typo'd {@code <maven.compiler.release>177</…>} reached
     * the compiler unvalidated, the exact class of value this check exists to
     * refuse. Then the fix bounded it to two digits starting 1–4 and the
     * Javadoc claimed that "covers every release JDT can be set to", which was
     * false in BOTH directions: it accepted 28–49, which JDT cannot be set to,
     * and refused {@code 1.1}/{@code 1.2}, which it can.</p>
     *
     * <p>A ceiling has to be maintained as JDT moves. That is the honest cost
     * of validating at all, and it is smaller than the cost of the alternative
     * — which is what a wrong level does to a user's error list.</p>
     */
    private static final Pattern COMPLIANCE_LEVEL =
        Pattern.compile("1\\.[1-8]|[9]|1[0-9]|2[0-7]");

    /**
     * {@code level} in the form JDT accepts, or empty — said out loud.
     *
     * <p>Two distinct jobs, and conflating them cost a real level (C1 audit
     * round 3). {@code <maven.compiler.source>8</maven.compiler.source>} is
     * ordinary, correct Maven meaning Java 8; JDT's name for that level is
     * {@code 1.8}, and a bare {@code 8} handed to {@code setOption} is a value
     * it does not recognise. So {@code 5}–{@code 8} are NORMALIZED, while a
     * genuine non-version — an unresolved {@code ${java.version}} — is
     * REFUSED.</p>
     */
    private static Optional<String> usableLevel(Optional<String> declared,
            java.nio.file.Path projectPath, String declaredIn) {
        if (declared.isEmpty()) {
            return Optional.empty();
        }
        String level = declared.get();
        if (level.length() == 1 && level.charAt(0) >= '5' && level.charAt(0) <= '8') {
            return Optional.of("1." + level);
        }
        if (COMPLIANCE_LEVEL.matcher(level).matches()) {
            return Optional.of(level);
        }
        log.warn("{} declares the Java language level as \"{}\" in {}, which is not a level JDT can"
            + " be set to — most often an unresolved build property. The project keeps the default"
            + " level, so it may compile at a different level than it declares.",
            projectPath, level, declaredIn);
        return Optional.empty();
    }

    /** {@code org.eclipse.jdt.core.compiler.compliance=21} in the project's own settings. */
    private static Optional<String> readEclipseCompliance(java.nio.file.Path projectPath) {
        java.nio.file.Path prefs = projectPath.resolve(".settings/org.eclipse.jdt.core.prefs");
        if (!Files.isRegularFile(prefs)) {
            return Optional.empty();
        }
        try {
            return readLinesLenient(prefs, 4096).stream()
                        .map(String::trim)
                        .filter(l -> l.startsWith("org.eclipse.jdt.core.compiler.compliance="))
                        .findFirst()
                        .map(l -> l.substring(l.indexOf('=') + 1).trim())
                        .filter(v -> !v.isEmpty());
        } catch (IOException e) {
            // The settings file EXISTS and could not be read — that is not the
            // same fact as "this project declares no level", and returning the
            // empty Optional silently makes them identical. Say which one it is.
            log.warn("{} exists but could not be read for its declared Java level ({}) —"
                + " the project will take the default, which may not be what it declares",
                prefs, e.getMessage());
            return Optional.empty();
        }
    }

    /** {@code <maven.compiler.release>} wins over {@code <maven.compiler.source>}. */
    private static Optional<String> readMavenCompliance(java.nio.file.Path pomXml) {
        if (!Files.isRegularFile(pomXml)) {
            return Optional.empty();
        }
        try {
            String pom = readStringLenient(pomXml);
            for (String tag : new String[] {"maven.compiler.release", "maven.compiler.source"}) {
                Matcher m = Pattern.compile("<" + Pattern.quote(tag) + ">\\s*([^<\\s]+)\\s*</").matcher(pom);
                if (m.find()) {
                    return Optional.of(m.group(1));
                }
            }
            Matcher release = Pattern.compile("<release>\\s*([^<\\s]+)\\s*</release>").matcher(pom);
            if (release.find()) {
                return Optional.of(release.group(1));
            }
            Matcher source = Pattern.compile("<source>\\s*([^<\\s]+)\\s*</source>").matcher(pom);
            if (source.find()) {
                return Optional.of(source.group(1));
            }
        } catch (IOException e) {
            log.debug("Could not read {} for its declared Java level: {}", pomXml, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * {@code Bundle-RequiredExecutionEnvironment: JavaSE-21} — how an OSGi/PDE
     * bundle states the Java level it must be built against.
     *
     * <p>Sprint 28 (C1 audit). PDE was the one advertised build system with no
     * way to state a level at all, because we read only the four file formats
     * the other systems use. A bundle states it in its manifest, and PDE
     * bundles rarely carry a {@code .settings} file — so every OSGi project
     * silently took the workspace default, which is the defect this sprint
     * exists to end, left standing for one build system.</p>
     *
     * <p>Read through {@link Manifest} rather than textually: the header may be
     * wrapped across continuation lines, which a line-oriented match would
     * truncate. Legacy names ({@code J2SE-1.5}) and profile forms
     * ({@code JavaSE/compact1-1.8}) both yield their version; the first BREE
     * governs when several are listed.</p>
     */
    private static Optional<String> readBreeCompliance(java.nio.file.Path projectPath) {
        java.nio.file.Path manifestPath = projectPath.resolve("META-INF/MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            String bree = new Manifest(in).getMainAttributes()
                .getValue("Bundle-RequiredExecutionEnvironment");
            if (bree == null || bree.isBlank()) {
                return Optional.empty();
            }
            Matcher m = Pattern.compile("(?:JavaSE|J2SE|CDC|OSGi)[^,-]*-([0-9]+(?:\\.[0-9]+)?)")
                .matcher(bree);
            if (m.find()) {
                return Optional.of(m.group(1));
            }
            log.debug("{} declares Bundle-RequiredExecutionEnvironment \"{}\", which names no"
                + " Java version we recognise", manifestPath, bree);
        } catch (IOException e) {
            log.warn("{} exists but could not be read for its required execution environment ({})"
                + " — the bundle will take the default Java level", manifestPath, e.getMessage());
        }
        return Optional.empty();
    }

    /** {@code sourceCompatibility = '21'} / {@code JavaVersion.VERSION_21} in a Gradle build file. */
    private static Optional<String> readGradleCompliance(java.nio.file.Path projectPath) {
        for (String name : new String[] {"build.gradle", "build.gradle.kts"}) {
            java.nio.file.Path buildFile = projectPath.resolve(name);
            if (!Files.isRegularFile(buildFile)) {
                continue;
            }
            try {
                String script = readStringLenient(buildFile);
                Matcher m = Pattern.compile(
                    "(?:sourceCompatibility|targetCompatibility)\\s*(?:=|\\.set\\()\\s*"
                        + "[\"']?(?:JavaVersion\\.VERSION_)?([0-9._]+)[\"']?")
                    .matcher(script);
                if (m.find()) {
                    return Optional.of(m.group(1).replace('_', '.'));
                }
            } catch (IOException e) {
                log.debug("Could not read {} for its declared Java level: {}", buildFile, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /**
     * {@code javacopts = ["--release", "17"]} — or the older {@code -source}
     * form — in any BUILD file of a Bazel project.
     *
     * <p>Bazel states the level per target, not per project, so the first
     * declaration found governs. A project mixing levels across targets is not a
     * shape one project-wide compliance can represent; the alternative, silently
     * taking the default, is exactly what this ends.</p>
     */
    private static Optional<String> readBazelCompliance(java.nio.file.Path projectPath) {
        if (!Files.exists(projectPath.resolve("MODULE.bazel"))
                && !Files.exists(projectPath.resolve("WORKSPACE.bazel"))
                && !Files.exists(projectPath.resolve("WORKSPACE"))) {
            return Optional.empty();
        }
        List<java.nio.file.Path> buildFiles = new ArrayList<>();
        // Prune the OUTPUT tree here too (C1 re-audit). Reading the language
        // level out of bazel-bin/ would take it from generated copies of the
        // BUILD files — the same tree source discovery deliberately excludes.
        walkPruned(projectPath, dir -> isBazelOutputDirectory(projectPath, dir), dir -> {
            for (String name : new String[] {"BUILD", "BUILD.bazel"}) {
                java.nio.file.Path candidate = dir.resolve(name);
                if (Files.isRegularFile(candidate)) {
                    buildFiles.add(candidate);
                }
            }
        });
        // Deterministic: "the first declaration governs" is only a rule if
        // "first" is the same on every machine. Walk order is not.
        buildFiles.sort(java.util.Comparator.comparing(java.nio.file.Path::toString));
        Pattern javacopt = Pattern.compile(
            "[\"'](?:--release|-source|--source)[\"']\\s*,\\s*[\"']([0-9.]+)[\"']");
        for (java.nio.file.Path buildFile : buildFiles) {
            try {
                Matcher m = javacopt.matcher(String.join("\n", readLinesLenient(buildFile, 20000)));
                if (m.find()) {
                    return Optional.of(m.group(1));
                }
            } catch (IOException e) {
                log.debug("Could not read {} for javacopts: {}", buildFile, e.getMessage());
            }
        }
        return Optional.empty();
    }

    /** The stable id under which we register the running JVM (jawata-mcp#3). */
    private static final String RUNNING_JVM_ID = "jawata-running-jvm";

    /**
     * The EXTENSION id of JDT's standard VM install type — not its class name.
     *
     * <p>Sprint 28 (v3.6.3): these two differ, and the difference is the whole
     * macOS defect. {@code org.eclipse.jdt.launching}'s {@code plugin.xml}
     * contributes the type as
     * {@code class="org.eclipse.jdt.internal.launching.StandardVMType"} under
     * {@code id="org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType"},
     * and {@link JavaRuntime#getVMInstallType(String)} matches on
     * {@link IVMInstallType#getId()}, which {@code AbstractVMInstallType} fills
     * from the {@code id} attribute. Looking the type up by its class name
     * therefore returned {@code null} on EVERY platform — invisibly, because
     * only macOS ever reaches this code (see {@link #ensureDefaultVm()}).
     */
    private static final String STANDARD_VM_TYPE_ID =
        "org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType";

    /**
     * The implementing class, used as a fallback when the extension id changes
     * upstream. The id above is not API and JDT is free to rename it; the class
     * is what we actually need, so match on either.
     */
    private static final String STANDARD_VM_TYPE_CLASS =
        "org.eclipse.jdt.internal.launching.StandardVMType";

    /**
     * JDT's standard VM install type, or {@code null} when the launching
     * bundle contributes none.
     *
     * <p>Tries the declared extension id first, then falls back to scanning the
     * registered types for the implementing class, so an upstream id rename
     * degrades to a slower lookup rather than to a silently unbound JRE.
     */
    static IVMInstallType standardVmType() {
        IVMInstallType byId = JavaRuntime.getVMInstallType(STANDARD_VM_TYPE_ID);
        if (byId != null) {
            return byId;
        }
        for (IVMInstallType candidate : JavaRuntime.getVMInstallTypes()) {
            if (STANDARD_VM_TYPE_CLASS.equals(candidate.getClass().getName())) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Ensure the workspace has a default VM so {@code JRE_CONTAINER} binds
     * (jawata-mcp#3). No-op when one is already registered (the ordinary case);
     * otherwise register the JVM this process runs on ({@code java.home}) and
     * make it the default. Best-effort — a failure is logged, never fatal, so a
     * project still loads (degraded) rather than not at all.
     *
     * <p><b>Only macOS reaches the registration.</b> JDT's
     * {@code StandardVMType.detectInstallLocation()} opens with
     * {@code if (Platform.OS.isMac()) return null;}, so Linux and Windows
     * auto-detect the running JVM and return at the first line here. macOS
     * detects nothing, falls through, and depends entirely on the code below —
     * which is why a defect in it (Sprint 28: the wrong type id) presented as a
     * macOS-only failure while being platform-independent.
     */
    private static void ensureDefaultVm() {
        try {
            if (JavaRuntime.getDefaultVMInstall() != null) {
                return; // a default VM is already present — nothing to bind
            }
            java.io.File javaHome = new java.io.File(System.getProperty("java.home", ""));
            IVMInstallType stdType = standardVmType();
            if (stdType == null || !javaHome.isDirectory()) {
                log.warn("jawata-mcp#3: no default VM and cannot register one "
                    + "(type={}, java.home={}); JRE_CONTAINER may stay unbound", stdType, javaHome);
                return;
            }
            IVMInstall vm = stdType.findVMInstall(RUNNING_JVM_ID);
            if (vm == null) {
                for (IVMInstall existing : stdType.getVMInstalls()) {
                    if (javaHome.equals(existing.getInstallLocation())) {
                        vm = existing;
                        break;
                    }
                }
            }
            if (vm == null) {
                vm = stdType.createVMInstall(RUNNING_JVM_ID);
                vm.setName("jawata running JVM");
                vm.setInstallLocation(javaHome);
            }
            JavaRuntime.setDefaultVMInstall(vm, new NullProgressMonitor());
            log.info("jawata-mcp#3: registered the running JVM as the default VM: {}", javaHome);
        } catch (Exception e) {
            log.warn("jawata-mcp#3: could not register a default VM ({}: {}); "
                + "JRE_CONTAINER may stay unbound", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /**
     * Detect build system from project structure.
     *
     * <p>Order matters when a project has multiple markers. Maven wins over
     * Gradle wins over Bazel wins over Eclipse — common Tycho-style hybrids
     * (PDE + Maven pom) are classified as Maven so their dependency resolution
     * uses the Maven path. Plain Eclipse PDE bundles (MANIFEST.MF +
     * Bundle-SymbolicName, no pom/gradle) become ECLIPSE_PDE; plain Eclipse
     * projects (.classpath only) become ECLIPSE. (v1.7.1 / bug #4.)
     */
    public BuildSystem detectBuildSystem(java.nio.file.Path projectPath) {
        if (Files.exists(projectPath.resolve("pom.xml"))) {
            return BuildSystem.MAVEN;
        }
        if (Files.exists(projectPath.resolve("build.gradle")) ||
            Files.exists(projectPath.resolve("build.gradle.kts"))) {
            return BuildSystem.GRADLE;
        }
        // Bazel: check root-level workspace markers (not BUILD files, which are per-package)
        if (Files.exists(projectPath.resolve("MODULE.bazel")) ||
            Files.exists(projectPath.resolve("WORKSPACE.bazel")) ||
            Files.exists(projectPath.resolve("WORKSPACE"))) {
            return BuildSystem.BAZEL;
        }
        if (hasManifestSymbolicName(projectPath)) {
            return BuildSystem.ECLIPSE_PDE;
        }
        if (Files.exists(projectPath.resolve(".classpath"))) {
            return BuildSystem.ECLIPSE;
        }
        return BuildSystem.UNKNOWN;
    }

    /**
     * @return {@code true} iff {@code META-INF/MANIFEST.MF} exists at the
     * project root AND contains a {@code Bundle-SymbolicName} header. That
     * combination is what makes a directory an Eclipse PDE bundle.
     */
    private boolean hasManifestSymbolicName(java.nio.file.Path projectPath) {
        java.nio.file.Path manifest = projectPath.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifest)) {
            return false;
        }
        try {
            // Sprint 28 (C1 re-audit): NOT Files.lines. This runs from
            // detectBuildSystem on EVERY project load, and a manifest is exactly
            // where a non-UTF-8 byte turns up in the field — a Bundle-Vendor
            // with an umlaut is ordinary. Strict lazy decoding would throw
            // UncheckedIOException out of detection and take the whole load
            // with it; the catch below cannot see a RuntimeException.
            return readLinesLenient(manifest, 2000).stream()
                .anyMatch(line -> line.startsWith("Bundle-SymbolicName:"));
        } catch (java.io.IOException e) {
            log.debug("Failed to read {}: {}", manifest, e.getMessage());
            return false;
        }
    }

    /**
     * Detect if this is a multi-module Maven project.
     */
    public boolean isMultiModuleProject(java.nio.file.Path projectPath) {
        java.nio.file.Path pomPath = projectPath.resolve("pom.xml");
        if (!Files.exists(pomPath)) {
            return false;
        }
        try {
            String content = readStringLenient(pomPath);
            return content.contains("<modules>") || content.contains("<packaging>pom</packaging>");
        } catch (IOException e) {
            log.debug("Error reading pom.xml: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Get list of module directories for a multi-module project.
     */
    public List<java.nio.file.Path> getModules(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> modules = new ArrayList<>();
        java.nio.file.Path pomPath = projectPath.resolve("pom.xml");

        if (!Files.exists(pomPath)) {
            return modules;
        }

        try {
            String content = readStringLenient(pomPath);
            Matcher matcher = MODULE_PATTERN.matcher(content);
            while (matcher.find()) {
                String moduleName = matcher.group(1).trim();
                java.nio.file.Path modulePath = projectPath.resolve(moduleName);
                if (Files.exists(modulePath) && Files.isDirectory(modulePath)) {
                    modules.add(modulePath);
                }
            }
        } catch (IOException e) {
            log.warn("Error reading pom.xml for modules: {}", e.getMessage());
        }

        log.debug("Found {} modules in multi-module project", modules.size());
        return modules;
    }

    /**
     * Get all source directories, including from submodules if multi-module project.
     */
    private List<java.nio.file.Path> getAllSourcePaths(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> sourcePaths = new ArrayList<>();

        // v2.9.1 (dogfood D1): module traversal is RECURSIVE — nested aggregators
        // (root pom -> build/ aggregator -> leaf modules whose <sourceDirectory>
        // points back into sibling bundle dirs, the post-22d jawata-mcp shape)
        // yielded 0 sources under the old one-level walk, blinding the resident
        // to its own code and cascading into the store's staleness pass.
        collectSourcePaths(projectPath, sourcePaths, new HashSet<>(), 0);

        // For Bazel projects without standard source layout, scan for Java source directories
        if (sourcePaths.isEmpty() && detectBuildSystem(projectPath) == BuildSystem.BAZEL) {
            addBazelSourcePaths(projectPath, sourcePaths);
        }

        // Sprint 28 (D-IMPORTER): LAST RESORT for any layout none of the rules
        // above recognises. Without this a project with sources under, say,
        // `sources/` loaded with ZERO source roots and reported success — its
        // files silently absent from every listing and scan, which reads as "no
        // findings" about code that was never on the classpath. Loading empty
        // and looking healthy is the failure this closes; discovering the roots
        // from the package declarations is the same derivation the Bazel path
        // uses, applied without needing to recognise the build system at all.
        if (sourcePaths.isEmpty()) {
            addDiscoveredSourceRoots(projectPath, sourcePaths);
        }

        // Sprint 11 Phase C: for Gradle projects, also pull source directories
        // declared via sourceSets in build.gradle (custom srcDirs etc.) — the
        // Tooling API gives us the resolved list. Falls back silently to the
        // heuristic when Gradle isn't reachable.
        if (detectBuildSystem(projectPath) == BuildSystem.GRADLE) {
            readGradleProjectModel(projectPath).ifPresent(model -> {
                for (java.nio.file.Path declared : model.srcPaths()) {
                    if (!sourcePaths.contains(declared)) {
                        sourcePaths.add(declared);
                    }
                }
            });
        }

        return sourcePaths;
    }

    /** Depth-first module traversal (cycle-safe via visited set, depth-capped). */
    private void collectSourcePaths(java.nio.file.Path dir, List<java.nio.file.Path> sourcePaths,
            Set<java.nio.file.Path> visited, int depth) {
        if (depth > 10 || !visited.add(dir.toAbsolutePath().normalize())) {
            return;
        }
        List<java.nio.file.Path> found = new ArrayList<>();
        addSourcePathsFromDirectory(dir, found);
        for (java.nio.file.Path src : found) {
            if (!sourcePaths.contains(src)) {
                sourcePaths.add(src);
            }
        }
        if (isMultiModuleProject(dir)) {
            for (java.nio.file.Path modulePath : getModules(dir)) {
                collectSourcePaths(modulePath, sourcePaths, visited, depth + 1);
            }
        }
    }

    /**
     * Add source paths from a single project directory.
     *
     * Discovery precedence (ADR 0001):
     * 1. pom.xml &lt;sourceDirectory&gt; / &lt;testSourceDirectory&gt; overrides if declared.
     * 2. Eclipse .classpath &lt;classpathentry kind="src"&gt; entries if .classpath exists.
     * 3. Hardcoded SOURCE_MAPPINGS heuristic walk + &lt;root&gt;/src/ fallback.
     */
    private void addSourcePathsFromDirectory(java.nio.file.Path projectPath, List<java.nio.file.Path> sourcePaths) {
        int initialSize = sourcePaths.size();

        // 1. pom.xml <sourceDirectory> / <testSourceDirectory> overrides.
        SourceDirs pomDirs = readPomSourceDirs(projectPath.resolve("pom.xml"));
        pomDirs.srcMain().filter(Files::isDirectory).ifPresent(sourcePaths::add);
        pomDirs.srcTest().filter(Files::isDirectory).ifPresent(sourcePaths::add);
        if (sourcePaths.size() > initialSize) {
            return;
        }

        // 2. Eclipse .classpath src entries.
        ClasspathInfo cp = readEclipseClasspath(projectPath);
        for (java.nio.file.Path src : cp.srcPaths()) {
            if (Files.isDirectory(src)) {
                sourcePaths.add(src);
            }
        }
        if (sourcePaths.size() > initialSize) {
            return;
        }

        // 3. Heuristic: standard SOURCE_MAPPINGS layouts.
        for (int i = 0; i < SOURCE_MAPPINGS.length - 1; i++) {
            java.nio.file.Path srcPath = projectPath.resolve(SOURCE_MAPPINGS[i][0]);
            if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                sourcePaths.add(srcPath);
            }
        }

        // Only add "src" fallback if no standard layout found for this directory
        boolean foundStandard = sourcePaths.stream()
            .anyMatch(p -> p.startsWith(projectPath));
        if (!foundStandard) {
            java.nio.file.Path srcPath = projectPath.resolve("src");
            if (Files.exists(srcPath) && Files.isDirectory(srcPath)) {
                sourcePaths.add(srcPath);
            }
        }
    }

    /**
     * Is this source root TEST code?
     *
     * <p>Sprint 28 Stage 2 (D-UNWIRED's producer). The importer has always
     * KNOWN test-ness — {@code readPomSourceDirs} returns {@code srcMain} and
     * {@code srcTest} separately, {@code .classpath} carries the {@code test}
     * flag — and then flattened everything into one {@code List<Path>},
     * destroying the knowledge before it reached the model. That is why
     * {@code compile_workspace(scope=…)} misclassifies jawata's own test
     * bundles (mcp#9): the model never learned what the importer knew.</p>
     *
     * <p>Three rules, in an order that is LOAD-BEARING, not cosmetic:</p>
     * <ol>
     *   <li><b>An explicit declaration wins.</b> Tycho
     *       {@code eclipse-test-plugin} packaging (the whole bundle is test
     *       code); Maven's {@code <testSourceDirectory>} /
     *       {@code <sourceDirectory>}; the {@code .classpath} {@code test}
     *       flag — and a {@code .classpath} src entry WITHOUT the flag is an
     *       explicit MAIN, because that is what the absence means to
     *       Eclipse.</li>
     *   <li><b>Else the folder convention.</b> {@code src/test/**} → test,
     *       {@code src/main/**} → main. This must outrank content:
     *       {@code build/testrunner/src/main/java} imports JUnit because it
     *       RUNS tests, and content alone would mislabel the runner as test
     *       code.</li>
     *   <li><b>Else the content.</b> A flat root with no declaration and no
     *       convention — jawata's own {@code *.tests} bundles are exactly
     *       this shape — is test code iff it contains classes importing a
     *       test framework ({@code org.junit}, {@code org.testng}).</li>
     * </ol>
     *
     * <p>Classification happens HERE, at emission, through one pure function —
     * not by re-plumbing the six collectors' {@code List<Path>} into a tagged
     * type. This checkpoint's own record shows a measured
     * one-new-defect-per-fix rate for structural changes to the collection
     * paths; one function applied at one site is the shape that risk
     * allows.</p>
     */
    boolean isTestSourceRoot(java.nio.file.Path srcPath, java.nio.file.Path projectPath) {
        java.nio.file.Path owner = owningModuleDir(srcPath);

        // Rule 1a — Tycho: the bundle's packaging declares the WHOLE bundle.
        if (owner != null
                && "eclipse-test-plugin".equals(
                    readPomPackaging(owner.resolve("pom.xml")).orElse(null))) {
            return true;
        }
        // Rule 1b — Maven's explicit source declarations.
        if (owner != null) {
            SourceDirs pomDirs = readPomSourceDirs(owner.resolve("pom.xml"));
            if (pomDirs.srcTest().map(srcPath::equals).orElse(false)) {
                return true;
            }
            if (pomDirs.srcMain().map(srcPath::equals).orElse(false)) {
                return false;
            }
            // Rule 1c — the .classpath test flag; an entry WITHOUT it is
            // explicit main.
            ClasspathInfo cp = readEclipseClasspath(owner);
            if (cp.testSrcPaths().contains(srcPath)) {
                return true;
            }
            if (cp.srcPaths().contains(srcPath)) {
                return false;
            }
        }
        // Rule 2 — the folder convention.
        String slashed = srcPath.toString().replace(File.separatorChar, '/');
        if (slashed.contains("/src/test/") || slashed.endsWith("/src/test")) {
            return true;
        }
        if (slashed.contains("/src/main/") || slashed.endsWith("/src/main")) {
            return false;
        }
        // Rule 3 — the content.
        return containsTestFrameworkImports(srcPath);
    }

    /**
     * The module directory that DECLARES {@code srcPath}: the nearest ancestor
     * carrying a {@code pom.xml} or {@code .classpath}. Walks upward unbounded
     * by the project root, because a declared source dir may live OUTSIDE the
     * project tree ({@code <sourceDirectory>../../bundle/src</sourceDirectory>}
     * is the post-22d jawata shape); bounded at 12 levels so a filesystem walk
     * to {@code /} cannot happen. Null when nothing declares it.
     */
    private static java.nio.file.Path owningModuleDir(java.nio.file.Path srcPath) {
        java.nio.file.Path dir = srcPath.getParent();
        for (int i = 0; dir != null && i < 12; i++, dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("pom.xml"))
                    || Files.isRegularFile(dir.resolve(".classpath"))) {
                return dir;
            }
        }
        return null;
    }

    /**
     * Does this root contain classes importing a test framework? Bounded (500
     * files, 120 lines each) and lenient — one unreadable or legacy-encoded
     * file must not decide, or abort, the classification.
     */
    private boolean containsTestFrameworkImports(java.nio.file.Path srcPath) {
        List<java.nio.file.Path> javaFiles = new ArrayList<>();
        walkPruned(srcPath, dir -> false, dir -> {
            if (javaFiles.size() < 500) {
                try (Stream<java.nio.file.Path> files = Files.list(dir)) {
                    files.filter(p -> p.toString().endsWith(".java"))
                         .limit(500L - javaFiles.size())
                         .forEach(javaFiles::add);
                } catch (IOException | UncheckedIOException e) {
                    log.debug("Could not list {}: {}", dir, e.getMessage());
                }
            }
        });
        for (java.nio.file.Path file : javaFiles) {
            try {
                for (String line : readLinesLenient(file, 120)) {
                    String t = line.trim();
                    if (t.startsWith("import ")
                            && (t.contains("org.junit.") || t.contains("org.testng."))) {
                        return true;
                    }
                }
            } catch (IOException e) {
                log.debug("Could not read {} for test-framework imports: {}", file, e.getMessage());
            }
        }
        return false;
    }

    /**
     * Create linked folders for source directories and add them to classpath.
     * Uses linked folders to keep Eclipse metadata in the workspace.
     * Supports multi-module projects by scanning submodules.
     */
    private void addSourceEntries(List<IClasspathEntry> entries, IProject project,
            java.nio.file.Path projectPath, org.jawata.core.workspace.WorkspaceManager workspaceManager)
            throws CoreException {

        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);
        int folderIndex = 0;

        for (java.nio.file.Path srcPath : sourcePaths) {
            // Create unique linked folder name based on relative path
            String relativePath = projectPath.relativize(srcPath).toString().replace(File.separator, "-");
            String linkedName = "src-" + folderIndex + "-" + sanitizeFolderName(relativePath);
            folderIndex++;

            try {
                workspaceManager.createLinkedFolder(project, linkedName, srcPath);
                IPath sourceEntryPath = project.getFolder(linkedName).getFullPath();
                // Sprint 28 Stage 2: carry test-ness into the MODEL, as JDT's
                // own TEST attribute — the knowledge used to be destroyed right
                // here, which is why scope-filtered tools misclassified test
                // bundles (mcp#9). Absence of the attribute is JDT's spelling
                // of "main".
                boolean test = isTestSourceRoot(srcPath, projectPath);
                IClasspathAttribute[] extraAttributes = test
                    ? new IClasspathAttribute[] {
                        JavaCore.newClasspathAttribute(IClasspathAttribute.TEST, "true") }
                    : new IClasspathAttribute[0];
                // JDT REFUSES a test root sharing the main output folder
                // ("must have a separate output folder", code 1015) — a real
                // model constraint the tag surfaced, and the mirror of the
                // real world: test classes never land in the production jar.
                IPath testOutput = test
                    ? project.getFullPath().append("test-classes")
                    : null;
                entries.add(JavaCore.newSourceEntry(sourceEntryPath,
                    new IPath[0], SOURCE_EXCLUSIONS, testOutput, extraAttributes));
                log.debug("Added linked source folder: {} -> {} (test={})", linkedName, srcPath,
                    extraAttributes.length > 0);
            } catch (Exception e) {
                // Swallowing this loaded a project with a MISSING SOURCE ROOT —
                // its files silently absent from every listing and scan ("no
                // findings" about code that was never on the classpath). A
                // project that cannot mount its source folders must not load.
                throw new CoreException(new org.eclipse.core.runtime.Status(
                    org.eclipse.core.runtime.IStatus.ERROR, "org.jawata.core",
                    "Creating the linked source folder for " + srcPath + " FAILED while loading "
                        + projectPath + " — the project would silently lack that source root, and"
                        + " every listing/scan over it would be incomplete. Run refresh_workspace"
                        + " and reload; if it persists, the workspace is unhealthy.", e));
            }
        }

        log.info("Added {} source folders (multi-module: {})", sourcePaths.size(), isMultiModuleProject(projectPath));
    }

    /**
     * Sanitize folder name for Eclipse project.
     */
    private String sanitizeFolderName(String name) {
        return name.replaceAll("[^a-zA-Z0-9\\-_]", "-").replaceAll("-+", "-");
    }

    private void addDependencyEntries(List<IClasspathEntry> entries, java.nio.file.Path projectPath,
            org.jawata.core.workspace.WorkspaceManager workspaceManager) {
        // Bug #7 (Sprint 14): dedupe library and project entries by IPath
        // before passing to setRawClasspath(). JDT throws
        // "Build path contains duplicate entry" on any dup. Sources of
        // duplication seen in production:
        //   1. .classpath listing the same jar twice.
        //   2. .classpath kind="lib" AND a build-system path both contributing
        //      the same resolved jar (the fork's own multi-module repo, where
        //      gradle-tooling-api-8.10.jar surfaces from both).
        //   3. Compiled-output directories overlapping a .classpath entry.
        //   4. Multiple Require-Bundle headers resolving to the same workspace
        //      sibling.
        // First occurrence wins; subsequent duplicates are dropped silently.
        Set<IPath> addedLibPaths = new HashSet<>();
        Set<IPath> addedProjectPaths = new HashSet<>();

        // Eclipse .classpath kind="lib" entries (ADR 0001).
        // Merged alongside build-system-resolved deps; pure-Eclipse projects without a pom
        // get full dependency resolution from .classpath alone.
        ClasspathInfo cp = readEclipseClasspath(projectPath);
        int classpathLibCount = 0;
        for (java.nio.file.Path lib : cp.libPaths()) {
            if (Files.isRegularFile(lib)) {
                IPath eclipsePath = new Path(lib.toString());
                if (addedLibPaths.add(eclipsePath)) {
                    entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
                    classpathLibCount++;
                }
            }
        }

        BuildSystem buildSystem = detectBuildSystem(projectPath);
        boolean tycho = buildSystem == BuildSystem.MAVEN && isTychoProject(projectPath);
        if (tycho) {
            log.debug("Tycho packaging detected at {} — skipping mvn dependency:build-classpath", projectPath);
        }

        List<String> jars = switch (buildSystem) {
            case MAVEN -> tycho ? List.of() : getMavenDependencies(projectPath);
            case GRADLE -> getGradleDependencies(projectPath);
            case BAZEL -> getBazelDependencies(projectPath);
            default -> List.of();
        };

        for (String jar : jars) {
            java.nio.file.Path jarPath = java.nio.file.Path.of(jar);
            if (Files.exists(jarPath)) {
                IPath eclipsePath = new Path(jar);
                if (addedLibPaths.add(eclipsePath)) {
                    entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
                }
            }
        }

        if (classpathLibCount > 0) {
            log.debug("Added {} library entries from .classpath", classpathLibCount);
        }

        // Sprint 28 (mcp#3): required PROJECTS declared in .classpath as
        // kind="src" path="/other.project". These are the sibling plug-ins a
        // PDE tree compiles against. They used to be parsed as source folders
        // and silently dropped, which is why a 5-reference project reached JDT
        // with at most the one reference its Require-Bundle header happened to
        // repeat. Resolved through the same workspace bundle registry the
        // Require-Bundle pass uses: in a PDE workspace a project reference
        // names a sibling whose bundle symbolic name is its project name.
        int projectRefEntries = 0;
        for (String refName : cp.projectRefs()) {
            Optional<org.eclipse.jdt.core.IJavaProject> sibling = workspaceManager == null
                ? Optional.empty() : workspaceManager.resolveBundle(refName);
            if (sibling.isPresent()) {
                IPath projPath = sibling.get().getPath();
                if (addedProjectPaths.add(projPath)) {
                    entries.add(JavaCore.newProjectEntry(projPath));
                    projectRefEntries++;
                }
            } else {
                log.debug("Required project '{}' from .classpath is not loaded in this workspace; skipping",
                        refName);
            }
        }
        if (projectRefEntries > 0) {
            log.info("Resolved {} required project(s) from .classpath", projectRefEntries);
        }

        // Add compiled classes directories (Maven)
        addIfExists(entries, projectPath, "target/classes", addedLibPaths);
        addIfExists(entries, projectPath, "target/test-classes", addedLibPaths);
        // Add compiled classes directories (Gradle)
        addIfExists(entries, projectPath, "build/classes/java/main", addedLibPaths);
        addIfExists(entries, projectPath, "build/classes/java/test", addedLibPaths);

        // Sprint 11 Phase B: workspace bundle pool — resolve Require-Bundle
        // entries against sibling projects already loaded into the workspace.
        // Sprint 23 (D7): whatever the workspace pool does NOT satisfy is
        // resolved against EXTERNAL pools (materialized target platform via
        // jawata.bundle.pools, the shared p2 pool, the server's own dist
        // bundles) — as EXPORTED library entries, plus an Import-Package pass
        // over the pools' Export-Package index. Still-unresolved requirements
        // stay unresolved (logged), exactly as before.
        int bundleEntries = 0;
        List<String> unresolvedRequires = new ArrayList<>();
        for (String required : readManifestRequireBundle(projectPath)) {
            Optional<org.eclipse.jdt.core.IJavaProject> sibling = workspaceManager == null
                ? Optional.empty() : workspaceManager.resolveBundle(required);
            if (sibling.isPresent()) {
                IPath projPath = sibling.get().getPath();
                if (addedProjectPaths.add(projPath)) {
                    entries.add(JavaCore.newProjectEntry(projPath));
                    bundleEntries++;
                }
            } else {
                unresolvedRequires.add(required);
            }
        }
        List<String> importedPackages = readManifestImportPackage(projectPath);
        List<String> junitBundles = junitContainerBundles(cp.containers());
        if (!unresolvedRequires.isEmpty() || !importedPackages.isEmpty() || !junitBundles.isEmpty()) {
            ExternalBundlePool pool = ExternalBundlePool.index(ExternalBundlePool.defaultPoolDirs());
            int external = 0;

            // Sprint 28 (mcp#3): the JDT JUnit container. Eclipse resolves
            // JUNIT_CONTAINER/<n> to the JUnit runtime; jawata's synthetic
            // project has no containers at all, so JUnit annotations did not
            // resolve and find_tests reported ZERO test classes in a tree that
            // had three test source folders — an honest-looking empty answer
            // for a question that could not be asked.
            for (String symbolicName : junitBundles) {
                Optional<java.nio.file.Path> jar = pool.bundleJar(symbolicName);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (addedLibPaths.add(eclipsePath)) {
                        entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
                        external++;
                    }
                } else {
                    log.debug("JUnit container bundle '{}' not found in the external pools; skipping",
                            symbolicName);
                }
            }
            for (String required : unresolvedRequires) {
                Optional<java.nio.file.Path> jar = pool.bundleJar(required);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (addedLibPaths.add(eclipsePath)) {
                        // exported=true: a required bundle's classes are part of
                        // this project's runtime surface for dependents.
                        entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null, true));
                        external++;
                    }
                } else {
                    log.debug("Require-Bundle '{}' not found in workspace or external pools; skipping", required);
                }
            }
            for (String pkg : importedPackages) {
                Optional<java.nio.file.Path> jar = pool.packageProvider(pkg);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (addedLibPaths.add(eclipsePath)) {
                        entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null, true));
                        external++;
                    }
                } else {
                    log.debug("Import-Package '{}' has no provider in the external pools; skipping", pkg);
                }
            }
            if (external > 0) {
                log.info("Resolved {} PDE requirement(s) from the external bundle pools", external);
            }
        }
        if (bundleEntries > 0) {
            log.info("Resolved {} Require-Bundle entries from the workspace bundle pool", bundleEntries);
        }

        log.info("Added {} dependency entries from {}", jars.size(), buildSystem);
    }

    /** The JDT JUnit classpath container, as written in a {@code .classpath}. */
    private static final String JUNIT_CONTAINER = "org.eclipse.jdt.junit.JUNIT_CONTAINER";

    /**
     * The bundle symbolic names that stand in for a {@code JUNIT_CONTAINER}
     * entry, by declared JUnit version.
     *
     * <p>Sprint 28 (mcp#3). Eclipse expands the container itself; a synthetic
     * JDT project has no containers, so without this the JUnit types never
     * resolve and {@code find_tests} answers "no tests" for a project full of
     * them. The container path carries the version — {@code JUNIT_CONTAINER/3}
     * and {@code /4} are the JUnit-4 line, {@code /5} the Jupiter line. An
     * unversioned or unrecognised suffix asks for BOTH sets rather than
     * guessing: a superfluous jar on the classpath is harmless, a missing one
     * silently breaks every test lookup.</p>
     *
     * @param containers the {@code kind="con"} paths read from {@code .classpath}
     * @return the symbolic names to resolve against the external bundle pools,
     *         in a stable order and without duplicates; empty when no JUnit
     *         container is declared
     */
    static List<String> junitContainerBundles(List<String> containers) {
        List<String> junit4 = List.of("org.junit", "org.hamcrest.core");
        List<String> junit5 = List.of(
                "org.junit.jupiter.api",
                "org.junit.jupiter.engine",
                "org.junit.jupiter.params",
                "org.junit.platform.commons",
                "org.junit.platform.engine",
                "org.opentest4j",
                "org.apiguardian.api");
        List<String> result = new ArrayList<>();
        for (String container : containers) {
            if (container == null || !container.startsWith(JUNIT_CONTAINER)) {
                continue;
            }
            String suffix = container.substring(JUNIT_CONTAINER.length());
            if (suffix.startsWith("/")) {
                suffix = suffix.substring(1);
            }
            List<String> wanted = switch (suffix) {
                case "3", "4" -> junit4;
                case "5" -> junit5;
                default -> {
                    List<String> both = new ArrayList<>(junit4);
                    both.addAll(junit5);
                    yield both;
                }
            };
            for (String name : wanted) {
                if (!result.contains(name)) {
                    result.add(name);
                }
            }
        }
        return result;
    }

    private void addIfExists(List<IClasspathEntry> entries, java.nio.file.Path projectPath,
                              String relativePath, Set<IPath> addedLibPaths) {
        java.nio.file.Path fullPath = projectPath.resolve(relativePath);
        if (Files.exists(fullPath) && Files.isDirectory(fullPath)) {
            IPath eclipsePath = new Path(fullPath.toString());
            if (addedLibPaths.add(eclipsePath)) {
                entries.add(JavaCore.newLibraryEntry(eclipsePath, null, null));
            }
        }
    }

    /** Per-module classpath file name — relative, so the reactor writes one per module. */
    private static final String CP_FILE_NAME = "jawata-classpath.txt";

    /**
     * v2.9.2 (dogfood D4): resolve the Maven executable robustly. A Studio-launched
     * resident inherits a desktop/AppImage PATH without the user's shell profile —
     * /opt/apache-maven/bin was absent, ProcessBuilder("mvn") failed silently, and
     * the whole workspace classpath came back EMPTY (17k unresolved imports).
     * Order: project wrapper (mvnw) > PATH > caller-supplied known locations.
     * Package-visible seam for tests; production callers use the 1-arg overload.
     */
    static java.nio.file.Path resolveMavenCommand(java.nio.file.Path projectPath, String pathEnv,
            List<java.nio.file.Path> knownLocations, boolean windows) {
        String mvn = windows ? "mvn.cmd" : "mvn";
        java.nio.file.Path wrapper = projectPath.resolve(windows ? "mvnw.cmd" : "mvnw");
        if (Files.isExecutable(wrapper)) {
            return wrapper;
        }
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                if (!dir.isBlank()) {
                    java.nio.file.Path candidate = java.nio.file.Path.of(dir).resolve(mvn);
                    if (Files.isExecutable(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        for (java.nio.file.Path dir : knownLocations) {
            java.nio.file.Path candidate = dir.resolve(mvn);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private java.nio.file.Path resolveMavenCommand(java.nio.file.Path projectPath) {
        List<java.nio.file.Path> known = new ArrayList<>();
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null && !mavenHome.isBlank()) {
            known.add(java.nio.file.Path.of(mavenHome, "bin"));
        }
        known.add(java.nio.file.Path.of("/opt/apache-maven/bin"));
        known.add(java.nio.file.Path.of("/opt/maven/bin"));
        known.add(java.nio.file.Path.of("/usr/share/maven/bin"));
        known.add(java.nio.file.Path.of("/usr/local/bin"));
        String home = System.getProperty("user.home", "");
        if (!home.isBlank()) {
            known.add(java.nio.file.Path.of(home, ".sdkman", "candidates", "maven", "current", "bin"));
        }
        return resolveMavenCommand(projectPath, System.getenv("PATH"), known, isWindows());
    }

    /**
     * Sprint 23 (Stage 6): resolution cache keyed by the CONTENT of every
     * pom.xml in the project tree. The mvn shell-out costs seconds; the same
     * unchanged pom set (fixture suites, project re-loads) pays it once per
     * JVM. Any pom edit changes the key; entries are absolute repository
     * paths, location-independent. Bounded.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, List<String>>
        MAVEN_CLASSPATH_CACHE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAVEN_CLASSPATH_CACHE_MAX = 64;

    private static String pomTreeHash(java.nio.file.Path projectPath) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            try (Stream<java.nio.file.Path> walk = Files.walk(projectPath, 8)) {
                for (java.nio.file.Path pom : walk
                        .filter(p -> "pom.xml".equals(p.getFileName().toString()))
                        .sorted()
                        .toList()) {
                    md.update(Files.readAllBytes(pom));
                }
            }
            return java.util.HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return null; // unhashable tree → no caching, resolve fresh
        }
    }

    List<String> getMavenDependencies(java.nio.file.Path projectPath) {
        // v2.9.1 (dogfood D1): the output file is RELATIVE — it resolves against each
        // module's basedir, so a multi-module reactor writes one file per module.
        // (An absolute path made every module OVERWRITE the same file and the last,
        // dependency-less aggregator left it EMPTY — 0 classpath entries for the
        // whole workspace; -Dmdep.appendOutput proved a no-op in plugin 3.7.1.)
        // The per-module files are merged + deduped by parseClasspathOutput and
        // deleted after reading; they live under target/, never in source dirs.
        //
        // FAILURE IS LOUD. Every failure path here used to return an EMPTY list
        // (warn-logged into a NOP in the test harness), which loaded the project
        // WITHOUT its declared dependencies — and the first symptom was a tool
        // answering "Type not found" about a type sitting in the local repo
        // (reproduced under 4-way suite-shard load: resolved classpath of 3
        // entries, junit jar absent). A transient failure gets ONE retry; a
        // persistent one fails the LOAD, with the cause, instead of poisoning it.
        java.nio.file.Path mvnCmd = resolveMavenCommand(projectPath);
        if (mvnCmd == null) {
            throw new DependencyResolutionException(
                "No Maven executable found (checked project mvnw, PATH, MAVEN_HOME, known install"
                    + " dirs) while loading " + projectPath + ". Its Maven dependencies cannot be"
                    + " resolved, and loading it anyway would produce a project whose classpath"
                    + " silently lacks them — every compile/type/search answer would be wrong."
                    + " Make Maven reachable, then reload the project.");
        }
        String cacheKey = pomTreeHash(projectPath);
        if (cacheKey != null) {
            List<String> cached = MAVEN_CLASSPATH_CACHE.get(cacheKey);
            if (cached != null) {
                log.debug("Maven classpath served from cache ({} entries)", cached.size());
                return new ArrayList<>(cached);
            }
        }
        DependencyResolutionException firstFailure = null;
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                List<String> jars = runMavenBuildClasspath(mvnCmd, projectPath);
                if (cacheKey != null && MAVEN_CLASSPATH_CACHE.size() < MAVEN_CLASSPATH_CACHE_MAX) {
                    MAVEN_CLASSPATH_CACHE.put(cacheKey, List.copyOf(jars));
                }
                return jars; // may be genuinely empty: a pom with no dependencies
            } catch (DependencyResolutionException e) {
                log.warn("Maven dependency resolution attempt {}/2 failed for {}: {}",
                    attempt, projectPath, e.getMessage());
                if (firstFailure == null) {
                    firstFailure = e;
                }
            }
        }
        throw firstFailure;
    }

    /**
     * One {@code mvn dependency:build-classpath} run; throws instead of returning partial truth.
     *
     * <p>The output filename carries a per-invocation NONCE. With a fixed name, concurrent
     * resolutions of the SAME directory (four suite-shard JVMs all loading the in-place
     * {@code simple-maven} fixture as their first test) wrote, read and DELETED one shared
     * file — and the loser read nothing from a Maven run that exited 0, parsed zero jars,
     * and cached the empty classpath for the JVM's lifetime. Proven live: 3 of 24 staggered
     * concurrent runs lost their file exactly that way. The name must stay RELATIVE
     * (v2.9.1: an absolute path makes every reactor module overwrite one file).</p>
     */
    private List<String> runMavenBuildClasspath(java.nio.file.Path mvnCmd,
            java.nio.file.Path projectPath) {
        String cpFileName = "jawata-classpath-" + ProcessHandle.current().pid() + "-"
            + Long.toHexString(System.nanoTime()) + ".txt";
        try {
            ProcessBuilder pb = new ProcessBuilder(
                mvnCmd.toString(),
                "dependency:build-classpath",
                "-Dmdep.outputFile=target/" + cpFileName,
                "-q"
            );
            pb.directory(projectPath.toFile());
            pb.redirectErrorStream(true);

            log.info("Running Maven ({}) to get classpath...", mvnCmd);
            Process process = pb.start();

            // Consume output to prevent blocking — keep a bounded tail so a
            // failure can SAY what Maven said instead of discarding it.
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (tail.size() >= 15) {
                        tail.pollFirst();
                    }
                    tail.addLast(line);
                }
            }

            boolean completed = process.waitFor(120, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new DependencyResolutionException(
                    "Maven dependency resolution timed out after 120s for " + projectPath + ".");
            }

            if (process.exitValue() != 0) {
                throw new DependencyResolutionException(
                    "Maven dependency resolution failed (exit " + process.exitValue() + ") for "
                        + projectPath + ". Last output: " + String.join(" | ", tail));
            }

            List<String> jars = new ArrayList<>();
            StringBuilder merged = new StringBuilder();
            int filesFound = 0;
            try (Stream<java.nio.file.Path> walk = Files.walk(projectPath, 8)) {
                for (java.nio.file.Path f : walk
                        .filter(pth -> cpFileName.equals(pth.getFileName().toString()))
                        .filter(pth -> pth.getParent() != null
                            && "target".equals(pth.getParent().getFileName().toString()))
                        .toList()) {
                    merged.append(Files.readString(f)).append('\n');
                    Files.deleteIfExists(f);
                    filesFound++;
                }
            }
            if (filesFound == 0) {
                // Even a dependency-less module writes its (empty) file — zero FILES from
                // an exit-0 run is an anomaly (historically: a sibling process stole the
                // shared file), and answering "no dependencies" over it poisons the project.
                throw new DependencyResolutionException(
                    "Maven exited 0 for " + projectPath + " but wrote no classpath output file"
                        + " (target/" + cpFileName + "). Refusing to treat that as 'no"
                        + " dependencies'.");
            }
            jars.addAll(parseClasspathOutput(merged.toString()));
            log.info("Got {} classpath entries from Maven ({} module file(s))",
                jars.size(), filesFound);
            return jars;
        } catch (DependencyResolutionException e) {
            throw e;
        } catch (Exception e) {
            throw new DependencyResolutionException(
                "Maven dependency resolution failed for " + projectPath + ": "
                    + e.getClass().getSimpleName()
                    + (e.getMessage() != null ? ": " + e.getMessage() : ""), e);
        }
    }

    /**
     * Parse {@code dependency:build-classpath} output: one line per reactor module
     * (with {@code -Dmdep.appendOutput=true}), entries newline- and
     * pathSeparator-delimited, deduped preserving order. Package-visible for tests.
     */
    static List<String> parseClasspathOutput(String content) {
        java.util.LinkedHashSet<String> jars = new java.util.LinkedHashSet<>();
        for (String line : content.split("\\R")) {
            for (String piece : line.split(File.pathSeparator)) {
                String trimmed = piece.trim();
                if (!trimmed.isEmpty()) {
                    jars.add(trimmed);
                }
            }
        }
        return new ArrayList<>(jars);
    }

    private List<String> getGradleDependencies(java.nio.file.Path projectPath) {
        // Sprint 11 Phase C: ask Gradle for the actual classpath via the
        // Tooling API. Falls back to an empty list when Gradle isn't
        // reachable (no internet on first run, daemon failure, etc.) —
        // build/classes/java/{main,test} added in addDependencyEntries
        // remains as a backstop for the heuristic case.
        return readGradleProjectModel(projectPath)
            .map(model -> model.classpathJars().stream()
                .map(java.nio.file.Path::toString)
                .toList())
            .orElseGet(List::of);
    }

    /**
     * Sprint 11 Phase C — Gradle Tooling API integration.
     *
     * <p>Connects to Gradle via the embedded
     * {@code gradle-tooling-api} jar (see {@code Bundle-ClassPath} in
     * MANIFEST.MF), queries the {@link EclipseProject} model, and
     * returns the resolved source directories and classpath jars.</p>
     *
     * <p>Returns {@link Optional#empty()} on any failure (no Gradle
     * distribution available, daemon launch failure, project doesn't
     * apply the {@code java} plugin, etc.) so callers can fall back to
     * heuristics. Failures are logged at debug level — they're expected
     * for non-Gradle projects and CI environments without network access.</p>
     */
    public static Optional<GradleProjectModel> readGradleProjectModel(java.nio.file.Path projectPath) {
        if (!Files.isDirectory(projectPath)) {
            return Optional.empty();
        }
        boolean hasBuildScript = Files.isRegularFile(projectPath.resolve("build.gradle"))
            || Files.isRegularFile(projectPath.resolve("build.gradle.kts"));
        if (!hasBuildScript) {
            return Optional.empty();
        }
        GradleConnector connector = GradleConnector.newConnector()
            .forProjectDirectory(projectPath.toFile());
        try (ProjectConnection connection = connector.connect()) {
            EclipseProject eclipseProject = connection.getModel(EclipseProject.class);
            List<java.nio.file.Path> srcPaths = new ArrayList<>();
            for (EclipseSourceDirectory srcDir : eclipseProject.getSourceDirectories()) {
                java.nio.file.Path resolved = srcDir.getDirectory().toPath().toAbsolutePath().normalize();
                if (Files.isDirectory(resolved)) {
                    srcPaths.add(resolved);
                }
            }
            List<java.nio.file.Path> classpathJars = new ArrayList<>();
            for (EclipseExternalDependency dep : eclipseProject.getClasspath()) {
                java.nio.file.Path jar = dep.getFile().toPath().toAbsolutePath().normalize();
                if (Files.isRegularFile(jar)) {
                    classpathJars.add(jar);
                }
            }
            return Optional.of(new GradleProjectModel(srcPaths, classpathJars));
        } catch (Exception e) {
            log.debug("Gradle Tooling API failed for {}: {}", projectPath, e.getMessage());
            return Optional.empty();
        } finally {
            // Daemons spawned by the Tooling API would otherwise stick around
            // after the test JVM exits. Disconnecting the singleton-per-JVM
            // connector tells them to stop on the next idle cycle.
            try {
                ((org.gradle.tooling.internal.consumer.DefaultGradleConnector) connector).disconnect();
            } catch (Throwable t) {
                // Best-effort cleanup; ignore if the internal API isn't available.
            }
        }
    }

    /** Outcome of {@link #readGradleProjectModel(java.nio.file.Path)}. */
    public record GradleProjectModel(
        List<java.nio.file.Path> srcPaths,
        List<java.nio.file.Path> classpathJars
    ) {}

    /**
     * Get dependency JARs from Bazel build output.
     * Scans bazel-bin and bazel-out for JAR files rather than running a Bazel subprocess,
     * similar to how Gradle dependencies are resolved via build output.
     */
    private List<String> getBazelDependencies(java.nio.file.Path projectPath) {
        List<String> jars = new ArrayList<>();
        scanBazelDirForJars(projectPath.resolve("bazel-bin"), jars);
        scanBazelDirForJars(projectPath.resolve("bazel-out"), jars);
        log.debug("Found {} JARs from Bazel output", jars.size());
        return jars;
    }

    private void scanBazelDirForJars(java.nio.file.Path dir, List<String> jars) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<java.nio.file.Path> stream = Files.walk(dir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                  .filter(Files::isRegularFile)
                  .map(java.nio.file.Path::toString)
                  .forEach(jars::add);
        } catch (IOException | UncheckedIOException e) {
            // UncheckedIOException too: Files.walk fails LAZILY, so one
            // unreadable directory inside the output tree surfaces as a
            // RuntimeException that would otherwise escape the load.
            log.warn("Failed to scan {} for JARs: {}", dir, e.getMessage());
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    /**
     * Count Java source files in the project.
     * Supports multi-module projects.
     */
    public int countSourceFiles(java.nio.file.Path projectPath) {
        int count = 0;
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);

        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                count += (int) stream.filter(p -> p.toString().endsWith(".java")).count();
            } catch (IOException | UncheckedIOException e) {
                log.warn("Failed to count files in {}", srcPath, e);
            }
        }
        return count;
    }

    /**
     * Find all packages in the project.
     * Supports multi-module projects.
     */
    public List<String> findPackages(java.nio.file.Path projectPath) {
        List<String> packages = new ArrayList<>();
        List<java.nio.file.Path> sourcePaths = getAllSourcePaths(projectPath);

        for (java.nio.file.Path srcPath : sourcePaths) {
            try (Stream<java.nio.file.Path> stream = Files.walk(srcPath)) {
                stream.filter(Files::isDirectory)
                      .filter(this::containsJavaFiles)
                      .map(p -> srcPath.relativize(p).toString())
                      .map(s -> s.replace(File.separator, "."))
                      .filter(s -> !s.isEmpty())
                      .filter(s -> !packages.contains(s))  // Avoid duplicates
                      .forEach(packages::add);
            } catch (IOException | UncheckedIOException e) {
                log.warn("Failed to find packages in {}", srcPath, e);
            }
        }

        return packages;
    }

    /**
     * Scan for Java source directories in a Bazel project.
     * Looks for directories containing both a BUILD/BUILD.bazel file and .java files.
     * Skips bazel-* output directories.
     */
    private void addBazelSourcePaths(java.nio.file.Path projectPath, List<java.nio.file.Path> sourcePaths) {
        List<java.nio.file.Path> packageDirs = new ArrayList<>();
        walkPruned(projectPath, dir -> isBazelOutputDirectory(projectPath, dir), dir -> {
            if (isBazelJavaPackage(dir)) {
                packageDirs.add(dir);
            }
        });
        addRootsForPackageDirs(packageDirs, sourcePaths, projectPath);
        log.debug("Found {} Bazel source roots", sourcePaths.size());
    }

    /**
     * Walk {@code projectPath}'s directories, PRUNING what must not be
     * descended into.
     *
     * <p>Sprint 28 (C1 audit). The previous form filtered {@link #IGNORED_DIRS}
     * out of a flat {@link Files#walk}, which tests only the LEAF name — so
     * {@code target} itself was rejected while
     * {@code target/generated-sources/annotations/com/example} passed the
     * filter, held {@code .java} files, and was mounted as a source root.
     * Generated sources then appear as duplicate types beside the originals
     * they were generated from. Pruning the subtree is the difference between
     * "do not add this directory" and "do not look inside it", and only the
     * second one is what {@code IGNORED_DIRS} means.</p>
     *
     * <p>{@code visitFileFailed} continues rather than aborting: one
     * unreadable directory must not end the scan of a project, which is the
     * same class of over-reaction as the strict decoding fixed in
     * {@link #readLinesLenient}.</p>
     */
    private static void walkPruned(java.nio.file.Path projectPath,
            Predicate<java.nio.file.Path> prune,
            java.util.function.Consumer<java.nio.file.Path> onDirectory) {
        try {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<java.nio.file.Path>() {
                @Override
                public FileVisitResult preVisitDirectory(java.nio.file.Path dir,
                        BasicFileAttributes attrs) {
                    if (!dir.equals(projectPath)
                            && (IGNORED_DIRS.contains(dir.getFileName().toString())
                                || prune.test(dir))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    onDirectory.accept(dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(java.nio.file.Path file, IOException exc) {
                    log.debug("Skipping unreadable path {} while scanning {}: {}",
                        file, projectPath, exc.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | UncheckedIOException e) {
            log.warn("Failed to scan {} for source directories: {}", projectPath, e.getMessage());
        }
    }

    /** Map package directories to their source roots, appending each new one once. */
    private void addRootsForPackageDirs(List<java.nio.file.Path> packageDirs,
            List<java.nio.file.Path> sourcePaths, java.nio.file.Path projectRoot) {
        // SHALLOWEST FIRST, then drop anything nested inside an accepted root.
        //
        // Sprint 28 (C1, audit round 5). De-duplicating was not enough: a
        // directory whose files declare NO package — or a package shallower
        // than its depth — yields ITSELF as a root, and if a sibling produced
        // an ancestor root, both were mounted. JDT then expects the default
        // package inside the nested one, so legal code reports "The declared
        // package "" does not match the expected package "com.example.util"",
        // the same file is counted twice, and the project's file count is
        // wrong in the direction that looks like more coverage.
        //
        // That is the Bazel-package-directory defect and the unknown-layout
        // defect a third time, re-created inside the last-resort path written
        // to close them — which is why nesting, not just equality, is what
        // gets checked here.
        List<DerivedRoot> candidates = packageDirs.stream()
            .map(dir -> derivedRootFor(dir, projectRoot))
            .distinct()
            .sorted(java.util.Comparator.comparingInt(
                (DerivedRoot d) -> d.root().getNameCount())
                .thenComparing(d -> d.root().toString()))
            .toList();

        // Only a PACKAGE-DERIVED root may suppress one nested inside it. A
        // fallback root — "the directory this file happened to sit in", from a
        // file that declares nothing — carries no evidence about where the
        // package tree begins, and letting it suppress deeper roots is how the
        // first version of this dropped a project's real sources.
        List<java.nio.file.Path> suppressors = candidates.stream()
            .filter(DerivedRoot::fromPackageDeclaration)
            .map(DerivedRoot::root)
            .toList();
        for (DerivedRoot candidate : candidates) {
            java.nio.file.Path root = candidate.root();
            java.util.Optional<java.nio.file.Path> outer = suppressors.stream()
                .filter(s -> !s.equals(root) && root.startsWith(s))
                .findFirst();
            if (outer.isPresent()) {
                log.debug("Skipping {} — inside the package-derived source root {}",
                    root, outer.get());
                continue;
            }
            if (!sourcePaths.contains(root)) {
                sourcePaths.add(root);
            }
        }
    }

    /**
     * Last-resort source-root discovery for a layout no rule recognised.
     *
     * <p>Sprint 28 (D-IMPORTER). Walks for directories holding {@code .java}
     * files and derives each one's source root from its package declaration —
     * the same mapping the Bazel path uses, minus the {@code BUILD}-file
     * requirement, so it works for a project with no build system at all.</p>
     *
     * <p>Only reached when every other rule found nothing, so it cannot
     * override a declared or conventional layout.</p>
     */
    private void addDiscoveredSourceRoots(java.nio.file.Path projectPath,
            List<java.nio.file.Path> sourcePaths) {
        List<java.nio.file.Path> packageDirs = new ArrayList<>();
        walkPruned(projectPath, dir -> false, dir -> {
            if (containsJavaFiles(dir)) {
                packageDirs.add(dir);
            }
        });
        addRootsForPackageDirs(packageDirs, sourcePaths, projectPath);
        if (sourcePaths.isEmpty()) {
            log.warn("No Java source roots found anywhere under {} — the project will load with"
                + " NO sources, so every listing and scan over it is empty by construction,"
                + " not because the code is clean.", projectPath);
        } else {
            log.info("Discovered {} source root(s) under {} by package declaration (no recognised"
                + " build layout)", sourcePaths.size(), projectPath);
        }
    }

    /**
     * Map a Bazel java PACKAGE directory to its SOURCE ROOT.
     *
     * <p>Sprint 28 (D-IMPORTER). In Bazel the {@code BUILD} file sits in the
     * package directory — {@code java/com/example/BUILD.bazel} beside
     * {@code Greeter.java} declaring {@code package com.example}. Adding that
     * directory as a source root makes JDT expect the DEFAULT package, so every
     * class in a Bazel project loaded with
     * <em>"The declared package &quot;com.example&quot; does not match the
     * expected package &quot;&quot;"</em> — detection passed, roots were found,
     * jars resolved, output was excluded, and nothing compiled.</p>
     *
     * <p>The source root is the directory the package path is relative to: strip
     * one parent per package segment. A default-package directory IS its own
     * root, and a package deeper than the path allows (a malformed tree) falls
     * back to the package directory rather than escaping the project.</p>
     */
    private java.nio.file.Path bazelSourceRootFor(java.nio.file.Path packageDir) {
        return derivedRootFor(packageDir, null).root();
    }

    /**
     * A candidate source root, and whether a {@code package} declaration
     * produced it.
     *
     * <p>The flag is load-bearing (C1, audit round 6). A root DERIVED from a
     * declaration is evidence about where the package tree begins; a root that
     * is merely "the directory a file happened to sit in" is a fallback. Only
     * the first may suppress a root nested inside it — treating the two alike
     * let a fallback swallow the real roots.</p>
     */
    private record DerivedRoot(java.nio.file.Path root, boolean fromPackageDeclaration) {}

    /**
     * Map a package directory to its source root, never escaping
     * {@code projectRoot}.
     *
     * <p>Sprint 28 (C1, audit round 6) — the CLAMP, and why its absence was
     * only fatal once de-nesting existed. Stripping one parent per package
     * segment can walk above the project: a file at the project root declaring
     * {@code package com.example} asks for two parents, landing OUTSIDE the
     * project entirely. Before de-nesting that bogus root was merely mounted
     * beside the correct ones and the code still resolved. With de-nesting it
     * sorted shallowest and suppressed every correct root, so nothing resolved
     * at all — a fix that turned a cosmetic defect into the exact failure the
     * discovery path exists to prevent: a project that loads with no usable
     * roots and reports success.</p>
     */
    private DerivedRoot derivedRootFor(java.nio.file.Path packageDir,
            java.nio.file.Path projectRoot) {
        Optional<String> declared = readPackageDeclaration(packageDir);
        if (declared.isEmpty()) {
            return new DerivedRoot(packageDir, false);
        }
        java.nio.file.Path root = packageDir;
        for (int i = 0; i < declared.get().split("\\.").length; i++) {
            java.nio.file.Path parent = root.getParent();
            if (parent == null || (projectRoot != null && !parent.startsWith(projectRoot))) {
                // The declaration is deeper than the tree allows — a source
                // file directly in the project root, or a malformed layout.
                // Its own directory is the honest answer; escaping the project
                // is never one.
                return new DerivedRoot(packageDir, false);
            }
            root = parent;
        }
        return new DerivedRoot(root, true);
    }

    /**
     * The {@code package} declaration of the first Java file in a directory, if
     * any declares one. Read textually — this runs while the classpath is being
     * BUILT, so no JDT model exists yet to ask.
     */
    private Optional<String> readPackageDeclaration(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> files = Files.list(dir)) {
            for (java.nio.file.Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                try {
                    Optional<String> pkg = readPackageDeclarationFrom(file);
                    if (pkg.isPresent()) {
                        return pkg;
                    }
                } catch (IOException e) {
                    log.debug("Could not read {} for its package declaration: {}", file, e.getMessage());
                }
            }
        } catch (IOException | UncheckedIOException e) {
            log.debug("Could not list {} for package declarations: {}", dir, e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * The {@code package} declaration of one Java file, read until the
     * declaration is found or the file's first type begins.
     *
     * <p>Sprint 28 (C1 audit round 3). A fixed line cap was the wrong shape:
     * bounded at 200 lines it silently misses a declaration under a long
     * licence header, and the directory then becomes its own source root with
     * every class in it landing in the wrong package — the exact defect this
     * whole path exists to fix, reintroduced by the cost control for it.
     * Unbounded, it reads a whole generated file to learn one line.</p>
     *
     * <p>The language settles it: the declaration, if present, precedes the
     * first type declaration. Stopping there is both correct and cheaper than
     * any line count, and a file with no declaration stops at its first type
     * rather than being read to the end.</p>
     */
    private static Optional<String> readPackageDeclarationFrom(java.nio.file.Path file)
            throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), lenientUtf8()))) {
            String line;
            boolean inBlockComment = false;
            while ((line = reader.readLine()) != null) {
                String code = stripComments(line, inBlockComment);
                inBlockComment = blockCommentStillOpen(line, inBlockComment);
                String trimmed = code.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                // `package\tcom.example;` is legal Java. Requiring the literal
                // "package " sent it to the type check below, which declared it
                // the default package — the defect this method prevents (C1,
                // final audit).
                // lookingAt, not matches: `package com.example; import java.util.List;`
                // is legal on one line, and requiring the whole line to BE the
                // declaration sent it to the type check below, which declared
                // it the default package (C1, audit round 5).
                Matcher pkg = PACKAGE_DECLARATION.matcher(trimmed);
                if (pkg.lookingAt()) {
                    return Optional.of(pkg.group(1).replaceAll("\\s+", ""));
                }
                if (TYPE_DECLARATION.matcher(trimmed).find()) {
                    return Optional.empty();   // default package
                }
            }
        }
        return Optional.empty();
    }

    /**
     * The start of a type declaration — where a package declaration can no
     * longer appear.
     *
     * <p>Applied only to lines with their COMMENTS REMOVED. Matching it against
     * raw lines was a defect of exactly the kind this method exists to prevent:
     * a licence header whose lines do not begin with {@code *} — legal and not
     * rare — stops the scan on any line starting "class hierarchy…" or
     * "record of changes…", the file is declared to be in the default package,
     * and every class under that directory lands in the wrong package.</p>
     */
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile(
        "package\\s+([\\p{L}_$][\\p{L}\\p{N}_$]*(?:\\s*\\.\\s*[\\p{L}_$][\\p{L}\\p{N}_$]*)*)\\s*;");

    private static final Pattern TYPE_DECLARATION = Pattern.compile(
        "^(?:public\\s+|final\\s+|abstract\\s+|sealed\\s+|non-sealed\\s+|static\\s+)*"
            + "(?:class|interface|enum|record|@interface)\\b");

    /** {@code line} with {@code //} and {@code /* *}{@code /} comment text removed. */
    private static String stripComments(String line, boolean inBlockComment) {
        StringBuilder code = new StringBuilder();
        boolean inBlock = inBlockComment;
        for (int i = 0; i < line.length(); i++) {
            if (inBlock) {
                if (line.startsWith("*/", i)) {
                    inBlock = false;
                    i++;
                }
                continue;
            }
            if (line.startsWith("//", i)) {
                break;
            }
            if (line.startsWith("/*", i)) {
                inBlock = true;
                i++;
                continue;
            }
            code.append(line.charAt(i));
        }
        return code.toString();
    }

    /** Whether a block comment is still open after {@code line}. */
    private static boolean blockCommentStillOpen(String line, boolean wasOpen) {
        boolean open = wasOpen;
        for (int i = 0; i < line.length(); i++) {
            if (open) {
                if (line.startsWith("*/", i)) {
                    open = false;
                    i++;
                }
            } else if (line.startsWith("//", i)) {
                break;
            } else if (line.startsWith("/*", i)) {
                open = true;
                i++;
            }
        }
        return open;
    }

    /**
     * A whole file, decoded so that no byte sequence can lose its content.
     *
     * <p>Sprint 28 (C1, final audit). {@link Files#readString} decodes UTF-8
     * STRICTLY, and its {@link java.nio.charset.MalformedInputException} is a
     * checked {@code IOException} — so every reader that wraps it in
     * {@code catch (IOException)} and returns an empty result converts "this
     * file has a byte I could not decode" into "this project declares
     * nothing". One legacy-encoded byte in a {@code pom.xml} or
     * {@code build.gradle} — a vendor name, a copyright line — and the project
     * silently takes the workspace default level. That is the defect this
     * entire sprint exists to end, and it survived three audit rounds here
     * because the exception type was checked and the catch therefore looked
     * correct. The type was never the problem; what the catch DID was.</p>
     *
     * <p>Substituting on malformed input keeps every byte that decodes, which
     * is all any of these readers need: a compliance level, a
     * {@code <module>} name and a {@code sourceCompatibility} are ASCII.</p>
     */
    private static String readStringLenient(java.nio.file.Path file) throws IOException {
        return lenientUtf8().decode(java.nio.ByteBuffer.wrap(Files.readAllBytes(file))).toString();
    }

    /** A UTF-8 decoder that substitutes rather than reports — see {@link #readLinesLenient}. */
    private static CharsetDecoder lenientUtf8() {
        return StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE);
    }

    /**
     * The first {@code maxLines} lines of a file, decoded so that no byte
     * sequence can abort the read.
     *
     * <p>Sprint 28 (C1 audit). {@link Files#lines} decodes LAZILY and strictly:
     * a malformed UTF-8 byte surfaces as an {@link UncheckedIOException} from
     * inside the terminal stream operation — a RuntimeException, which a
     * {@code catch (IOException)} around the try-with-resources does not catch.
     * The consequence is out of all proportion to the cause: one file somewhere
     * in the tree that is not UTF-8 (a source in a legacy encoding, a stray
     * binary named {@code .java}) throws out of source-root discovery and
     * aborts the load of the ENTIRE project. Bazel and unknown-layout projects
     * read a package declaration from every candidate directory, so they meet
     * that file on every load.</p>
     *
     * <p>Decoding with {@link CodingErrorAction#REPLACE} substitutes U+FFFD for
     * what cannot be decoded and reads on. A package declaration or a
     * preference key is ASCII; a file whose relevant line survives is answered
     * correctly, and a file whose does not simply yields no match — instead of
     * taking the project down with it. Genuine I/O failure still throws
     * {@link IOException}, which callers already handle.</p>
     */
    private static List<String> readLinesLenient(java.nio.file.Path file, int maxLines)
            throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(Files.newInputStream(file), lenientUtf8()))) {
            String line;
            while (lines.size() < maxLines && (line = reader.readLine()) != null) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static boolean isBazelOutputDirectory(java.nio.file.Path projectRoot, java.nio.file.Path dir) {
        if (dir.equals(projectRoot)) {
            return false;
        }
        java.nio.file.Path relative = projectRoot.relativize(dir);
        String first = relative.getName(0).toString();
        return first.startsWith("bazel-");
    }

    private boolean isBazelJavaPackage(java.nio.file.Path dir) {
        boolean hasBuildFile = Files.exists(dir.resolve("BUILD")) ||
                               Files.exists(dir.resolve("BUILD.bazel"));
        return hasBuildFile && containsJavaFiles(dir);
    }

    private boolean containsJavaFiles(java.nio.file.Path dir) {
        try (Stream<java.nio.file.Path> stream = Files.list(dir)) {
            return stream.anyMatch(p -> p.toString().endsWith(".java"));
        } catch (IOException | UncheckedIOException e) {
            // UncheckedIOException too: Files.list defers the directory read to
            // traversal, so an unreadable directory surfaces as a RuntimeException
            // that this catch would otherwise miss — and one such directory would
            // abort discovery for the whole project.
            log.debug("Could not list {}: {}", dir, e.getMessage());
            return false;
        }
    }

    // ============================================================
    // Portable project-metadata helpers (ADR 0004).
    //
    // Pure java.nio.file.Path + DOM/XML / java.util.jar.Manifest parsing.
    // No JDT, OSGi, or Eclipse Workspace types in helper signatures.
    // Designed to be lifted verbatim into a future Eclipse IDE plugin or
    // LSP-based standalone server.
    // ============================================================

    /** pom.xml &lt;sourceDirectory&gt; / &lt;testSourceDirectory&gt; overrides. */
    record SourceDirs(Optional<java.nio.file.Path> srcMain, Optional<java.nio.file.Path> srcTest) {
        static SourceDirs empty() {
            return new SourceDirs(Optional.empty(), Optional.empty());
        }
    }

    /**
     * Eclipse .classpath src/lib/output entries.
     *
     * <p>{@code projectRefs} holds the names of REQUIRED PROJECTS — in a
     * {@code .classpath} these are written as {@code kind="src"} entries whose
     * path begins with {@code /}, e.g.
     * {@code <classpathentry kind="src" path="/com.jats2.libs"/>}. They are
     * project references, not source folders, and were previously resolved
     * against the filesystem: {@code projectRoot.resolve("/x")} returns the
     * absolute {@code /x}, which never exists, so every one of them was
     * silently dropped (Sprint 28, mcp#3).</p>
     */
    record ClasspathInfo(List<java.nio.file.Path> srcPaths,
                          List<java.nio.file.Path> libPaths,
                          Optional<java.nio.file.Path> outputPath,
                          List<String> projectRefs,
                          List<String> containers,
                          List<java.nio.file.Path> testSrcPaths) {
        static ClasspathInfo empty() {
            return new ClasspathInfo(List.of(), List.of(), Optional.empty(), List.of(), List.of(),
                List.of());
        }
    }

    /**
     * Read the top-level &lt;packaging&gt; element from pom.xml.
     * Returns {@code Optional.empty()} when the pom is missing, malformed,
     * or has no &lt;packaging&gt; element (which Maven treats as "jar").
     */
    static Optional<String> readPomPackaging(java.nio.file.Path pomXml) {
        if (!Files.isRegularFile(pomXml)) {
            return Optional.empty();
        }
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(pomXml.toFile());
            // <packaging> is always a direct child of the project root in pom.xml.
            Element root = doc.getDocumentElement();
            if (root == null) {
                return Optional.empty();
            }
            NodeList kids = root.getChildNodes();
            for (int i = 0; i < kids.getLength(); i++) {
                if (kids.item(i) instanceof Element el && "packaging".equals(el.getTagName())) {
                    String text = el.getTextContent();
                    if (text != null) {
                        String trimmed = text.trim();
                        if (!trimmed.isEmpty()) {
                            return Optional.of(trimmed);
                        }
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to parse pom.xml at {}: {}", pomXml, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * True for Tycho packaging types (eclipse-plugin, eclipse-feature, …)
     * for which Maven's classpath-extraction goal produces misleading results.
     */
    static boolean isTychoPackaging(String packaging) {
        return packaging != null && TYCHO_PACKAGINGS.contains(packaging);
    }

    /**
     * True if {@code projectPath/pom.xml} declares a Tycho packaging type.
     * Used to gate the {@code mvn dependency:build-classpath} shell-out.
     */
    static boolean isTychoProject(java.nio.file.Path projectPath) {
        return readPomPackaging(projectPath.resolve("pom.xml"))
            .map(ProjectImporter::isTychoPackaging)
            .orElse(false);
    }

    /**
     * Read {@code Bundle-SymbolicName} from {@code META-INF/MANIFEST.MF},
     * stripping any directives such as {@code ;singleton:=true}.
     * Returns {@code Optional.empty()} when the manifest is absent, malformed,
     * or has no {@code Bundle-SymbolicName} header.
     *
     * <p>Phase B (Sprint 11): used by the workspace bundle pool to register
     * each loaded PDE bundle by its symbolic name so {@code Require-Bundle}
     * dependencies between sibling projects in the same workspace resolve
     * to project-typed classpath entries.</p>
     */
    public static Optional<String> readManifestSymbolicName(java.nio.file.Path projectRoot) {
        java.nio.file.Path manifestPath = projectRoot.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            Manifest manifest = new Manifest(in);
            Attributes attrs = manifest.getMainAttributes();
            String value = attrs.getValue("Bundle-SymbolicName");
            if (value == null) {
                return Optional.empty();
            }
            return Optional.of(stripDirectives(value));
        } catch (IOException e) {
            log.warn("Failed to parse MANIFEST.MF at {}: {}", manifestPath, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read {@code Require-Bundle} from {@code META-INF/MANIFEST.MF} and
     * return the list of required bundle symbolic names (without version
     * or visibility directives).
     *
     * <p>Multi-line OSGi headers (continuation lines starting with a single
     * space) are joined automatically by {@link Manifest}; this method
     * handles the resulting comma-separated list and per-entry directives.</p>
     */
    public static List<String> readManifestRequireBundle(java.nio.file.Path projectRoot) {
        java.nio.file.Path manifestPath = projectRoot.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            Manifest manifest = new Manifest(in);
            String header = manifest.getMainAttributes().getValue("Require-Bundle");
            if (header == null || header.isBlank()) {
                return List.of();
            }
            // Split on commas at the top level (commas inside quoted version
            // ranges would be a problem in theory; in practice OSGi version
            // ranges use semicolons after the comma-separated entries).
            List<String> result = new ArrayList<>();
            for (String entry : header.split(",")) {
                String name = stripDirectives(entry).trim();
                if (!name.isEmpty()) {
                    result.add(name);
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Failed to parse MANIFEST.MF at {}: {}", manifestPath, e.getMessage());
            return List.of();
        }
    }

    /**
     * Read {@code Import-Package} from {@code META-INF/MANIFEST.MF} and return
     * the imported package names (attributes/directives stripped; {@code java.*}
     * skipped — the JRE container provides those). Sprint 23 (D7): feeds the
     * external-pool Export-Package resolution.
     */
    public static List<String> readManifestImportPackage(java.nio.file.Path projectRoot) {
        java.nio.file.Path manifestPath = projectRoot.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return List.of();
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            Manifest manifest = new Manifest(in);
            String header = manifest.getMainAttributes().getValue("Import-Package");
            if (header == null || header.isBlank()) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (String entry : ExternalBundlePool.splitTopLevel(header)) {
                String name = stripDirectives(entry).trim();
                if (!name.isEmpty() && !name.startsWith("java.")) {
                    result.add(name);
                }
            }
            return result;
        } catch (IOException e) {
            log.warn("Failed to parse MANIFEST.MF at {}: {}", manifestPath, e.getMessage());
            return List.of();
        }
    }

    /** Strip OSGi attribute / directive suffixes (e.g. {@code ;singleton:=true}). */
    private static String stripDirectives(String value) {
        int semi = value.indexOf(';');
        return (semi == -1 ? value : value.substring(0, semi)).trim();
    }

    /**
     * Read pom.xml &lt;build&gt;&lt;sourceDirectory&gt; and &lt;testSourceDirectory&gt;.
     * Resolves declared paths against the pom's directory.
     * Returns SourceDirs.empty() if pom.xml is absent, malformed, or has no overrides.
     */
    static SourceDirs readPomSourceDirs(java.nio.file.Path pomXml) {
        if (!Files.isRegularFile(pomXml)) {
            return SourceDirs.empty();
        }
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(pomXml.toFile());
            java.nio.file.Path pomDir = pomXml.toAbsolutePath().getParent();
            return new SourceDirs(
                readBuildPath(doc, "sourceDirectory", pomDir),
                readBuildPath(doc, "testSourceDirectory", pomDir)
            );
        } catch (Exception e) {
            log.warn("Failed to parse pom.xml at {}: {}", pomXml, e.getMessage());
            return SourceDirs.empty();
        }
    }

    private static Optional<java.nio.file.Path> readBuildPath(Document doc, String elementName,
            java.nio.file.Path pomDir) {
        NodeList builds = doc.getElementsByTagName("build");
        for (int i = 0; i < builds.getLength(); i++) {
            NodeList kids = ((Element) builds.item(i)).getElementsByTagName(elementName);
            if (kids.getLength() > 0) {
                String text = kids.item(0).getTextContent().trim();
                if (!text.isEmpty()) {
                    // v2.9.1 (dogfood D1): interpolate the basedir properties — real
                    // poms write ${project.basedir}/../..; resolving the literal text
                    // produced a path with a ${...} component that silently failed
                    // the isDirectory filter.
                    text = text.replace("${project.basedir}", pomDir.toString())
                               .replace("${basedir}", pomDir.toString());
                    return Optional.of(pomDir.resolve(text).normalize());
                }
            }
        }
        return Optional.empty();
    }

    /** The {@code test} flag of one {@code <classpathentry>}, in either spelling. */
    private static boolean classpathEntryIsTest(Element entry) {
        if ("true".equals(entry.getAttribute("test"))) {
            return true;
        }
        NodeList attrs = entry.getElementsByTagName("attribute");
        for (int i = 0; i < attrs.getLength(); i++) {
            Element attr = (Element) attrs.item(i);
            if ("test".equals(attr.getAttribute("name")) && "true".equals(attr.getAttribute("value"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Read Eclipse .classpath at the project root.
     * Resolves &lt;classpathentry path="..."&gt; values against projectRoot
     * (so "../lib/foo.jar"-style relative refs work).
     * Returns ClasspathInfo.empty() if .classpath is absent or malformed.
     */
    static ClasspathInfo readEclipseClasspath(java.nio.file.Path projectRoot) {
        java.nio.file.Path file = projectRoot.resolve(".classpath");
        if (!Files.isRegularFile(file)) {
            return ClasspathInfo.empty();
        }
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            Document doc = builder.parse(file.toFile());
            NodeList entries = doc.getElementsByTagName("classpathentry");
            List<java.nio.file.Path> srcPaths = new ArrayList<>();
            List<java.nio.file.Path> libPaths = new ArrayList<>();
            List<String> projectRefs = new ArrayList<>();
            List<String> containers = new ArrayList<>();
            List<java.nio.file.Path> testSrcPaths = new ArrayList<>();
            Optional<java.nio.file.Path> outputPath = Optional.empty();
            for (int i = 0; i < entries.getLength(); i++) {
                Element entry = (Element) entries.item(i);
                String kind = entry.getAttribute("kind");
                String path = entry.getAttribute("path");
                if (path.isEmpty()) {
                    continue;
                }
                switch (kind) {
                    // Sprint 28 (mcp#3): a kind="src" entry whose path starts
                    // with '/' is a REQUIRED PROJECT, not a source folder —
                    // Eclipse writes project references in exactly this shape.
                    // Resolving it as a directory yields the absolute path
                    // itself (NIO resolve of an absolute path), which never
                    // exists, so all of them used to vanish without a word.
                    case "src" -> {
                        if (path.startsWith("/")) {
                            String name = path.substring(1);
                            if (!name.isEmpty()) {
                                projectRefs.add(name);
                            }
                        } else {
                            java.nio.file.Path resolved = projectRoot.resolve(path).normalize();
                            srcPaths.add(resolved);
                            // Sprint 28 Stage 2: the test flag. Eclipse writes it
                            // as a NESTED element —
                            //   <attributes><attribute name="test" value="true"/></attributes>
                            // — and some hand-written files carry it as a direct
                            // test="true" XML attribute. Read both: the point is
                            // the DECLARATION, whichever way it was spelled.
                            if (classpathEntryIsTest(entry)) {
                                testSrcPaths.add(resolved);
                            }
                        }
                    }
                    case "lib" -> libPaths.add(projectRoot.resolve(path).normalize());
                    case "output" -> outputPath = Optional.of(projectRoot.resolve(path).normalize());
                    case "con" -> containers.add(path);
                    default -> {
                        // "var" (variables) and unknown kinds: ignore.
                    }
                }
            }
            return new ClasspathInfo(srcPaths, libPaths, outputPath, projectRefs, containers,
                testSrcPaths);
        } catch (Exception e) {
            log.warn("Failed to parse .classpath at {}: {}", file, e.getMessage());
            return ClasspathInfo.empty();
        }
    }
}
