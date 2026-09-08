package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#63 — <b>a record's canonical constructor has no signature of its own, and the
 * product now says so instead of failing two different confusing ways.</b>
 *
 * <p>The language fixes it: a record constructor either takes exactly the components or delegates
 * to one that does. So the signature is a projection of the header, and changing it is not a
 * signature change at all — adding or removing a component is a HEADER edit that cascades to the
 * accessors, {@code equals}/{@code hashCode}/{@code toString} and every {@code new} call site. No
 * operation performs that; the issue says as much and asks for the boundary to be legible.</p>
 *
 * <p><b>Measured before the fix, and the two entry points failed differently.</b></p>
 *
 * <ul>
 *   <li><b>IMPLICIT</b> ({@code CodeAddress#CodeAddress}) — the model holds no member, so the
 *       resolver answered <i>"exists, but it has no member 'CodeAddress' … It has: of, text,
 *       number, symbolOf, qualified, complete, arguments"</i>. That reads as <i>this record has
 *       no constructor</i>, which is false: {@code new CodeAddress(...)} compiles at seven sites.
 *       It is the issue's own quoted repro.</li>
 *   <li><b>COMPACT</b> ({@code NextStep#NextStep}) — accepted, and JDT's engine wrote a parameter
 *       list OVER the opening brace, producing {@code public NextStep(…)} with no {@code &#123;}
 *       and a sibling constructor stripped of its semicolon.</li>
 * </ul>
 *
 * <p><b>Nothing corrupt shipped, and that is worth stating precisely rather than dramatising.</b>
 * {@code compileGateMode()} is {@code REPORT}, which keeps introduced TYPE errors and refuses
 * SYNTAX ones — so the compact case was undone with {@code REFACTORING_BROKE_COMPILE} and
 * <i>"no files remain modified"</i>. The defect is what the caller is told: an error whose own
 * hint says <i>"report it"</i>, where the truth is that the operation does not apply to this
 * shape. It WAS reported; this is that issue.</p>
 *
 * <p><b>The control is the whole point of the second case.</b> A rule that refused every
 * constructor on a record would satisfy the refusal assertion exactly as well as the right one,
 * and a refusal alone cannot tell the two apart — so the same record's NON-canonical constructor
 * must still change.</p>
 *
 * <p>Both fixtures are written into a COPY of the shared project: adding a file to
 * {@code simple-maven} has moved a counted population four times in this sprint. Each is the
 * public primary type of its own file, which also keeps the name form off the package-private
 * secondary-type path that step 9 found broken.</p>
 */
class RecordCanonicalConstructorHasNoSignatureOfItsOwnTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** Its canonical constructor is COMPACT — declared, with no parameter list in source. */
    private static final String COMPACT = """
        package com.example;

        /** mcp#63 fixture: a compact canonical constructor beside an ordinary one. */
        public record RecordCompactTargets(String label, int size) {

            public RecordCompactTargets {
                label = label == null ? "" : label;
            }

            /** NON-canonical, and the control: an ordinary constructor that delegates. */
            public RecordCompactTargets(String label) {
                this(label, 0);
            }
        }
        """;

    /** Declares no constructor at all, so its canonical one is IMPLICIT. */
    private static final String IMPLICIT = """
        package com.example;

        /** mcp#63 fixture: nothing is declared, so there is no member to address. */
        public record RecordImplicitTargets(String label, int size) {
        }
        """;

    private ChangeMethodSignatureTool tool;
    private ObjectMapper mapper;
    private Path compactFile;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        compactFile = pkg.resolve("RecordCompactTargets.java");
        Files.writeString(compactFile, COMPACT);
        Files.writeString(pkg.resolve("RecordImplicitTargets.java"), IMPLICIT);
        // The production reconcile, so the test proves how the product notices a file written
        // outside it rather than how a test-only refresh does.
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
        tool = new ChangeMethodSignatureTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
    }

    /**
     * The caret, computed from the fixture rather than hard-coded — a line number written by
     * hand is stale the first time the fixture gains a line.
     */
    private ObjectNode caretAt(String needle) {
        String[] lines = COMPACT.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int column = lines[i].indexOf(needle);
            if (column >= 0) {
                ObjectNode args = mapper.createObjectNode();
                args.put("kind", "change_signature");
                args.put("filePath", compactFile.toString());
                args.put("line", i);
                args.put("column", column);
                return args;
            }
        }
        throw new AssertionError("the fixture has no line containing: " + needle);
    }

    @Test
    @DisplayName("mcp#63: a record's COMPACT canonical constructor is refused by name, not broken")
    void theCompactCanonicalConstructorIsRefusedByName() throws Exception {
        String before = Files.readString(compactFile);

        ObjectNode args = caretAt("RecordCompactTargets {");
        args.put("newName", "RecordCompactTargets");
        ToolResponse response = tool.execute(args);

        assertAll(
            () -> assertFalse(response.isSuccess(), "the canonical constructor is refused: "
                + response.getData()),
            // The reason CODE, not a substring: two refusals can share prose, and the one this
            // replaced (REFACTORING_BROKE_COMPILE) also names the record and the constructor.
            () -> assertEquals("RECORD_CANONICAL_CONSTRUCTOR", response.getError().getReason(),
                "the precondition declined, before any change was built: " + response.getError()),
            () -> assertFalse("REFACTORING_FAILED".equals(response.getError().getCode())
                    || "REFACTORING_BROKE_COMPILE".equals(response.getError().getCode()),
                "and it is no longer the engine failing downstream: " + response.getError()),
            // The refusal earns its keep only if it says what the caller must do instead.
            () -> assertTrue(response.getError().getMessage().contains("HEADER"),
                "the message names where a component is actually added: "
                    + response.getError().getMessage()),
            () -> assertEquals(before, Files.readString(compactFile),
                "and nothing was written"));
    }

    /**
     * The control. Without it, a rule reading "refuse any constructor on a record" passes the
     * test above, and no refusal can distinguish the two.
     */
    @Test
    @DisplayName("mcp#63 control: a NON-canonical constructor of the same record still changes")
    void aNonCanonicalConstructorOfTheSameRecordStillChanges() throws Exception {
        ObjectNode args = caretAt("RecordCompactTargets(String label)");
        ArrayNode parameters = mapper.createArrayNode();
        ObjectNode renamed = mapper.createObjectNode();
        renamed.put("name", "initialLabel");
        renamed.put("type", "String");
        parameters.add(renamed);
        args.set("newParameters", parameters);

        ToolResponse response = tool.execute(args);

        assertAll(
            () -> assertTrue(response.isSuccess(),
                "an ordinary constructor on a record is not the canonical one: "
                    + response.getError()),
            () -> assertTrue(Files.readString(compactFile).contains("initialLabel"),
                "and the change reached disk"));
    }

    /**
     * The issue's own repro. It is driven by NAME because there is no position to point at —
     * which is the defect: nothing is declared, so nothing can be addressed.
     */
    @Test
    @DisplayName("mcp#63: an IMPLICIT canonical constructor is named as implicit, not as absent")
    void theImplicitCanonicalConstructorIsNamedAsImplicit() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "change_signature");
        args.put("symbol", "com.example.RecordImplicitTargets#RecordImplicitTargets");
        args.put("newName", "RecordImplicitTargets");

        ToolResponse response = tool.execute(args);
        String message = response.getError() == null ? "" : response.getError().getMessage();

        assertAll(
            () -> assertFalse(response.isSuccess(), "there is still nothing to address"),
            () -> assertTrue(message.contains("IMPLICIT"),
                "the answer says the constructor is implicit: " + message),
            // The negative half is what discriminates: the old answer listed the members the
            // record DOES declare, which reads as "this type has no constructor".
            () -> assertFalse(message.contains("it has no member"),
                "and no longer claims the type has no such member: " + message));
    }
}
