package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.runtime.profile.GdbAdapter;
import org.jawata.mcp.tools.ProfileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D14, C19) — native boundary triage. No live session (the process
 * that crashed is gone) — these actions read an hs_err_pid<pid>.log file.
 *
 * <p>Grounded against REAL generated crashes, not hand-typed fixture text
 * (Sprint 24 Stage 19): {@code NativeCrashTarget} runs as a genuine
 * subprocess and genuinely SIGSEGVs via an out-of-bounds {@code Unsafe}
 * write, so the hs_err file this test parses is exactly what HotSpot itself
 * produces — including the real resolved native symbol
 * ({@code Unsafe_PutInt+0xa4} in {@code libjvm.so}) and the real Java frames
 * naming this class's own method.</p>
 */
class NativeTriageTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ProfileTool profile;
    private ObjectMapper om;
    private Path classesDir;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        profile = new ProfileTool(() -> service, new RuntimeSessionRegistry());
        om = new ObjectMapper();

        classesDir = Files.createTempDirectory("jawata-native-triage-classes-");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-d", classesDir.toString(),
            pkg.resolve("NativeCrashTarget.java").toString());
        assertEquals(0, rc, "the native-crash fixture must compile");
    }

    /** Launches NativeCrashTarget as a genuine subprocess and returns its hs_err file. */
    private Path crashAndCaptureHsErr(boolean nativeMemoryTracking) throws Exception {
        Path workDir = Files.createTempDirectory("jawata-native-triage-run-");
        List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-XX:ErrorFile=" + workDir.resolve("hs_err_pid%p.log"));
        if (nativeMemoryTracking) {
            command.add("-XX:NativeMemoryTracking=summary");
        }
        command.add("-cp");
        command.add(classesDir.toString());
        command.add("com.example.debug.NativeCrashTarget");

        Process process = new ProcessBuilder(command)
            .directory(workDir.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        boolean finished = process.waitFor(20, TimeUnit.SECONDS);
        assertTrue(finished, "the crash fixture must exit (it deliberately SIGSEGVs)");

        File[] hsErrFiles = workDir.toFile().listFiles((dir, name) -> name.startsWith("hs_err_pid"));
        assertNotNull(hsErrFiles, "no hs_err file appeared in " + workDir);
        assertEquals(1, hsErrFiles.length, "expected exactly one hs_err file: "
            + java.util.Arrays.toString(hsErrFiles));
        return hsErrFiles[0].toPath();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode action(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    // ========================================================== native_hs_err

    @Test
    @DisplayName("native_hs_err: a REAL crash parses with signal, problematic frame, and correlated Java+native frames")
    void hsErrParsesRealCrash() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(false);

        ObjectNode args = action("native_hs_err");
        args.put("hsErrFile", hsErrFile.toString());
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);

        assertEquals("SIGSEGV", result.get("signal"));
        assertNotNull(result.get("problematicFrame"));
        assertNotNull(result.get("jreVersion"));

        @SuppressWarnings("unchecked")
        Map<String, Object> thread = (Map<String, Object>) result.get("crashingThread");
        assertEquals(Boolean.TRUE, thread.get("javaThread"),
            "an Unsafe-triggered crash happens ON a JavaThread: " + thread);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> javaFrames = (List<Map<String, Object>>) result.get("javaFrames");
        assertTrue(javaFrames.stream().anyMatch(f ->
            "com.example.debug.NativeCrashTarget#triggerCrash".equals(f.get("symbol"))),
            "the crashing method's own name must be in the Java frames: " + javaFrames);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nativeFrames = (List<Map<String, Object>>) result.get("nativeFrames");
        assertTrue(nativeFrames.stream().anyMatch(f -> "vmInternal".equals(f.get("kind"))
            && f.get("resolvedSymbol") != null),
            "libjvm.so resolves its own symbols (e.g. Unsafe_PutInt): " + nativeFrames);
    }

    @Test
    @DisplayName("native_hs_err: missing hsErrFile parameter is refused up front")
    void hsErrMissingParameterIsRefused() {
        ToolResponse r = profile.execute(action("native_hs_err"));
        assertFalse(r.isSuccess());
    }

    @Test
    @DisplayName("native_hs_err: a non-existent path is an honest miss, not a crash")
    void hsErrNonexistentFileIsHonestMiss() {
        ObjectNode args = action("native_hs_err");
        args.put("hsErrFile", "/no/such/hs_err_pid1.log");
        ToolResponse r = profile.execute(args);
        assertFalse(r.isSuccess());
        assertEquals("HSERR_FILE_NOT_FOUND", r.getError().getCode());
    }

    // ========================================================== native_nmt

    @Test
    @DisplayName("native_nmt: absent when the crashed JVM had no -XX:NativeMemoryTracking")
    void nmtAbsentWithoutFlag() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(false);
        ObjectNode args = action("native_nmt");
        args.put("hsErrFile", hsErrFile.toString());
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);
        assertEquals(Boolean.FALSE, result.get("enabled"));
        assertNotNull(result.get("why"));
    }

    @Test
    @DisplayName("native_nmt: present and categorized when the crashed JVM had NativeMemoryTracking on")
    void nmtPresentWithFlag() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(true);
        ObjectNode args = action("native_nmt");
        args.put("hsErrFile", hsErrFile.toString());
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);
        assertEquals(Boolean.TRUE, result.get("enabled"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories = (List<Map<String, Object>>) result.get("categories");
        assertFalse(categories.isEmpty(), "the embedded NMT section must yield categories: " + result);
    }

    // ========================================================== native_handoff

    @Test
    @DisplayName("native_handoff: honest capability-absent when no adapter is installed")
    void handoffHonestWhenAdapterAbsent() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(false);
        ObjectNode args = action("native_handoff");
        args.put("hsErrFile", hsErrFile.toString());
        args.put("adapterCommand", "jawata-nonexistent-debugger-xyz");
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);
        assertEquals(Boolean.FALSE, result.get("available"));
        assertNotNull(result.get("why"));
    }

    @Test
    @DisplayName("native_handoff: the default adapter (gdb) is honestly reported present or absent on THIS machine")
    void handoffDefaultAdapterReportedHonestly() throws Exception {
        Path hsErrFile = crashAndCaptureHsErr(false);
        ObjectNode args = action("native_handoff");
        args.put("hsErrFile", hsErrFile.toString());
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);
        boolean actuallyAvailable = GdbAdapter.isAvailable("gdb");
        assertEquals(actuallyAvailable, result.get("available"),
            "must match ground truth for this machine, not assume either way: " + result);
    }

    // ========================================================== GdbAdapter.parseBacktrace (pure, no gdb needed)

    @Test
    @DisplayName("THE CORRELATION ITSELF: an hs_err symbol carries its +offset, a gdb function does not — they must still match")
    void correlationMatchesAcrossTheOffsetSuffix() throws Exception {
        // Sprint-24 audit (2026-07-14): this comparison was an exact Set.contains between
        // hs_err's "Unsafe_PutInt+0xa4" and gdb's "Unsafe_PutInt" — so it could never be
        // true. correlatedWithHsErr was false for every frame ever produced, and the one
        // capability the optional adapter adds over the FREE hs_err baseline was dead code:
        // no test touched it, and the release box had no gdb, so no live run touched it either.
        // These are the two tools' REAL symbol spellings for the same frame.
        Path hsErrFile = crashAndCaptureHsErr(false);
        ObjectNode args = action("native_hs_err");
        args.put("hsErrFile", hsErrFile.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nativeFrames =
            (List<Map<String, Object>>) data(profile.execute(args)).get("nativeFrames");

        // Straight from OUR OWN parse of a real crash. HotSpot writes THREE shapes, all
        // present in this very crash, and gdb spells every one of them differently:
        //   Unsafe_PutInt+0xa4                                  → gdb: Unsafe_PutInt
        //   JavaCalls::call_helper(JavaValue*, ...)+0x2da       → gdb: JavaCalls::call_helper
        //   jni_invoke_static(JNIEnv_*, ...) [clone ...]+0x360  → gdb: jni_invoke_static
        // The parameter list, the [clone] marker AND the +offset must all come off.
        Set<String> hsErrSymbols = nativeFrames.stream()
            .map(f -> (String) f.get("resolvedSymbol"))
            .filter(java.util.Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        assertFalse(hsErrSymbols.isEmpty(), "the crash resolved at least one native symbol");
        assertTrue(hsErrSymbols.stream().anyMatch(s -> s.startsWith("Unsafe_PutInt+0x")),
            "this crash really does resolve Unsafe_PutInt WITH an offset suffix — the suffix "
                + "is the whole bug: " + hsErrSymbols);
        assertTrue(hsErrSymbols.stream().anyMatch(s -> s.startsWith("JavaCalls::call_helper(")),
            "…and a C++ frame WITH its full signature: " + hsErrSymbols);

        assertEquals("Unsafe_PutInt", GdbAdapter.baseSymbolName("Unsafe_PutInt+0xa4"));
        assertEquals("JavaCalls::call_helper", GdbAdapter.baseSymbolName(
            "JavaCalls::call_helper(JavaValue*, methodHandle const&, JavaCallArguments*, JavaThread*)+0x2da"));
        assertEquals("jni_invoke_static", GdbAdapter.baseSymbolName(
            "jni_invoke_static(JNIEnv_*, JavaValue*, _jobject*) [clone .constprop.1]+0x360"));

        // Exactly how gdb renders those same frames (bare function, args as VALUES).
        List<Map<String, Object>> gdbThreads = GdbAdapter.parseBacktrace("""
            Thread 1 (Thread 0x7f8a2c1a5740 (LWP 5001)):
            #0  0x00007f8a2b3c4d5e in raise () from /lib/x86_64-linux-gnu/libc.so.6
            #1  0x000055d1a2b3c4e6 in Unsafe_PutInt (env=0x7f8a1c0d5e30, obj=0x0) at /build/openjdk/src/hotspot/share/prims/unsafe.cpp:150
            #2  0x000055d1a2912a2a in JavaCalls::call_helper (result=0x7ffc134fdc50, method=..., args=0x0) at /build/openjdk/src/hotspot/share/runtime/javaCalls.cpp:392
            """);

        int correlated = GdbAdapter.correlate(gdbThreads, hsErrSymbols);
        assertEquals(2, correlated,
            "BOTH frames the crash report also resolved must correlate — the plain C symbol "
                + "across its +offset, and the C++ symbol across its signature AND offset");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>) gdbThreads.get(0).get("frames");
        assertEquals(Boolean.FALSE, frames.get(0).get("correlatedWithHsErr"),
            "a frame hs_err did NOT resolve is honestly left unmarked: " + frames.get(0));
        assertEquals(Boolean.TRUE, frames.get(1).get("correlatedWithHsErr"),
            "the C symbol correlates: " + frames.get(1));
        assertEquals(Boolean.TRUE, frames.get(2).get("correlatedWithHsErr"),
            "the C++ symbol correlates: " + frames.get(2));
    }

    /** A stand-in debugger we fully control — the only way to prove the failure paths. */
    private Path fakeAdapter(String name, String script) throws Exception {
        Path adapter = Files.createTempFile(name, ".sh");
        Files.writeString(adapter, "#!/bin/sh\n" + script);
        assertTrue(adapter.toFile().setExecutable(true));
        return adapter;
    }

    @Test
    @DisplayName("a WEDGED adapter times out — it does not block the call forever")
    void runBacktraceTimesOutOnAWedgedAdapter() throws Exception {
        // Sprint-24 audit: runBacktrace called readAllBytes() — which blocks until the
        // child closes stdout, i.e. until it EXITS — BEFORE waitFor(timeout). So the
        // timeout could never fire on the one case it exists for: a debugger wedged on a
        // huge or mismatched core, producing no output and never returning. The MCP call
        // hung indefinitely. (Jcmd.run had the identical ordering, and the identical fate
        // against a SIGSTOPped target — see the floor actions.)
        Path wedged = fakeAdapter("wedged-adapter", "sleep 20\n");
        Path anyPath = Files.createTempFile("core", ".dump");

        long start = System.currentTimeMillis();
        assertThrows(Exception.class,
            () -> GdbAdapter.runBacktrace(wedged.toString(), anyPath, anyPath, 2),
            "a wedged adapter must be abandoned, loudly");
        long elapsedMillis = System.currentTimeMillis() - start;

        assertTrue(elapsedMillis < 10_000,
            "the 2s timeout must actually fire — it took " + elapsedMillis + "ms, which means "
                + "we waited for the process instead of timing it out");
    }

    @Test
    @DisplayName("a FAILING adapter is reported by name — never as an empty-but-successful backtrace")
    void runBacktraceReportsANonZeroExit() throws Exception {
        // The old code ignored the exit code entirely: gdb's error text was fed to the
        // parser, matched nothing, and the tool answered `available: true, correlated:
        // true, threads: []` — a silently empty result, the codebase's own documented
        // deepest bug class.
        Path failing = fakeAdapter("failing-adapter",
            "echo 'gdb: could not open core file: No such file or directory' >&2\nexit 1\n");
        Path anyPath = Files.createTempFile("core", ".dump");

        Exception thrown = assertThrows(Exception.class,
            () -> GdbAdapter.runBacktrace(failing.toString(), anyPath, anyPath, 10));
        assertTrue(thrown.getMessage().contains("could not open core file"),
            "the adapter's OWN words must reach the caller: " + thrown.getMessage());
    }

    @Test
    @DisplayName("lldb is understood too — the schema says so, so it must be true")
    void lldbTranscriptIsParsed() {
        // The tool description and schema both claim "gdb and lldb both understand this
        // flag". They do not: lldb takes neither -batch nor -ex, and does not load a core
        // as a bare positional. The claim was false; rather than weaken it, lldb is now
        // genuinely supported — its own argument shape AND its own frame syntax.
        List<Map<String, Object>> threads = GdbAdapter.parseBacktrace("""
            * thread #1, name = 'java', stop reason = signal SIGSEGV
              * frame #0: 0x00007ffff7a42e97 libc.so.6`raise + 199
                frame #1: 0x00007ffff6dc4e60 libjvm.so`Unsafe_PutInt(env=0x0, unsafe=0x0) + 164 at unsafe.cpp:150
              thread #2, name = 'GC Thread#0'
                frame #0: 0x00007ffff7a9c39a libpthread.so.0`__pthread_cond_wait + 511
            """);

        assertEquals(2, threads.size(), "got: " + threads);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> frames = (List<Map<String, Object>>) threads.get(0).get("frames");
        assertEquals(2, frames.size(), "got: " + frames);
        assertEquals("raise", frames.get(0).get("function"));
        assertEquals("libc.so.6", frames.get(0).get("library"));
        // lldb welds the arg list onto the symbol; the stored function is the bare name, so
        // it correlates with hs_err exactly as a gdb frame does.
        assertEquals("Unsafe_PutInt", frames.get(1).get("function"), "got: " + frames.get(1));
        assertEquals("unsafe.cpp", frames.get(1).get("file"));
        assertEquals(150, frames.get(1).get("line"));

        assertEquals(1, GdbAdapter.correlate(threads, Set.of("Unsafe_PutInt+0xa4")),
            "and an lldb frame correlates with the crash report the same way a gdb one does");
    }

    @Test
    @DisplayName("GdbAdapter.parseBacktrace: the 'correlated evidence' path's parsing logic, proven without needing gdb installed")
    void parseBacktraceHandlesAStandardTranscript() {
        String transcript = """
            Thread 2 (Thread 0x7f8a1c000700 (LWP 5002)):
            #0  0x00007f8a2b400a10 in pthread_cond_wait () from /lib/x86_64-linux-gnu/libpthread.so.0
            #1  0x000055d1a2b30111 in Monitor::wait (this=0x55d1a3000000) at /build/openjdk/src/hotspot/share/runtime/mutex.cpp:210

            Thread 1 (Thread 0x7f8a2c1a5740 (LWP 5001)):
            #0  0x00007f8a2b3c4d5e in raise () from /lib/x86_64-linux-gnu/libc.so.6
            #1  0x00007f8a2b3a6a7f in abort () from /lib/x86_64-linux-gnu/libc.so.6
            #2  0x000055d1a2b3c4e6 in Unsafe_PutInt (env=0x7f8a1c0d5e30, unsafe=..., o=..., offset=2989, x=42) at /build/openjdk/src/hotspot/share/prims/unsafe.cpp:150
            """;

        List<Map<String, Object>> threads = GdbAdapter.parseBacktrace(transcript);
        assertEquals(2, threads.size());

        Map<String, Object> thread2 = threads.get(0);
        assertEquals(2, thread2.get("threadNum"));
        assertEquals(5002, thread2.get("lwp"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thread2Frames = (List<Map<String, Object>>) thread2.get("frames");
        assertEquals(2, thread2Frames.size());
        assertEquals("pthread_cond_wait", thread2Frames.get(0).get("function"));
        assertEquals("/lib/x86_64-linux-gnu/libpthread.so.0", thread2Frames.get(0).get("library"),
            "a frame resolved only via the dynamic symbol table (no debug info) must still "
                + "carry its library, not be silently dropped");

        Map<String, Object> thread1 = threads.get(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> thread1Frames = (List<Map<String, Object>>) thread1.get("frames");
        assertEquals(3, thread1Frames.size());
        Map<String, Object> unsafeFrame = thread1Frames.get(2);
        assertEquals("Unsafe_PutInt", unsafeFrame.get("function"));
        assertEquals("/build/openjdk/src/hotspot/share/prims/unsafe.cpp", unsafeFrame.get("file"));
        assertEquals(150, unsafeFrame.get("line"));
    }
}
