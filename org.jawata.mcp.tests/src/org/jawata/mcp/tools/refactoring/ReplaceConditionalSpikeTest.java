package org.jawata.mcp.tools.refactoring;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 8 — THE RANK 2 SPIKE, and it runs before any of the operation.
 *
 * <h2>The one question this answers</h2>
 *
 * <p>Rank 2 (Replace Conditional with Polymorphism) has <b>no JDT engine</b> —
 * {@code search_symbols *Polymorphism*} returns zero across the workspace and its
 * jars. So unlike Extract Class and Introduce Factory it cannot be delegated; it is
 * hand-rolled AST work, and the nearest thing we own is {@code RefactorToStateTool}.
 * </p>
 *
 * <p><b>But that tool cannot read the shape rank 2 must handle.</b> Its case walk
 * iterates {@code switch.statements()} expecting the OLD LABELLED form — a
 * {@code SwitchCase} followed by loose statements until the next label. The
 * before-case is an ARROW switch ({@code case OPEN -> { … }}), where JDT models each
 * arm differently. If the arms cannot even be ENUMERATED, every later step of rank 2
 * is built on sand.</p>
 *
 * <p>That is the whole question here: <b>can the target shape be decomposed at
 * all?</b> Stage 7's spike earned this discipline by finding a production NPE with
 * every precondition green, before the front door, the gates and the E2E promise
 * were written against it.</p>
 *
 * <h2>The before-case is real, and already vendored</h2>
 *
 * <p>{@code DefaultCircuitBreaker.setState} from the pinned fork slice: an arrow
 * switch on the {@code State} ENUM whose arms mutate fields. It violates all three
 * of {@code refactor_to_state}'s restrictions at once — enum rather than private int
 * field, arrow rather than labelled form, and a selector that is a PARAMETER rather
 * than a field of the context.</p>
 *
 * <h2>What this does NOT claim</h2>
 *
 * <p>It is a spike, not the operation: no front door, no done-definition, no
 * rewrite. And it is not a claim that this particular switch OUGHT to be refactored
 * — that judgement belongs to the advise tier, and the measurement behind it is that
 * the detector reports 24 findings on this tree of which most are parsers where
 * polymorphism would add types holding no state.</p>
 */
class ReplaceConditionalSpikeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path breakerFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("fork-circuit-breaker");
        breakerFile = helper.getTempDirectory().resolve(
            "fork-circuit-breaker/src/main/java/com/iluwatar/circuitbreaker/"
                + "DefaultCircuitBreaker.java");
    }

    private CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        return (CompilationUnit) parser.createAST(null);
    }

    @Test
    @DisplayName("rank2 spike: an ARROW switch on an enum decomposes into named arms")
    void anArrowSwitchDecomposesIntoArms() throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(breakerFile);
        assertNotNull(cu, "the fixture must resolve before anything here means anything");
        CompilationUnit ast = parse(cu);

        List<SwitchStatement> switches = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SwitchStatement node) {
                switches.add(node);
                return true;
            }
        });
        assertEquals(1, switches.size(),
            "PROOF OF LIFE: the fork slice must still contain exactly one switch — if the"
                + " fixture changed, this spike is measuring something else");

        SwitchStatement sw = switches.get(0);

        // (1) THE SELECTOR. refactor_to_state requires a private int FIELD; this is an
        // enum, and it is a PARAMETER. Both facts are asserted, because they are two of
        // the three restrictions rank 2 exists to lift.
        assertTrue(sw.getExpression() instanceof SimpleName,
            "the selector is expected to be a simple name");
        SimpleName selector = (SimpleName) sw.getExpression();
        assertTrue(selector.resolveBinding() instanceof IVariableBinding,
            "the selector must resolve, or nothing downstream can reason about it");
        IVariableBinding binding = (IVariableBinding) selector.resolveBinding();
        assertTrue(binding.getType().isEnum(),
            "the discriminator must be an ENUM — that is restriction one, and the reason"
                + " refactor_to_state refuses this file");
        assertTrue(!binding.isField(),
            "the selector is a PARAMETER, not a field of the context — restriction two");

        // (2) THE ARM WALK, which is the question. In the arrow form each SwitchCase is
        // a LABELED RULE and its body is the statement that follows it, rather than a
        // run of loose statements terminated by a break.
        Map<String, List<Statement>> arms = new LinkedHashMap<>();
        String pending = null;
        boolean sawLabeledRule = false;
        for (Object o : sw.statements()) {
            if (o instanceof SwitchCase sc) {
                sawLabeledRule |= sc.isSwitchLabeledRule();
                pending = sc.isDefault() ? "default"
                    : String.valueOf(sc.expressions().get(0));
                arms.put(pending, new ArrayList<>());
            } else if (o instanceof Statement st && pending != null) {
                arms.get(pending).add(st);
            }
        }

        assertTrue(sawLabeledRule,
            "the fixture's switch must be in the ARROW form, or this spike is measuring"
                + " the shape refactor_to_state already handles and proves nothing new");
        assertEquals(List.of("OPEN", "HALF_OPEN", "default"), List.copyOf(arms.keySet()),
            () -> "the three arms must be enumerable BY LABEL. This is the question the"
                + " spike exists to answer: refactor_to_state's case walk assumes the old"
                + " labelled form, and if the arrow form cannot be decomposed then rank 2"
                + " is not a generalisation of it but a rewrite. Got: " + arms.keySet());
        for (Map.Entry<String, List<Statement>> e : arms.entrySet()) {
            assertTrue(!e.getValue().isEmpty(),
                () -> "arm '" + e.getKey() + "' decomposed to an EMPTY body. An arm whose"
                    + " statements cannot be recovered cannot become a method body, and a"
                    + " green count of arms would then mean nothing");
        }
    }
}
