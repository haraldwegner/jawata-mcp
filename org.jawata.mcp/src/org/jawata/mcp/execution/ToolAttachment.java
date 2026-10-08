package org.jawata.mcp.execution;

/**
 * How a hired brain is told which tools it may use — or that it cannot be told at all
 * (Sprint 28g S16, D36).
 *
 * <p>D36 requires that all four brains are <i>told which tools to attach</i>, and that <b>a brain
 * that cannot be told is REPORTED rather than quietly left out of the count</b>. Until that clause
 * had a subject it passed by being untested; this type is what gives it one, and the subject is
 * real: measured 2026-10-08 from each CLI's own {@code --help} on the development machine,</p>
 *
 * <ul>
 *   <li><b>claude</b> — {@code --allowedTools <tools...>}, per run. {@link Reach#PER_RUN};</li>
 *   <li><b>copilot</b> — {@code --additional-mcp-config <json>} supplies servers and
 *       {@code --add-github-mcp-tool <tool>} enables named tools on one. Per run, by a different
 *       mechanism than a flat allowlist. {@link Reach#PER_RUN};</li>
 *   <li><b>codex</b> — {@code codex mcp} manages servers and {@code -c key=value} overrides
 *       config; there is no per-run allowlist. {@link Reach#OUT_OF_BAND};</li>
 *   <li><b>cursor-agent</b> — none. Its {@code -p/--print} says outright <i>"Has access to all
 *       tools, including write and shell"</i>. {@link Reach#NONE}.</li>
 * </ul>
 *
 * <h2>Why {@code NONE} carries a second string, and why the constructor refuses it empty</h2>
 *
 * <p><b>"Cannot be told which tools" is not the same claim as "cannot be contained", and reporting
 * the first as the second is the defect this field exists to prevent.</b> cursor-agent cannot be
 * handed a tool list — and it CAN be told read-only, through {@code --mode plan}. A report that
 * stops at <i>unsupported</i> would have a reader conclude the brain is unusable, when what is
 * true is narrower: it is uncontainable <i>per tool</i>. So a dialect declaring {@code NONE} must
 * say what containment it does offer, and a blank answer is refused rather than accepted as
 * "nothing" — because the honest nothing is the word {@code "none"}, written by someone who
 * checked, and an empty string is what an implementor leaves behind when they did not.</p>
 *
 * <p>{@code mechanism} is likewise required for the two reaches that HAVE one: a brain reported as
 * told, without naming what told it, cannot be verified by the person reading the run.</p>
 *
 * @param reach whether the tools can be set per run, only out of band, or not at all
 * @param mechanism the flag, file or subcommand that does it — required unless {@code NONE}
 * @param containmentInstead what containment the brain DOES offer — required when {@code NONE}
 */
public record ToolAttachment(Reach reach, String mechanism, String containmentInstead) {

    /** How far a brain's tool set can be set by the hirer. */
    public enum Reach {
        /** Settable on the run being hired — the hirer decides, per brief. */
        PER_RUN,
        /**
         * Settable, but only in the brain's own configuration, before the hire. The hirer can
         * REPORT what is attached and cannot change it for one run, which is a materially
         * different promise and is why this is not folded into {@link #PER_RUN}.
         */
        OUT_OF_BAND,
        /** Not settable at all. The brain takes whatever tools it has. */
        NONE
    }

    public ToolAttachment {
        if (reach == null) {
            throw new IllegalArgumentException("a tool attachment must state its reach");
        }
        if (reach != Reach.NONE && (mechanism == null || mechanism.isBlank())) {
            throw new IllegalArgumentException(
                "reach " + reach + " claims the tools CAN be set, so it must name what sets them"
                    + " — a brain reported as told, without naming what told it, cannot be"
                    + " verified by whoever reads the run");
        }
        if (reach == Reach.NONE && (containmentInstead == null || containmentInstead.isBlank())) {
            throw new IllegalArgumentException(
                "a brain that cannot be told which tools to attach must say what containment it"
                    + " DOES offer — 'cannot be told which tools' is a narrower claim than"
                    + " 'cannot be contained', and reporting the first as the second makes a"
                    + " usable brain read as unusable. Write \"none\" if that is the measured"
                    + " answer; blank is what is left behind by not checking");
        }
    }

    /** Settable on this run, by the named flag or file. */
    public static ToolAttachment perRun(String mechanism) {
        return new ToolAttachment(Reach.PER_RUN, mechanism, null);
    }

    /** Settable only in the brain's own config, before the hire. */
    public static ToolAttachment outOfBand(String mechanism) {
        return new ToolAttachment(Reach.OUT_OF_BAND, mechanism, null);
    }

    /**
     * Not settable. {@code containmentInstead} is required — see the type javadoc.
     *
     * @param containmentInstead what the brain can be told instead, or the word {@code "none"}
     */
    public static ToolAttachment cannotBeTold(String containmentInstead) {
        return new ToolAttachment(Reach.NONE, null, containmentInstead);
    }

    /** Whether the hirer can decide this run's tools, which is the only reach it can act on. */
    public boolean settableByTheHirer() {
        return reach == Reach.PER_RUN;
    }

    /**
     * One line for the run's report, phrased so the three reaches cannot be confused with each
     * other by a reader skimming a journal.
     */
    public String report() {
        return switch (reach) {
            case PER_RUN -> "tools set for this run via " + mechanism;
            case OUT_OF_BAND -> "tools NOT settable per run; configured out of band via "
                + mechanism + " — reported, not chosen";
            case NONE -> "CANNOT be told which tools to attach; containment available instead: "
                + containmentInstead;
        };
    }
}
