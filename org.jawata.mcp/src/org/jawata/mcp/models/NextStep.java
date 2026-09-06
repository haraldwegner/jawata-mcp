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
                       String discriminator) {

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
        return one;
    }
}
