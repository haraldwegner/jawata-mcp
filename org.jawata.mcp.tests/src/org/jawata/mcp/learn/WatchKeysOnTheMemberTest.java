package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D8a (feedback D7): THE ARCHITECT WATCH KEYED ITS FINDING ON A LINE NUMBER.
 *
 * <p>After an extract moved a pre-existing empty catch from line 976 to 987, the watch reported it
 * as "new since your change" — twice, at two lines, for a block that was on master the whole time.
 * The {@code bugs} rows carried a line and no symbol, so the engine's identity fell through to
 * {@code String.valueOf(line)}, and anything that shifted the line made a known finding unknown.</p>
 *
 * <h2>Why a real detector and a fresh engine</h2>
 *
 * <p>The engine's identity is best-first over the fields a row carries, so a fake detector that
 * supplies a {@code symbol} is silent at HEAD already and proves nothing: the defect lives in what
 * the PRODUCER emits. So this binds the real {@code find_quality_issue}, exactly as the
 * application wires it. And the second pass runs on a SECOND engine over the same store, because
 * the first engine's in-memory cooldown would keep it quiet whatever the persisted baseline says
 * (the reason {@link WatchIsSilentTheSecondTimeTest} exists).</p>
 */
class WatchKeysOnTheMemberTest {

    private static final String SWALLOWER =
        "    void watchSeededSwallower() {\n"
            + "        try { Integer.parseInt(\"x\"); } catch (NumberFormatException ignore) {}\n"
            + "    }\n";

    private static final String SWALLOWER_TWICE =
        "    void watchSeededSwallower() {\n"
            + "        try { Integer.parseInt(\"x\"); } catch (NumberFormatException ignore) {}\n"
            + "        try { Integer.parseInt(\"y\"); } catch (NumberFormatException ignore) {}\n"
            + "    }\n";

    private static final String SWALLOWER_AND_LEAK =
        "    void watchSeededSwallower() {\n"
            + "        try { Integer.parseInt(\"x\"); } catch (NumberFormatException ignore) {}\n"
            + "        java.io.StringReader leaked = new java.io.StringReader(\"y\");\n"
            + "    }\n";

    private static final String ANOTHER_MEMBER =
        "    void watchSeededOtherMember() {\n"
            + "        try { Integer.parseInt(\"z\"); } catch (NumberFormatException ignore) {}\n"
            + "    }\n";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private H2ExperienceStore store;
    private JdtServiceImpl service;
    private WatchEngine.DetectorFn binding;
    private LearnerEventStore events;
    private Path src;
    private String original;

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    private void open() throws Exception {
        Path copy = helper.copyFixture("simple-maven");
        service = new JdtServiceImpl();
        service.loadProject(copy);
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectMapper mapper = new ObjectMapper();
        binding = (kind, filePath) -> {
            ObjectNode args = mapper.createObjectNode();
            args.put("kind", kind);
            args.put("filePath", filePath);
            return tool.execute(args);
        };
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
        Path projectRoot = service.allProjects().iterator().next().projectRoot();
        try (var stream = Files.walk(projectRoot.resolve("src/main/java"))) {
            src = stream.filter(f -> f.toString().endsWith(".java")).findFirst().orElseThrow();
        }
        original = Files.readString(src);
    }

    private WatchEngine freshEngine() {
        return new WatchEngine(binding, events);
    }

    private Optional<String> watchNow(WatchEngine engine) {
        return engine.watch("it-session", List.of(src.toString()));
    }

    private void write(String text) throws Exception {
        Files.writeString(src, text);
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
    }

    private static String withMember(String source, String member) {
        return source.replaceFirst("}\\s*$", member + "}\n");
    }

    /** Two lines pushed in after the package declaration: every line below it moves. */
    private static String shifted(String source) {
        return source.replaceFirst("(?m)^package [^\n]*;\n", "$0\n// shifted\n// shifted\n");
    }

    @Test
    @DisplayName("a finding that merely moved down the file is not new")
    void aLineShiftIsNotANewFinding() throws Exception {
        open();
        watchNow(freshEngine());                       // baseline of whatever the file carries

        write(withMember(original, SWALLOWER));
        Optional<String> introduced = watchNow(freshEngine());
        // THE CONTROL: the empty catch is genuinely new here and must speak, or the silence
        // below would be satisfied by a watch that says nothing at all.
        assertTrue(introduced.isPresent() && introduced.get().contains("Empty catch"),
            "the control: a newly written empty catch must surface. Got: "
                + introduced.orElse("<silent>"));

        write(shifted(withMember(original, SWALLOWER)));
        Optional<String> afterShift = watchNow(freshEngine());
        assertTrue(afterShift.isEmpty(),
            "the same empty catch, in the same member, two lines lower, was reported as new"
                + " — the watch keyed it on its line. Got: " + afterShift.orElse(""));
    }

    @Test
    @DisplayName("a second finding of the same kind in ANOTHER member still surfaces after the shift")
    void aNewFindingInAnotherMemberStillSurfaces() throws Exception {
        open();
        watchNow(freshEngine());
        write(withMember(original, SWALLOWER));
        watchNow(freshEngine());

        write(shifted(withMember(withMember(original, SWALLOWER), ANOTHER_MEMBER)));
        Optional<String> next = watchNow(freshEngine());
        assertTrue(next.isPresent(),
            "a NEW empty catch in a different method must still surface — a member key that"
                + " swallowed it would pass the shift test above and be wrong");
    }

    @Test
    @DisplayName("a DIFFERENT kind of finding in a member that already has one still surfaces")
    void aDifferentKindOfFindingInTheSameMemberStillSurfaces() throws Exception {
        open();
        watchNow(freshEngine());
        write(withMember(original, SWALLOWER));
        watchNow(freshEngine());

        write(withMember(original, SWALLOWER_AND_LEAK));
        Optional<String> next = watchNow(freshEngine());
        assertTrue(next.isPresent() && next.get().contains("Closeable"),
            "a resource leak added to a method that already swallows an exception is a NEW"
                + " finding. A key of member alone would hide it behind the one already known."
                + " Got: " + next.orElse("<silent>"));
    }

    @Test
    @DisplayName("a second identical finding in the SAME member still surfaces")
    void aSecondFindingInTheSameMemberStillSurfaces() throws Exception {
        open();
        watchNow(freshEngine());
        write(withMember(original, SWALLOWER));
        watchNow(freshEngine());

        write(withMember(original, SWALLOWER_TWICE));
        Optional<String> next = watchNow(freshEngine());
        assertTrue(next.isPresent(),
            "keying on the member alone collapses two empty catches in one method into one"
                + " finding, so the second would be silent for ever. The line key surfaced it.");
    }
}
