package com.example;

import com.example.port.SlotListener;

/**
 * Sprint 28g D10 — the wrappers. One that must go, and five that must be refused, each for its
 * own named reason.
 *
 * <p>The census counts <strong>17</strong> hand edits of the first shape and no tool at all:
 * <em>"no tool removes an anonymous implementation and makes its target implement the interface
 * instead (the stage-1/2/3 shape, done by hand each time)"</em>. Six of the seventeen were
 * production and eleven were test code, which is why the fixture's wrappers sit in an ordinary
 * class rather than in a test harness — the shape is the same either side.</p>
 */
public class Strategy {

    private final SlotManager manager = new SlotManager();

    /** THE CASE: every method forwards to `manager`, unchanged, under the same name. */
    public SlotListener wrapper() {
        return new SlotListener() {
            @Override
            public void onFill(String order) {
                manager.onFill(order);
            }

            @Override
            public void onCancel(String order) {
                manager.onCancel(order);
            }
        };
    }

    /** REFUSED — the second body does more than forward, so deleting it loses the log line. */
    public SlotListener wrapperThatDoesWork() {
        return new SlotListener() {
            @Override
            public void onFill(String order) {
                manager.onFill(order);
            }

            @Override
            public void onCancel(String order) {
                System.out.println("about to cancel " + order);
                manager.onCancel(order);
            }
        };
    }

    /** REFUSED — the forward transforms its argument, so it is an adapter, not a wrapper. */
    public SlotListener wrapperThatTransforms() {
        return new SlotListener() {
            @Override
            public void onFill(String order) {
                manager.onFill(order.trim());
            }

            @Override
            public void onCancel(String order) {
                manager.onCancel(order);
            }
        };
    }

    /** REFUSED — two different receivers, so there is no single target to promote. */
    public SlotListener wrapperWithTwoReceivers() {
        final SlotManager other = new SlotManager();
        return new SlotListener() {
            @Override
            public void onFill(String order) {
                manager.onFill(order);
            }

            @Override
            public void onCancel(String order) {
                other.onCancel(order);
            }
        };
    }

    /** REFUSED — an implicit receiver forwards to the ENCLOSING instance, a different edit. */
    public SlotListener wrapperOnItself() {
        return new SlotListener() {
            @Override
            public void onFill(String order) {
                handle(order);
            }

            @Override
            public void onCancel(String order) {
                handle(order);
            }
        };
    }

    /** REFUSED — the wrapper owns state, so it is not a wrapper. */
    public SlotListener wrapperWithState() {
        return new SlotListener() {
            private int seen;

            @Override
            public void onFill(String order) {
                manager.onFill(order);
            }

            @Override
            public void onCancel(String order) {
                manager.onCancel(order);
            }
        };
    }

    private void handle(String order) {
        manager.onFill(order);
    }

    public String managerLog() {
        return manager.log();
    }
}
