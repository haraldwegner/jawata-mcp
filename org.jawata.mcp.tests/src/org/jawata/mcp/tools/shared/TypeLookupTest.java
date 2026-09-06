package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SHARED TYPE LOOKUP, WHICH HAD 25 CALL SITES IN 20 FILES AND NO TEST.
 *
 * <p>This class exists because a C8b round-3 mutation found nothing. Reverting one of the
 * doors that had just been repointed at {@code TypeLookup} — {@code refactor_to_visitor}, back
 * to its own top-level-only walk — left the suite GREEN, so five production repairs had
 * shipped with nothing that would notice them regressing. The defect they fix was reproduced
 * against the RUNNING product by an audit, not by any test, and a live reproduction is not a
 * regression guard.</p>
 *
 * <h2>What it pins, and why the control is in the same test</h2>
 *
 * <p>The claim is narrow and it is the one every call site depends on: {@code declaration}
 * finds a type declared INSIDE another type. Asserting only that would be weak — it would pass
 * against any implementation that happened to return something — so each case carries the
 * FORMER implementation beside it, walking {@code CompilationUnit#types()} by simple name, and
 * asserts that it returns null for the same input. Without that half a reader cannot tell a
 * lookup that descends from one that never needed to.</p>
 *
 * <p>It does not assert that any particular door calls this. That is a fact the compiler
 * already holds: each call site is now a single expression naming this method, not a copied
 * algorithm, so a door that stopped using it would stop compiling rather than drift.</p>
 */
class TypeLookupTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    /** The former implementation, verbatim in shape: the unit's TOP-LEVEL types, by name. */
    private static TypeDeclaration topLevelByName(CompilationUnit ast, String simpleName) {
        for (Object t : ast.types()) {
            if (t instanceof TypeDeclaration td
                    && simpleName.equals(td.getName().getIdentifier())) {
                return td;
            }
        }
        return null;
    }

    @Test
    @DisplayName("a NESTED type is found — and the top-level walk it replaced returns null")
    void findsANestedType() throws Exception {
        IType outer = service.getJavaProject().findType("com.example.DeadCodeTargets");
        assertNotNull(outer, "the fixture must declare com.example.DeadCodeTargets");
        IType nested = outer.getType("NeverUsed");
        assertTrue(nested.exists(),
            "PROOF OF LIFE: the fixture must declare a NESTED type, or this test asserts"
                + " nothing about nesting at all");

        CompilationUnit ast = parse(outer.getCompilationUnit());

        AbstractTypeDeclaration found = TypeLookup.declaration(ast, nested);
        assertNotNull(found,
            "the shared lookup must find a type declared inside another type — this is the"
                + " defect that shipped in eleven separate private copies");
        assertEquals("NeverUsed", found.getName().getIdentifier());

        // THE CONTROL. Without it the assertion above passes against any lookup at all, and
        // could not distinguish one that descends from one whose input never needed it.
        assertNull(topLevelByName(ast, "NeverUsed"),
            "the implementation this replaced must FAIL on this input; if it succeeds, the"
                + " fixture is top-level and the case above proves nothing about nesting");
    }

    @Test
    @DisplayName("a top-level type is still found — the fix did not trade one case for the other")
    void stillFindsATopLevelType() throws Exception {
        IType top = service.getJavaProject().findType("com.example.DeadCodeTargets");
        assertNotNull(top);
        CompilationUnit ast = parse(top.getCompilationUnit());

        AbstractTypeDeclaration found = TypeLookup.declaration(ast, top);
        assertNotNull(found, "the ordinary case must keep working");
        assertEquals("DeadCodeTargets", found.getName().getIdentifier());
        // Here the old form agrees, which is exactly why the nested case is the discriminator.
        assertNotNull(topLevelByName(ast, "DeadCodeTargets"));
    }
}
