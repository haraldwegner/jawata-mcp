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

    /** Where the symbol lives now; empty when it no longer exists or could not be looked up — {@link #pointer} tells which. */
    default Optional<Location> locate(String symbol) {
        Map<String, Object> pointer = pointer(symbol);
        Object file = pointer.get("file");
        if (!Boolean.TRUE.equals(pointer.get("resolved")) || file == null) {
            return Optional.empty();
        }
        return Optional.of(new Location(file.toString(), pointer.get("line") instanceof Integer line ? line : -1));
    }

    /** Whether this language owns symbols spelled like this one (a Java fully-qualified name today). */
    boolean answersFor(String symbol);
}
