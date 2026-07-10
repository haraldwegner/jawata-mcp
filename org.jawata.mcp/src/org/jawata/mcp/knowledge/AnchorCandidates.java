package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts symbol-anchor CANDIDATES from an entry's markdown text (Sprint 21e, item A).
 *
 * Pure parsing — no JDT. A candidate is a backticked code span that looks like a Java
 * type reference, optionally with one member. Whether a candidate actually ANCHORS is
 * decided later by resolution: only tokens that resolve uniquely to a project-source
 * type are eligible, so a mis-classified span here merely fails resolution — it can
 * never mis-anchor. Prose (non-backticked) mentions are deliberately ignored: too noisy.
 *
 * Notation normalization (the sprint's plan-pinned decision): member notation is
 * canonically {@code #} ({@code pkg.Type#member}); {@code .}-notation member mentions
 * ({@code Type.member}) collapse into the same candidate before counting, so
 * {@code SlotManager.freeSlot} and {@code SlotManager#freeSlot} are ONE candidate with
 * two mentions.
 */
public final class AnchorCandidates {

    /**
     * One distinct type token with its aggregated mention count and the distinct
     * members seen for it, both in first-appearance order.
     */
    public record Candidate(String typeToken, int mentions, List<String> members) {}

    private static final Pattern CODE_SPAN = Pattern.compile("`([^`\n]+)`");
    private static final Pattern TRAILING_ARGS = Pattern.compile("\\([^()]*\\)$");
    /** identifier(.identifier)* with at most one trailing #identifier — anything else is not a symbol. */
    private static final Pattern TOKEN = Pattern.compile(
            "[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*(?:#[A-Za-z_$][A-Za-z0-9_$]*)?");
    private static final int MAX_SPAN_LENGTH = 256;

    private AnchorCandidates() {
    }

    /** Parses {@code text} and returns the distinct type candidates in first-appearance order. */
    public static List<Candidate> extract(String text) {
        Map<String, Agg> byType = new LinkedHashMap<>();
        boolean inFence = false;
        for (String line : text.split("\n", -1)) {
            if (line.strip().startsWith("```")) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            Matcher m = CODE_SPAN.matcher(line);
            while (m.find()) {
                Optional<Parts> parts = classify(m.group(1).strip());
                if (parts.isPresent()) {
                    Agg agg = byType.computeIfAbsent(parts.get().typeToken(), k -> new Agg());
                    agg.mentions++;
                    String member = parts.get().member();
                    if (member != null && !agg.members.contains(member)) {
                        agg.members.add(member);
                    }
                }
            }
        }
        List<Candidate> out = new ArrayList<>(byType.size());
        byType.forEach((type, agg) -> out.add(new Candidate(type, agg.mentions, List.copyOf(agg.members))));
        return out;
    }

    private record Parts(String typeToken, String member) {}

    private static Optional<Parts> classify(String span) {
        if (span.isEmpty() || span.length() > MAX_SPAN_LENGTH) {
            return Optional.empty();
        }
        String token = TRAILING_ARGS.matcher(span).replaceFirst("");
        if (!TOKEN.matcher(token).matches()) {
            return Optional.empty();
        }
        String typePart;
        String member = null;
        int hash = token.indexOf('#');
        if (hash >= 0) {
            typePart = token.substring(0, hash);
            member = token.substring(hash + 1);
        } else {
            int lastDot = token.lastIndexOf('.');
            String last = token.substring(lastDot + 1);
            if (lastDot >= 0 && Character.isLowerCase(last.charAt(0))) {
                member = last;
                typePart = token.substring(0, lastDot);
            } else {
                typePart = token;
            }
        }
        String simpleName = typePart.substring(typePart.lastIndexOf('.') + 1);
        if (!Character.isUpperCase(simpleName.charAt(0))) {
            return Optional.empty();
        }
        // SCREAMING_SNAKE spans are constant references by convention, never types
        if (simpleName.indexOf('_') >= 0 && simpleName.equals(simpleName.toUpperCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(new Parts(typePart, member));
    }

    private static final class Agg {
        int mentions;
        final List<String> members = new ArrayList<>();
    }
}
