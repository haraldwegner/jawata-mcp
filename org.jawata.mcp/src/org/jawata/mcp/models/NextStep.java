package org.jawata.mcp.models;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ONE THING TO DO NEXT — an operation, where to run it, and why this one.
 *
 * <p>A finding used to carry its cure as PROSE appended to the message: readable, and
 * something a caller had to parse back into a call. A {@code NextStep} is the same
 * answer in a shape the caller can execute — which is what "callable straight from the
 * finding" has to mean if it is to mean anything.</p>
 *
 * <p>The discriminator is what makes a LIST of these usable. Where a smell offers
 * several, each says what tells it from its neighbours; the agent reads them and picks.
 * It is null only where a cure has no neighbour to be told apart from.</p>
 *
 * <h2>{@link #rendered()} is the ONE wire shape, and it is here rather than at the two
 * channels that print it</h2>
 *
 * <p>S8b step 7 put a next step on the REFUSAL channel beside the one findings already
 * carried. The map was assembled by hand inside the findings renderer, so a second channel
 * meant a second copy — and a copy of a published shape is wrong from the first unmirrored
 * change, with no moment at which it says so. An agent that learns to read a cure must be
 * able to read a refusal's pointer the same way, so both ask the step to render itself.</p>
 */
@com.fasterxml.jackson.annotation.JsonInclude(
    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
public record NextStep(String operation,
                       @com.fasterxml.jackson.annotation.JsonIgnore CodeAddress address,
                       String discriminator,
                       java.util.List<String> needs) {

    /**
     * mcp#71 — {@code needs} is never null; empty and "declares nothing" are the same fact,
     * and the renderer omits an empty list rather than publishing an empty array.
     */
    public NextStep {
        needs = needs == null ? java.util.List.of() : java.util.List.copyOf(needs);
    }

    /** The three-argument form, for a step whose door asks for nothing but an address. */
    public NextStep(String operation, CodeAddress address, String discriminator) {
        this(operation, address, discriminator, java.util.List.of());
    }

    /**
     * The arguments the operation is called with — the address, resolved to a door's own
     * parameter names. This is what travels, not the address: a caller executes arguments.
     */
    @com.fasterxml.jackson.annotation.JsonProperty("arguments")
    public Map<String, Object> arguments() {
        return address == null ? Map.of() : address.arguments();
    }

    /**
     * The wire shape, for the channels that build their response map by hand.
     *
     * <p>{@code discriminator} is omitted rather than written null, matching what the
     * findings renderer already published — a cure with no neighbour says nothing instead
     * of saying nothing loudly.</p>
     */
    public Map<String, Object> rendered() {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("operation", operation);
        one.put("arguments", arguments());
        if (discriminator != null) {
            one.put("discriminator", discriminator);
        }
        // mcp#71: what the AGENT must supply, named on the step rather than discovered by
        // running it and reading a refusal.
        //
        // Some doors require an input no finding can carry, because it is a DECISION and not
        // a fact — the name of a class to create, which fields travel together. `extract
        // kind=class` documents that deliberately: "WHICH state travels together is the design
        // decision this carries out." Correct of the door. The defect was upstream: a god_class
        // finding rendered "TIER: RUN — run extract kind=class" and handed over
        // {symbol, filePath}, so following the instruction verbatim earns
        // "INVALID_PARAMETER 'newTypeName': A valid Java type name is required."
        //
        // `Cure` has carried needs[] since 28d's v4.1 clause. THIS record did not, so
        // Cures.stepsFor passed the discriminator and dropped the needs — the field shipped
        // and never reached the wire, which is why the issue reads as "needs[] did not ship".
        // Omitted when empty: a step that asks for nothing should say nothing.
        if (!needs.isEmpty()) {
            one.put("needs", needs);
        }
        return one;
    }
}
