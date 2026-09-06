package org.jawata.mcp.tools.smell;

import org.jawata.mcp.domain.DetectorCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY ONE OF FOWLER'S TWENTY-FOUR IS A FIRING DETECTOR OR A WRITTEN DECLINATION —
 * NO THIRD STATE.
 *
 * <p>That sentence is Sprint 28d-rescue's C8 exit clause, and until a C8 audit read the
 * repository nothing enforced it. TWO smells were in the third state: <i>Mysterious Name</i>
 * and <i>Comments</i> were not detected, not declined, and not mentioned anywhere — a search
 * of the whole tree for either name returned nothing. <b>An undetected smell and a forgotten
 * one read identically from outside</b>, which is exactly the state the clause exists to
 * forbid, and prose alone would have closed its letter while leaving the next omission just
 * as invisible.</p>
 *
 * <h2>Why the twenty-four are written out here, and why that is NOT the defect this sprint
 * keeps finding</h2>
 *
 * <p>This sprint has found six hand-written lists that had gone stale beside the thing they
 * described, and the standing cure is to DERIVE them. <b>Deriving this one would destroy
 * it.</b> A catalogue of smells read off our own registered kinds plus our own declination
 * map would agree with them by construction — it could never fail, which is the tautology
 * shape an architect watch flagged on two other counts in this same checkpoint.</p>
 *
 * <p>The twenty-four below are an EXTERNAL, HISTORICAL fact: chapter 3 of <i>Refactoring</i>,
 * 2nd edition, fixed in print in 2018. No object in this codebase owns it, nothing here can
 * change it, and that is precisely the case the ownership rule exempts — the same argument
 * {@code EveryDeclaringKindRendersItsCureTest.WERE_SILENT} makes for itself and, on
 * measurement, correctly.</p>
 *
 * <h2>What each name maps to</h2>
 *
 * <p>Fowler's names and our {@code kind} names differ where ours predate the 2nd edition or
 * where we chose the older title — {@code lazy_class} for Lazy Element,
 * {@code inappropriate_intimacy} for Insider Trading, {@code switch_statements} for Repeated
 * Switches, {@code god_class} for Large Class, {@code long_method} for Long Function. The
 * mapping is stated per row rather than inferred, so a rename on our side fails HERE rather
 * than quietly dropping a smell out of the partition.</p>
 */
class EverySmellIsDetectedOrDeclinedTest {

    /**
     * Fowler, <i>Refactoring</i> 2nd ed., chapter 3 — the twenty-four, in the book's order,
     * each against the {@code kind} that reports it or {@code null} where we decline.
     *
     * <p>{@code null} is a CLAIM, not a gap: it asserts the name appears in
     * {@link FowlerDetectors#DECLINED} with a reason, which is checked below. A smell added
     * here with a kind that does not exist fails; a smell with {@code null} and no
     * declination fails; a smell in both fails.</p>
     */
    private static final Map<String, String> FOWLER_CHAPTER_3 = chapterThree();

    private static Map<String, String> chapterThree() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("Mysterious Name", null);                             // declined
        m.put("Duplicated Code", "DUPLICATED_CODE_IS_ITS_OWN_TOOL");
        m.put("Long Function", "long_method");
        m.put("Long Parameter List", "long_parameter_list");
        m.put("Global Data", "global_data");
        m.put("Mutable Data", "mutable_data");
        m.put("Divergent Change", "divergent_change");
        m.put("Shotgun Surgery", "shotgun_surgery");
        m.put("Feature Envy", "feature_envy");
        m.put("Data Clumps", "data_clumps");
        m.put("Primitive Obsession", "primitive_obsession");
        m.put("Repeated Switches", "switch_statements");
        m.put("Loops", "loops");
        m.put("Lazy Element", "lazy_class");
        m.put("Speculative Generality", "speculative_generality");
        m.put("Temporary Field", "temporary_field");
        m.put("Message Chains", "message_chains");
        m.put("Middle Man", "middle_man");
        m.put("Insider Trading", "inappropriate_intimacy");
        m.put("Large Class", "god_class");
        m.put("Alternative Classes with Different Interfaces", "alternative_classes");
        m.put("Data Class", "data_class");
        m.put("Refused Bequest", "refused_bequest");
        m.put("Comments", null);                                    // declined
        return java.util.Collections.unmodifiableMap(m);
    }

    /**
     * Duplicated Code ships as {@code find_duplicate_code}, a TOOL rather than a
     * {@code find_quality_issue} kind, because its answer is clone GROUPS — a set of
     * locations that are the same — and the findings shape carries one location each. It is
     * detected, so it is not declined; it is simply not in the catalog this test reads.
     */
    private static final String OWN_TOOL = "DUPLICATED_CODE_IS_ITS_OWN_TOOL";

    private static List<String> registeredKinds() {
        return FowlerDetectors.registerInto(new DetectorCatalog(), () -> null).kinds();
    }

    @Test
    @DisplayName("all 24 of Fowler's smells are detected or declined, and none is both")
    void theTwentyFourArePartitioned() {
        List<String> kinds = registeredKinds();
        assertFalse(kinds.isEmpty(),
            "PROOF OF LIFE: with an empty catalog every 'is it registered' question below"
                + " answers no, and the whole partition would be decided by the declination"
                + " map alone");

        List<String> thirdState = new ArrayList<>();
        List<String> inBoth = new ArrayList<>();
        for (Map.Entry<String, String> smell : FOWLER_CHAPTER_3.entrySet()) {
            String name = smell.getKey();
            String kind = smell.getValue();
            boolean declined = FowlerDetectors.DECLINED.containsKey(name);
            boolean detected = kind != null && (OWN_TOOL.equals(kind) || kinds.contains(kind));

            if (!declined && !detected) {
                thirdState.add(name + (kind == null
                    ? " — no kind and no declination"
                    : " — claims kind '" + kind + "', which nothing registers"));
            }
            if (declined && detected) {
                inBoth.add(name + " — declined AND registered as '" + kind + "'");
            }
        }

        assertTrue(thirdState.isEmpty(),
            "THE THIRD STATE, which is what C8 forbids. Each of these is neither reported by"
                + " a detector nor written down as a deliberate declination, and from outside"
                + " that is indistinguishable from having forgotten it: " + thirdState);
        assertTrue(inBoth.isEmpty(),
            "and a smell cannot be both — a declination standing beside a working detector is"
                + " a stale explanation a reader would act on: " + inBoth);
    }

    @Test
    @DisplayName("a declined smell is absent from the shipped kind list, and says why")
    void aDeclinedSmellShipsNoKindAndCarriesItsReason() {
        List<String> kinds = registeredKinds();
        for (Map.Entry<String, String> declined : FowlerDetectors.DECLINED.entrySet()) {
            String name = declined.getKey();
            String reason = declined.getValue();

            assertTrue(FOWLER_CHAPTER_3.containsKey(name),
                "a declination must be ABOUT one of the twenty-four; '" + name + "' is not"
                    + " one of Fowler's names, so nothing above would ever check it");
            assertEquals(null, FOWLER_CHAPTER_3.get(name),
                "'" + name + "' is declined, so the table above must claim no kind for it");

            // The reason has to survive being read. A blank or one-word entry would satisfy
            // "a declination exists" while telling a reader nothing, which is the state this
            // whole file replaces.
            assertTrue(reason != null && reason.length() > 120,
                "'" + name + "' must carry a reason a reader can weigh, not a placeholder:"
                    + " got " + (reason == null ? "null" : "\"" + reason + "\""));

            String slug = name.toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
            assertFalse(kinds.contains(slug),
                "C8's clause is that a declined smell is ABSENT from the shipped kind list,"
                    + " and '" + slug + "' is registered: " + kinds);
        }
    }

    @Test
    @DisplayName("the catalogue is the book's, not ours: exactly twenty-four")
    void theBookHasTwentyFour() {
        // NOT a count of our own lists — that would be a count of itself. This pins the
        // EXTERNAL fact: chapter 3 of the 2nd edition enumerates twenty-four smells. It fails
        // if someone adds a row for a smell the book does not have, or drops one it does.
        assertEquals(24, FOWLER_CHAPTER_3.size(),
            "Refactoring, 2nd edition, chapter 3 lists twenty-four smells: "
                + FOWLER_CHAPTER_3.keySet());
        assertEquals(2, FowlerDetectors.DECLINED.size(),
            "and exactly two are declined today. This number is allowed to move — but it"
                + " moves in the same change that adds or removes a declination, which is"
                + " what the four stale-count findings in this sprint were all about: "
                + FowlerDetectors.DECLINED.keySet());
    }
}
