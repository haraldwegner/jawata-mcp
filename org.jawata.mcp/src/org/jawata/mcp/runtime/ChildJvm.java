package org.jawata.mcp.runtime;

import java.nio.file.Path;
import java.util.function.Supplier;

import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.IJdtService;
import org.jawata.core.JavaRuntimes;
import org.jawata.core.LoadedProject;

/**
 * The one place the engine decides which Java runtime a child JVM it starts runs on (Sprint 28h, contract §4.2; ledger 28k-C5-15,
 * 28k-A19-1). A test run, a mutation run and a debug launch each start a JVM for a project; the project's runtime is the host's to say
 * (an IDE knows the runtime a project is set up with), so none of them reads {@code java.home} itself: they ask here, by the name of the
 * project they start the JVM for.
 *
 * <p>The Java provider installs the host's {@link JavaRuntimes} and the model the project's name is looked up in when it is handed its
 * host ({@link #hostedBy}). With no host runtime installed, for a project the host does not know, or when no project is named, the
 * answer is the runtime this engine runs on, which is what every site answered before the host was asked.</p>
 *
 * <p>A tool that is not a launch of a project's JVM (the profiler's {@code jcmd} against a JVM that is already running, the native
 * symboliser's {@code java} binary for a core dump) asks with no project and gets the engine's own runtime.</p>
 */
public final class ChildJvm {

    private static volatile JavaRuntimes runtimes;
    private static volatile Supplier<IJdtService> model;

    private ChildJvm() {
    }

    /**
     * Takes the host's runtimes, and the model a project's name is looked up in, for every later ask.
     *
     * @param hostRuntimes the host's answer to "which runtime does this project run on"; null puts the engine back to running alone
     * @param loadedModel  the Java model as it is at the moment of each ask; it answers null while none is loaded
     */
    public static void hostedBy(JavaRuntimes hostRuntimes, Supplier<IJdtService> loadedModel) {
        runtimes = hostRuntimes;
        model = loadedModel;
    }

    /**
     * The home of the runtime a child JVM for {@code projectName} runs on.
     *
     * @param projectName the Eclipse project's name, as {@code run_tests} and the debugger name the project they start a JVM for, or
     *                    null when the JVM is not for a project
     * @return the host's runtime for that project, else the runtime this engine runs on
     */
    public static Path home(String projectName) {
        JavaRuntimes host = runtimes;
        if (host != null && projectName != null) {
            IJavaProject project = projectNamed(projectName);
            Path hosts = project == null ? null : host.jreFor(project);
            if (hosts != null) {
                return hosts;
            }
        }
        return Path.of(System.getProperty("java.home"));
    }

    /**
     * A program in the {@code bin} folder of the runtime for {@code projectName}.
     *
     * @param projectName the project the JVM is started for, or null when it is not for a project
     * @param program     the program's name, such as {@code java} or {@code jcmd}
     * @return the program's path
     */
    public static Path binary(String projectName, String program) {
        return Path.of(home(projectName).toString(), "bin", program);
    }

    /** The project of the loaded model whose Eclipse project has this name, or null when no model is loaded or none has it. */
    private static IJavaProject projectNamed(String projectName) {
        Supplier<IJdtService> source = model;
        IJdtService loaded = source == null ? null : source.get();
        if (loaded == null) {
            return null;
        }
        for (LoadedProject candidate : loaded.allProjects()) {
            if (candidate.javaProject().getProject().getName().equals(projectName)) {
                return candidate.javaProject();
            }
        }
        return null;
    }
}
