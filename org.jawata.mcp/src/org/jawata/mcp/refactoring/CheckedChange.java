package org.jawata.mcp.refactoring;

import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * The outcome of a {@link RefactoringEngine#propose} call (Sprint 25, D1):
 * either an applicable {@link Change} — the engine ran the transformation's
 * preconditions and they permit it — or a REFUSAL carrying the precondition
 * reasons.
 *
 * <p>MCP-agnostic by design: the engine layer knows nothing about
 * {@code ToolResponse}. The calling tool maps a {@code CheckedChange} into its
 * own response contract (e.g.
 * {@code AbstractApplyingRefactoringTool.Preparation}). A refused change has a
 * {@code null} {@link #change()} and a {@link #status()} whose severity is
 * ERROR or FATAL; an applicable one has a non-null change and a status that is
 * at worst a WARNING — surfaced to the caller, never fatal.</p>
 */
public record CheckedChange(Change change, RefactoringStatus status) {

    /** The preconditions permit the transformation (WARNING advisories possible). */
    public static CheckedChange applicable(Change change, RefactoringStatus status) {
        return new CheckedChange(change, status);
    }

    /** The preconditions refuse the transformation; {@code status} carries why. */
    public static CheckedChange refused(RefactoringStatus status) {
        return new CheckedChange(null, status);
    }

    public boolean isRefused() {
        return change == null;
    }

    public boolean hasWarnings() {
        return status != null && status.hasWarning();
    }

    /**
     * The precondition entries joined into one human-readable line — the
     * refusal reason when {@link #isRefused()}, or the advisory warnings on an
     * applicable change. Empty when the status is clean.
     */
    public String messages() {
        if (status == null) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (RefactoringStatusEntry entry : status.getEntries()) {
            out.add(entry.getMessage());
        }
        return String.join(" | ", out);
    }
}
