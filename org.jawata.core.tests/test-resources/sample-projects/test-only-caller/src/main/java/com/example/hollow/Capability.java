package com.example.hollow;

/**
 * The v3.4.0 shape, seeded: one public member kept alive entirely by its
 * tests, surrounded by every neighbouring case that must NOT be reported.
 *
 * <p>Findings expected here: {@link #enable()} and {@link #hollowField}.
 * Everything else in this type is a control — see each member.</p>
 */
public class Capability implements Plugin {

    /** FINDING: read only by test code — a field can be hollow too. */
    public final int hollowField = 7;

    /** Control: read by production ({@link Production#run()}). */
    public static final String LABEL = "capability";

    /**
     * FINDING — the founding shape. Two test callers, zero production
     * callers: the capability looks alive because its tests hold it up.
     */
    public void enable() {
        // the real thing would flip a switch here
    }

    /**
     * Control: a production caller exists ({@link Production#run()}), so the
     * member is wired even though tests also exercise it. This is the
     * difference between "covered" and "hollow".
     */
    public String usedInProduction() {
        return LABEL;
    }

    /**
     * Control: ZERO callers. That is the ordinary unused-code check, a
     * different finding — this detector must stay silent here, or it becomes
     * a second unused-member reporter.
     */
    public void neverCalled() {
        // nobody calls this, on either side
    }

    /**
     * Control: {@code @Override} — dispatched polymorphically, so the caller
     * that matters calls the supertype. Test code calls this directly and it
     * must still not be reported.
     */
    @Override
    public String toString() {
        // Deliberately does NOT read hollowField: a production reader here
        // would legitimately suppress that finding, and the first run of this
        // fixture proved it — both seeded findings vanished because go() called
        // enable() and toString() read hollowField. The suppression was
        // correct; the fixture was wrong.
        return LABEL;
    }

    /** Control: the {@link Plugin} implementation, reached via the interface. */
    @Override
    public void go() {
        // Deliberately does NOT call enable() — see toString().
    }

    /**
     * FINDING — the OVERLOAD case, found live on jawata's own
     * {@code PurityCheck#check}. This arity is called only by tests while
     * {@link #render()} is wired, so a finding that named merely
     * "Capability#render" would read as a claim about the wired sibling.
     */
    public String render(int width) {
        return LABEL.substring(0, Math.min(width, LABEL.length()));
    }

    /** Control: the sibling overload, called from production. */
    public String render() {
        return LABEL;
    }

    /**
     * Control: an entry point. The JVM calls it; no source caller exists in
     * production by construction. Test code calls it here, so the skip is
     * discriminating rather than vacuous.
     */
    public static void main(String[] args) {
        new Capability().usedInProduction();
    }
}
