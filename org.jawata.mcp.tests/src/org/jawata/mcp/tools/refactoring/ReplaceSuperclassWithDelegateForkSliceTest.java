package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.ReplaceSuperclassWithDelegateTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 57 ON CODE WE DID NOT AUTHOR — and it REFUSES, which is evidence rather than a
 * demonstration. Stage 6 recorded that distinction and this row inherits it.
 *
 * <h2>The census, and the reason the absence is not a surprise</h2>
 *
 * <p>Every class in the vendored slices that extends a named superclass and overrides nothing
 * was enumerated. They fall into two groups and neither is a candidate:</p>
 *
 * <ul>
 *   <li><b>EXCEPTIONS</b> — {@code RemoteServiceException extends Exception},
 *       {@code ThrottlingException extends RateLimitException},
 *       {@code ServiceUnavailableException extends RateLimitException},
 *       {@code LockingException extends RuntimeException}. Inheritance is not merely correct
 *       here, it is the mechanism: a {@code catch} of the supertype is a substitution, so these
 *       are the LEAST delegatable classes there are.</li>
 *   <li><b>DOMAIN SUBCLASSES USED POLYMORPHICALLY</b> — {@code Orc}, {@code Elf} and
 *       {@code Human} extending {@code Creature}; {@code RegisterWorker extends ServerCommand}.
 *       Every one is held and passed as its supertype.</li>
 * </ul>
 *
 * <p><b>That is what a curated pattern corpus is:</b> it uses inheritance where inheritance is
 * right, so the smell this row cures is what such a corpus is written to NOT contain. The
 * absence is explained rather than merely reported — and it is the same shape row 29 found, one
 * row earlier, for the same reason.</p>
 *
 * <h2>What is pinned, and why it is the strongest available evidence</h2>
 *
 * <p>Upstream's {@code Orc} passes every LOCAL check this row makes: it overrides nothing, has
 * one constructor, and nothing in its own file objects. It is refused ONLY because of a line in
 * a DIFFERENT file — {@code App} adds one to a {@code List<Creature>}. So this test exercises
 * precisely the cross-file half that is the row's entire safety argument, on code nobody wrote
 * for it.</p>
 */
class ReplaceSuperclassWithDelegateForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar/lockableobject";

    /** Upstream's own line — the inheritance this row is asked about. */
    private static final String UPSTREAM_EXTENDS = "public class Orc extends Creature";

    @Test
    @DisplayName("row 57 REFUSES upstream's Orc — nothing in its own file objects, and a line in "
        + "App uses it as a Creature")
    void refusesAClassUsedAsItsSuperclassInAnotherFile() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-lockable-object", PKG,
            "domain/Orc.java", UPSTREAM_EXTENDS);
        String before = slice.read("domain/Orc.java");

        Assertions.assertAll(
            () -> assertFalse(before.contains("@Override"),
                "PROOF OF LIFE: Orc overrides nothing, so the override refusal is NOT what"
                    + " fires here — without this the test could pass for the wrong reason:\n"
                    + before),
            () -> assertTrue(slice.read("App.java").contains("creatures.add(new Orc("),
                "PROOF OF LIFE, and it is in ANOTHER FILE: this single line is the whole reason"
                    + " the change is refused"));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("direction", "replace_superclass_with_delegate");
        args.put("typeName", "com.iluwatar.lockableobject.domain.Orc");

        ToolResponse r = new HierarchyTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(),
                "deleting `extends Creature` would break the line in App that treats one as a"
                    + " Creature, and that line is invisible from Orc's own file"),
            () -> assertEquals(ReplaceSuperclassWithDelegateTool.Refusal.USED_AS_ITS_SUPERCLASS,
                r.getError().getReason(),
                "and it must be THIS refusal — a reason code rather than a substring, because"
                    + " several of this row's branches could be argued for a domain subclass: "
                    + r.getError()),
            () -> assertEquals(before, slice.read("domain/Orc.java"),
                "upstream's file is untouched, which a refusal must leave true"));
    }
}
