package org.jawata.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.IJdtService;
import org.jawata.core.JavaRuntimes;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.coverage.MutationService;
import org.jawata.mcp.execution.ForkedTestRunner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, contract §4.2 (ledger 28k-C5-15, 28k-A19-1): the Java runtime a child JVM runs on is the host's to say, by project. The
 * test runner, the mutation run and the debug launch each ask {@link ChildJvm}; with no host runtime the command line is what it was
 * before the host was asked, byte for byte.
 */
class ChildJvmTest {

    private static final Path HOST_JRE = Path.of("/opt/the-hosts-jre-for-alpha");

    @AfterEach
    void backToTheEngineThatRunsAlone() {
        // The resolver is process-wide, so whatever a test left is what every later test finds.
        ChildJvm.hostedBy(null, null);
    }

    /** A project of the given name, as far as the resolver looks: its Eclipse project's name. */
    static IJavaProject projectNamed(String name) {
        IProject project = (IProject) Proxy.newProxyInstance(ChildJvmTest.class.getClassLoader(), new Class<?>[] {IProject.class},
            (proxy, method, args) -> "getName".equals(method.getName()) ? name : objectMethod(proxy, method.getName(), args));
        return (IJavaProject) Proxy.newProxyInstance(ChildJvmTest.class.getClassLoader(), new Class<?>[] {IJavaProject.class},
            (proxy, method, args) -> "getProject".equals(method.getName()) ? project : objectMethod(proxy, method.getName(), args));
    }

    private static Object objectMethod(Object proxy, String name, Object[] args) {
        return switch (name) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> "proxy";
            default -> throw new UnsupportedOperationException(name);
        };
    }

    /** A model that holds the given projects, as far as the resolver looks: {@code allProjects()}. */
    private static IJdtService modelHolding(IJavaProject... projects) {
        return (IJdtService) Proxy.newProxyInstance(ChildJvmTest.class.getClassLoader(), new Class<?>[] {IJdtService.class},
            (proxy, method, args) -> {
                if ("allProjects".equals(method.getName())) {
                    List<LoadedProject> loaded = new ArrayList<>();
                    for (IJavaProject project : projects) {
                        loaded.add(new LoadedProject(project.getProject().getName(), Path.of("/p"), project, null, null, Instant.EPOCH,
                            0, 0, List.of(), null, List.of()));
                    }
                    return loaded;
                }
                return objectMethod(proxy, method.getName(), args);
            });
    }

    /** A host that knows the runtime of the project named alpha only; its model holds alpha and beta. */
    private static void hostThatKnowsAlpha(AtomicInteger asked) {
        IJdtService held = modelHolding(projectNamed("alpha"), projectNamed("beta"));
        JavaRuntimes runtimes = project -> {
            asked.incrementAndGet();
            return "alpha".equals(project.getProject().getName()) ? HOST_JRE : null;
        };
        ChildJvm.hostedBy(runtimes, () -> held);
    }

    private static String engineJava() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    @Test
    @DisplayName("a host that declares a runtime for a project: that runtime's java is the one a child JVM for the project runs")
    void aHostRuntimeIsTheJavaOfTheProject() {
        hostThatKnowsAlpha(new AtomicInteger());

        assertEquals(HOST_JRE.resolve("bin").resolve("java").toString(), ChildJvm.binary("alpha", "java").toString());
        assertEquals(HOST_JRE, ChildJvm.home("alpha"));
    }

    @Test
    @DisplayName("no host runtime, a project the host does not know, or no project named: the engine's own runtime, byte for byte")
    void everythingElseIsTheEnginesOwnRuntime() {
        // Nothing installed.
        assertEquals(engineJava(), ChildJvm.binary("alpha", "java").toString());
        assertEquals(Path.of(System.getProperty("java.home"), "bin", "jcmd").toString(), ChildJvm.binary(null, "jcmd").toString());

        AtomicInteger asked = new AtomicInteger();
        hostThatKnowsAlpha(asked);
        assertEquals(engineJava(), ChildJvm.binary("beta", "java").toString(), "the host answers nothing for beta");
        assertEquals(engineJava(), ChildJvm.binary("gamma", "java").toString(), "the model holds no gamma");
        int askedAboutProjects = asked.get();
        assertEquals(engineJava(), ChildJvm.binary(null, "java").toString(), "no project named");
        assertEquals(askedAboutProjects, asked.get(), "the host is asked about a project, never about none");

        ChildJvm.hostedBy(project -> HOST_JRE, () -> null);
        assertEquals(engineJava(), ChildJvm.binary("alpha", "java").toString(), "no model is loaded yet, so no project is found");
    }

    private static ForkedTestRunner.Spec runSpec(String projectName) {
        ForkedTestRunner.Spec spec = new ForkedTestRunner.Spec();
        spec.projectName = projectName;
        spec.classpath.add(Path.of("/p/classes"));
        spec.classpath.add(Path.of("/p/test-classes"));
        spec.vmArgs.add("-Dfoo=bar");
        return spec;
    }

    @Test
    @DisplayName("the test runner's command line starts with the host's java for the project; without one it is byte for byte what it was")
    void theTestRunnerAsksForTheProjectsJava() {
        Path argFile = Path.of("/s/run.args");
        Path childTmp = Path.of("/s/tmp");

        // Without a host runtime: the command as it was built before the host was asked.
        List<String> before = List.of(engineJava(), "-ea", "-Xmx512m", "-Dfoo=bar", "-Djava.io.tmpdir=/s/tmp", "-cp",
            "/p/classes" + java.io.File.pathSeparator + "/p/test-classes", "org.jawata.testrunner.Main", "/s/run.args");
        assertEquals(before, ForkedTestRunner.command(runSpec("alpha"), argFile, childTmp));
        assertEquals(before, ForkedTestRunner.command(runSpec(null), argFile, childTmp));

        hostThatKnowsAlpha(new AtomicInteger());
        List<String> withHost = new ArrayList<>(before);
        withHost.set(0, HOST_JRE.resolve("bin").resolve("java").toString());
        assertEquals(withHost, ForkedTestRunner.command(runSpec("alpha"), argFile, childTmp));
        assertEquals(before, ForkedTestRunner.command(runSpec("beta"), argFile, childTmp));
    }

    @Test
    @DisplayName("the mutation run's command line starts with the host's java for the project; without one it is byte for byte what it was")
    void theMutationRunAsksForTheProjectsJava() {
        List<Path> pit = List.of(Path.of("/t/pit.jar"));
        Set<String> projectCp = new LinkedHashSet<>(List.of("/p/classes", "/p/test-classes"));
        Path reportDir = Path.of("/r");
        List<String> before = MutationService.command(null, pit, projectCp, reportDir, List.of("a.B"), List.of("a.BTest"), List.of("/p/src"));

        assertEquals(engineJava(), before.get(0));
        assertEquals("-cp", before.get(1));
        assertEquals("org.pitest.mutationtest.commandline.MutationCoverageReport", before.get(3));
        assertTrue(before.contains("--targetClasses"));

        hostThatKnowsAlpha(new AtomicInteger());
        List<String> withHost = MutationService.command("alpha", pit, projectCp, reportDir, List.of("a.B"), List.of("a.BTest"), List.of("/p/src"));
        List<String> expected = new ArrayList<>(before);
        expected.set(0, HOST_JRE.resolve("bin").resolve("java").toString());
        assertEquals(expected, withHost);
        assertEquals(before, MutationService.command("beta", pit, projectCp, reportDir, List.of("a.B"), List.of("a.BTest"), List.of("/p/src")));
    }

    @Test
    @DisplayName("a debug launch's command line starts with the host's java for the project; without one it is byte for byte what it was")
    void theDebugLaunchAsksForTheProjectsJava() {
        List<String> program = List.of("-cp", "/p/classes", "a.Main", "x");
        List<String> extra = List.of("-Dextra=1");

        List<String> before = JvmTargets.launchCommand(null, program, extra);
        assertEquals(engineJava(), before.get(0));
        assertEquals(program, before.subList(before.size() - program.size(), before.size()));
        assertEquals(before, JvmTargets.launchCommand("alpha", program, extra), "nothing is hosted yet");

        hostThatKnowsAlpha(new AtomicInteger());
        List<String> expected = new ArrayList<>(before);
        expected.set(0, HOST_JRE.resolve("bin").resolve("java").toString());
        assertEquals(expected, JvmTargets.launchCommand("alpha", program, extra));
        assertEquals(before, JvmTargets.launchCommand("beta", program, extra));
    }
}
