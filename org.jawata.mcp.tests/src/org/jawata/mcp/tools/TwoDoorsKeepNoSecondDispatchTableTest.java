package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28g, finding C4-1 — <b>a door has ONE routing table, and it is the one it publishes.</b>
 *
 * <p>{@code data} and {@code change_method_signature} used to look their delegate up in
 * {@code delegates()}, discard it, and route through an eleven-arm {@code switch} on the same
 * string, with an "unreachable" default arm guarding the day the two disagreed. Two routing tables
 * for one fact. They now dispatch through the delegate they just looked up.</p>
 *
 * <h2>How the claim is checked, and why not by running the doors</h2>
 *
 * <p>The old and the new code behave identically for every published kind — that is what made the
 * duplication safe to leave. A behavioural test is green on both. What differs is the compiled
 * class: a {@code switch} on a string leaves each kind name in the class's constant pool as its
 * own entry, and a door that dispatches through its table holds none, because the names live in
 * the delegates ({@code kindName()}). So the test reads the constant pool and fails if a door
 * still carries one of its own kind names as a constant.</p>
 *
 * <p><b>Scoped to these two doors on purpose.</b> {@code extract} builds its table with
 * {@code d.put("method", ...)}, so its kind names ARE constants — as the table itself, not a
 * second copy of it. A rule over every door would call that a violation. These two are the ones
 * that were measured holding both.</p>
 */
class TwoDoorsKeepNoSecondDispatchTableTest {

    @Test
    void dataAndChangeMethodSignatureCarryNoKindNameOfTheirOwn() throws IOException {
        Supplier<IJdtService> none = () -> null;
        List<AbstractTool> doors = RefactoringDoors.all(none, new RefactoringChangeCache());
        int checked = 0;
        for (AbstractTool door : doors) {
            if (!(door instanceof DataTool) && !(door instanceof ChangeMethodSignatureTool)) {
                continue;
            }
            checked++;
            List<String> kinds = ((KindedTool) door).publishedKinds();
            assertTrue(kinds.size() >= 10, door.getName() + " publishes its kinds: " + kinds);
            Set<String> constants = utf8Constants(door.getClass());
            for (String kind : kinds) {
                assertFalse(constants.contains(kind),
                    door.getName() + " still holds '" + kind + "' as a constant of its own class — "
                        + "a second routing table beside delegates()");
            }
        }
        assertTrue(checked == 2, "both doors were found and checked, not skipped: " + checked);
    }

    @Test
    void theReaderSeesAConstantThatIsThere() throws IOException {
        // The control the first test cannot give itself: a reader that returned an empty set for
        // every class would pass it. ExtractTool builds its table with put("method", ...), so its
        // class MUST hold "method" as a constant.
        Set<String> constants = utf8Constants(ExtractTool.class);
        assertTrue(constants.contains("method"),
            "the constant-pool reader finds a name the class demonstrably holds");
    }

    /** Every CONSTANT_Utf8 entry of the class file, read straight from the bytes. */
    private static Set<String> utf8Constants(Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(in != null, "class file readable: " + resource);
            DataInputStream data = new DataInputStream(in);
            data.readInt();            // magic
            data.readUnsignedShort();  // minor
            data.readUnsignedShort();  // major
            int count = data.readUnsignedShort();
            Set<String> utf8 = new HashSet<>();
            for (int i = 1; i < count; i++) {
                int tag = data.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8.add(data.readUTF());
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> data.skipNBytes(4);
                    case 5, 6 -> {
                        data.skipNBytes(8);
                        i++; // a long or double takes two slots
                    }
                    case 7, 8, 16, 19, 20 -> data.skipNBytes(2);
                    case 15 -> data.skipNBytes(3);
                    default -> throw new IOException("unknown constant-pool tag " + tag);
                }
            }
            return utf8;
        }
    }
}
