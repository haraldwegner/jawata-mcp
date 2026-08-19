package org.jawata.core.resolve;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * OSGi manifest-header parsing that keeps what the headers actually say.
 *
 * <p>Two defects this replaces (plan Stage 11.1, risks R5/R6). The old
 * {@code Require-Bundle} reader split on every comma, so a quoted version
 * range — {@code bundle-version="[1.0.0,2.0.0)"} — was torn into a phantom
 * requirement (recorded red: {@code [org.example.pinned, 2.0.0)",
 * org.example.plain]}). And {@code stripDirectives} discarded everything
 * after the first {@code ;}, so version floors, {@code visibility:=reexport}
 * and {@code resolution:=optional} — the three facts the resolver needs —
 * were thrown away at the door.</p>
 */
public final class OsgiHeaders {

    private OsgiHeaders() {
    }

    /**
     * One {@code Require-Bundle} / {@code Fragment-Host} clause, with its
     * directives KEPT.
     *
     * @param name         the bundle symbolic name
     * @param versionFloor the {@code bundle-version} attribute's lower bound,
     *                     or empty — the measured manifests declare floors
     *                     ({@code "1.0.0"}) or ranges ({@code "[1.0.0,2.0.0)"});
     *                     only the lower bound is kept, per the ruled
     *                     newest-satisfying-floor policy
     * @param reexport     {@code visibility:=reexport}
     * @param optional     {@code resolution:=optional}
     */
    public record Requirement(String name, Optional<String> versionFloor,
                              boolean reexport, boolean optional) {
    }

    /**
     * Split a header on TOP-LEVEL commas only — a comma inside quotes is
     * content, not a separator.
     */
    public static List<String> splitTopLevel(String header) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == ',' && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.isEmpty()) {
            parts.add(current.toString());
        }
        return parts;
    }

    /** Parse a {@code Require-Bundle}-shaped header into requirements. */
    public static List<Requirement> requirements(String header) {
        List<Requirement> out = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return out;
        }
        for (String clause : splitTopLevel(header)) {
            Requirement r = requirement(clause);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    /** Parse one clause: {@code name;attr="v";directive:=v}. Null on an empty clause. */
    public static Requirement requirement(String clause) {
        List<String> params = splitOnTopLevel(clause, ';');
        if (params.isEmpty()) {
            return null;
        }
        String name = params.get(0).trim();
        if (name.isEmpty()) {
            return null;
        }
        Optional<String> floor = Optional.empty();
        boolean reexport = false;
        boolean optional = false;
        for (int i = 1; i < params.size(); i++) {
            String p = params.get(i).trim();
            if (p.startsWith("bundle-version")) {
                floor = Optional.of(lowerBound(valueOf(p)));
            } else if (p.replace(" ", "").equals("visibility:=reexport")) {
                reexport = true;
            } else if (p.replace(" ", "").equals("resolution:=optional")) {
                optional = true;
            }
        }
        return new Requirement(name, floor, reexport, optional);
    }

    /** The bare name of a clause — directives and attributes dropped, quotes respected. */
    public static String nameOf(String clause) {
        List<String> params = splitOnTopLevel(clause, ';');
        return params.isEmpty() ? "" : params.get(0).trim();
    }

    /** The names of every top-level clause in a header (the old readers' view). */
    public static List<String> names(String header) {
        List<String> out = new ArrayList<>();
        if (header == null || header.isBlank()) {
            return out;
        }
        for (String clause : splitTopLevel(header)) {
            String name = nameOf(clause);
            if (!name.isEmpty()) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * The lower bound of a version attribute: {@code "1.0.0"} → 1.0.0;
     * {@code "[1.0.0,2.0.0)"} → 1.0.0. Only the floor is used —
     * newest-satisfying-floor is the ruled policy, and the measured manifests
     * never declare an upper bound the projects rely on.
     */
    static String lowerBound(String versionAttribute) {
        String v = versionAttribute.trim();
        if (v.startsWith("[") || v.startsWith("(")) {
            v = v.substring(1);
        }
        int comma = v.indexOf(',');
        if (comma >= 0) {
            v = v.substring(0, comma);
        }
        return v.replace("]", "").replace(")", "").trim();
    }

    private static String valueOf(String param) {
        int eq = param.indexOf('=');
        if (eq < 0) {
            return "";
        }
        String v = param.substring(eq + 1).trim();
        if (v.startsWith("=")) { // the := directive form
            v = v.substring(1).trim();
        }
        return v.replace("\"", "");
    }

    private static List<String> splitOnTopLevel(String s, char sep) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (c == sep && !inQuotes) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }
}
