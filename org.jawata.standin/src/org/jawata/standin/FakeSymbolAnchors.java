package org.jawata.standin;

import java.util.Map;
import java.util.Optional;

import org.jawata.spi.lang.SymbolAnchors;

/**
 * The stand-in language's symbol anchors (Sprint 28k, Stage 8; contract §18 ring 1): the language has no symbols, so nothing is
 * anchored and every pointer is unresolved. Memory recorded against this language keeps its text and finds no code.
 */
public final class FakeSymbolAnchors implements SymbolAnchors {

    @Override
    public Optional<String> anchorFor(String freeText) {
        return Optional.empty();
    }

    @Override
    public Map<String, Object> pointer(String symbol) {
        return Map.of("symbol", symbol, "resolved", false);
    }

    @Override
    public boolean answersFor(String symbol) {
        return false;
    }
}
