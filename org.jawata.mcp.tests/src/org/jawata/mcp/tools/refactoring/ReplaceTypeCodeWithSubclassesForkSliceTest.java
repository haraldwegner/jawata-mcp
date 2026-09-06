package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.ReplaceTypeCodeWithSubclassesTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 59 ON CODE WE DID NOT AUTHOR — and it REFUSES, which is evidence rather than a
 * demonstration. Stage 6 recorded that distinction and this row inherits it.
 *
 * <h2>The census: the corpus has constant GROUPS in quantity and no TYPE CODES</h2>
 *
 * <p>Every class in the fork's <b>1354 main sources</b> declaring two or more {@code static
 * final} constants that share a {@code PREFIX_} was enumerated. There are plenty — and reading
 * them is what settles it, because a scan for the SHAPE cannot tell the two apart:</p>
 *
 * <ul>
 *   <li>They are <b>SQL and configuration literals</b> — {@code SELECT_*}, {@code CREATE_*},
 *       {@code JNDI_*}, {@code HEALTH_*}, {@code MONGO_*}. A group of related constants is not a
 *       type code; a type code is a value some FIELD takes to say which KIND of thing this is,
 *       and none of these is held by a field at all.</li>
 *   <li>The nearest miss is upstream's {@code RegisterWorkerDto}, pinned below: three
 *       {@code MISSING_*} constants that are VALIDATION ERRORS, of a type the class holds no
 *       field of.</li>
 * </ul>
 *
 * <p><b>The accessor precondition is what tells them apart, and this is where it earns its
 * keep.</b> On a fixture it reads as bookkeeping — of course the code is encapsulated, the
 * fixture was written that way. On foreign code it is the whole discriminator: without it this
 * row would have generated {@code RegisterWorkerDtoMissingName} and two siblings from a group of
 * error messages, which is a plausible-looking change to code that has no type code in it.</p>
 *
 * <p><b>WHICH refusal fires is asserted by reason CODE.</b> Two of this row's refusals could be
 * argued for a class like this, and a shared substring would prove only that something
 * declined.</p>
 */
class ReplaceTypeCodeWithSubclassesForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar";

    /** Upstream's own line — one of the constants a shape-only scan would take for a code. */
    private static final String UPSTREAM_CONSTANT = "MISSING_NAME";

    @Test
    @DisplayName("row 59 REFUSES upstream's RegisterWorkerDto — its constant group is validation "
        + "errors, not a type code, and the accessor precondition is what says so")
    void refusesAConstantGroupThatIsNotATypeCode() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-notification", PKG,
            "RegisterWorkerDto.java", UPSTREAM_CONSTANT);
        String before = slice.read("RegisterWorkerDto.java");

        Assertions.assertAll(
            () -> assertTrue(before.contains("MISSING_OCCUPATION"),
                "PROOF OF LIFE, the GROUP: with fewer than two shared-prefix constants this"
                    + " would refuse as NO_TYPE_CODE_CONSTANTS and prove something else:\n"
                    + before),
            () -> assertFalse(before.contains("public class RegisterWorkerDto extends"
                    + " DataTransferObject {\n  private NotificationError"),
                "PROOF OF LIFE, the ABSENCE the refusal is about: the class holds no field of"
                    + " the constants' type, which is why they are not a code"));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("direction", "replace_type_code_with_subclasses");
        args.put("typeName", "com.iluwatar.RegisterWorkerDto");

        ToolResponse r = new HierarchyTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(),
                "generating three subclasses from a group of error messages would be a"
                    + " plausible-looking change to code with no type code in it"),
            () -> assertEquals(ReplaceTypeCodeWithSubclassesTool.Refusal.CODE_NOT_ENCAPSULATED,
                r.getError().getReason(),
                "and it must be THIS refusal — the constants exist, so the group check passes;"
                    + " what is missing is anything READING them as the object's own kind: "
                    + r.getError()),
            () -> assertEquals(before, slice.read("RegisterWorkerDto.java"),
                "upstream's file is untouched, which a refusal must leave true"));
    }
}
