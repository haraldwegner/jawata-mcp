package org.jawata.mcp.tools.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 27, finding 28h-C27-16: the source-reading tool asked the loaded projects in load order and the first to find a type name answered, so the
 * Eclipse project's bundled jar of the engine answered a disassembled stub for a type whose source another loaded project owns. Projects that hold
 * the source are asked first; the others keep their order behind them.
 */
class LibrarySourceOrderTest {

    @Test
    @DisplayName("projects that hold a type's source are asked before the ones that hold only a class file of it, each group in its own order")
    void projectsWithTheSourceAreAskedFirst() {
        List<String> loaded = List.of("eclipse-with-jar", "other-jar", "mcp-with-source", "another-with-source");

        List<String> order = LibrarySource.sourceFirst(loaded, project -> project.endsWith("with-source"));

        assertEquals(List.of("mcp-with-source", "another-with-source", "eclipse-with-jar", "other-jar"), order);
        assertEquals(loaded, LibrarySource.sourceFirst(loaded, project -> false), "with no source anywhere the order is the load order");
        assertEquals(List.of(), LibrarySource.sourceFirst(List.<String>of(), project -> true));
    }
}
