package org.jawata.mcp.agent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-role figures of the measures (Sprint 28h, S7; D10), computed from facts read off hire records and from nothing the seat said
 * about itself. A developer is counted against for an edit made where the instruction named a tool; a chief engineer is counted against for
 * an edit order given where a tool existed. Every other role is reported with its usage and nothing counted against it.
 *
 * @param seat           the role
 * @param hires          how many hires of it the records hold
 * @param tokens         what they reported spending, summed
 * @param toolSteps      the tool calls they made, summed
 * @param countedAgainst the edits, or edit orders, that the role's measure counts against it
 */
public record RoleFigures(String seat, int hires, long tokens, int toolSteps, int countedAgainst) {

    /**
     * What one hire record says.
     *
     * @param seat                       the seat hired
     * @param tokens                     tokens the brain reported
     * @param toolSteps                  tool calls it made
     * @param editsWhereToolNamed        edits it made where its instruction named a tool
     * @param editOrdersWhereToolExisted edit orders it gave where a tool existed
     */
    public record HireFacts(String seat, long tokens, int toolSteps, int editsWhereToolNamed, int editOrdersWhereToolExisted) {
    }

    /** One row per role, in the order the roles first appear in the records. */
    public static List<RoleFigures> of(Collection<HireFacts> records) {
        Map<String, long[]> sums = new LinkedHashMap<>();
        for (HireFacts r : records) {
            long[] s = sums.computeIfAbsent(r.seat(), k -> new long[4]);
            s[0]++;
            s[1] += r.tokens();
            s[2] += r.toolSteps();
            s[3] += switch (r.seat()) {
                case "developer", "senior-developer" -> r.editsWhereToolNamed();
                case "chief-engineer" -> r.editOrdersWhereToolExisted();
                default -> 0;
            };
        }
        return sums.entrySet().stream()
            .map(e -> new RoleFigures(e.getKey(), (int) e.getValue()[0], e.getValue()[1], (int) e.getValue()[2], (int) e.getValue()[3]))
            .toList();
    }
}
