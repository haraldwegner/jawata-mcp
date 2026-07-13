package org.jawata.mcp.coverage;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.jawata.mcp.execution.RunnerClasspath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
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

/**
 * Sprint 23 (D5) — targeted mutation testing: PIT (CLI, forked JVM) on a
 * BOUNDED target set — the changed class(es) × the tests that exercise them
 * — strictly opt-in, runtime-bounded, survivors mapped to symbols +
 * candidate missing-assertion locations.
 */
public final class MutationService {

    private static final Logger log = LoggerFactory.getLogger(MutationService.class);

    public static final class Result {
        public boolean ran;
        public String note;
        public int killed;
        public int survived;
        public int timedOutMutations;
        public int noCoverage;
        public List<Map<String, Object>> survivors = new ArrayList<>();
        public long timeMs;
        public String stderrTail = "";
    }

    /**
     * @param targetClasses production classes to mutate (globs ok)
     * @param targetTests   the exercising tests (from attribution or explicit)
     */
    public Result run(IJavaProject project, List<String> targetClasses,
            List<String> targetTests, List<String> sourceRoots, int timeoutSeconds)
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
        // The junit5 plugin needs the platform launcher; user projects'
        // jupiter aggregates don't carry it.
        cp.add(RunnerClasspath.toolJar("junit-platform-launcher-"));
        // PIT's XML reporter needs commons-text (+lang3).
        cp.add(RunnerClasspath.toolJar("commons-text-"));
        cp.add(RunnerClasspath.toolJar("commons-lang3-"));
        Set<String> projectCp = new LinkedHashSet<>();
        for (var entry : org.eclipse.jdt.launching.JavaRuntime
                .computeUnresolvedRuntimeClasspath(project)) {
            for (var resolved : org.eclipse.jdt.launching.JavaRuntime
                    .resolveRuntimeClasspathEntry(entry, project)) {
                if (resolved.getClasspathProperty()
                        == org.eclipse.jdt.launching.IRuntimeClasspathEntry.USER_CLASSES
                        && resolved.getLocation() != null) {
                    projectCp.add(resolved.getLocation());
                }
            }
        }

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

        long start = System.nanoTime();
        Process p = new ProcessBuilder(cmd)
            .directory(new File(project.getProject().getLocation().toOSString()))
            .start();
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
            switch (status) {
                case "KILLED" -> result.killed++;
                case "SURVIVED" -> result.survived++;
                case "TIMED_OUT" -> result.timedOutMutations++;
                case "NO_COVERAGE" -> result.noCoverage++;
                default -> { }
            }
            if ("SURVIVED".equals(status)) {
                Map<String, Object> row = new LinkedHashMap<>();
                String clazz = text(m, "mutatedClass");
                String method = text(m, "mutatedMethod");
                row.put("symbol", clazz + "#" + method);
                row.put("line", Integer.parseInt(text(m, "lineNumber")));
                row.put("mutator", text(m, "mutator").replaceAll(".*\\.", ""));
                row.put("description", text(m, "description"));
                row.put("candidateAssertion", "assert the behavior of " + clazz + "#"
                    + method + " at line " + text(m, "lineNumber")
                    + " — the mutation '" + text(m, "description")
                    + "' changed the code and NO test noticed");
                result.survivors.add(row);
            }
        }
        log.info("PIT: {} killed, {} survived, {} timed out, {} no-coverage",
            result.killed, result.survived, result.timedOutMutations, result.noCoverage);
    }

    private static String text(Element parent, String tag) {
        NodeList list = parent.getElementsByTagName(tag);
        return list.getLength() == 0 ? "" : list.item(0).getTextContent();
    }
}
