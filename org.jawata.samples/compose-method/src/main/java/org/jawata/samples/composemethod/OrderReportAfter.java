package org.jawata.samples.composemethod;

import java.util.ArrayList;
import java.util.List;

/**
 * THE CURE — {@link OrderReportBefore} after Compose Method.
 *
 * <p>The entry point now reads as a sequence of intentions at ONE level of
 * abstraction; each step it names is a method at the level below. Nothing was
 * cleverer, only separated: parsing is a function of a line, totalling is a
 * function of the parsed items, and rendering is a function of the totals.</p>
 *
 * <p><b>What it costs, stated because a cure with no cost is a sales pitch:</b>
 * five members where there was one, and a reader chasing a detail now follows a
 * call instead of scrolling. That trade is worth making when the jobs change at
 * different times — a new output format must not risk the parsing rules — and
 * not worth making for a method that is long but does exactly one thing.</p>
 */
public final class OrderReportAfter {

    /** One parsed line. A name for the thing the loop was juggling in two locals. */
    private record Item(String name, double value) {
    }

    private OrderReportAfter() {
    }

    /** The intention, in the order a reader asks about it. */
    public static String report(List<String> lines, double taxRate) {
        List<Item> items = parse(lines);
        double net = total(items);
        return render(items, net, net * taxRate);
    }

    private static List<Item> parse(List<String> lines) {
        List<Item> items = new ArrayList<>();
        for (String line : lines) {
            Item item = parseLine(line);
            if (item != null) {
                items.add(item);
            }
        }
        return items;
    }

    /** Null means "not an item" — the four skip conditions, in one place. */
    private static Item parseLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        int sep = line.lastIndexOf(':');
        if (sep < 0) {
            return null;
        }
        try {
            double value = Double.parseDouble(line.substring(sep + 1).trim());
            return value > 0 ? new Item(line.substring(0, sep).trim(), value) : null;
        } catch (NumberFormatException notAnAmount) {
            return null;
        }
    }

    private static double total(List<Item> items) {
        double sum = 0;
        for (Item item : items) {
            sum += item.value();
        }
        return sum;
    }

    private static String render(List<Item> items, double net, double tax) {
        StringBuilder out = new StringBuilder("ORDER REPORT\n");
        for (Item item : items) {
            out.append("  ").append(item.name()).append(" .......... ")
                .append(String.format("%.2f", item.value())).append('\n');
        }
        out.append("  ---\n");
        out.append("  items: ").append(items.size()).append('\n');
        out.append("  net:   ").append(String.format("%.2f", net)).append('\n');
        out.append("  tax:   ").append(String.format("%.2f", tax)).append('\n');
        out.append("  gross: ").append(String.format("%.2f", net + tax)).append('\n');
        return out.toString();
    }
}
