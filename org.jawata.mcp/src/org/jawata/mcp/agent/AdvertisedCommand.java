package org.jawata.mcp.agent;

/**
 * One slash command the agent advertises to a host (contract E1): its name, a one-line purpose and its group, which a host renders
 * as seats, skills or utilities. The group travels in {@code _meta["jawata/group"]}, so a plain client sees an ordinary command.
 *
 * @param name        the command without the slash, such as {@code refactor}
 * @param description what it does, in one line
 * @param group       {@code seat}, {@code skill} or {@code utility}
 */
public record AdvertisedCommand(String name, String description, String group) {
}
