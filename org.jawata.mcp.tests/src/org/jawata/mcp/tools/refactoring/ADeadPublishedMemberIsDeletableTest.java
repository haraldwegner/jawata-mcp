package org.jawata.mcp.tools.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.CompileWorkspaceTool;
import org.jawata.mcp.tools.InlineTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S5 / D9 row 3 — {@code inline kind=unreferenced_member}.
 *
 * <p>The census counts SEVEN hand edits across two rows with one cause, stated in row 75:
 * <em>"the cleanup covers private members only; these were public with 0 references
 * (`find_references` proves it, git proved `#312` dead at the start commit)"</em>. Row 76 adds
 * two on a public nested interface's members.</p>
 *
 * <p><strong>Driven through the DOOR rather than the delegate</strong>, so the dispatch arm is
 * exercised with it. A delegate reached directly would pass while {@code inline} refused the
 * kind as unknown — which is this repository's recorded defect of a kind that "ran correctly for
 * anyone who knew its argument names and was invisible to everyone else".</p>
 *
 * <p>Most of this class asserts REFUSALS, and that is the subject rather than defensiveness.
 * The refusals are also where two of my own claims were corrected by mutations rather than by
 * review: an override does NOT report zero references in this engine — JDT matches the
 * supertype's own declaration, and matches {@code toString()} polymorphically besides — so
 * that branch delivers a precise REASON rather than the only barrier to a deletion. Each test
 * therefore asserts the reason CODE, because on this operation the code is the deliverable.</p>
 */
class ADeadPublishedMemberIsDeletableTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private InlineTool inline;
    private ObjectMapper mapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("dead-api");
        inline = new InlineTool(() -> service,
            new org.jawata.mcp.refactoring.RefactoringChangeCache());
        mapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    @Test
    @DisplayName("a public member nothing references is deleted, and the project still compiles")
    void theDeadMemberGoes() throws Exception {
        Path registry = registryPath();
        assertTrue(Files.readString(registry).contains("public void retireEverything()"),
            "precondition: the member is there to start with");

        ToolResponse r = remove("retireEverything", true);
        assertTrue(r.isSuccess(), () -> "the deletion failed: " + describe(r));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("undoChangeId"), "a deletion must be reversible");

        String after = Files.readString(registry);
        assertFalse(after.contains("retireEverything"),
            () -> "the member must be gone, javadoc and all:\n" + after);
        // The members around it must survive — a deletion that took the file's other members
        // with it would satisfy the assertion above.
        assertTrue(after.contains("public void register(String entry)"),
            () -> "the neighbouring member must survive:\n" + after);

        CompileWorkspaceTool compile = new CompileWorkspaceTool(() -> service, () -> new org.jawata.core.workspace.StrictDiskSync(() -> service));
        @SuppressWarnings("unchecked")
        Map<String, Object> cdata =
            (Map<String, Object>) compile.execute(mapper.createObjectNode()).getData();
        assertEquals(0, ((Number) cdata.get("errorCount")).intValue(),
            () -> "deleting a member nothing references cannot break the build; diagnostics="
                + cdata.get("diagnostics"));
    }

    @Test
    @DisplayName("a member WITH a caller is refused, and the refusal says where to look")
    void aReferencedMemberIsRefusedWithItsSites() throws Exception {
        Path registry = registryPath();
        String before = Files.readString(registry);

        ToolResponse r = remove("register", true);
        assertFalse(r.isSuccess(), "a member with a caller is not dead code");
        assertEquals("MEMBER_IS_REFERENCED", r.getError().getReason(),
            () -> "and it must refuse for THAT reason rather than another: " + describe(r));
        assertTrue(String.valueOf(r.getError().getMessage()).contains("Caller.java"),
            () -> "the useful half of the refusal is the site: " + r.getError().getMessage());

        assertEquals(before, Files.readString(registry), "nothing may be written");
    }

    @Test
    @DisplayName("a PRIVATE member is refused and handed to the tool whose proof is stronger")
    void aPrivateMemberIsSentToTheCompilersOwnProof() throws Exception {
        ToolResponse r = remove("unusedAndPrivate", true);
        assertFalse(r.isSuccess(), "a search is the wrong instrument where the compiler decides");
        assertEquals("MEMBER_IS_PRIVATE", r.getError().getReason(), () -> describe(r));
        // D3a: a refusal names the step that DOES apply, as something runnable.
        assertNotNull(r.getError().getNextStep(), "the refusal must name its successor");
        assertEquals("apply_cleanup kind=remove_dead_code",
            r.getError().getNextStep().operation(),
            "and it must be the operation that proves this case with the compiler");
    }

    @Test
    @DisplayName("an override is refused AS an override, not as a member with one reference")
    void anUnreferencedOverrideIsRefused() throws Exception {
        // MEASURED, and it corrected this test twice. Nothing calls shutdown() anywhere, so I
        // predicted the search would return zero and this branch would be the only thing
        // preventing a deletion. It returns ONE: JDT matches Lifecycle's own declaration as a
        // reference to the implementing method. So an override NEVER reports zero in this
        // engine, and what this branch buys is the REASON — "start at the supertype" instead
        // of "it has one reference at Lifecycle.java", which tells a caller nothing about why.
        // That is why the assertion is on the reason code: the code IS the deliverable.
        ToolResponse r = removeIn("Service.java", "shutdown", true);
        assertFalse(r.isSuccess(), "deleting it leaves Service not implementing Lifecycle");
        assertEquals("MEMBER_OVERRIDES_A_SUPERTYPE", r.getError().getReason(), () -> describe(r));
        assertTrue(String.valueOf(r.getError().getMessage()).contains("Lifecycle"),
            () -> "the refusal should name where the contract is declared: "
                + r.getError().getMessage());
    }

    @Test
    @DisplayName("an override the search DOES see is refused earlier, and the order is the point")
    void anOverrideWithPolymorphicCallersIsRefusedToo() throws Exception {
        // MEASURED, after a mutation corrected what this test used to claim: the search does
        // NOT report zero for toString(). JDT matches calls to toString() polymorphically, so
        // on this five-file fixture it reports TEN. The override check still answers first,
        // which is right — it names the real reason — but with it disabled the reference check
        // catches this member anyway. So toString is NOT evidence that the override branch is
        // load-bearing; the test above is, and it is where mutation D lands.
        ToolResponse r = remove("toString", true);
        assertFalse(r.isSuccess(), "deleting an override changes dispatch");
        assertEquals("MEMBER_OVERRIDES_A_SUPERTYPE", r.getError().getReason(), () -> describe(r));
        assertTrue(String.valueOf(r.getError().getMessage()).contains("Object"),
            () -> "the refusal should name where the contract is declared: "
                + r.getError().getMessage());
    }

    @Test
    @DisplayName("an interface member with an implementor is refused — the census's row-76 case")
    void anInterfaceMemberWithAnImplementorIsRefused() throws Exception {
        ToolResponse r = removeIn("Lifecycle.java", "shutdown", true);
        assertFalse(r.isSuccess(), "removing the declaration leaves the implementor overriding "
            + "nothing");
        assertEquals("MEMBER_HAS_IMPLEMENTORS", r.getError().getReason(), () -> describe(r));
        assertTrue(String.valueOf(r.getError().getMessage()).contains("Service.java"),
            () -> "and it should name the implementor: " + r.getError().getMessage());
    }

    @Test
    @DisplayName("a field sharing its declaration is refused — its siblings would go with it")
    void aSharedFieldDeclarationIsRefused() throws Exception {
        ToolResponse r = remove("shared", true);
        assertFalse(r.isSuccess(), "removing the declaration would delete the sibling field");
        assertEquals("SHARED_FIELD_DECLARATION", r.getError().getReason(), () -> describe(r));
    }

    @Test
    @DisplayName("the consumer-scope claim has NO DEFAULT: absent is refused, false is refused")
    void theConsumerScopeMustBeStated() throws Exception {
        // ABSENT. A boolean defaulted either way would make the assumption this operation
        // exists to stop making — false would read as a decision nobody took, true would
        // delete published API on the strength of a search that cannot see out of the
        // workspace.
        ObjectNode args = argsFor(registryPath(), "retireEverything");
        ToolResponse absent = inline.execute(args);
        assertFalse(absent.isSuccess(), "an absent claim must not be defaulted");
        assertTrue(String.valueOf(absent.getError().getMessage()).contains("no default"),
            () -> "the refusal must say so: " + absent.getError().getMessage());

        // DECLINED, which is a different answer from absent and gets its own reason code.
        ToolResponse declined = remove("retireEverything", false);
        assertFalse(declined.isSuccess(), "declining the claim refuses the deletion");
        assertEquals("CONSUMER_SCOPE_NOT_DECLARED", declined.getError().getReason(),
            () -> describe(declined));

        assertTrue(Files.readString(registryPath()).contains("retireEverything"),
            "neither path may write anything");
    }

    private Path registryPath() {
        return projectPath.resolve("src/main/java/com/example/Registry.java");
    }

    private ToolResponse remove(String memberNeedle, boolean consumersHere) throws Exception {
        return removeIn("Registry.java", memberNeedle, consumersHere);
    }

    private ToolResponse removeIn(String fileName, String memberNeedle, boolean consumersHere)
            throws Exception {
        Path file = projectPath.resolve("src/main/java/com/example/" + fileName);
        ObjectNode args = argsFor(file, memberNeedle);
        args.put("consumersAreInWorkspace", consumersHere);
        return inline.execute(args);
    }

    private ObjectNode argsFor(Path file, String memberNeedle) throws Exception {
        String source = Files.readString(file);
        // The DECLARATION's own name, not the first mention: every one of these members is named
        // in a javadoc above it, and a caret in a comment resolves to nothing.
        int[] pos = posOfDeclaration(source, memberNeedle);
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "unreferenced_member");
        args.put("filePath", file.toString());
        args.put("line", pos[0]);
        args.put("column", pos[1]);
        return args;
    }

    /**
     * Zero-based [line, column] of {@code needle} on the first line that is NOT a comment —
     * which is what makes these carets land on declarations in a fixture whose javadoc names
     * every member it describes.
     */
    private static int[] posOfDeclaration(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].stripLeading();
            if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
                continue;
            }
            int col = lines[i].indexOf(needle);
            if (col >= 0) {
                return new int[]{i, col};
            }
        }
        throw new AssertionError("no declaration line contains '" + needle + "' in:\n" + source);
    }

    private static String describe(ToolResponse r) {
        return r.getError() == null ? "(no error)"
            : r.getError().getReason() + " / " + r.getError().getMessage();
    }
}
