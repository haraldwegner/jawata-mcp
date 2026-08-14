package org.jawata.core.host;

/**
 * Sprint 28a (1b, step M1) — which operating system we are running on, as a
 * VALUE rather than a scattered string test.
 *
 * <p>This is the first member of {@code org.jawata.core.host}, the boundary
 * every host contact crosses. Its whole reason to exist is that the OS was
 * previously re-derived from {@code System.getProperty("os.name")} at five
 * production sites, each with its own spelling of the same question — so a
 * change to how we recognise Windows meant finding all five, and a test could
 * only exercise the branch belonging to the machine it ran on.</p>
 *
 * <p>{@link #of(String)} is what makes the seam testable: it takes the
 * {@code os.name} string as an argument, so every branch is reachable from
 * every runner. {@link #current()} is the one place that reads the live JVM.
 * The 19 red parity tests and the eight releases that preceded this package
 * were all the same lesson — a platform decision that only the platform can
 * reach is a decision no test can hold.</p>
 *
 * <p>Leaf by contract: this package depends on the JDK and nothing else.</p>
 */
public enum HostOS {

    WINDOWS,
    MACOS,
    LINUX;

    /** The operating system this JVM is running on. */
    public static HostOS current() {
        return of(osName());
    }

    /**
     * Classify an {@code os.name} value.
     *
     * <p>Total by construction: anything unrecognised — AIX, Solaris, a BSD, an
     * operating system that does not exist yet, or {@code null} from a JVM that
     * withheld the property — answers {@link #LINUX}. Those hosts are POSIX
     * shaped, so a POSIX answer is the useful one; the alternative is a
     * {@code NullPointerException} inside a path resolver, which helps nobody.</p>
     *
     * @param osName the {@code os.name} system property, or {@code null}
     * @return the classification; never {@code null}
     */
    public static HostOS of(String osName) {
        String name = osName == null ? "" : osName.toLowerCase();
        // Mac is tested FIRST, and that order is the whole point. The predicate
        // this method replaces is `contains("win")` — and "darwin" contains
        // "win". The first run of HostOSTest classified Darwin as WINDOWS: the
        // latent trap that survives when a platform decision is spelled out at
        // five call sites and reachable by no test. It never fired in
        // production only because the JVM reports "Mac OS X" rather than
        // "Darwin" on macOS. One reachable branch found it in seconds.
        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        // "win" — not "windows" — because that is the exact substring the two
        // methods this replaces used (PathUtilsImpl#isWindows,
        // ProjectImporter#isWindows). No os.name a JVM reports for Windows
        // contains "mac" or "darwin", so the ordering above changes the answer
        // for no real host: M4 stays a move, not a behaviour change.
        if (name.contains("win")) {
            return WINDOWS;
        }
        return LINUX;
    }

    /** Whether this is Windows — the question four modules used to ask the string. */
    public boolean isWindows() {
        return this == WINDOWS;
    }

    /** Whether this is macOS — the lldb/Mach-O dialect's precondition. */
    public boolean isMacOs() {
        return this == MACOS;
    }

    /** The raw {@code os.name}, for provenance reporting that must stay verbatim. */
    public static String osName() {
        return System.getProperty("os.name", "");
    }

    /** The raw {@code os.arch}, for provenance reporting that must stay verbatim. */
    public static String osArch() {
        return System.getProperty("os.arch", "");
    }

    /**
     * The environment string written into coverage manifests: {@code name/arch}.
     *
     * <p>The format is an EXTERNAL CONTRACT — it is persisted in stored coverage
     * artifacts and read back by later runs — so it is reproduced here exactly as
     * {@code CoverageService} composed it, and must not be prettified.</p>
     */
    public static String description() {
        return osName() + "/" + osArch();
    }
}
