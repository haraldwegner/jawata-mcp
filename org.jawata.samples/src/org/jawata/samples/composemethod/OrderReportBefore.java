package org.jawata.samples.composemethod;

import java.util.List;

/**
 * THE VIOLATION, deliberately — one method doing four jobs at four levels of
 * abstraction. Paired with {@link OrderReportAfter}.
 *
 * <p>This class exists to be POINTED AT. A cure entry in the knowledge store
 * names an address, and a reader opens it to see what the cure means; for cures
 * the upstream pattern fork has no module for — {@code compose_method} is one —
 * that address has to be ours. It is compiled by the build (an address pointing
 * at code that does not compile is worse than no address) and excluded from the
 * shipped product and from our own smell sweeps.</p>
 *
 * <p><b>Do not "fix" this file.</b> Its defects are the specimen.</p>
 */
public final class OrderReportBefore {

    private OrderReportBefore() {
    }

    /**
     * Selects, totals, formats and renders — the four jobs, interleaved, with
     * the loop bodies and the string building at the same indentation as the
     * policy decisions.
     */
    public static String report(List<String> lines, double taxRate) {
        StringBuilder out = new StringBuilder();
        out.append("ORDER REPORT\n");
        double total = 0;
        int counted = 0;
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int sep = line.lastIndexOf(':');
            if (sep < 0) {
                continue;
            }
            String name = line.substring(0, sep).trim();
            String amount = line.substring(sep + 1).trim();
            double value;
            try {
                value = Double.parseDouble(amount);
            } catch (NumberFormatException e) {
                continue;
            }
            if (value <= 0) {
                continue;
            }
            total += value;
            counted++;
            out.append("  ").append(name).append(" .......... ")
                .append(String.format("%.2f", value)).append('\n');
        }
        double tax = total * taxRate;
        out.append("  ---\n");
        out.append("  items: ").append(counted).append('\n');
        out.append("  net:   ").append(String.format("%.2f", total)).append('\n');
        out.append("  tax:   ").append(String.format("%.2f", tax)).append('\n');
        out.append("  gross: ").append(String.format("%.2f", total + tax)).append('\n');
        return out.toString();
    }
}
