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

    /** The files an agent is told its rules by, and the folders they live in: a document in any of them changes what a seat or a hire may do. */
    private static final java.util.Set<String> INSTRUCTION_FILES = java.util.Set.of("claude.md", "claude.local.md", "agents.md", "agents.override.md",
        "gemini.md", "skill.md", "copilot-instructions.md", ".cursorrules", ".windsurfrules", ".clinerules");

    /**
     * The endings of the instruction files Copilot keeps under .github, its custom agents ({@code .agent.md}) among them; the folder's other
     * documents (CONTRIBUTING.md) are ordinary ones.
     */
    private static final List<String> INSTRUCTION_ENDINGS = List.of(".instructions.md", ".prompt.md", ".chatmode.md", ".agent.md");

    /** The folders whose whole content configures an agent. */
    private static final java.util.Set<String> INSTRUCTION_FOLDERS = java.util.Set.of(".claude", ".cursor", ".codex", ".clinerules", ".windsurf",
        ".continue", ".gemini", ".kiro");

    /** The folder that holds jawata's seat files: a document directly in it is a seat, one in a project below it is not. */
    private static final String SEATS_FOLDER = "seats";

    /**
     * Whether this path is one an agent takes its instructions from, by its file name or any folder on its way. Such a file is not a document a
     * lead may write whatever its ending: a lead that could change it could widen what a hire may do (C27-24: a lead's own editor could write
     * CLAUDE.md, because it ends in .md). It is judged as spelled and as the place it really is, so a link named notes.md that leads into a
     * seats folder is a seat (the post-C26 audit, row 28h-C26-27).
     */
    public static boolean tellsAnAgentWhatItMayDo(String path) {
        return instructsByName(normalised(path)) || realPathOf(path).map(OwnWrites::instructsByName).orElse(false);
    }

    /** The path with its {@code .} and {@code ..} steps taken, so {@code seats/zz/../pm.md} is judged as the seat it names; as given when it cannot be read. */
    private static String normalised(String path) {
        try {
            return java.nio.file.Path.of(path).normalize().toString();
        } catch (java.nio.file.InvalidPathException unreadable) {
            return path;
        }
    }

    /**
     * Where an absolute path really leads, asked of the one resolver the access policy judges with, so what is written is what was judged
     * (28h z70). None for a relative path, which names no place until a folder is given for it, so it is not resolved against the engine's own
     * folder; none when the path cannot be read as one.
     */
    private static java.util.Optional<String> realPathOf(String path) {
        try {
            java.nio.file.Path spelled = java.nio.file.Path.of(path);
            if (!spelled.isAbsolute()) {
                return java.util.Optional.empty();
            }
            return java.util.Optional.of(org.jawata.mcp.access.PlaceNames.realOf(spelled).toString());
        } catch (RuntimeException unreadable) {
            // a path that cannot be followed is judged as spelled
            return java.util.Optional.empty();
        }
    }

    /**
     * The place a write to this path lands, as it was judged: its real place when that can be read, else the path with its steps taken. The
     * files tool writes there and nowhere else, so a link the check followed is not followed a second time to somewhere it did not judge.
     */
    public static java.nio.file.Path placeOf(String path) {
        return java.nio.file.Path.of(realPathOf(path).orElse(normalised(path)));
    }

    private static boolean instructsByName(String path) {
        String[] parts = path.replace('\\', '/').split("/");
        if (parts.length == 0) {
            return false;
        }
        String name = parts[parts.length - 1].toLowerCase(java.util.Locale.ROOT);
        if (INSTRUCTION_FILES.contains(name) || INSTRUCTION_ENDINGS.stream().anyMatch(name::endsWith)) {
            return true;
        }
        if (parts.length >= 2 && SEATS_FOLDER.equalsIgnoreCase(parts[parts.length - 2])) {
            return true;
        }
        for (String part : parts) {
            if (INSTRUCTION_FOLDERS.contains(part.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a path is a document, a data file or a picture, which the main seat and a lead may write, and not code: judged as spelled and as
     * the place it really is, so a link named notes.md that leads to code is code.
     */
    public static boolean isDocument(String path) {
        return DOCUMENT_ENDINGS.contains(endingOf(path)) && DOCUMENT_ENDINGS.contains(endingOf(realPathOf(path).orElse(path)))
            && !tellsAnAgentWhatItMayDo(path);
    }

    /** Whether a path is a document of the kinds above that is a text: the ones the files tool writes and edits. */
    public static boolean isTextDocument(String path) {
        return isDocument(path) && !BINARY_ENDINGS.contains(endingOf(path)) && !BINARY_ENDINGS.contains(endingOf(realPathOf(path).orElse(path)));
    }

    /** The ending of a file's name in lower case, or empty when it has none (a dot that starts the name is no ending). */
    private static String endingOf(String path) {
        int dot = path.lastIndexOf('.');
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return dot > slash + 1 ? path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }

    /** The files these calls name as written by the brain's own editor. */
    public static java.util.Set<String> pathsWritten(List<ToolCall> made) {
        return made.stream().map(TestFirst::writtenFile).flatMap(java.util.Optional::stream).collect(java.util.stream.Collectors.toSet());
    }

    /** One flag, against no instruction, for each write the brain's own editor made to a file that is not a document. */
    public static List<Observer.Flag> flags(List<ToolCall> made) {
        List<Observer.Flag> flags = new ArrayList<>();
        for (int i = 0; i < made.size(); i++) {
            ToolCall call = made.get(i);
            TestFirst.writtenFile(call).filter(path -> !isDocument(path)).ifPresent(path -> flags.add(new Observer.Flag(0,
                "a code-changing call of the brain's own editor on a file that is not a document: " + call.bareName() + " on " + path
                    + (tellsAnAgentWhatItMayDo(path)
                        ? " (it tells an agent what it may do: an instruction file, a seat, or a note under an agent's own folder such as .claude, which a lead does not write; "
                            + "the person changes it, or a seat that changes code does)"
                        : " (it is code, or a build or configuration file)")
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
