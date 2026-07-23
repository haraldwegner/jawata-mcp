package org.jawata.mcp.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Sprint 27a D10 — admission ROUTING for the experience store's write path.
 *
 * <p>The store holds more than one kind of high-value knowledge: EXPERIENCE
 * (project/outcome/business-domain lessons, in prose), TOOL OUTPUT (structured
 * results whose natural content is paths, symbols and flags — home: the tool
 * lane), and future pattern knowledge. A file path is payload in a tool-output
 * record and poison in a symptoms field: embedding the symptoms as they stood
 * regressed the frozen calibration gate 11→9 of 12 (dossier-27a, C4). This
 * policy refuses content stored AS THE WRONG KIND — with a redirect saying
 * where it belongs — so the correction happens at write time, by the agent
 * that still has the context.
 *
 * <p>PROVENANCE: every pattern here mirrors the committed derivation script
 * {@code org.jawata.mcp.tests/test-resources/embed-goldens/derive_admission.py},
 * run 2026-07-23 against the live corpus export (sha256 {@code 96b128af…},
 * 2,080 entries, 17,077 symptom items — per-shape counts in dossier-27a).
 * Derived from observed misplaced content, never guessed.
 *
 * <p>Pure: no store, no I/O, no rendering. The gate guards NEW writes only —
 * import/restore round-trips are untouched by design (what is already stored
 * must always round-trip).
 */
public final class AdmissionPolicy {

    /** The observed content shapes. The first six are misplaced OUTSIDE the
     *  tool lane; {@link #WORD} and {@link #PROSE} are legitimate symptom
     *  content ("how it looked", in words). */
    public enum Shape { PATH, FLAG, HEADING, CODE, ID, TAG, WORD, PROSE }

    // Mirrors derive_admission.py PATH_RE (observed: 1,218 items).
    private static final Pattern PATH = Pattern.compile(
        "(^~|^\\.{0,2}/|[\\\\/].*[\\\\/]|^\\*\\*/"
        + "|\\.(md|java|rs|py|json|xml|yml|yaml|sh|log|jar|toml|txt|html"
        + "|properties|parquet|class|db|gz)$)");
    // Mirrors CODE_RE (observed: 5,508 items) — backticks, calls, camelCase,
    // dotted identifiers, PascalCase compounds.
    private static final Pattern CODE = Pattern.compile(
        "(`[^`]+`"
        + "|\\w+\\([^)]*\\)"
        + "|\\b[a-z][a-zA-Z0-9]*[A-Z]\\w*"
        + "|\\b[A-Za-z_][\\w$]*\\.[A-Za-z_][\\w$(]"
        + "|\\b[A-Z][a-z]+[A-Z]\\w*)");
    private static final Pattern HEX_ID = Pattern.compile("^[0-9a-f]{7,40}$");
    private static final Pattern NUMERIC_ID = Pattern.compile("^[\\d.,%\"'\\s–-]+$");
    // A heading-shaped SUMMARY: '#'-prefixed, or a short fragment ending ':'
    // (observed: 36 of 2,080 summaries).
    private static final Pattern HEADING_SUMMARY = Pattern.compile("(^#)|(^.{0,60}:\\s*$)");

    private AdmissionPolicy() {
    }

    /** Classify one symptom item exactly as the derivation script does. */
    public static Shape classify(String item) {
        String t = item == null ? "" : item.strip();
        if (t.isEmpty()) {
            return Shape.WORD;
        }
        if (PATH.matcher(t).find()) {
            return Shape.PATH;
        }
        if (t.startsWith("-")) {
            return Shape.FLAG;
        }
        if (t.startsWith("#") || t.endsWith(":")) {
            return Shape.HEADING;
        }
        if (CODE.matcher(t).find()) {
            return Shape.CODE;
        }
        if (HEX_ID.matcher(t).matches() || NUMERIC_ID.matcher(t).matches()) {
            return Shape.ID;
        }
        if (!t.contains(" ")) {
            return (t.contains("-") || t.contains("_")) ? Shape.TAG : Shape.WORD;
        }
        return Shape.PROSE;
    }

    /** True for the shapes that do not belong in experience prose fields. */
    public static boolean misplaced(Shape shape) {
        return shape != Shape.WORD && shape != Shape.PROSE;
    }

    /** A refusal names the field, the offending value and its shape; the
     *  message carries the rule, the redirect and a concrete rephrase. */
    public record Refusal(String field, String offending, Shape shape, String message) {
    }

    /**
     * Check a NEW experience record's prose fields. Empty result = admitted.
     * The summary is checked for heading shape only (a summary legitimately
     * names symbols); each symptom item is checked against all shapes.
     */
    public static Optional<Refusal> check(String summary, List<String> symptoms) {
        String s = summary == null ? "" : summary.strip();
        if (!s.isEmpty() && HEADING_SUMMARY.matcher(s).find()) {
            return Optional.of(new Refusal("summary", summary, Shape.HEADING,
                "summary '" + summary + "' is a heading, not an experience."
                + " RULE: the summary is one judgeable sentence of what was learned."
                + " REPHRASE: state the lesson the heading introduces, e.g. \""
                + s.replaceFirst("^#+\\s*", "").replaceFirst(":\\s*$", "")
                + " — <what happened and what it means>\"."));
        }
        if (symptoms != null) {
            int misplacedCount = 0;
            Refusal first = null;
            for (int i = 0; i < symptoms.size(); i++) {
                String item = symptoms.get(i);
                Shape shape = classify(item);
                if (misplaced(shape)) {
                    misplacedCount++;
                    if (first == null) {
                        first = new Refusal("symptoms", item, shape, "");
                    }
                }
            }
            if (first != null) {
                String tail = misplacedCount > 1
                    ? " (and " + (misplacedCount - 1) + " more misplaced item"
                        + (misplacedCount > 2 ? "s" : "") + " in symptoms)"
                    : "";
                return Optional.of(new Refusal("symptoms", first.offending(), first.shape(),
                    "symptom '" + first.offending() + "' is " + describe(first.shape())
                    + ", not an observation" + tail + "."
                    + " RULE: symptoms are how the problem LOOKED, in words."
                    + " " + redirect(first.shape())
                    + " REPHRASE: put '" + first.offending() + "' in 'details' and write"
                    + " the symptom as what you observed, e.g. \"" + example(first.shape())
                    + "\"."));
            }
        }
        return Optional.empty();
    }

    private static String describe(Shape shape) {
        return switch (shape) {
            case PATH -> "a file path";
            case FLAG -> "a command-line flag";
            case HEADING -> "a section heading";
            case CODE -> "a code symbol";
            case ID -> "a bare identifier";
            case TAG -> "a tag";
            default -> "not prose";
        };
    }

    private static String redirect(Shape shape) {
        return switch (shape) {
            case CODE -> "WHERE IT BELONGS: a symbol goes in 'symbol'/'symbols'"
                + " (the anchor) or in 'details'; tool results belong in the tool"
                + " lane, recorded by the tools themselves.";
            case PATH, FLAG, ID -> "WHERE IT BELONGS: artifacts (paths, flags,"
                + " ids) go in 'details'; tool results belong in the tool lane,"
                + " recorded by the tools themselves.";
            default -> "WHERE IT BELONGS: put it in 'details'.";
        };
    }

    private static String example(Shape shape) {
        return switch (shape) {
            case PATH -> "the import failed on every file under that directory";
            case FLAG -> "the JVM refused to start when the flag was present";
            case HEADING -> "the section's steps no longer matched the shipped behaviour";
            case CODE -> "the method returned an empty result although the data was present";
            case ID -> "the same id appeared twice in the report";
            case TAG -> "the tagged runs all failed the same way";
            default -> "what you observed, in a sentence";
        };
    }
}
