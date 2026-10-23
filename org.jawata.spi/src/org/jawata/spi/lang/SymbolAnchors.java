package org.jawata.spi.lang;

import java.util.Optional;

/** The knowledge store's question to a language: does this text name a symbol, and where is it now (contract §4.5)? */
public interface SymbolAnchors {

    /** At most one symbol the free text names, in this language's own spelling; empty when it names none or several. */
    Optional<String> anchorFor(String freeText);

    /** Where the symbol lives now; empty when it no longer exists. */
    Optional<Location> locate(String symbol);

    /** Whether this language owns symbols spelled like this one (a Java fully-qualified name today). */
    boolean answersFor(String symbol);
}
