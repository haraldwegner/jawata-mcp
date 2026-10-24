package org.jawata.spi.host;

/**
 * What one host supplies to one language provider (Sprint 28k, E14; contract §4.2).
 *
 * <p>A marker, on purpose: the neutral host contract must not grow one method per language. Each provider names its own sub-interface
 * (for Java, {@code org.jawata.core.JavaBinding}) and asks the host for it by type.</p>
 */
public interface LanguageBinding {
}
