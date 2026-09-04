package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.text.edits.TextEdit;

/**
 * ONE MECHANICAL REWRITE, over one parsed file.
 *
 * <p>{@code apply_cleanup} is a SWEEP: it lists the files, reads them, computes an edit
 * per file, and gathers every edit into one change with one undo handle. Everything in
 * that sentence is the same whatever the cleanup is — the file listing, the honesty about
 * files it could not read, the parity gate, the apply. The only thing that differs is the
 * edit, and this is that.</p>
 *
 * <h2>Why a registry of rules, and why now</h2>
 *
 * <p>The tool held two rules and chose between them with a ternary, and a two-entry
 * {@code KINDS} list beside it. Sprint 28d-rescue takes it to eleven. That is the shape
 * {@link org.jawata.mcp.tools.ExtractTool} carries a long note about — the kind list, the
 * dispatch and the published schema in three places — and the worked example there cost a
 * shipped operation that ran correctly for anyone who knew its argument names and was
 * invisible to everyone else.</p>
 *
 * <p>So the kinds are DERIVED from the registry: a rule that exists is dispatched,
 * published and described, and a rule that does not exist is none of those. Adding a
 * cleanup is writing one class and one registry line.</p>
 *
 * <p><b>A rule returns null when it has nothing to do</b>, which is the ordinary case for
 * most files. It must never throw for a file it simply does not apply to — the sweep
 * distinguishes "read it, nothing to change" from "could not read it", and a rule that
 * throws on the first turns the second into a lie.</p>
 */
public interface CleanupRule extends org.jawata.mcp.tools.KindDelegate {

    /** The published kind name — what a caller passes as {@code kind}. */
    String kind();

    /**
     * THE ROLE, ANSWERED BY WHAT THIS INTERFACE ALREADY DECLARES (Stage 6a, M3a).
     *
     * <p>A cleanup rule is the one delegate shape that is NOT a tool — no name of its own, no
     * schema, no execute — and it is the reason {@code KindDelegate} is not typed to
     * {@code Tool}. Had it been, this door would have stayed outside the seam and kept the
     * hand-written copies the other five are losing, which is how a law acquires the
     * exception the next door points at.</p>
     *
     * <p>Nothing is added here: the rule was already required to say its kind and to describe
     * itself in one line. The role asks for the same two facts under the names every other
     * delegate uses, so the sweep's rules join without a single rule class changing.</p>
     */
    @Override
    default String kindName() {
        return kind();
    }

    /** The rule's own one-line description, which is the door's whole documentation surface. */
    @Override
    default String kindSummary() {
        return describe();
    }

    /**
     * EMPTY, and truthfully so: {@code apply_cleanup} is a sweep. It takes a kind and an
     * optional file path, and no rule adds a parameter of its own — which is exactly why row
     * 8 could not live on this door and went to {@code refactor_to_pattern} instead.
     */
    @Override
    default java.util.Map<String, Object> parameterSchema() {
        return java.util.Map.of();
    }

    /**
     * One line for the tool's description, which is the whole documentation surface a
     * client can reach: the delegates are not registered standalone.
     */
    String describe();

    /**
     * The edit for this file, or null when there is nothing to change here.
     *
     * @throws Exception only when the file could not be analysed at all — never as a way
     *                   of saying the rule does not apply
     */
    TextEdit edit(CompilationUnit ast) throws Exception;
}
