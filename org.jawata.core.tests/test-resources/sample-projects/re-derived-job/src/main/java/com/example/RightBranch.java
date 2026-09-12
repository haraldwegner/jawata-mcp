package com.example;

/** CONTROL FOUR, half 2 — the same job under the same parent, derived differently. */
public class RightBranch extends SharedBase {

    private static String label(Catalogue catalogue) {
        int count = catalogue.size();
        Item marker = new Item();
        StringBuilder out = new StringBuilder();
        out.append(marker.label());
        out.append(':');
        out.append(count);
        return out.toString();
    }

    public String caption(Catalogue catalogue) {
        return label(catalogue);
    }
}
