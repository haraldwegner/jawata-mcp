package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.jawata.spi.lang.Location;
import org.jawata.spi.lang.SymbolAnchors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every language's symbol anchors promise the knowledge store (Sprint 28k, Stage 8; contract §18 ring 1): an entry anchored to a
 * symbol is resolved to where the symbol is now, or is said to resolve nowhere; a language with no symbols says so for every one and the
 * entry keeps its text.
 */
public abstract class SymbolAnchorsContract {

    /**
     * @return the anchors under test
     * @throws Exception when the implementation cannot be built
     */
    protected abstract SymbolAnchors anchors() throws Exception;

    /**
     * @return a symbol the implementation resolves to a place, or nothing for a language that has no symbols
     * @throws Exception when the implementation cannot be built
     */
    protected abstract Optional<String> resolvableSymbol() throws Exception;

    @Test
    @DisplayName("a symbol that exists nowhere is not resolved, and has no location")
    void anUnknownSymbolIsUnresolved() throws Exception {
        SymbolAnchors anchors = anchors();

        Map<String, Object> pointer = anchors.pointer("no.such.Symbol#nothing");

        assertNotNull(pointer, "an unresolved pointer is a map that says so, not an absent one");
        assertFalse(Boolean.TRUE.equals(pointer.get("resolved")), "got: " + pointer);
        assertTrue(anchors.locate("no.such.Symbol#nothing").isEmpty());
    }

    @Test
    @DisplayName("free text that names no symbol has no anchor")
    void freeTextWithNoSymbolHasNoAnchor() throws Exception {
        assertTrue(anchors().anchorFor("a sentence about nothing in particular").isEmpty());
    }

    @Test
    @DisplayName("a symbol the language resolves is resolved to a location, and the language answers for it")
    void aResolvableSymbolHasALocation() throws Exception {
        Optional<String> symbol = resolvableSymbol();
        if (symbol.isEmpty()) {
            return; // a language with no symbols resolves none: the other cases are its whole contract
        }
        SymbolAnchors anchors = anchors();

        Map<String, Object> pointer = anchors.pointer(symbol.get());
        Optional<Location> location = anchors.locate(symbol.get());

        assertEquals(Boolean.TRUE, pointer.get("resolved"), "got: " + pointer);
        assertTrue(location.isPresent(), "a resolved pointer has a place: " + pointer);
        assertFalse(location.get().filePath().isBlank());
        assertTrue(anchors.answersFor(symbol.get()));
    }
}
