package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;

/**
 * The rule for the main seat and for a lead: they edit no code, only documents (Sprint 28h Stage 27 (z2)). A brain that runs in its normal mode
 * keeps an editor of its own, so the engine cannot take it away; it reads the brain's stream and says, afterwards, when a lead or the main seat
 * wrote a file that is not a document with that editor. Nothing is put back: a revert could not tell the brain's edit from the person's own edit
 * in the same folders.
 */
public final class OwnWrites {

    /** The sentence a lead is told and corrected with; the lead's seat file carries the same words with the seat it hires named. */
    static final String RULE = "Do not edit code, but hire; only .md file edits are allowed.";

    private OwnWrites() {
    }

    /** One flag, against no instruction, for each write the brain's own editor made to a file that is not a document. */
    public static List<Observer.Flag> flags(List<ToolCall> made) {
        List<Observer.Flag> flags = new ArrayList<>();
        for (int i = 0; i < made.size(); i++) {
            ToolCall call = made.get(i);
            TestFirst.writtenFile(call).filter(path -> !path.endsWith(".md")).ifPresent(path -> flags.add(new Observer.Flag(0,
                "a code-changing call of the brain's own editor on a file that is not a document: " + call.bareName() + " on " + path
                    + ". A lead edits only .md files and hires the rest.")));
        }
        return List.copyOf(flags);
    }

    /** What the main seat is told, and shown to the person, about these flags; empty when there are none. */
    public static String correction(List<Observer.Flag> flags) {
        if (flags.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder("[jawata] You wrote with your own editor, and you are not to:");
        for (Observer.Flag flag : flags) {
            text.append("\n- ").append(flag.why());
        }
        return text.append("\n").append(RULE).toString();
    }
}
