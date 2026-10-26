package org.jawata.mcp.coverage;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.xml.parsers.DocumentBuilderFactory;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.java.RunnerClasspath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Sprint 23 (D5) — targeted mutation testing: PIT (CLI, forked JVM) on a
 * BOUNDED target set — the changed class(es) × the tests that exercise them
 * — strictly opt-in, runtime-bounded, survivors mapped to symbols +
 * candidate missing-assertion locations.
 */
public final class MutationService {

    private static final Logger log = LoggerFactory.getLogger(MutationService.class);

    /**
     * The two strings the engine and the PIT plugin in org.jawata.testrunner share: the system
     * property naming the file of tests that abort before any mutation, and the mark the plugin
     * appends to the name of a test that aborted under one. They are written twice, once on each side
     * of a jar boundary, and AbortConversionTest is what fails if they drift: no abort is reported.
     */
    static final String ABORT_BASELINE_PROPERTY = "jawata.pit.abortBaseline";
    static final String ABORT_MARK = " [ABORTED UNDER MUTATION]";

    public static final class Result {
        public boolean ran;
        public String note;
        public int killed;
        public int survived;
        public int timedOutMutations;
        public int noCoverage;
        /**
         * Mutations a test ABORTED under, having passed before them: the gate swallowed the defect
         * (sprint spec D41). Not in {@link #killed} and not in {@link #survived}, which is where
         * PIT itself puts them, because it counts an abort as a pass.
         */
        public int abortConverted;
        public List<Map<String, Object>> abortConversions = new ArrayList<>();
        public List<Map<String, Object>> survivors = new ArrayList<>();

        /**
         * The one count a hire's gate reads: the mutations nothing rejected, which are the survivors
         * (changes no test noticed) and the abort conversions (changes a test aborted under instead of
         * failing, which PIT itself counts as survivors).
         */
        public int errorCount() {
            return survived + abortConverted;
        }

        /** Every mutation the run classified, whatever came of it. */
        public int mutationsConsidered() {
            return killed + survived + abortConverted + timedOutMutations + noCoverage;
        }
        public long timeMs;
        public String stderrTail = "";
    }

    /**
     * @param projectClasspath what the project's own code needs at run time, as paths (the language's answer)
     * @param projectFolder    the folder PIT starts in
     * @param targetClasses    production classes to mutate (globs ok)
     * @param targetTests      the exercising tests (from attribution or explicit)
     * @param sourceRoots      where the project's sources are
     * @param timeoutSeconds   how long PIT may run before it is reaped
     */
    public Result run(List<String> projectClasspath, java.nio.file.Path projectFolder, List<String> targetClasses, List<String> targetTests, List<String> sourceRoots, int timeoutSeconds)
            throws Exception {
        Result result = new Result();
        Path reportDir = Files.createTempDirectory("jawata-pit-");

        // PIT JVM classpath: the PIT toolchain + the UNFILTERED project
        // classpath (PIT drives the project's own test engine).
        List<Path> cp = new ArrayList<>();
        cp.add(RunnerClasspath.toolJar("pitest-command-line-"));
        cp.add(RunnerClasspath.toolJar("pitest-entry-"));
        cp.add(RunnerClasspath.toolJar("pitest-1"));
        cp.add(RunnerClasspath.toolJar("pitest-junit5-plugin-"));
        // The same plugin with one difference (S10-10): a test that ABORTS under a mutation, having
        // passed before it, is reported as the mutation's killer under a mark, so the report can tell
        // an abort from a survivor. PIT's own plugin counts an abort as a pass.
        cp.add(RunnerClasspath.toolJar("org.jawata.pitplugin-"));
        // The junit5 plugin needs the platform launcher; user projects'
        // jupiter aggregates don't carry it.
        cp.add(RunnerClasspath.toolJar("junit-platform-launcher-"));
        // PIT's XML reporter needs commons-text (+lang3).
        cp.add(RunnerClasspath.toolJar("commons-text-"));
        cp.add(RunnerClasspath.toolJar("commons-lang3-"));
        Set<String> projectCp = new LinkedHashSet<>(projectClasspath);

        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.add("-cp");
        cmd.add(String.join(File.pathSeparator,
            cp.stream().map(Path::toString).toList())
            + File.pathSeparator + String.join(File.pathSeparator, projectCp));
        cmd.add("org.pitest.mutationtest.commandline.MutationCoverageReport");
        cmd.add("--reportDir");
        cmd.add(reportDir.toString());
        cmd.add("--targetClasses");
        cmd.add(String.join(",", targetClasses));
        cmd.add("--targetTests");
        cmd.add(String.join(",", targetTests));
        cmd.add("--sourceDirs");
        cmd.add(String.join(",", sourceRoots));
        cmd.add("--outputFormats");
        cmd.add("XML");
        cmd.add("--timestampedReports");
        cmd.add("false");
        // The file PIT's coverage JVM writes the tests that abort UNMUTATED to, and its mutation JVM
        // reads: such a test aborts under every mutation too, so it converted nothing.
        cmd.add("--jvmArgs");
        cmd.add("-D" + ABORT_BASELINE_PROPERTY + "=" + reportDir.resolve("aborting-before-mutation.txt"));

        long start = System.nanoTime();
        // The SPAWN crosses the boundary; the two streams stay here, because
        // this site reads stdout and stderr SEPARATELY (stderr is the error
        // report shown to the user, stdout is only drained).
        Process p = HostProcesses.system().start(
            HostCommand.of(cmd)
                .in(projectFolder)
                .withSeparateStderr());
        StringBuilder err = new StringBuilder();
        Thread gobbler = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (err.length() < 20_000) err.append(line).append('\n');
                }
            } catch (Exception ignored) { }
        });
        gobbler.setDaemon(true);
        gobbler.start();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            while (r.readLine() != null) { /* drain */ }
        }
        boolean finished = p.waitFor(Math.max(30, timeoutSeconds), TimeUnit.SECONDS);
        result.timeMs = (System.nanoTime() - start) / 1_000_000L;
        if (!finished) {
            p.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
            result.note = "PIT exceeded the declared time bound (" + timeoutSeconds
                + " s) and was reaped — no mutation claims.";
            return result;
        }
        result.stderrTail = err.toString();

        Path mutationsXml = reportDir.resolve("mutations.xml");
        if (!Files.isRegularFile(mutationsXml) || Files.size(mutationsXml) == 0) {
            result.note = "PIT produced no usable mutations.xml (exit " + p.exitValue()
                + ") — see stderr.";
            return result;
        }
        try {
            parse(mutationsXml, result);
        } catch (Exception e) {
            result.note = "PIT report unparsable (" + e.getMessage() + "; exit "
                + p.exitValue() + ") — see stderr.";
            return result;
        }
        result.ran = true;
        return result;
    }

    private static void parse(Path mutationsXml, Result result) throws Exception {
        Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(mutationsXml.toFile());
        NodeList mutations = doc.getElementsByTagName("mutation");
        for (int i = 0; i < mutations.getLength(); i++) {
            Element m = (Element) mutations.item(i);
            String status = m.getAttribute("status");
            // The killer is the first test to reject the mutation. Our plugin marks one that ABORTED
            // under it (a failed assumption), where PIT's own would have counted a pass.
            String killer = text(m, "killingTest");
            boolean aborted = "KILLED".equals(status) && killer.contains(ABORT_MARK);
            switch (status) {
                case "KILLED" -> {
                    if (aborted) {
                        result.abortConverted++;
                    } else {
                        result.killed++;
                    }
                }
                case "SURVIVED" -> result.survived++;
                case "TIMED_OUT" -> result.timedOutMutations++;
                case "NO_COVERAGE" -> result.noCoverage++;
                default -> { }
            }
            if ("SURVIVED".equals(status)) {
                Map<String, Object> row = mutationRow(m);
                row.put("candidateAssertion", "assert the behavior of " + row.get("symbol")
                    + " at line " + row.get("line")
                    + " — the mutation '" + row.get("description")
                    + "' changed the code and NO test noticed");
                result.survivors.add(row);
            }
            if (aborted) {
                Map<String, Object> row = mutationRow(m);
                row.put("abortedTests", killer.replace(ABORT_MARK, ""));
                row.put("candidateAssertion", "the test aborted instead of failing under the mutation '"
                    + row.get("description") + "' in " + row.get("symbol") + " at line " + row.get("line")
                    + " — an assumption or a skip swallowed the defect; make the check an assertion");
                result.abortConversions.add(row);
            }
        }
        log.info("PIT: {} killed, {} survived, {} aborted under mutation, {} timed out, {} no-coverage",
            result.killed, result.survived, result.abortConverted, result.timedOutMutations,
            result.noCoverage);
    }

    /** Where a mutation is, in the terms every row of the report shares. */
    private static Map<String, Object> mutationRow(Element mutation) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", text(mutation, "mutatedClass") + "#" + text(mutation, "mutatedMethod"));
        row.put("line", Integer.parseInt(text(mutation, "lineNumber")));
        row.put("mutator", text(mutation, "mutator").replaceAll(".*\\.", ""));
        row.put("description", text(mutation, "description"));
        return row;
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent();
    }
}
