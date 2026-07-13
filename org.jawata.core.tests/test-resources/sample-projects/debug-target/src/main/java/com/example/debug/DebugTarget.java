package com.example.debug;

/**
 * Sprint 24 fixture — the app the debugger stages attach to. It runs until it is
 * killed, so every proof is about a LIVE program, not a replayed recording.
 *
 * <p>It deliberately contains one of each thing an interactive debugger must
 * handle: a named seam to break on, a hot loop that must keep RUNNING while a
 * non-suspending probe watches it, a field that changes (so a watchpoint has
 * something to catch), and a site that throws (so an exception breakpoint has
 * something to catch).</p>
 */
public final class DebugTarget {

    /** Changes on every tick — the field a WRITE watchpoint watches. */
    private static int lastSignal;

    /** Flipped by the debugger in the hypothesis-testing proofs (D7). */
    private static boolean tripped;

    /** A deep graph, so a bounded object expansion has something to truncate. */
    static Node graph;

    /**
     * Exactly this many, allocated once and never again — so "how many Widgets are
     * live?" has a right answer the debugger can be checked against.
     */
    static final int WIDGET_COUNT = 25;
    static final Widget[] WIDGETS = new Widget[WIDGET_COUNT];

    public static void main(String[] args) throws Exception {
        graph = chain(12);
        for (int i = 0; i < WIDGET_COUNT; i++) {
            WIDGETS[i] = new Widget(i, "widget-" + i);
        }

        Node head = graph;                           // a LOCAL holding the deep graph
        int iteration = 0;
        while (true) {
            int signal = computeSignal(iteration);   // the named seam
            lastSignal = signal;                     // the field WRITE
            int echoed = echo();                     // the field READ

            if (echoed % 17 == 0) {
                try {
                    riskyStep(signal);               // the exception site
                } catch (IllegalStateException expected) {
                    // Swallowed on purpose: an exception breakpoint should still catch it.
                }
            }
            if (tripped) {
                System.out.println("tripped at iteration " + iteration);
            }
            if (head != null && head.value < 0) {
                throw new AssertionError("unreachable; keeps 'head' live for the debugger");
            }
            spin();                                  // the hot loop
            Thread.sleep(25);
            iteration++;
        }
    }

    /** The seam: a method worth breaking on, with an argument and a local. */
    static int computeSignal(int iteration) {
        int doubled = iteration * 2;
        int adjusted = doubled + offset();
        return adjusted;
    }

    static int offset() {
        return 7;
    }

    /**
     * Reads {@link #lastSignal}. Without a reader, a field-ACCESS watchpoint would
     * never fire — and a test that hangs proves nothing at all.
     */
    static int echo() {
        return lastSignal;
    }

    /** A method with arguments and a return value — what `evaluate` invokes. */
    static int multiply(int a, int b) {
        return a * b;
    }

    private static Node chain(int depth) {
        Node head = null;
        for (int i = depth; i >= 1; i--) {
            Node node = new Node();
            node.value = i;
            node.label = "node-" + i;
            node.next = head;
            head = node;
        }
        return head;
    }

    /** A link in the deep graph — the thing a bounded expansion must stop walking. */
    static final class Node {
        int value;
        String label;
        Node next;

        @Override
        public String toString() {
            return "Node(" + label + ")";
        }
    }

    /** A type with a known, fixed population — the instances-of-type proof. */
    static final class Widget {
        final int id;
        final String name;

        Widget(int id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return "Widget(" + id + ")";
        }
    }

    /** Throws every time it is called — the exception site. */
    static void riskyStep(int signal) {
        throw new IllegalStateException("signal " + signal + " is divisible by 17");
    }

    /**
     * The hot loop. A non-suspending probe (D8) must stream values out of this
     * while it demonstrably keeps running — that is the whole point of the proof.
     */
    static void spin() {
        long burned = 0;
        for (int i = 0; i < 50_000; i++) {
            burned += i % 7;
        }
        if (burned < 0) {
            throw new AssertionError("unreachable; keeps the loop from being optimized away");
        }
    }

    public static int getLastSignal() {
        return lastSignal;
    }

    private DebugTarget() {
    }
}
