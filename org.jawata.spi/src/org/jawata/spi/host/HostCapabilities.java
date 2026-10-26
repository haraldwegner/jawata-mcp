package org.jawata.spi.host;

import java.util.List;

/**
 * What one host is and what it can do (Sprint 28k, contract §4.2): the host's kind, whether the engine may change its workspace, whether
 * it can run programs for the engine, whether it checks a change against the person's unsaved text, and which languages it serves. The
 * engine awaits exactly the languages named here, so a host that serves none is an engine with only the tools that name no language, and a
 * host that serves {@code cpp} later changes no type anything compiled against.
 *
 * @param hostKind           what kind of host this is, such as {@code headless} or {@code eclipse}
 * @param ownsWorkspace      whether the engine may add and remove the workspace's projects
 * @param hostRuns           whether the host runs programs and tests on the engine's behalf
 * @param writePreconditions whether the host refuses a change that would overwrite what the person has not saved
 * @param languages          the languages the host serves, by the name each language provider answers to
 */
public record HostCapabilities(String hostKind, boolean ownsWorkspace, boolean hostRuns, boolean writePreconditions,
        List<String> languages) {

    /** Makes the list of languages unmodifiable, so what the engine awaits cannot change under it. */
    public HostCapabilities {
        languages = List.copyOf(languages);
    }
}
