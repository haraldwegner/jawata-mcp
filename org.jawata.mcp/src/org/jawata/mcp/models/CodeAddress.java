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
     * THE ADDRESS A CALL WAS POINTED AT — read back off the arguments it arrived with.
     *
     * <p>S8b step 7: a refusal that names a smaller step has to point that step SOMEWHERE, and
     * the only place it can honestly point is where the caller was already pointing. Reading
     * it back here rather than at each refusal site keeps the door's parameter names in one
     * place — a site that spelled {@code "file"} instead of {@code "filePath"} would build an
     * address nothing resolves and nothing would say so.</p>
     *
     * <p><b>NO conversion happens here, and that is the difference from {@link #of(Finding)}.</b>
     * A finding's line is 1-based and is converted once, there. These coordinates came from a
     * door, so they are already 0-based; subtracting again would move the step one line up the
     * file, which compiles, runs, and is wrong.</p>
     */
    public static CodeAddress of(com.fasterxml.jackson.databind.JsonNode arguments) {
        if (arguments == null) {
            return new CodeAddress(null, -1, -1, null);
        }
        String named = text(arguments, "symbol");
        return new CodeAddress(text(arguments, "filePath"),
            number(arguments, "line"), number(arguments, "column"),
            named != null ? named : text(arguments, "typeName"));
    }

    private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank()
            ? null : value.asText();
    }

    private static int number(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode value = node.get(field);
        return value == null || !value.isNumber() ? -1 : value.asInt();
    }

    /**
     * Can a door be pointed at this?
     *
     * <p>Two forms answer yes, and they are the two every converted door publishes: a
     * QUALIFIED symbol, or a file with a COMPLETE position. A symbol is qualified when it
     * carries a package — {@code com.foo.Bar} or {@code com.foo.Bar#items} — because that is
     * what the shared resolver can look up. A BARE name cannot be resolved and is exactly the
     * case this method exists to catch.</p>
     *
     * <p><b>The position form needs a column, and this used to accept a line alone.</b> The
     * architecture says {@code (filePath, line, column)} with {@code column ≥ 0}; the code
     * asked only for a line, so a finding carrying {@code column = -1} — which 37 of the 40
     * detector emission sites emit — was called complete and rendered RUN, and the door then
     * answered {@code INVALID_COORDINATES}. That is the fail-open this method exists to
     * prevent, in the method itself.</p>
     */
    public boolean complete() {
        if (symbol != null && symbol.contains(".")) {
            return true;
        }
        return filePath != null && !filePath.isBlank() && line >= 0 && column >= 0;
    }

    /**
     * THE ARGUMENTS A DOOR IS CALLED WITH — everything the address holds, not the best one.
     *
     * <p>This used to RETURN EARLY on a qualified symbol, on the reasoning that a name is the
     * better address because it survives an edit above it. S8b step 9's INVARIANT A measured
     * what that cost, by driving each routed cure's door with the address its own finding
     * carries, and it cost EIGHT of them:</p>
     *
     * <ul>
     *   <li>five {@code data} kinds — {@code replace_primitive}, {@code hide_delegate},
     *       {@code special_case} and {@code encapsulate_collection} — answered
     *       <i>"filePath is required"</i>. They take a name form for the TARGET and still
     *       need the file, and dropping it made the product render an instruction it
     *       refuses;</li>
     *   <li>three {@code hierarchy} kinds answered {@code SYMBOL_NOT_FOUND} for
     *       {@code com.example.Rejecter#op} — a correct fully-qualified name for a
     *       package-private top-level class declared in {@code LspTargets.java}, which the
     *       resolver looks for in a file of its own. The position was in hand and thrown
     *       away.</li>
     * </ul>
     *
     * <p>So both go. This is not a workaround around either door: every converted door
     * publishes the same sentence — <i>"Explicit filePath/line/column win when both are
     * given"</i> — so supplying both is the form they document, and which one they use is
     * their decision rather than this record's guess.</p>
     */
    public Map<String, Object> arguments() {
        Map<String, Object> args = new LinkedHashMap<>();
        if (symbol != null && symbol.contains(".")) {
            args.put("symbol", symbol);
        }
        if (filePath != null && !filePath.isBlank()) {
            args.put("filePath", filePath);
        }
        // A LINE WITHOUT A COLUMN IS NOT A POSITION, and half of one is worse than none:
        // every converted door prefers an explicit position over a symbol, so handing it
        // `line` alone makes it take the positional path and then refuse
        // INVALID_COORDINATES — which is what INVARIANT A measured on SIXTEEN routed cures
        // at once, because 37 of the 40 detector emission sites pass the literal -1 for
        // column. Both or neither; with neither, the symbol above is what the door resolves.
        if (line >= 0 && column >= 0) {
            args.put("line", line);
            args.put("column", column);
        }
        return args;
    }
}
