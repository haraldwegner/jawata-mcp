package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.text.edits.TextEdit;

/**
 * A cleanup whose edit comes from one of JDT's own clean-up engines.
 *
 * <p>The two cleanups that shipped before Sprint 28d-rescue — {@code add_final} and
 * {@code redundant_modifiers} — are each a one-line call into a JDT fix, and there is
 * nothing for a class of their own to hold. They keep their bodies where they are and
 * enter the registry through this adapter, so the registry is the single kind list
 * without either of them being rewritten to prove it.</p>
 *
 * <p>A rule with its own analysis, like {@link GuardClausesRule}, implements
 * {@link CleanupRule} directly instead.</p>
 */
public final class JdtCleanupRule implements CleanupRule {

    /** The engine call, as a function of the parsed file. */
    @FunctionalInterface
    public interface Fix {
        TextEdit apply(CompilationUnit ast) throws Exception;
    }

    private final String kind;
    private final String summary;
    private final Fix fix;

    public JdtCleanupRule(String kind, String summary, Fix fix) {
        this.kind = kind;
        this.summary = summary;
        this.fix = fix;
    }

    @Override
    public String kind() {
        return kind;
    }

    @Override
    public String kindSummary() {
        return summary;
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        return fix.apply(ast);
    }
}
