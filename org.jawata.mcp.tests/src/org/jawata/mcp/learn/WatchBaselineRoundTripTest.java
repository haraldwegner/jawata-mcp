package org.jawata.mcp.learn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE DEFECT THE 4.0.3 DOGFOOD FOUND, pinned at its cause.
 *
 * <p>The change reviewer keyed its stored baseline on {@code "watch:" + absolute path}
 * and the column holding it is sixty characters wide. Real paths are longer, so every
 * write failed and the read mapped the missing row to an empty baseline — which meant
 * a file's whole pre-existing backlog was reported as newly introduced, on every edit,
 * permanently. The feature ran, raised nothing, and never remembered.</p>
 *
 * <p>This asserts the CAUSE rather than the symptom: the key must fit the column for a
 * path of realistic length. A test of the symptom would need a store, a file and two
 * edits; a test of the cause fails today and cannot pass by accident.</p>
 */
class WatchBaselineRoundTripTest {

    /** The width of learner_state.learner, which the key must respect. */
    private static final int COLUMN_WIDTH = 60;

    private static String key(String path) throws Exception {
        Method m = WatchEngine.class.getDeclaredMethod("baselineKey", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, path);
    }

    @Test
    @DisplayName("a realistic absolute path yields a key that fits the column")
    void aRealisticPathFits() throws Exception {
        String path = "/home/harald/CursorProjects/jawata-mcp/org.jawata.mcp/src/org/"
            + "jawata/mcp/knowledge/CatalogueSeeder.java";
        assertTrue(path.length() > COLUMN_WIDTH,
            "PROOF OF LIFE: the path must be longer than the column, or this test proves"
                + " nothing — it is exactly the length that broke the write");
        assertTrue(key(path).length() <= COLUMN_WIDTH,
            "the key must fit the column it is stored in; it was " + key(path).length()
                + " characters for a path of " + path.length());
    }

    @Test
    @DisplayName("the key is bounded whatever the path length")
    void theKeyIsBoundedByConstruction() throws Exception {
        String shallow = "/a.java";
        String deep = "/" + "very-long-directory-segment/".repeat(40) + "Thing.java";
        assertEquals(key(shallow).length(), key(deep).length(),
            "a digest is fixed width: a deeper path must not produce a longer key");
        assertTrue(key(deep).length() <= COLUMN_WIDTH, "and it must fit the column");
    }

    @Test
    @DisplayName("different files get different keys, and the same file the same one")
    void keysIdentifyTheFile() throws Exception {
        String one = "/home/harald/CursorProjects/jawata-mcp/src/org/jawata/Alpha.java";
        String two = "/home/harald/CursorProjects/jawata-mcp/src/org/jawata/Beta.java";
        assertNotEquals(key(one), key(two),
            "two files sharing a baseline would report each other's findings");
        assertEquals(key(one), key(one),
            "the same file must key the same way, or nothing is ever found again");
        assertTrue(key(one).startsWith("watch:"),
            "the prefix says which learner owns the row, which is why it is readable");
    }
}
