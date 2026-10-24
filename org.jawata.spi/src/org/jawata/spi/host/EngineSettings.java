package org.jawata.spi.host;

import java.util.Optional;

/**
 * The named settings a host hands the engine (Sprint 28k, E17; contract §4.2).
 *
 * <p>A key is a dotted name such as {@code jawata.backups.depth}. The standalone engine answers from the system properties it was
 * started with; an IDE answers from its preference pages. The engine asks per call, so a host whose settings change at run time is
 * followed without a restart. What a value means, its default and what to do with one that does not parse belong to the reader, which
 * names the problem in its own words.</p>
 */
public interface EngineSettings {

    /** The value set for {@code key}, or empty when none is set; a blank value is a value. */
    Optional<String> get(String key);
}
