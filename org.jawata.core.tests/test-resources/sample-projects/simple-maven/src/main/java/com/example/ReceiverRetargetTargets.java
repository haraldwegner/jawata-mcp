package com.example;

/**
 * Sprint 28g S5 / D9 row 1 — the shape every one of Sprint 8's 18 retargets had.
 *
 * <p>The census (`jawata-enterprise/docs/feedback/hand-edit-shapes.md`) records the gap in one
 * line: {@code change_method_signature retargetCallsTo=} <em>"keeps the receiver; the sprint's 18
 * retargets change it (`callback.x(s, …)` → `s.x(…)`)"</em>. Behaviour moved off a callback the
 * manager held and onto the object the manager had been passing IN, so every call site had to
 * change what it was invoked on — and the tool could only change the name.</p>
 *
 * <p><strong>The new owner already declares the method, and that is the operation's contract
 * rather than a convenience of this fixture.</strong> Retargeting rewrites CALL SITES and leaves
 * every declaration alone, so the method being moved onto must exist there already; if it does
 * not, the compile gate is what says so. A fixture whose new owner lacked it would be testing
 * the gate instead of the rewrite.</p>
 */
public class ReceiverRetargetTargets {

    /** The old owner — held by the manager, called with the slot passed in. */
    public static class Callback {
        public String launchExit(Slot slot, String reason) {
            return slot.name() + ":" + reason;
        }
    }

    /** The new owner. It declares the same method WITHOUT the slot parameter. */
    public static class Slot {
        private final String name;

        public Slot(String name) {
            this.name = name;
        }

        public String name() {
            return name;
        }

        public String launchExit(String reason) {
            return name + ":" + reason;
        }
    }

    private final Callback callback = new Callback();
    private final Slot primary = new Slot("primary");
    private final Slot secondary = new Slot("secondary");

    /** Four sites, because one rewritten site is an accident and four is the behaviour. */
    public String onFill() {
        return callback.launchExit(primary, "siteOneFill");
    }

    public String onTimeout() {
        return callback.launchExit(secondary, "siteTwoTimeout");
    }

    public String onFlatten() {
        return callback.launchExit(primary, "siteThreeFlatten");
    }

    /**
     * The fourth site passes a NON-TRIVIAL receiver expression, which is the case a rewrite
     * built from a parameter NAME rather than from the argument NODE gets wrong.
     */
    public String onAbort(Slot[] pool) {
        return callback.launchExit(pool[0], "siteFourAbort");
    }
}
