package org.jawata.mcp.runtime.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 24 (D14) — an OPTIONAL external adapter for native-crash triage
 * beyond what {@link HsErrParser} already gets for free from the JVM's own
 * report. Deliberately split in two: {@link #isAvailable} / {@link #runBacktrace}
 * (thin, genuinely needs the external tool, hard to unit-test without it) and
 * {@link #parseBacktrace} (a pure function over gdb's own text output,
 * unit-testable with a fixture transcript whether or not gdb is installed on
 * the box running the test — gdb's {@code thread apply all bt} frame syntax
 * is a decades-stable, documented format).
 *
 * <p>Honest about platform support (spec D14): if no adapter command is
 * configured or resolvable on {@code PATH}, {@link #isAvailable} says so and
 * {@code profile(action=native_handoff)} reports a capability-absent result —
 * never a silent skip.</p>
 */
public final class GdbAdapter {

    private static final Logger log = LoggerFactory.getLogger(GdbAdapter.class);

    private static final Pattern THREAD_HEADER = Pattern.compile("^Thread (?<num>\\d+) \\(.*\\):\\s*$");
    // A SEPARATE find() against the header line, not folded into THREAD_HEADER: a lazy
    // `.*?` either side of an OPTIONAL "LWP N" group lets the engine satisfy the whole
    // match by skipping the group entirely (the trailing `.*?` happily swallows the
    // literal "(LWP 5002)" text as ordinary characters) — found live against a real
    // fixture transcript, Sprint 24 Stage 19: `lwp` came back null every time.
    private static final Pattern LWP_IN_HEADER = Pattern.compile("LWP (?<lwp>\\d+)");
    // A frame with debug info ends " at file:line"; one resolved only via the dynamic
    // symbol table (routine system-library calls — libc, libpthread — almost never carry
    // debug info) ends " from /path/to/lib.so" instead. Found live against a real gdb-shaped
    // fixture transcript, Sprint 24 Stage 19: every "from"-shaped frame matched neither
    // trailing group, so `matches()` failed and the frame was silently dropped — 3 of a
    // 5-frame transcript vanished with no error.
    private static final Pattern FRAME_LINE = Pattern.compile(
        "^#(?<num>\\d+)\\s+0x(?<addr>[0-9a-f]+) in (?<func>\\S+)\\s*\\((?<args>.*?)\\)"
            + "(?:\\s+at (?<file>[^:]+):(?<line>\\d+))?"
            + "(?:\\s+from (?<library>\\S+))?"
            + "\\s*$");

    // lldb speaks a different language entirely:
    //   * thread #1, name = 'java', stop reason = signal SIGSEGV
    //     * frame #0: 0x00007ffff6dc4e60 libjvm.so`Unsafe_PutInt(env=0x0) + 164 at unsafe.cpp:150
    private static final Pattern LLDB_THREAD = Pattern.compile(
        "^\\*?\\s*thread #(?<num>\\d+)(?:,\\s*name\\s*=\\s*'(?<name>[^']*)')?(?:,.*)?$");
    private static final Pattern LLDB_FRAME = Pattern.compile(
        "^\\*?\\s*frame #(?<num>\\d+):\\s*0x(?<addr>[0-9a-f]+)\\s+(?<module>[^`]+)`(?<symbol>.+?)"
            + "(?:\\s+\\+\\s+(?<offset>\\d+))?"
            + "(?:\\s+at\\s+(?<file>[^:\\s]+):(?<line>\\d+))?\\s*$");

    private GdbAdapter() {
    }

    /** Is {@code command} (e.g. "gdb", "lldb") resolvable and runnable on THIS machine? */
    public static boolean isAvailable(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version").redirectErrorStream(true).start();
            boolean exited = p.waitFor(5, TimeUnit.SECONDS);
            if (!exited) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Is this adapter lldb rather than gdb? They take completely different arguments. */
    static boolean isLldb(String command) {
        String name = Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT);
        return name.contains("lldb");
    }

    /** The argument shape each debugger actually accepts — they share none of it. */
    static List<String> backtraceCommand(String command, Path javaBinary, Path coreFile) {
        if (isLldb(command)) {
            // lldb takes neither -batch nor -ex, and will not load a core as a bare
            // positional. The tool's schema claimed lldb worked; this is what makes it true.
            return List.of(command, "--batch", "--one-line", "thread backtrace all",
                "--core", coreFile.toString(), "--file", javaBinary.toString());
        }
        return List.of(command, "-batch", "-ex", "thread apply all bt",
            javaBinary.toString(), coreFile.toString());
    }

    /**
     * Run the adapter's all-thread backtrace and return its raw text.
     *
     * <p>The output is drained on a SEPARATE thread while the main thread waits with the
     * timeout. The obvious-looking {@code readAllBytes()} then {@code waitFor(timeout)}
     * ordering — which shipped in v2.13.0 — cannot work: {@code readAllBytes} blocks until
     * the child closes stdout, which is to say until it exits, so the timeout is only ever
     * reached by a process that already finished. A debugger wedged on a mismatched core
     * held the MCP call open forever, which is the exact case the timeout exists for.</p>
     *
     * <p>A non-zero exit is raised with the adapter's own words. Feeding an error message
     * to the frame parser (as v2.13.0 did) yields zero frames and a cheerful
     * {@code correlated: true} — a silently empty result, which this codebase treats as a
     * lie, not a result.</p>
     */
    public static String runBacktrace(String command, Path javaBinary, Path coreFile, int timeoutSeconds)
            throws Exception {
        Process process = new ProcessBuilder(backtraceCommand(command, javaBinary, coreFile))
            .redirectErrorStream(true)
            .start();

        StringBuilder output = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (output) {
                        output.append(line).append('\n');
                    }
                }
            } catch (IOException e) {
                log.debug("{} output stream ended: {}", command, e.getMessage());
            }
        }, "jawata-native-adapter-drain");
        drain.setDaemon(true);
        drain.start();

        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            drain.join(2_000);
            throw new IllegalStateException(command + " timed out after " + timeoutSeconds
                + "s and was killed — a debugger that produces nothing and never returns is "
                + "usually a core that does not match the binary.");
        }
        drain.join(5_000);

        String text;
        synchronized (output) {
            text = output.toString();
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(command + " failed (exit " + process.exitValue()
                + "): " + text.strip());
        }
        return text;
    }

    /**
     * The bare function name, with any {@code +0x…} / {@code +N} offset suffix removed.
     *
     * <p>HotSpot writes a resolved native frame as {@code [libjvm.so+0xfc8ac4]
     * Unsafe_PutInt+0xa4} — the symbol AND its offset into that symbol. gdb writes the
     * same frame's function as bare {@code Unsafe_PutInt}. Comparing the two verbatim can
     * never match, which is precisely what shipped in v2.13.0: {@code correlatedWithHsErr}
     * was false for every frame, always, and {@code correlatedFrameCount} was permanently
     * zero — the adapter's entire value-add over the free hs_err baseline was dead code
     * that no test covered and no live run had ever exercised (gdb was absent on the
     * release box). Sprint-24 audit; see {@code NativeTriageTest#correlation…}.</p>
     */
    public static String baseSymbolName(String symbol) {
        if (symbol == null) {
            return null;
        }
        // The three shapes HotSpot really writes (verified against a genuine crash):
        //   Unsafe_PutInt+0xa4
        //   JavaCalls::call_helper(JavaValue*, methodHandle const&, ...)+0x2da
        //   jni_invoke_static(JNIEnv_*, ...) [clone .constprop.1]+0x360
        // gdb prints the same three frames as the BARE function — Unsafe_PutInt,
        // JavaCalls::call_helper, jni_invoke_static — because it renders the argument
        // list separately (as values, not types). So the parameter list, the [clone]
        // marker and the +offset all have to come off before the two can be compared.
        String text = symbol.strip();
        int cut = text.length();
        int paren = text.indexOf('(');
        if (paren >= 0) {
            cut = Math.min(cut, paren);
        }
        int plus = text.indexOf('+');
        if (plus >= 0) {
            cut = Math.min(cut, plus);
        }
        int clone = text.indexOf(" [");
        if (clone >= 0) {
            cut = Math.min(cut, clone);
        }
        String base = text.substring(0, cut).strip();
        return base.isEmpty() ? text : base;
    }

    /**
     * Mark every frame the crash report ALSO resolved, comparing bare function names.
     * Pure: text and structure in, structure and a count out — no process, no filesystem,
     * so the correlation is provable on a machine with no debugger installed at all.
     *
     * @return how many frames correlated
     */
    @SuppressWarnings("unchecked")
    public static int correlate(List<Map<String, Object>> threads, Set<String> hsErrSymbols) {
        Set<String> hsErrBaseNames = new HashSet<>();
        for (String symbol : hsErrSymbols) {
            String base = baseSymbolName(symbol);
            if (base != null && !base.isEmpty()) {
                hsErrBaseNames.add(base);
            }
        }
        int correlated = 0;
        for (Map<String, Object> thread : threads) {
            for (Map<String, Object> frame : (List<Map<String, Object>>) thread.get("frames")) {
                boolean match = hsErrBaseNames.contains(baseSymbolName((String) frame.get("function")));
                frame.put("correlatedWithHsErr", match);
                if (match) {
                    correlated++;
                }
            }
        }
        return correlated;
    }

    /**
     * Parse an all-thread backtrace into per-thread frame lists — from EITHER debugger.
     * Pure text-in, structure-out: no process, no filesystem, so both formats are provable
     * on a machine with neither debugger installed.
     *
     * <p>The two syntaxes have nothing in common ({@code #0  0x… in func () at f:1} vs
     * {@code frame #0: 0x… libjvm.so`func + 164 at f:1}), so the transcript is detected
     * rather than assumed.</p>
     */
    public static List<Map<String, Object>> parseBacktrace(String output) {
        // `frame #` is lldb's spelling and gdb never writes it. (Detecting with the
        // LLDB_FRAME pattern itself does NOT work: it is ^-anchored, and without MULTILINE
        // a find() over the whole transcript can only anchor at offset 0.)
        return output.contains("frame #")
            ? parseLldbBacktrace(output)
            : parseGdbBacktrace(output);
    }

    private static List<Map<String, Object>> parseLldbBacktrace(String output) {
        List<Map<String, Object>> threads = new ArrayList<>();
        List<Map<String, Object>> currentFrames = null;

        for (String line : output.split("\n")) {
            String stripped = line.strip();
            Matcher threadHeader = LLDB_THREAD.matcher(stripped);
            if (threadHeader.matches()) {
                Map<String, Object> thread = new LinkedHashMap<>();
                thread.put("threadNum", Integer.parseInt(threadHeader.group("num")));
                if (threadHeader.group("name") != null) {
                    thread.put("name", threadHeader.group("name"));
                }
                currentFrames = new ArrayList<>();
                thread.put("frames", currentFrames);
                threads.add(thread);
                continue;
            }
            Matcher frame = LLDB_FRAME.matcher(stripped);
            if (frame.matches() && currentFrames != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("frameNum", Integer.parseInt(frame.group("num")));
                f.put("address", "0x" + frame.group("addr"));
                // lldb welds the argument list onto the symbol; store the bare name so it
                // correlates with a crash report exactly as a gdb frame does.
                f.put("function", baseSymbolName(frame.group("symbol")));
                f.put("library", frame.group("module").strip());
                if (frame.group("file") != null) {
                    f.put("file", frame.group("file"));
                    f.put("line", Integer.parseInt(frame.group("line")));
                }
                currentFrames.add(f);
            }
        }
        return threads;
    }

    private static List<Map<String, Object>> parseGdbBacktrace(String gdbOutput) {
        List<Map<String, Object>> threads = new ArrayList<>();
        Map<String, Object> currentThread = null;
        List<Map<String, Object>> currentFrames = null;

        for (String line : gdbOutput.split("\n")) {
            String stripped = line.strip();
            Matcher threadHeader = THREAD_HEADER.matcher(stripped);
            if (threadHeader.matches()) {
                currentThread = new LinkedHashMap<>();
                currentThread.put("threadNum", Integer.parseInt(threadHeader.group("num")));
                Matcher lwp = LWP_IN_HEADER.matcher(stripped);
                if (lwp.find()) {
                    currentThread.put("lwp", Integer.parseInt(lwp.group("lwp")));
                }
                currentFrames = new ArrayList<>();
                currentThread.put("frames", currentFrames);
                threads.add(currentThread);
                continue;
            }
            Matcher frame = FRAME_LINE.matcher(stripped);
            if (frame.matches() && currentFrames != null) {
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("frameNum", Integer.parseInt(frame.group("num")));
                f.put("address", "0x" + frame.group("addr"));
                f.put("function", frame.group("func"));
                f.put("args", frame.group("args"));
                if (frame.group("file") != null) {
                    f.put("file", frame.group("file"));
                    f.put("line", Integer.parseInt(frame.group("line")));
                } else if (frame.group("library") != null) {
                    f.put("library", frame.group("library"));
                }
                currentFrames.add(f);
            }
        }
        return threads;
    }
}
