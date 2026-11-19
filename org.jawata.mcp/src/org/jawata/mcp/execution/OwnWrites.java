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
    static final String RULE = "Do not edit code, but hire; only documents, data and pictures (.md, .csv, .png and the like) may be written.";

    /**
     * What the main seat and a lead may write with their own hands: documents, data and pictures. A file is read as one by its ending, since the
     * engine sees only paths here; everything else (source, build and configuration files) is code, and code is made by hiring (his word,
     * 2026-11-19: "there could be other changes, pictures, csv, websites").
     */
    private static final java.util.Set<String> DOCUMENT_ENDINGS = java.util.Set.of("md", "markdown", "txt", "rst", "adoc", "csv", "tsv",
        "png", "jpg", "jpeg", "gif", "webp", "svg", "pdf", "html", "htm", "css");

    private OwnWrites() {
    }

    /** The kinds among them that are no text: a picture or a binary document, which the files tool, whose content is a text, cannot write. */
    private static final java.util.Set<String> BINARY_ENDINGS = java.util.Set.of("png", "jpg", "jpeg", "gif", "webp", "pdf");

    /** Whether a path is a document, a data file or a picture, which the main seat and a lead may write, and not code. */
    public static boolean isDocument(String path) {
        return DOCUMENT_ENDINGS.contains(endingOf(path));
    }

    /** Whether a path is a document of the kinds above that is a text: the ones the files tool writes and edits. */
    public static boolean isTextDocument(String path) {
        return isDocument(path) && !BINARY_ENDINGS.contains(endingOf(path));
    }

    /** The ending of a file's name in lower case, or empty when it has none (a dot that starts the name is no ending). */
    private static String endingOf(String path) {
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return dot > slash + 1 ? path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** One flag, against no instruction, for each write the brain's own editor made to a file that is not a document. */
    public static List<Observer.Flag> flags(List<ToolCall> made) {
        List<Observer.Flag> flags = new ArrayList<>();
        for (int i = 0; i < made.size(); i++) {
            ToolCall call = made.get(i);
            TestFirst.writtenFile(call).filter(path -> !isDocument(path)).ifPresent(path -> flags.add(new Observer.Flag(0,
                "a code-changing call of the brain's own editor on a file that is not a document: " + call.bareName() + " on " + path
                    + ". A lead writes only documents, data and pictures and hires the rest.")));
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
