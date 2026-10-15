package org.jawata.mcp.execution;

/**
 * What stops a hired brain writing — and whether that is a MECHANISM or an INSTRUCTION
 * (Sprint 28g S16, the architect's F1; S16-19, -25, -29, -32, -35).
 *
 * <p>Five findings at the C16 checkpoint were one failure: a property asserted about a flag that
 * was really an instruction to the model, reported the flattering way. {@code --allowedTools}
 * carried tool names the CLI does not know and bounded nothing; copilot's containment was credited
 * to this product's deny flags when it was the CLI's own headless default; cursor's
 * {@code --sandbox enabled} was credited with a write bound it does not have; claude's plan mode
 * was called a bound when it is an instruction. The one question that separates them is whether
 * something OTHER THAN THE MODEL can refuse the write — an OS sandbox, a permission denial, or a
 * tool that is absent from the process — and that is what {@link Basis#MECHANISM} means.</p>
 *
 * <p>Each {@link BrainDialect} answers it, with no default, from a fact it already holds (a
 * constructor field or an argv element). A sentence is owed with every basis, so the claim can be
 * checked by whoever reads the run: a mechanism names the refusal point, and an instruction or
 * nothing names what bounds the run instead.</p>
 *
 * @param basis          what kind of bound there is
 * @param enforcedBy     MECHANISM only: the refusal point outside the model
 * @param boundedInstead INSTRUCTION and NONE: what bounds the run instead, or why nothing does
 */
public record WriteContainment(Basis basis, String enforcedBy, String boundedInstead) {

    /** Who refuses a write. */
    public enum Basis {
        /** Something outside the model refuses it: a sandbox, a permission denial, an absent tool. */
        MECHANISM,
        /** The model is told not to, and a model that is told can still do it (measured). */
        INSTRUCTION,
        /** Nothing is asked of the brain and nothing refuses: writes are permitted. */
        NONE
    }

    public WriteContainment {
        if (basis == null) {
            throw new IllegalArgumentException("a write containment must say what its basis is");
        }
        if (basis == Basis.MECHANISM && isBlank(enforcedBy)) {
            throw new IllegalArgumentException("a MECHANISM claims something other than the model"
                + " refuses the write, so it must name that refusal point — a mechanism nobody can"
                + " name cannot be checked by whoever reads the run");
        }
        if (basis != Basis.MECHANISM && isBlank(boundedInstead)) {
            throw new IllegalArgumentException("a brain whose writes are held by "
                + basis + " must say what bounds the run instead, or why nothing does. Blank is"
                + " what is left behind by not checking");
        }
    }

    /** Something outside the model refuses the write; {@code enforcedBy} names it. */
    public static WriteContainment mechanism(String enforcedBy) {
        return new WriteContainment(Basis.MECHANISM, enforcedBy, null);
    }

    /** The model is told not to write; what bounds the run if it does anyway is stated. */
    public static WriteContainment instruction(String instruction, String boundedInstead) {
        return new WriteContainment(Basis.INSTRUCTION,
            null, instruction + " — an instruction the model carries out, not a refusal: "
                + boundedInstead);
    }

    /** Writes are permitted, by explicit request or because the brain offers no bound. */
    public static WriteContainment none(String why) {
        return new WriteContainment(Basis.NONE, null, why);
    }

    /** Whether a write is refused by something other than the model — the only containment. */
    public boolean isContained() {
        return basis == Basis.MECHANISM;
    }

    /** One line for a run's record. */
    public String report() {
        return switch (basis) {
            case MECHANISM -> "writes REFUSED by a mechanism: " + enforcedBy;
            case INSTRUCTION -> "writes held only by an INSTRUCTION to the model: " + boundedInstead;
            case NONE -> "writes NOT bounded: " + boundedInstead;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
