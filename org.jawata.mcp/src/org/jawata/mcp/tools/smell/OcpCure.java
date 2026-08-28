package org.jawata.mcp.tools.smell;

import java.util.List;

/**
 * Sprint 19 / 20 seam — the <b>OCP cure</b>. Sprint 20 deliberately ships no
 * {@code ocp} detector: an Open/Closed violation is only meaningful relative to the
 * extension axis you intend to keep open (design intent, not a code property). Its
 * observable <em>trace</em> is {@code divergent_change} / {@code shotgun_surgery}
 * (surfaced under family {@code solid}). The <b>cure is a goal</b>, and goals are
 * Kerievsky recipes — introduce an abstraction at the modification axis. This maps a
 * churn-trace kind to the applicable {@code refactor_to_pattern} kinds and is the seed
 * of the future {@code get_target_recipe}.
 *
 * <h2>Sprint 28d — this is now the DECLARED FALLBACK, not the answer</h2>
 * <p>The store became the authority: a cure's address is resolved from a
 * catalogue row by {@link CureLookup}, and the recipes below are what is handed
 * over when a catalogue namespace holds zero rows. That handover is always
 * LABELLED — see {@code Cures.degradation} — because a fallback nobody declared
 * is indistinguishable from an answer, and "there is no catalogue" and "the
 * catalogue had nothing for you" must not read alike.</p>
 *
 * <p>Nothing here changed behaviourally, deliberately: the degraded path has to
 * keep working exactly as it did, or the degradation would be an outage wearing
 * a notice.</p>
 */
public final class OcpCure {

    private OcpCure() {
    }

    /**
     * Appended to the churn detectors' messages: the OCP-cure pointer.
     *
     * <p>Carries no address, and cannot: it is assembled from plan-kind names
     * with nothing behind them checked. That is the reason it is a fallback now
     * rather than the answer.</p>
     */
    public static final String HINT =
        " OCP cure: introduce an abstraction at the modification axis — refactor_to_pattern "
            + "kind=refactor_to_state / refactor_to_command_dispatcher / form_template_method "
            + "(or refactoring(action=plan, kind=<same>) then apply_plan for a parity-gated run).";

    /** The {@code refactor_to_pattern} kinds that close the axis a churn trace exposes. */
    public static List<String> recipesFor(String smellKind) {
        return switch (smellKind) {
            case "divergent_change", "shotgun_surgery" ->
                List.of("refactor_to_state", "refactor_to_command_dispatcher", "form_template_method");
            default -> List.of();
        };
    }
}
