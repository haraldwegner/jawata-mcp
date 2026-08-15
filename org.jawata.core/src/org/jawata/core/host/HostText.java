package org.jawata.core.host;

/**
 * Sprint 28a (1b, step M10) — text whose LINE ENDINGS differ by host, made
 * canonical at the boundary.
 *
 * <p>Above this line everything is LF. That is not a preference: the first
 * Windows CI run ever executed failed nineteen refactoring-parity tests on one
 * invisible byte, because Git for Windows checks out CRLF by default and a
 * byte-exact comparison against LF-rendered engine output cannot survive it.
 * The checkout is now pinned (a repository setting), and this class is the
 * code-side half — for the user's project, whose line endings we do not
 * control and must not mangle.</p>
 *
 * <p>Leaf by contract: this package depends on the JDK and nothing else.</p>
 */
public final class HostText {

    /** The line separator this host writes by default. */
    public static final String HOST_EOL = System.lineSeparator();

    private HostText() {
    }

    /**
     * Split on ANY line terminator — CRLF, CR, or LF.
     *
     * <p>{@code split("\n")} leaves a trailing {@code \r} on every line of a
     * CRLF file. Those carriage returns then travel into whatever the caller
     * builds: a diff whose every context line ends in an invisible byte, which
     * a user sees as a file that "changed" on lines nobody touched.</p>
     *
     * <p>A trailing terminator yields one empty final element, which is not a
     * line; it is dropped.</p>
     */
    public static String[] splitLines(String content) {
        if (content == null || content.isEmpty()) {
            return new String[0];
        }
        String[] raw = content.split("\\R", -1);
        if (raw.length > 0 && raw[raw.length - 1].isEmpty()) {
            String[] trimmed = new String[raw.length - 1];
            System.arraycopy(raw, 0, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return raw;
    }

    // canonicalizeToLf and eolOf were written here ahead of their consumers and
    // deleted at the release gate, which proved no production code called them.
    // The NEED they anticipated is real and recorded in the 1b dossier: reading
    // a user's CRLF file and writing it back as LF rewrites every line, so a
    // one-method refactoring arrives in review as the whole file. Whoever
    // implements EOL PRESERVATION adds them back with the caller that uses
    // them — an uncalled method is not a head start, it is a claim nothing
    // tests.
}
