package org.jawata.mcp.field;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed semantic version — the one field-record value that legitimately
 * carries digits (Sprint 28b, C1 audit F1/F2). Construction is parse-or-
 * unknown: only {@code major.minor.patch} numbers survive, so an
 * identifier-shaped secret or id cannot become a version. Serialized with
 * underscores ({@code 3_11_0}) to match the token grammar of the pile line.
 */
public record Version(int major, int minor, int patch) {

    private static final Pattern SEMVER =
        Pattern.compile("v?(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})(?:[.-].*)?");

    /** The coercion target for anything that does not parse. */
    public static final Version UNKNOWN = new Version(0, 0, 0);

    /** Parse-or-unknown; never throws, never preserves unparsed content. */
    public static Version of(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        Matcher m = SEMVER.matcher(raw.trim());
        if (!m.matches()) {
            return UNKNOWN;
        }
        try {
            return new Version(Integer.parseInt(m.group(1)),
                Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    /** The pile-line form: digits and underscores only, by construction. */
    public String token() {
        return major + "_" + minor + "_" + patch;
    }
}
