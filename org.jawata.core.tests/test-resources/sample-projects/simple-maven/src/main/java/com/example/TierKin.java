package com.example;

/**
 * The subtypes each refusal fixture needs in order to REACH its own precondition, held in one
 * file so the fixtures themselves stay one top-level class each.
 *
 * <p><b>Why they live here rather than beside their parents:</b> row 4 refuses a class whose
 * file declares anything else, because it deletes the FILE. A subtype declared beside its
 * middle class would therefore trip that refusal first, and every fixture below would be
 * answered by the wrong branch — the shadowing this stage has already had to repair twice.</p>
 */
public class TierKin {

    /** Gives {@link TierOverriding} a subtype, so it reaches the override refusal. */
    public static class OfOverriding extends TierOverriding {

        public OfOverriding(String name) {
            super(name);
        }
    }

    /** Gives {@link TierObserved} a subtype, so it reaches the observed-type refusal. */
    public static class OfObserved extends TierObserved {

        public OfObserved(String name) {
            super(name);
        }
    }

    /** Gives {@link TierUnderAbstract} a subtype, so it reaches the abstract-parent refusal. */
    public static class OfUnderAbstract extends TierUnderAbstract {
    }

    /**
     * A NESTED middle class that passes every earlier check — it extends {@link TierBase} and
     * has a subtype — so it is the one shape that actually REACHES the nested refusal. A
     * nested class with no subtypes would be answered by the no-subtypes check first, and the
     * mutation aimed at the nested rule would stay green while measuring nothing.
     */
    public static class Middling extends TierBase {

        public Middling(String name) {
            super(name);
        }

        public int rung() {
            return 4;
        }
    }

    /** Exists only to give {@link Middling} a subtype. */
    public static class UnderMiddling extends Middling {

        public UnderMiddling(String name) {
            super(name);
        }
    }

    /** Gives {@link TierFixedCtor} a subtype, so it reaches the fixed-argument refusal. */
    public static class OfFixedCtor extends TierFixedCtor {
    }

    /** Gives {@link TierColliding} a subtype, so it reaches the name-collision refusal. */
    public static class OfColliding extends TierColliding {

        public OfColliding(String label) {
            super(label);
        }
    }

    /** The observation itself — this is what makes TierObserved's refusal fire. */
    public boolean isObserved(Object candidate) {
        return candidate instanceof TierObserved;
    }
}
