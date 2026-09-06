package org.jawata.mcp.models;

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
 */
public record NextStep(String operation, CodeAddress address, String discriminator) {
}
