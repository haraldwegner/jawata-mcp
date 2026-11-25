package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The stored brain and model are one pair. A missing pair is a refusal, and Claude is not filled in. */
class SeatBrainsTest {

    private static final String REGISTRY = "{\"cursor\":{\"vendor\":\"cursor\",\"model\":\"registry-model\"},"
        + "\"claude-code\":{\"vendor\":\"anthropic\",\"model\":\"Haiku 5.5\"}}";

    @Test
    @DisplayName("a stored cursor and model is the pair, and a seat file is not read here")
    void aStoredPairIsTheChoice() {
        SeatBrains.Choice choice = SeatBrains.choose("developer=cursor/stored-model", REGISTRY, "developer");
        assertAll(
            () -> assertEquals("cursor", choice.brain()),
            () -> assertEquals("stored-model", choice.model()),
            () -> assertFalse(choice.modelTakenFromRegistry()));
    }

    @Test
    @DisplayName("a stored claude-code and Haiku 5.5 is that pair, whichever seat is named")
    void aStoredClaudeAndHaikuIsThatPair() {
        SeatBrains.Choice choice = SeatBrains.choose("pm=claude-code/Haiku 5.5", REGISTRY, "pm");
        assertEquals("claude-code", choice.brain());
        assertEquals("Haiku 5.5", choice.model());
    }

    @Test
    @DisplayName("nothing stored is a refusal that names the seat and where to set it")
    void nothingStoredIsARefusal() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SeatBrains.choose("", REGISTRY, "developer"));
        assertTrue(refused.getMessage().contains("developer"), refused.getMessage());
        assertTrue(refused.getMessage().contains("Seats page"), refused.getMessage());
        assertFalse(refused.getMessage().contains("claude-code"), refused.getMessage());
    }

    @Test
    @DisplayName("a brain the registry does not offer is refused by name")
    void aBrainTheRegistryDoesNotOfferIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SeatBrains.choose("developer=codex/x", REGISTRY, "developer"));
        assertTrue(refused.getMessage().contains("codex"), refused.getMessage());
    }

    @Test
    @DisplayName("a stored brain and no model uses the registry's model and says so")
    void aBrainWithNoModelUsesTheRegistryModel() {
        SeatBrains.Choice choice = SeatBrains.choose("developer=cursor", REGISTRY, "developer");
        assertEquals("cursor", choice.brain());
        assertEquals("registry-model", choice.model());
        assertTrue(choice.modelTakenFromRegistry());
    }

    @Test
    @DisplayName("a stored brain whose registry names no model is refused")
    void aBrainWhoseRegistryNamesNoModelIsRefused() {
        String registry = "{\"cursor\":{\"vendor\":\"cursor\"}}";
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SeatBrains.choose("developer=cursor", registry, "developer"));
        assertTrue(refused.getMessage().contains("cursor"), refused.getMessage());
        assertTrue(refused.getMessage().contains("no model"), refused.getMessage());
    }

    @Test
    @DisplayName("a store that cannot be read is a refusal, not Claude")
    void anUnreadableStoreIsARefusal() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SeatBrains.choose(null, REGISTRY, "developer"));
        assertTrue(refused.getMessage().contains("could not be read"), refused.getMessage());
        assertFalse(refused.getMessage().toLowerCase().contains("claude-code") && refused.getMessage().contains("run on"), refused.getMessage());
    }

    @Test
    @DisplayName("a corrupted store refuses the hire and is not read as empty")
    void aCorruptedStoreRefusesTheHire() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> SeatBrains.choose("this is not a seat map", REGISTRY, "developer"));
        assertTrue(refused.getMessage().contains("could not be read"), refused.getMessage());
    }

    @Test
    @DisplayName("many threads saving different seats lose none, and a reader never mixes one seat's brain with another's model")
    void writersDoNotLoseOrMixAPair() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("seat-brains-");
        SeatBrains.Store store = SeatBrains.Store.open(dir.resolve("choices"));
        String registry = "{\"cursor\":{\"vendor\":\"c\",\"model\":\"m\"},\"claude-code\":{\"vendor\":\"a\",\"model\":\"h\"}}";
        int seats = 32;
        Thread[] writers = new Thread[seats];
        for (int i = 0; i < seats; i++) {
            int n = i;
            writers[i] = new Thread(() -> store.save("s" + n, n % 2 == 0 ? "cursor" : "claude-code", "m" + n, registry));
        }
        for (Thread writer : writers) {
            writer.start();
        }
        for (Thread writer : writers) {
            writer.join();
        }
        java.util.concurrent.atomic.AtomicBoolean mixed = new java.util.concurrent.atomic.AtomicBoolean();
        Thread flipper = new Thread(() -> {
            for (int i = 0; i < 200; i++) {
                store.save("flip", i % 2 == 0 ? "cursor" : "claude-code", i % 2 == 0 ? "left" : "right", registry);
            }
        });
        Thread[] readers = new Thread[8];
        flipper.start();
        for (int i = 0; i < readers.length; i++) {
            readers[i] = new Thread(() -> {
                for (int n = 0; n < 200; n++) {
                    try {
                        SeatBrains.Choice choice = store.choose(registry, "flip");
                        boolean pair = ("cursor".equals(choice.brain()) && "left".equals(choice.model()))
                            || ("claude-code".equals(choice.brain()) && "right".equals(choice.model()));
                        if (!pair) {
                            mixed.set(true);
                        }
                    } catch (IllegalArgumentException notYet) {
                        // the flip seat is absent until the first save
                    }
                }
            });
            readers[i].start();
        }
        flipper.join();
        for (Thread reader : readers) {
            reader.join();
        }
        assertFalse(mixed.get(), "a hire took the brain of one value and the model of another");
        for (int i = 0; i < seats; i++) {
            SeatBrains.Choice choice = store.choose(registry, "s" + i);
            assertEquals(i % 2 == 0 ? "cursor" : "claude-code", choice.brain(), "seat s" + i);
            assertEquals("m" + i, choice.model(), "seat s" + i);
        }
        SeatBrains.Choice after = store.choose(registry, "s0");
        store.save("s0", "claude-code", "after", registry);
        assertEquals("after", store.choose(registry, "s0").model());
        assertEquals("cursor", after.brain(), "a choice already read does not change");
        String onDisk = java.nio.file.Files.readString(dir.resolve("choices"));
        assertTrue(onDisk.contains("s0=claude-code/after"), onDisk);
        assertFalse(java.nio.file.Files.exists(dir.resolve("choices.tmp")));
    }

    @Test
    @DisplayName("a model name that contains a semicolon or a slash is stored and read back as that name")
    void aModelNameMayContainASemicolon() {
        String stored = SeatBrains.put("", "developer", "claude-code", "Haiku; 5.5");
        SeatBrains.Choice choice = SeatBrains.choose(stored, REGISTRY, "developer");
        assertEquals("Haiku; 5.5", choice.model(), stored);
        String withSlash = SeatBrains.put(stored, "pm", "cursor", "a/b");
        assertEquals("a/b", SeatBrains.choose(withSlash, REGISTRY, "pm").model(), withSlash);
        assertEquals("Haiku; 5.5", SeatBrains.choose(withSlash, REGISTRY, "developer").model(), withSlash);
    }

    @Test
    @DisplayName("an unreadable file refuses a hire and does not block the next save")
    void anUnreadableFileDoesNotBlockTheNextSave() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("seat-brains-bad-");
        Path file = dir.resolve("choices");
        java.nio.file.Files.writeString(file, "this is not a seat map");
        SeatBrains.Store store = SeatBrains.install(file);
        try {
            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> store.choose(REGISTRY, "developer"));
            assertTrue(refused.getMessage().contains("could not be read"), refused.getMessage());
            SeatBrains.Notice notice = store.save("developer", "claude-code", "Haiku; 5.5", REGISTRY);
            assertEquals("Haiku; 5.5", notice.model());
            assertEquals("Haiku; 5.5", store.choose(REGISTRY, "developer").model());
        } finally {
            SeatBrains.uninstall();
        }
    }

    @Test
    @DisplayName("28h z78: the chat's pick of a brain with no model, where the registry names none, is kept for the next conversation; a hire of it is still refused")
    void aPickWithNoModelIsKeptAndAHireOfItIsStillRefused() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("seat-brains-pick-");
        SeatBrains.Store store = SeatBrains.Store.open(dir.resolve("choices"));
        String noModel = "{\"claude-code\":{\"command\":[\"x\"]},\"cursor\":{\"command\":[\"y\"]}}";

        SeatBrains.Notice notice = store.keepPick("main", "cursor", noModel);

        assertEquals("cursor", SeatBrains.listed(store.text()).get(0).brain());
        assertEquals("", notice.model());
        assertThrows(IllegalArgumentException.class, () -> store.choose(noModel, "main"), "a hire with no model was let through");
        assertThrows(IllegalArgumentException.class, () -> store.keepPick("main", "grok", noModel), "CONTROL: a brain the registry does not offer was kept");
    }

    @Test
    @DisplayName("a save tells each listener the pair that was stored")
    void aSaveTellsTheListener() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("seat-brains-hear-");
        SeatBrains.Store store = SeatBrains.Store.open(dir.resolve("choices"));
        java.util.List<SeatBrains.Notice> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        store.listen(heard::add);
        store.save("pm", "claude-code", "Haiku 5.5", REGISTRY);
        assertEquals(1, heard.size());
        assertEquals("pm", heard.get(0).seat());
        assertEquals("claude-code", heard.get(0).brain());
        assertEquals("Haiku 5.5", heard.get(0).model());
    }
}
