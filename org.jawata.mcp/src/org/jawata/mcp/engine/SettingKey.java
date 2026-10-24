package org.jawata.mcp.engine;

/**
 * One setting the engine reads (Sprint 28k, E17): its name, what it is when nothing sets it, and what it does. The list of all of them
 * is {@link SettingKeys#all()}, which is what a host's settings screen is built from and checked against.
 *
 * @param name     the dotted key, such as {@code jawata.backups.depth}
 * @param fallback what applies when nothing sets it, in words, since several defaults are not a single value
 * @param meaning  what changing it does, in a sentence a person choosing the value can act on
 */
public record SettingKey(String name, String fallback, String meaning) {
}
