package org.jawata.spi.host;

/** Sprint 28k (contract step E6): how a host rebuilds a scope after refreshing it from disk. */
public enum BuildKind {
    /** A full build. */
    FULL,
    /** A clean build, then a full one: the compiler's incremental state is discarded first. */
    CLEAN_FULL,
    /** An incremental build: the compiler recompiles what the changes since the last build reach, and decides itself what that is. */
    INCREMENTAL
}
