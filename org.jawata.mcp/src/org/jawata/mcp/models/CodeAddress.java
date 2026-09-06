package org.jawata.mcp.models;

import org.jawata.mcp.domain.Finding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WHERE A CURE WOULD BE RUN — one value type, on the finding and on the refusal.
 *
 * <p>A finding says something is wrong somewhere; a cure is an operation that must be
 * pointed at that somewhere. Between them sat a conversion nobody owned: findings carry
 * 1-BASED lines (JDT's convention) and every refactoring door takes ZERO-BASED ones, so
 * the subtraction happened wherever a caller remembered it. {@link #of(Finding)} is now
 * the one place it happens.</p>
 *
 * <h2>Completeness is the point, and it FAILS VISIBLE</h2>
 *
 * <p>Not every finding can be acted on. A detector that emits a bare member name — {@code
 * items} rather than {@code com.foo.Bar#items} — has named something no door can resolve,
 * and the honest answer is to say so rather than to render an instruction that cannot be
 * followed. {@link #complete()} is what separates the two, and a finding that fails it
 * renders CONSIDER NAMING ITS DETECTOR: the gap is the detector's and the reader is told
 * whose it is. The failure mode being refused here is the opposite one — rendering RUN and
 * letting the caller discover at the door that the address was never usable.</p>
 */
public record CodeAddress(String filePath, int line, int column, String symbol) {

    /**
     * Read a finding's address, converting its line ONCE.
     *
     * <p>A finding's line is 1-based and a door's is 0-based. {@code -1} is the
     * domain's "not applicable" and stays {@code -1} rather than becoming {@code -2}.</p>
     */
    public static CodeAddress of(Finding finding) {
        return new CodeAddress(finding.filePath(),
            finding.line() > 0 ? finding.line() - 1 : -1,
            finding.column() > 0 ? finding.column() - 1 : -1,
            finding.symbol());
    }

    /**
     * Can a door be pointed at this?
     *
     * <p>Two forms answer yes, and they are the two every converted door publishes: a
     * QUALIFIED symbol, or a file with a line. A symbol is qualified when it carries a
     * package — {@code com.foo.Bar} or {@code com.foo.Bar#items} — because that is what
     * the shared resolver can look up. A BARE name cannot be resolved and is exactly the
     * case this method exists to catch.</p>
     */
    public boolean complete() {
        if (symbol != null && symbol.contains(".")) {
            return true;
        }
        return filePath != null && !filePath.isBlank() && line >= 0;
    }

    /** The arguments a door is called with — the qualified symbol wins where both exist. */
    public Map<String, Object> arguments() {
        Map<String, Object> args = new LinkedHashMap<>();
        if (symbol != null && symbol.contains(".")) {
            args.put("symbol", symbol);
            return args;
        }
        if (filePath != null && !filePath.isBlank()) {
            args.put("filePath", filePath);
        }
        if (line >= 0) {
            args.put("line", line);
        }
        if (column >= 0) {
            args.put("column", column);
        }
        return args;
    }
}
