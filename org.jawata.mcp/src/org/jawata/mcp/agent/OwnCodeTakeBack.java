package org.jawata.mcp.agent;

import java.util.List;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.execution.OwnWrites;
import org.jawata.mcp.execution.TreeAttribution;

/**
 * What the main seat and a lead may not do, put right (Sprint 28h Stage 27, his word 2026-11-19: "This should not be possible that it edits
 * code"): the code a brain wrote with its own hands is read from the folder, by any way it was written (an editor, a shell, a patch), and put
 * back. A document, a data file or a picture is left, since those are the work of these seats. One reading of the tree serves the main seat
 * after each turn and a lead at the end of its hire, so what "code" is, and what is put back, is said in one place.
 */
public final class OwnCodeTakeBack {

    private OwnCodeTakeBack() {
    }

    /**
     * Puts back the code the brain wrote that was clean before it, and says what was put back and what was kept; empty when the brain wrote no
     * code. A file that already had changes of the person's is kept and named, not touched ({@link TakeBack}).
     *
     * @param host   the processes git is run through
     * @param folder the folder the brain ran in
     * @param seen   what the folder shows changed since before the brain ran
     */
    public static String of(HostProcesses host, String folder, TreeAttribution.Attribution seen) {
        List<TreeAttribution.FileChange> code = seen.changes()
            .map(changes -> changes.shown().stream().filter(change -> !OwnWrites.isDocument(change.path())).toList()).orElse(List.of());
        if (code.isEmpty()) {
            return "";
        }
        TakeBack.Result result = TakeBack.of(host, folder, code, List.of());
        StringBuilder said = new StringBuilder("[jawata] You wrote code, and you edit none. ");
        said.append(result.putBack().isEmpty() ? "Nothing could be put back." : "Put back: " + String.join(", ", result.putBack()) + ".");
        if (!result.kept().isEmpty()) {
            said.append(" Left as it was: ").append(String.join("; ", result.kept())).append('.');
        }
        return said.append(" Hire a developer for code.").toString();
    }
}
