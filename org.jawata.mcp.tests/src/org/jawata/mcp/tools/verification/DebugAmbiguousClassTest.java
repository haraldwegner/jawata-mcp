package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 audit (2026-07-14, T1.16) — a class name that is loaded by MORE THAN ONE
 * classloader at once. This is ordinary in OSGi (the same class present in two bundles),
 * and the intended debug target — JATS — is OSGi.
 *
 * <p>Every {@code vm.classesByName(name).get(0)} call site silently picked one of the
 * matches: {@code redefine} hot-swapped an arbitrary copy, {@code instances} counted an
 * arbitrary population, both reporting success while acting on a coin flip. On a hot-swap
 * that is the difference between fixing the running bug and patching a class that is not
 * even running. The mutating actions must now REFUSE an ambiguous name and NAME the
 * loaders, so the caller can say which one they mean.</p>
 */
class DebugAmbiguousClassTest {

    private static final String TARGET = "com.example.debug.DoubleLoadTarget";
    private static final String AMBIGUOUS = "com.example.debug.DoubleLoaded";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private String sessionId;
    private Path scratchClasses;
    private Path scratchRedef;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();

        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        scratchClasses = Files.createTempDirectory("jawata-ambiguous-");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", scratchClasses.toString(),
            pkg.resolve("DoubleLoadTarget.java").toString(),
            pkg.resolve("DoubleLoaded.java").toString()),
            "the double-load fixtures must compile");

        ObjectNode launch = action("launch");
        launch.put("mainClass", TARGET);
        launch.put("classpath", scratchClasses.toString());
        ToolResponse launched = tool.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        sessionId = (String) data(launched).get("sessionId");

        assertTrue(tool.execute(onSession("resume")).isSuccess());
        awaitBothCopiesLoaded();
    }

    /**
     * The target loads its second {@code DoubleLoaded} copy on its own schedule, then
     * idles forever holding both. A fixed sleep raced that load under machine pressure
     * (TYPE_NOT_LOADED, 2026-07-19); instead poll the ambiguity guard itself — the
     * moment {@code instances} refuses with TYPE_AMBIGUOUS, the state every test here
     * needs (two live copies) provably exists.
     */
    private void awaitBothCopiesLoaded() throws Exception {
        ObjectNode probe = onSession("instances");
        probe.put("className", AMBIGUOUS);
        long deadline = System.currentTimeMillis() + 30_000;
        String last = "(no probe answered)";
        while (System.currentTimeMillis() < deadline) {
            ToolResponse r = tool.execute(probe);
            if (!r.isSuccess() && "TYPE_AMBIGUOUS".equals(r.getError().getCode())) {
                return;
            }
            last = r.isSuccess() ? "one copy loaded so far"
                : r.getError().getCode() + ": " + r.getError().getMessage();
            Thread.sleep(200);
        }
        throw new AssertionError(
            "the target never reached the two-loader state within 30s — last probe: " + last);
    }

    @AfterEach
    void tearDown() throws Exception {
        sessions.closeAll();
        deleteRecursively(scratchClasses);
        deleteRecursively(scratchRedef);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (Exception ignored) {
                    // scratch cleanup is best-effort; the dirs live under /tmp
                }
            });
        }
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

    private ObjectNode onSession(String name) {
        ObjectNode args = action(name);
        args.put("sessionId", sessionId);
        return args;
    }

    @Test
    @DisplayName("instances of an AMBIGUOUS class is refused by name, with the loaders listed")
    void instancesRefusesAnAmbiguousClass() {
        ObjectNode args = onSession("instances");
        args.put("className", AMBIGUOUS);
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(),
            "with two live copies under different loaders, one count is a wrong number stated "
                + "with confidence: " + r.getData());
        assertEquals("TYPE_AMBIGUOUS", r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("classloaders"),
            "the refusal must explain WHY and name the loaders: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("redefine of an AMBIGUOUS class is refused — patching a coin-flip copy looks like success")
    void redefineRefusesAnAmbiguousClass() throws Exception {
        // A real .class of the ambiguous type (its own compiled form is fine — we never get
        // as far as applying it; the ambiguity is caught first, on the target's two loaders).
        scratchRedef = Files.createTempDirectory("jawata-ambiguous-redef-");
        Path pkg = helper.getService().getProjectRoot()
            .resolve("src/main/java/com/example/debug");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", scratchRedef.toString(),
            pkg.resolve("DoubleLoaded.java").toString()));

        ObjectNode args = onSession("redefine");
        args.put("className", AMBIGUOUS);
        args.put("classFile", scratchRedef.resolve("com/example/debug/DoubleLoaded.class").toString());
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "redefining a coin-flip copy must be refused: " + r.getData());
        assertEquals("TYPE_AMBIGUOUS", r.getError().getCode());
    }

    @Test
    @DisplayName("an UNambiguous class in the same session still works — the guard is precise")
    void anUnambiguousClassStillResolves() {
        // DoubleLoadTarget itself is loaded once, by the app loader only.
        ObjectNode args = onSession("instances");
        args.put("className", TARGET);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "a singly-loaded class must not be caught by the ambiguity guard: " + r.getError());
    }
}
