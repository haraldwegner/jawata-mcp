package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.jawata.mcp.engine.EngineEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 28g Stage 24 (D45) — which accepted rules bind a seat, and which rules nobody asks for.
 *
 * <p>A rule names who it binds in its {@code operation} label: {@code seat:<name>} for one seat, an
 * activity such as {@code release} for whoever carries that activity, or nothing for every seat.
 * A hired seat's brief carries the accepted rules that bind it, read from the store at the moment
 * of the hire, so a rule recorded after the build reaches the next hire with no code change.</p>
 *
 * <p>THE RISK THIS CLASS EXISTS TO NAME is an exact match on a label: a typo in a label binds no
 * seat and leaves nothing behind to notice. {@link #unaskedLabels} lists the labels that no seat and
 * no activity asks for, so a dead rule is reported instead of silent.</p>
 */
public final class SeatRules {

    private SeatRules() {
    }

    /**
     * The accepted rules that bind {@code seat} when it also carries {@code activities}: those
     * anchored {@code seat:<seat>}, those anchored to one of the activities, and those anchored to
     * no seat.
     *
     * @param store the store the rules live in
     * @param seat the seat's name, without the {@code seat:} prefix
     * @param activities the activity labels this seat carries, such as {@code release}
     * @return each binding rule's text, in the order the store holds them
     */
    public static List<String> bindingOn(ExperienceStore store, String seat, Collection<String> activities) {
        return bindingOn(store, seat, activities, limit());
    }

    /**
     * The limit on how many rules one brief carries, from {@code -Djawata.rules.limit}. UNSET MEANS
     * UNLIMITED, which is the default: every accepted rule that binds the seat is injected. A limit is
     * a setting he can turn on, and a value that is not a positive number leaves it off rather than
     * silently dropping rules.
     *
     * @return the most rules a brief carries, or 0 for no limit
     */
    public static int limit() {
        String raw = EngineEnvironment.settings().get(LIMIT_PROPERTY).orElse(null);
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim()));
        } catch (NumberFormatException e) {
            log.warn("{} is not a number ({}) — injecting every binding rule", LIMIT_PROPERTY, raw);
            return 0;
        }
    }

    /**
     * {@link #bindingOn(ExperienceStore, String, Collection)} with the limit given, not read.
     *
     * @param limit no more than this many rules; 0 or less means every binding rule
     */
    public static List<String> bindingOn(ExperienceStore store, String seat, Collection<String> activities,
                                         int limit) {
        List<String> all = binding(store, seat, activities);
        return limit > 0 && all.size() > limit ? List.copyOf(all.subList(0, limit)) : all;
    }

    private static final String LIMIT_PROPERTY = "jawata.rules.limit";

    private static List<String> binding(ExperienceStore store, String seat, Collection<String> activities) {
        String own = SEAT_PREFIX + seat.strip().toLowerCase(Locale.ROOT);
        Set<String> carried = new HashSet<>();
        carried.add(own);
        for (String activity : activities) {
            carried.add(activity.strip().toLowerCase(Locale.ROOT));
        }
        List<String> texts = new ArrayList<>();
        for (StoredEntry rule : acceptedRules(store)) {
            String label = rule.operation();
            if (label == null || label.isBlank() || carried.contains(label.strip().toLowerCase(Locale.ROOT))) {
                texts.add(textOf(rule));
            }
        }
        return texts;
    }

    /**
     * {@link #bindingOn(ExperienceStore, String, Collection)} for the hire path, which must not
     * stop a hire because a rule could not be read: a resident with no store, a store that answers
     * nothing, or one that fails, all yield no rules and say so in the log. A rule is guidance; a
     * hire that refused to start for want of it would turn the lane into a gate.
     *
     * @param source where the store is read from; null, or answering null, means none
     */
    public static List<String> bindingOn(Supplier<ExperienceStore> source, String seat, Collection<String> activities) {
        ExperienceStore store = source == null ? null : source.get();
        if (store == null) {
            return List.of();
        }
        try {
            return bindingOn(store, seat, activities);
        } catch (RuntimeException e) {
            log.warn("the rules that bind seat {} could not be read; the hire goes ahead without them: {}",
                seat, e.toString());
            return List.of();
        }
    }

    private static final Logger log = LoggerFactory.getLogger(SeatRules.class);

    /**
     * The operation labels of accepted rules that no seat and no activity asks for, each with the
     * ids of the rules carrying it.
     *
     * @param store the store the rules live in
     * @param asked every label some seat or activity asks for
     * @return label to rule ids; empty when every labelled rule is asked for
     */
    public static Map<String, List<String>> unaskedLabels(ExperienceStore store, Collection<String> asked) {
        Set<String> known = new HashSet<>();
        for (String label : asked) {
            known.add(label.strip().toLowerCase(Locale.ROOT));
        }
        Map<String, List<String>> unasked = new LinkedHashMap<>();
        for (StoredEntry rule : acceptedRules(store)) {
            String label = rule.operation();
            if (label != null && !label.isBlank() && !known.contains(label.strip().toLowerCase(Locale.ROOT))) {
                unasked.computeIfAbsent(label.strip(), k -> new ArrayList<>()).add(rule.id());
            }
        }
        return unasked;
    }

    /** The prefix of a label that names one seat. */
    private static final String SEAT_PREFIX = "seat:";

    /** The rules that are live and accepted — what the lane serves, asked of the lane itself. */
    private static List<StoredEntry> acceptedRules(ExperienceStore store) {
        List<StoredEntry> rules = new ArrayList<>();
        for (StoredEntry e : store.all()) {
            if (KnowledgeLane.RULE_TYPE.equals(e.type()) && e.isLive()
                    && e.answersFor(store.provenanceWorkspaceId())) {
                rules.add(e);
            }
        }
        return rules;
    }

    /** What a brief carries for one rule: the instruction, and when it applies if the rule says. */
    private static String textOf(StoredEntry rule) {
        String when = rule.facets().situation();
        return when == null || when.isBlank()
            ? rule.summary()
            : rule.summary() + " (applies " + when.strip() + ")";
    }
}
