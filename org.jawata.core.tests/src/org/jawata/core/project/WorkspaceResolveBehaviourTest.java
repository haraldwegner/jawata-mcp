package org.jawata.core.project;

import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.LoadedProject;
import org.jawata.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 12.1's own behaviours: the storm bound, source-entry preservation
 * under re-apply, wipe eviction, and the watcher-vs-MCP race.
 */
class WorkspaceResolveBehaviourTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /**
     * R2/R10 — THE STORM BOUND, a fixture-derived CONSTANT (audit N1: a bound
     * derived from the run it checks is circular).
     *
     * <p>The fixture: the reexport chain loaded WORST-CASE order (a, b, c —
     * every dependent before its provider). Derivation, written down so the
     * number cannot drift silently:</p>
     * <ul>
     *   <li>3 initial classpath sets, one per {@code configureJavaProject};</li>
     *   <li>add(a): re-resolve changes nothing (b, c absent) — 0;</li>
     *   <li>add(b): a gains its wire to b — 1 retro-wire;</li>
     *   <li>add(c): b gains c, and a gains c through b's reexport — 2.</li>
     * </ul>
     * <p>= 3 + 3 = <b>6</b> classpath-changing workspace operations, ceiling.</p>
     */
    @Test
    @DisplayName("a worst-case-order load performs at most the derived 6 classpath sets")
    void resolveStormStaysBounded() throws Exception {
        AtomicInteger classpathChanges = new AtomicInteger();
        org.eclipse.jdt.core.IElementChangedListener listener = event ->
            countClasspathChanges(event.getDelta(), classpathChanges);
        org.eclipse.jdt.core.JavaCore.addElementChangedListener(listener);
        try {
            JdtServiceImpl service = helper.loadWorkspaceCopy(
                "pde-reexport-a", "pde-reexport-b", "pde-reexport-c");
            LoadedProject a = byFixture(service, "pde-reexport-a");
            assertEquals(2, projectEntries(a.javaProject()).size(),
                "the worst-case order still wires the full closure");
        } finally {
            org.eclipse.jdt.core.JavaCore.removeElementChangedListener(listener);
        }
        // THE DERIVED BOUND (audit N1 — a constant from the fixture's own
        // shape, never from the run): 3 initial classpath sets, one per
        // configureJavaProject; then add(a) rewires nothing (b, c absent),
        // add(b) rewires a (1), add(c) rewires b and a (2). = 6.
        assertTrue(classpathChanges.get() <= 6,
            "classpath-change deltas during a 3-project worst-case load: "
                + classpathChanges.get() + " > the derived 6 — the re-resolve "
                + "is storming (R2/R10)");
    }

    private static void countClasspathChanges(org.eclipse.jdt.core.IJavaElementDelta delta,
            AtomicInteger counter) {
        if (delta == null) {
            return;
        }
        if ((delta.getFlags() & (org.eclipse.jdt.core.IJavaElementDelta.F_CLASSPATH_CHANGED
                | org.eclipse.jdt.core.IJavaElementDelta.F_RESOLVED_CLASSPATH_CHANGED)) != 0) {
            counter.incrementAndGet();
        }
        for (org.eclipse.jdt.core.IJavaElementDelta child : delta.getAffectedChildren()) {
            countClasspathChanges(child, counter);
        }
    }

    /** R3 — a re-resolve must never touch source entries (linked folders are delete+create). */
    @Test
    @DisplayName("re-resolve replaces ONLY wire entries; source entries survive verbatim")
    void reapplyPreservesSourceEntries() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-a");
        LoadedProject a = byFixture(service, "pde-bundle-a");
        List<IClasspathEntry> sourcesBefore = sourceEntries(a.javaProject());
        assertTrue(!sourcesBefore.isEmpty(), "the fixture has a source root");

        // Trigger a re-resolve that CHANGES a's wiring (b appears).
        service.addProject(helper.copyFixture("pde-bundle-b"));

        LoadedProject after = service.getProject(a.projectKey()).orElseThrow();
        assertEquals(1, projectEntries(after.javaProject()).size(), "the wire changed");
        assertEquals(entryPaths(sourcesBefore), entryPaths(sourceEntries(after.javaProject())),
            "and the source entries are byte-for-byte the ones from before — "
                + "re-running addSourceEntries would delete+recreate linked folders (R3)");
    }

    /**
     * Audit B2 — `loadProject` is the WIPE path: it must evict the whole
     * inventory, or the next resolve wires against deleted projects.
     */
    @Test
    @DisplayName("a wipe-and-reload evicts the inventory — nothing wires to a deleted project")
    void wipeReloadEvictsInventory() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-b", "pde-bundle-a");
        LoadedProject aBefore = byFixture(service, "pde-bundle-a");
        assertEquals(1, projectEntries(aBefore.javaProject()).size(), "precondition: wired");

        // The wipe: load ONE project; every earlier project is deleted.
        service.loadProject(helper.copyFixture("pde-lib-container"));

        // Now add a dependent of the DELETED b. Stale inventory facts would
        // wire it to a dead IProject; the honest outcome is an unresolved row.
        LoadedProject a2 = service.addProject(helper.copyFixtureAs("pde-bundle-a", "a-second"));
        assertEquals(0, projectEntries(a2.javaProject()).size(),
            "b was wiped; nothing may wire to its dead project");
        assertTrue(a2.unresolved().stream()
                .anyMatch(u -> u.name().contains("org.jawata.fixture.b")),
            "and the miss is an honest row: " + a2.unresolved());
    }

    /**
     * R1/R23 — the watcher thread and MCP request threads used to mutate the
     * workspace with NOTHING serializing them. Bounded race: two threads add
     * different projects concurrently; the lock must serialize them into a
     * consistent end state with no JavaModelException.
     */
    @Test
    @DisplayName("concurrent adds from two threads serialize under the resolve lock")
    void concurrentAddsSerialize() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-b");
        Path a = helper.copyFixture("pde-bundle-a");
        Path cycleA = helper.copyFixture("pde-cycle-a");
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable r1 = () -> {
            try {
                start.await();
                service.addProject(a);
            } catch (Throwable t) {
                failure.set(t);
            }
        };
        Runnable r2 = () -> {
            try {
                start.await();
                service.addProject(cycleA);
            } catch (Throwable t) {
                failure.set(t);
            }
        };
        Thread t1 = new Thread(r1, "race-add-1");
        Thread t2 = new Thread(r2, "race-add-2");
        t1.start();
        t2.start();
        start.countDown();
        t1.join(120_000);
        t2.join(120_000);
        assertNull(failure.get(), "no torn state, no duplicate-entry JavaModelException: "
            + failure.get());
        assertEquals(3, service.allProjects().size());
        LoadedProject aLoaded = byFixture(service, "pde-bundle-a");
        assertEquals(1, projectEntries(aLoaded.javaProject()).size(),
            "the interleaving must not lose a's wire to b");
    }

    // ------------------------------------------------------------------

    private static LoadedProject byFixture(JdtServiceImpl service, String fixtureName) {
        for (LoadedProject p : service.allProjects()) {
            if (p.projectRoot().getFileName().toString().equals(fixtureName)) {
                return p;
            }
        }
        throw new AssertionError("fixture '" + fixtureName + "' not loaded");
    }

    private static List<IClasspathEntry> sourceEntries(IJavaProject jp) throws Exception {
        List<IClasspathEntry> out = new ArrayList<>();
        for (IClasspathEntry e : jp.getRawClasspath()) {
            if (e.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<IClasspathEntry> projectEntries(IJavaProject jp) throws Exception {
        List<IClasspathEntry> out = new ArrayList<>();
        for (IClasspathEntry e : jp.getRawClasspath()) {
            if (e.getEntryKind() == IClasspathEntry.CPE_PROJECT) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<String> entryPaths(List<IClasspathEntry> entries) {
        return entries.stream().map(e -> e.getPath().toString()).toList();
    }
}
