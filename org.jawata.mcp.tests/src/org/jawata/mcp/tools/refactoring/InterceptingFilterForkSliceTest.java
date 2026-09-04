package org.jawata.mcp.tools.refactoring;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 17 — Inline Class — ON CODE WE DID NOT AUTHOR.
 *
 * <p>{@code FilterChain} is upstream's own class, and the reason it can carry this row is a
 * measurement rather than a choice: {@code find_references} over the whole fork returns
 * THREE references to it and all three are inside {@code FilterManager.java}. The manager
 * holds it as {@code private final FilterChain filterChain} and forwards both of its
 * methods. See {@code PROVENANCE.md} beside the slice.</p>
 *
 * <h2>What a fixture could not have shown here</h2>
 *
 * <p>A fixture written beside this row would have had one field and one forwarded method,
 * because that is the smallest thing that demonstrates the operation. Upstream's class has
 * a field whose type is an INTERFACE from the same package, a null-guarded branch in each
 * method, and a caller that constructs it in its own constructor rather than taking it as
 * a parameter — three things nobody writing a demonstration would think to include, and
 * every one of them is a thing the rewrite has to carry across.</p>
 */
class InterceptingFilterForkSliceTest {

    private static final String PKG = "com/iluwatar/intercepting/filter";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ForkSliceSupport.Slice slice;

    @BeforeEach
    void setUp() throws Exception {
        slice = ForkSliceSupport.load(helper, "fork-intercepting-filter", PKG,
            "FilterManager.java", "private final FilterChain filterChain;");
    }

    @Test
    @DisplayName("row 17 on fork code: FilterChain folds into its one client, FilterManager")
    void inlineClassOnForkCode() throws Exception {
        String chainBefore = slice.read("FilterChain.java");
        assertTrue(chainBefore.contains("private Filter chain;"),
            "PROOF OF LIFE: upstream's FilterChain must still hold the state this row has to"
                + " move, or the assertions below pass over an empty rewrite:\n" + chainBefore);

        ToolResponse r = slice.door("inline")
            .execute(slice.at("class", "FilterChain.java", "public class FilterChain", 13));
        assertTrue(r.isSuccess(), () -> "row 17 refused real upstream code: " + r.getError());

        String manager = slice.read("FilterManager.java");
        // The STATE arrives. A rewrite that folded only the methods would leave the manager
        // referring to a field that no longer exists anywhere, and it would still compile if
        // the old class were left behind — which is why the deletion is asserted too.
        assertTrue(manager.contains("private Filter chain;"),
            "FilterChain's own field must land in the class that absorbed it:\n" + manager);
        assertFalse(manager.contains("filterChain"),
            "and no code reference to the holder may survive:\n" + manager);
        assertFalse(manager.contains("{@link FilterChain}"),
            "AND NO DANGLING JAVADOC LINK. Upstream documents FilterManager as managing"
                + " \"the filters and {@link FilterChain}\", and deleting the class turns that"
                + " into a link to nothing. The compile gate cannot see it — javadoc is a"
                + " comment — so this is a defect a green pipeline would have shipped, and it"
                + " is exactly the kind only foreign code carrying real documentation has."
                + " The tag is unwrapped to prose rather than deleted, because the sentence"
                + " is still true and rewriting somebody's prose is not this row's business:\n"
                + manager);
        assertTrue(manager.contains("and FilterChain."),
            "the name survives as plain text, so the sentence still reads:\n" + manager);
        assertFalse(Files.exists(slice.pkg().resolve("FilterChain.java")),
            "the inlined class's file is removed — an Inline Class that leaves the class"
                + " behind has produced a copy, not a fold");

        // Upstream's null guard is the half a minimal fixture would not have had. Both of
        // FilterChain's methods branch on the field being unset, and both branches have to
        // survive the move intact or the manager's behaviour changes on an empty chain.
        assertTrue(manager.contains("RUNNING..."),
            "the guarded branch upstream wrote must survive the fold verbatim:\n" + manager);
        assertTrue(manager.contains("chain.getLast().setNext(filter)"),
            "and so must the append path through the Filter interface:\n" + manager);
    }
}
