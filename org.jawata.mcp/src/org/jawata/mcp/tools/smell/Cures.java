package org.jawata.mcp.tools.smell;

import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.models.CodeAddress;
import org.jawata.mcp.models.NextStep;
import org.jawata.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * THE ONE PLACE A FINDING GAINS ITS RUNNABLE STEPS — on the path every detector reaches.
 *
 * <p>This lived on {@code AbstractAstDetector} until S8b's C8b review, and being on a BASE
 * CLASS is what was wrong with it: a detector that does not extend it simply never gained
 * cures, and nothing said so. Measured through the built product, three of the twenty-seven
 * did not — {@code loops} and {@code data_class} (adapters over {@code find_modernization}),
 * the quality kinds adapted from tools, and {@code duplicated_code}, which S8b step 9 ITSELF
 * added. All three shipped their cure as prose in the message, which is the exact shape step
 * 4 was written to remove.</p>
 *
 * <h2>Why the dispatch path and not a shared helper</h2>
 *
 * <p>A helper the detectors call is a step a detector can DECLINE, and a step a detector can
 * decline is a step one will. {@code Detector#detect} has exactly two production call sites,
 * both in {@link org.jawata.mcp.tools.FindQualityIssueTool} — the single kind and the family
 * sweep — so attaching here covers every detector that exists and every one that will, with
 * nothing to remember. That is the same move S8b step 8 made for the door population: stop
 * maintaining a list of who participates, and put the behaviour where participation is not
 * optional.</p>
 *
 * <h2>It reads the ROW, which is what the caller will read</h2>
 *
 * <p>The join used to run on {@code Finding} objects, one layer before the wire. Working on
 * the rendered row instead is what makes it reach the adapters, which never build a
 * {@code Finding} at all — and it has a second property worth stating: what this attaches is
 * computed from EXACTLY the fields the caller receives, so a gate that reads the response is
 * reading the same address the cure was derived from.</p>
 */
public final class Cures {

    private Cures() {
    }

    /**
     * The steps a finding of this kind, at this address, can actually be cured by.
     *
     * <p>Empty for two different reasons, and they fail differently. A kind whose verdict is
     * CONSIDER has nothing to run, which is an honest empty. A kind whose verdict is RUN but
     * whose finding carries no usable address ALSO gets an empty list — and the rendered
     * sentence says so and names the detector, so that absence is visible rather than looking
     * like the first case.</p>
     */
    public static List<NextStep> stepsFor(String kind, CodeAddress address) {
        return stepsFor(kind, address, CureTier.derive(kind));
    }

    /**
     * The same, against an EXPLICIT registry of registered operations.
     *
     * <p>{@link CureTier#derive(String)} consults the PROCESS registry, which is correct in
     * the product and empty in a unit-test JVM because no tool has registered there — so
     * every kind answers CONSIDER for a reason about plumbing. That trap has cost this sprint
     * three separate false readings, so the overload exists rather than each caller
     * rediscovering it.</p>
     */
    public static List<NextStep> stepsFor(String kind, CodeAddress address,
                                          List<String> registeredOperations) {
        return stepsFor(kind, address, CureTier.derive(kind, registeredOperations));
    }

    private static List<NextStep> stepsFor(String kind, CodeAddress address,
                                           CureTier.Derivation tier) {
        if (tier.tier() != CureTier.Tier.RUN || !address.complete()) {
            return List.of();
        }
        List<NextStep> steps = new ArrayList<>();
        for (CureCatalog.Cure c : tier.runnable()) {
            steps.add(new NextStep(
                CureLookup.Cures.invocationOf(c.recipe()), address, c.discriminator()));
        }
        return List.copyOf(steps);
    }

    /**
     * Attach the runnable steps to every finding row a detector answered with.
     *
     * <p>Returns the response unchanged when it carries no findings — a refusal, a summary, a
     * shape this does not recognise. It never CREATES a findings list and never removes a
     * row: its whole effect is a {@code cures} key on rows that earn one.</p>
     *
     * @return the same response; the rows are mutated in place, which is what lets this sit
     *         in the dispatch chain beside the filters that already do
     */
    @SuppressWarnings("unchecked")
    public static ToolResponse attach(ToolResponse response) {
        if (response == null || !response.isSuccess()
            || !(response.getData() instanceof Map<?, ?> data)
            || !(data.get("findings") instanceof List<?> rows)) {
            return response;
        }
        for (Object o : rows) {
            if (o instanceof Map<?, ?> raw) {
                try {
                    attachTo((Map<String, Object>) raw);
                } catch (UnsupportedOperationException immutableRow) {
                    // A detector that answered with an immutable row keeps its own answer
                    // rather than the sweep failing. It loses its cures and says nothing —
                    // which the derived gate over every routed kind is what catches.
                    return response;
                }
            }
        }
        return response;
    }

    private static void attachTo(Map<String, Object> row) {
        Object kind = row.get("kind");
        if (!(kind instanceof String smell) || row.containsKey("cures")) {
            return;
        }
        // BUILT THROUGH `Finding` ON PURPOSE. A row's line is 1-based, a door's is 0-based,
        // and CodeAddress.of(Finding) is the ONE place that subtraction happens — so the row
        // is read back into the value type that factory takes rather than a second row-shaped
        // factory being added beside it with its own copy of the rule.
        CodeAddress address = CodeAddress.of(new Finding(
            smell, text(row, "filePath"), number(row, "line"), number(row, "column"),
            text(row, "severity"), text(row, "message"), text(row, "symbol")));
        List<NextStep> steps = stepsFor(smell, address);
        if (steps.isEmpty()) {
            return;
        }
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (NextStep step : steps) {
            // ONE renderer, on the step itself — the same one the refusal channel uses.
            rendered.add(step.rendered());
        }
        row.put("cures", rendered);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    /** A coordinate the row actually carries, or the domain's own "not applicable". */
    private static int number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number n ? n.intValue() : -1;
    }
}
