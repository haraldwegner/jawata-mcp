package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jawata.core.host.HostCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The hire path takes its argv from the brain's dialect and from nothing else (Sprint 28g S16-13).
 *
 * <p>The hire tool used to hand the run a {@link HostCommand} built around a placeholder argv, and
 * the run threw that argv away and asked the dialect. Two sources for one thing meant a caller could
 * set an argv that was silently discarded, which is how S16-12 (a removed {@code binary} input that a
 * caller kept sending) survived a green unit test: a spy that replaces the PROCESS cannot tell that
 * the base's argv was ignored, because it starts a stand-in whatever argv[0] said.</p>
 *
 * <p>The cure is in the type system and not in a check: the hire path's parameter carries where and
 * with what environment, and has no argv to discard. This pins that on the signatures, so a base
 * with an argv cannot come back through a convenient overload.</p>
 */
class TheHirePathHasOneArgvSourceTest {

    @Test
    @DisplayName("no way into a hired run takes a HostCommand, whose argv the run would discard")
    void noEntryIntoAHiredRunTakesAnArgvItWouldDiscard() {
        List<Method> entries = Arrays.stream(HiredRun.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("issue"))
            .toList();
        List<Method> hires = Arrays.stream(HiredBrain.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("hire"))
            .toList();

        // The proof of life: the loop below means nothing over an empty list.
        assertFalse(entries.isEmpty(), "HiredRun declares no issue(...) — this test is looking at nothing");
        assertFalse(hires.isEmpty(), "HiredBrain declares no hire(...) — this test is looking at nothing");

        List<String> offenders = Arrays.asList(entries, hires).stream()
            .flatMap(List::stream)
            .filter(m -> Arrays.asList(m.getParameterTypes()).contains(HostCommand.class))
            .map(m -> m.getDeclaringClass().getSimpleName() + "#" + m.getName())
            .toList();
        assertEquals(List.of(), offenders,
            "these take a HostCommand, so a caller can set an argv the run silently replaces");
    }

    @Test
    @DisplayName("the type that replaced it has no place to put an argv either")
    void theSiteHasNoPlaceForAnArgv() {
        // The test above bans one TYPE. An argv field added to HireSite would put the second source
        // back through the very type that was made to have none, and stay green there.
        List<String> carriers = Arrays.stream(HireSite.class.getRecordComponents())
            .filter(c -> List.class.isAssignableFrom(c.getType()) || c.getType().isArray()
                || c.getName().toLowerCase().contains("argv")
                || c.getName().toLowerCase().contains("command"))
            .map(c -> c.getName() + ":" + c.getType().getSimpleName())
            .toList();
        assertEquals(List.of(), carriers, "a HireSite component that could carry an argv");
    }

    // ---- the ACP program: the second place an argv can come from ---------------------------------------------------------
    //
    // A hired seat on an ACP brain is started by an AcpBrainSession.Program, which IS a command line. The two tests above ban the
    // HostCommand and the HireSite from carrying one; this part bans a second way in. The registry setting is the one source of the
    // program of a hired brain, and the only other places that read a setting into a program are the two single-purpose ones named
    // below (the principal's own brain and the reviewer's). A new reader of a program, or a program built anywhere but in
    // AcpBrainSession, turns these red and has to be answered here, not slipped in.

    private static final Pattern PROGRAM_BUILT = Pattern.compile("new\\s+(?:\\w+\\s*\\.\\s*)*Program\\s*\\(|\\bProgram\\s*::\\s*new");
    private static final Pattern PROGRAM_READ = Pattern.compile("\\b(parseProgram|programFor)\\b");

    /** The text with comments and the insides of string and character literals blanked, so a pattern only matches code. */
    static String codeOnly(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                int end = i + 1;
                while (end < text.length() && text.charAt(end) != c) {
                    end += text.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, text.length());
                out.append(c).append(" ".repeat(Math.max(0, end - i - 2))).append(c);
                i = end;
            } else if (text.startsWith("//", i)) {
                while (i < text.length() && text.charAt(i) != '\n') {
                    out.append(' ');
                    i++;
                }
            } else if (text.startsWith("/*", i)) {
                int end = text.indexOf("*/", i + 2);
                end = end < 0 ? text.length() : end + 2;
                for (int k = i; k < end; k++) {
                    out.append(text.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Production source file name (without folder) to the code of that file; the test is skipped, not passed, when this run has no sources. */
    private static Map<String, String> productionCode() throws IOException {
        Path root = null;
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("org.jawata.mcp/src"))) {
                root = candidate;
                break;
            }
        }
        assumeTrue(root != null, "sources are not present in this run: the program-source rule is UNPROVEN here, not passing");
        Map<String, String> code = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root.resolve("org.jawata.mcp/src"))) {
            for (Path file : walk.filter(p -> p.getFileName().toString().endsWith(".java")).toList()) {
                code.put(file.getFileName().toString(), codeOnly(Files.readString(file, StandardCharsets.UTF_8)));
            }
        }
        return code;
    }

    private static List<String> filesMatching(Map<String, String> code, Pattern pattern) {
        return code.entrySet().stream().filter(e -> pattern.matcher(e.getValue()).find()).map(Map.Entry::getKey).sorted().toList();
    }

    @Test
    @DisplayName("an ACP program is built in AcpBrainSession alone: no other production class makes a command line of its own for a brain")
    void anAcpProgramIsBuiltInOnePlace() throws IOException {
        Map<String, String> code = productionCode();

        assertEquals(List.of("AcpBrainSession.java"), filesMatching(code, PROGRAM_BUILT),
            "a production class builds an AcpBrainSession.Program, which is a second source of a brain's command line;"
                + " it must come from a setting through AcpBrainSession.parseProgram or programFor (and the list in the next test)");
    }

    @Test
    @DisplayName("a program is read out of a setting in two places only: the registry for a hired brain, and the principal's own setting")
    void aProgramIsReadFromASettingInTwoPlacesOnly() throws IOException {
        Map<String, String> code = productionCode();
        code.remove("AcpBrainSession.java");

        Map<String, List<String>> readers = new TreeMap<>();
        code.forEach((file, text) -> {
            java.util.regex.Matcher found = PROGRAM_READ.matcher(text);
            java.util.Set<String> methods = new TreeSet<>();
            while (found.find()) {
                methods.add(found.group(1));
            }
            if (!methods.isEmpty()) {
                readers.put(file, List.copyOf(methods));
            }
        });

        // D53 (i) and (j): the principal also reads the registry, for the brain its seat file names and the brain the person picks on its row.
        assertEquals(Map.of("HireTool.java", List.of("programFor"), "PrincipalBrain.java", List.of("parseProgram", "programFor")), readers,
            "a new reader of a brain's program appeared: a hired brain's program comes from the registry setting through HireTool alone;"
                + " the principal's own setting and its registry names are the only others");
    }

    @Test
    @DisplayName("the scan sees a program built in code and a reader of one, and ignores both inside a comment or a string")
    void theProgramScanSeesWhatItShouldAndNothingElse() {
        String planted = "class A { void f() { var p = new AcpBrainSession.Program(java.util.List.of(\"x\"), java.util.Map.of()); parseProgram(s); } }";
        String commented = "class B { // new Program(x)\n /* programFor(a) and new AcpBrainSession.Program( */ String s = \"new Program(\" + \"parseProgram\"; }";

        assertTrue(PROGRAM_BUILT.matcher(codeOnly(planted)).find(), "CONTROL: a program built in code was not seen");
        assertTrue(PROGRAM_READ.matcher(codeOnly(planted)).find(), "CONTROL: a program read in code was not seen");
        assertTrue(PROGRAM_BUILT.matcher("var p = new org.jawata.mcp.execution.AcpBrainSession.Program(a, b);").find(),
            "a program built under its fully qualified name was not seen");
        assertTrue(PROGRAM_BUILT.matcher("list.stream().map(AcpBrainSession.Program::new)").find(), "a program built by a method reference was not seen");
        assertFalse(PROGRAM_BUILT.matcher(codeOnly(commented)).find(), "a program built in a comment, or named in a string, was counted");
        assertFalse(PROGRAM_READ.matcher(codeOnly(commented)).find(), "a reader named in a comment or a string was counted");
    }
}
