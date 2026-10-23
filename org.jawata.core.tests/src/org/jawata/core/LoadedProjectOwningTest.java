package org.jawata.core;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 28k Stage 2b (architect D-SIX repair): the one lookup of which loaded project holds a file, used by the per-call disk sync and
 * by the plan gate's build planning. Only the project root matters to it, so the projects here carry nothing else.
 */
class LoadedProjectOwningTest {

    private static LoadedProject at(String root) {
        return new LoadedProject(root, Path.of(root), null, null, null, Instant.EPOCH, 0, 0, List.of(), null, null);
    }

    @Test
    @DisplayName("when roots nest, the deepest root that contains the file holds it, whichever is listed first")
    void theDeepestContainingRootWins() {
        LoadedProject aggregator = at("/work/repo");
        LoadedProject module = at("/work/repo/module");
        Path file = Path.of("/work/repo/module/src/A.txt");

        assertSame(module, LoadedProject.owning(List.of(aggregator, module), file));
        assertSame(module, LoadedProject.owning(List.of(module, aggregator), file));
    }

    @Test
    @DisplayName("a file outside the inner root belongs to the outer one")
    void aFileOutsideTheInnerRootBelongsToTheOuterOne() {
        LoadedProject aggregator = at("/work/repo");
        LoadedProject module = at("/work/repo/module");

        assertSame(aggregator, LoadedProject.owning(List.of(aggregator, module), Path.of("/work/repo/other/B.txt")));
    }

    @Test
    @DisplayName("no loaded project holds a file that lies under none of the roots, and a sibling folder with a longer name is not a prefix match")
    void noProjectHoldsAFileUnderNoRoot() {
        LoadedProject module = at("/work/repo/module");

        assertNull(LoadedProject.owning(List.of(module), Path.of("/elsewhere/C.txt")));
        assertNull(LoadedProject.owning(List.of(module), Path.of("/work/repo/module-two/D.txt")));
    }
}
