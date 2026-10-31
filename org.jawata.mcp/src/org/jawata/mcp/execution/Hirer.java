package org.jawata.mcp.execution;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What decides a hire (Sprint 28h, S7; D9, D11, D48): whether the hirer may hire the seat at all, which brain the seat runs on,
 * where a seat that cannot do its task escalates to, and which tools it may call. It holds no process and starts nothing; the caller starts
 * the brain with what this answers, so the same answers serve any way a brain is reached.
 *
 * <p><b>Brain per seat.</b> Deciding roles get a strong model and a developer executing a named tool call does not; choosing another brain
 * for one seat changes that seat's next hire and no other.</p>
 *
 * <p><b>The consultant.</b> It reviews and edits nothing, answers to the person, and runs on a different vendor's agent than main: a
 * review by the same vendor's brain is the working agent reviewing itself, so the hire is refused.</p>
 */
public final class Hirer {

    /**
     * A brain the studio offers: a vendor's model. What one hire may spend is the seat file's own cost ceiling ({@code Ceilings.maxCostUsd}),
     * enforced by the hire path, and is not repeated here: a second budget beside it would be read by nothing.
     */
    public record Brain(String vendor, String model) {
        public Brain {
            vendor = vendor == null ? "" : vendor.strip();
            model = model == null ? "" : model.strip();
        }
    }

    /**
     * The host setting that names the brain of the principal seat, main (S7a). Besides its command line it may name that brain's
     * {@code vendor} and {@code model}, which is how the consultant rule learns whose agent main is.
     */
    public static final String MAIN_BRAIN_SETTING = "jawata.agent.brain";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The model registry (D11): the brains the studio offers, read from the registry setting
     * {@code {"<brain>":{"command":[...],"env":{...},"vendor":"anthropic","model":"..."},...}}, where a brain is named the way its dialect names
     * itself. Its vendor and model, or none when the text is not JSON or the brain has no entry. A missing field reads as blank, which the
     * rules below treat as not known and never as a vendor of its own.
     */
    public static Optional<Brain> brainIn(String registry, String brain) {
        try {
            JsonNode entry = JSON.readTree(registry).path(brain);
            return entry.isObject() ? Optional.of(new Brain(entry.path("vendor").asText(""), entry.path("model").asText(""))) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** The vendor and model main's brain setting names beside its command line, or none when the text is not a JSON object. */
    public static Optional<Brain> brainOf(String mainBrainSetting) {
        try {
            JsonNode root = JSON.readTree(mainBrainSetting);
            return root.isObject() ? Optional.of(new Brain(root.path("vendor").asText(""), root.path("model").asText(""))) : Optional.empty();
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    private static final Set<String> BRAIN_EDITORS = Set.of("Edit", "Write", "MultiEdit");

    private final SeatTree tree;
    private final Map<String, Brain> brains;
    private final Set<String> engineCodeChangingTools;

    public Hirer(SeatTree tree, Map<String, Brain> brains, Set<String> engineCodeChangingTools) {
        this.tree = tree;
        this.brains = Map.copyOf(brains);
        this.engineCodeChangingTools = Set.copyOf(engineCodeChangingTools);
    }

    /** The reason this hire is not allowed, or empty when it is. */
    public Optional<String> refuse(String hirer, String seat) {
        if (!tree.knows(seat)) {
            return Optional.of("there is no seat '" + seat + "'");
        }
        if (!tree.mayHire(hirer, seat)) {
            return Optional.of(hirer + " does not hire " + seat + (tree.parentOf(seat).isPresent()
                ? "; its hirer is " + tree.parentOf(seat).get() : ""));
        }
        if ("consultant".equals(seat)) {
            Brain main = brains.get("main");
            Brain consultant = brains.get("consultant");
            if (main == null || consultant == null || main.vendor().isBlank() || consultant.vendor().isBlank()) {
                return Optional.of("the consultant needs a brain of its own and main's brain known, so the vendors can be compared: the vendor of main is read from "
                    + "\"vendor\" in the " + MAIN_BRAIN_SETTING + " setting and the consultant's from \"vendor\" in its brain's entry of the hire-brains registry setting");
            }
            if (main.vendor().equalsIgnoreCase(consultant.vendor())) {
                return Optional.of("the consultant runs on " + consultant.vendor()
                    + ", main's own vendor; a review by the same vendor's agent is the working agent reviewing itself");
            }
        }
        return Optional.empty();
    }

    /**
     * The hire path's entry to {@link #refuse}: the hierarchy applies only where the tree knows both names. A seat a project defined for itself,
     * or a hirer that is a person or a script and no seat, is outside the signed tree and is neither refused by it nor vouched for by it.
     */
    public Optional<String> refuseKnown(String hirer, String seat) {
        if (hirer == null || seat == null || !tree.knows(hirer) || !tree.knows(seat)) {
            return Optional.empty();
        }
        return refuse(hirer, seat);
    }

    /** The brain a hire of {@code seat} runs on, or none when none is configured. */
    public Optional<Brain> brainFor(String seat) {
        return Optional.ofNullable(brains.get(seat));
    }

    /** The same rules with another brain for one seat; the seat's next hire changes, no other seat's does. */
    public Hirer withBrain(String seat, Brain brain) {
        Map<String, Brain> next = new LinkedHashMap<>(brains);
        next.put(seat, brain);
        return new Hirer(tree, next, engineCodeChangingTools);
    }

    /** Where a seat that could not do its task goes next: a developer escalates to the senior developer, and nothing else escalates. */
    public Optional<String> escalateTo(String seat) {
        return "developer".equals(seat) ? Optional.of("senior-developer") : Optional.empty();
    }

    /**
     * The tools the seat may call, by the names the engine publishes. A seat that may edit code gets them all and the brain's own editors
     * too; every other seat gets the engine's tools without the ones that change code, and no editor of its own.
     */
    public List<String> allowlist(String seat, Collection<String> engineTools) {
        List<String> allowed = new ArrayList<>();
        boolean edits = tree.mayEditCode(seat);
        for (String tool : engineTools) {
            if (edits || !engineCodeChangingTools.contains(tool)) {
                allowed.add(tool);
            }
        }
        if (edits) {
            allowed.addAll(BRAIN_EDITORS);
        }
        return allowed;
    }
}
