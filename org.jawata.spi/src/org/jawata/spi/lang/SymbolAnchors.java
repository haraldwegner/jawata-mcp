package org.jawata.spi.lang;

import java.util.Map;
import java.util.Optional;

/** The knowledge store's question to a language: does this text name a symbol, and where is it now (contract §4.5)? */
public interface SymbolAnchors {

    /** At most one symbol the free text names, in this language's own spelling; empty when it names none or several. */
    Optional<String> anchorFor(String freeText);

    /**
     * Where the symbol lives now, as the pointer the knowledge layer and the tools already speak: always {@code symbol}; then
     * {@code resolved} true with {@code file} (and {@code line} when known, {@code member} when the symbol names one), or
     * {@code resolved} false with {@code note} saying why. {@code stale} is true only when the language POSITIVELY found the
     * symbol gone; neither flag set is the third answer, "could not check" — a lookup that failed is never reported as an
     * absence (28k ledger C3-1).
     */
    Map<String, Object> pointer(String symbol);
}
