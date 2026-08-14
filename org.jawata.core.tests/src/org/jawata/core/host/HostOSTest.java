package org.jawata.core.host;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28a (1b, step M1) — the host boundary's OS identity.
 *
 * <p>The whole point of the seam is that this classification is testable
 * WITHOUT the operating system it classifies. Every case below runs on every
 * runner, because {@link HostOS#of(String)} takes the {@code os.name} string
 * as a value rather than reading it from the JVM. The Windows branch that
 * three CI runners could not reach is reachable here from Linux.</p>
 */
class HostOSTest {

    @Test
    @DisplayName("every os.name spelling the JVM ships classifies, and the classification is total")
    void classifiesTheSpellingsTheJvmActuallyReports() {
        // The exact strings Oracle/OpenJDK report, lifted from java.lang.System's
        // documented os.name values — not invented approximations.
        assertEquals(HostOS.WINDOWS, HostOS.of("Windows 11"));
        assertEquals(HostOS.WINDOWS, HostOS.of("Windows Server 2022"));
        assertEquals(HostOS.MACOS, HostOS.of("Mac OS X"));
        assertEquals(HostOS.MACOS, HostOS.of("Darwin"));
        assertEquals(HostOS.LINUX, HostOS.of("Linux"));
    }

    @Test
    @DisplayName("an unknown os.name is LINUX, never null — a POSIX guess beats a NullPointerException")
    void anUnrecognisedNameFallsBackToPosix() {
        // AIX, Solaris, FreeBSD and anything future: they are POSIX-shaped, and
        // the boundary's job is to keep working, not to enumerate the world.
        assertEquals(HostOS.LINUX, HostOS.of("AIX"));
        assertEquals(HostOS.LINUX, HostOS.of("FreeBSD"));
        assertEquals(HostOS.LINUX, HostOS.of(""));
        assertEquals(HostOS.LINUX, HostOS.of(null));
    }

    @Test
    @DisplayName("classification is case-insensitive, because os.name capitalisation is not a contract")
    void caseDoesNotDecide() {
        assertEquals(HostOS.WINDOWS, HostOS.of("WINDOWS 10"));
        assertEquals(HostOS.MACOS, HostOS.of("mac os x"));
    }

    @Test
    @DisplayName("isWindows agrees with the enum on every value — one predicate, not five re-derivations")
    void isWindowsIsTheEnumsOwnAnswer() {
        assertTrue(HostOS.WINDOWS.isWindows());
        assertFalse(HostOS.MACOS.isWindows());
        assertFalse(HostOS.LINUX.isWindows());
        assertTrue(HostOS.MACOS.isMacOs());
        assertFalse(HostOS.LINUX.isMacOs());
    }

    @Test
    @DisplayName("the legacy predicate is preserved exactly: contains(\"win\"), lowercased")
    void matchesTheBehaviourItReplaces() {
        // PathUtilsImpl#isWindows and ProjectImporter#isWindows both read
        // System.getProperty("os.name").toLowerCase().contains("win"). M4 deletes
        // both; this test is the contract that says the replacement is the same
        // answer, so the deletion is a move and not a behaviour change.
        for (String name : new String[] {
                "Windows 11", "Windows Server 2022", "WINDOWS 10", "windows 7"}) {
            assertEquals(name.toLowerCase().contains("win"), HostOS.of(name).isWindows(), name);
        }
        for (String name : new String[] {"Linux", "Mac OS X", "AIX", "FreeBSD"}) {
            assertEquals(name.toLowerCase().contains("win"), HostOS.of(name).isWindows(), name);
        }
    }

    @Test
    @DisplayName("current() answers for the JVM we are in, and description() keeps the manifest's format")
    void currentAndDescriptionReadTheLiveJvm() {
        assertEquals(HostOS.of(System.getProperty("os.name")), HostOS.current());

        // CoverageService writes this string into stored coverage manifests, so
        // its shape is an external contract: name + "/" + arch, verbatim.
        assertEquals(System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
            HostOS.description());
        assertNotNull(HostOS.osName());
        assertNotNull(HostOS.osArch());
    }
}
