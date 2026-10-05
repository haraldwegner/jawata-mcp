package com.example;

/**
 * Sprint 28g S3 / D7c — a {@code defaultValue} is spliced in as TEXT, so it is inserted at
 * sites where the name it uses does not exist.
 *
 * The field report: adding {@code slotCount} with default {@code slots.length} to
 * {@code pipeline.SlotManager}'s constructor inserted {@code slots.length} at a site whose
 * local is {@code testSlots} and where no {@code slots} exists. Separately, adding
 * {@code slotManager} with default {@code slotManager} landed at sites whose local is
 * {@code sm}. Both compile-fail. And a third kind no compile gate can see: a default of
 * {@code 25_000} inserted at sites built with {@code 10_000} and {@code 100_000} — a
 * semantic mismatch a single text default cannot know about.
 *
 * The two call sites below hold the same value under DIFFERENT names on purpose:
 * {@code roster} and {@code teamRoster}. A default naming either one can resolve at only one
 * of them, which is the defect stated as a fixture rather than as prose.
 *
 * DO NOT rename those locals and do not make them agree — the disagreement IS the fixture.
 */
public class DefaultValueTargets {

    private final String[] names;
    private final int budget;

    public DefaultValueTargets(String[] names, int budget) {
        this.names = names;
        this.budget = budget;
    }

    public int size() {
        return names.length;
    }

    public int budget() {
        return budget;
    }

    /** The local is {@code roster}. */
    public static DefaultValueTargets siteWithRoster() {
        String[] roster = { "a", "b" };
        return new DefaultValueTargets(roster, 10_000);
    }

    /** The local is {@code teamRoster} — no {@code roster} exists in this scope. */
    public static DefaultValueTargets siteWithTeamRoster() {
        String[] teamRoster = { "c", "d", "e" };
        return new DefaultValueTargets(teamRoster, 100_000);
    }
}
