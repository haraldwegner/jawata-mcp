package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.RecoveringExperienceStore;
import org.jawata.mcp.knowledge.StoredEntry;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ARCH-C22-7 — the workspace-limited listing is used by every layer that claims it: the describe
 * tool, the recovering wrapper, and the interface default.
 *
 * <p>The store-level test proves the QUERY fills its window with this workspace's rows. It cannot
 * prove anything calls it: the real window is ten thousand rows, so no tool-level fixture crowds it.
 * Here a store double makes the plain listing "crowded" (it answers only another workspace's rows,
 * as a full window would) while the workspace-limited read goes to the real store. A layer that
 * reverts to the plain listing then finds nothing of its own and the case goes red.</p>
 */
class TheWorkspaceListingIsWiredThroughEveryLayerTest {

    private H2ExperienceStore real;

    @BeforeEach
    void setUp() {
        real = H2ExperienceStore.openMemory();
    }

    @AfterEach
    void tearDown() {
        real.close();
    }

    private void job(String symbol, String summary) throws InterruptedException {
        real.put(ExperienceEntry.of(
            SymbolFact.of("job", summary, Confidence.MEDIUM).symbol(symbol).build()).build());
        Thread.sleep(8);
    }

    private void area(String pkg, String summary) throws InterruptedException {
        ObjectNode a = new ObjectMapper().createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary", summary);
        a.putArray("packages").add(pkg);
        assertTrue(new ExperienceTool(() -> null, real).execute(a).isSuccess(), "fixture area refused");
        Thread.sleep(8);
    }

    /** One own row (older) under three foreign rows (newer) for each of job and area. */
    private void crowdedStore() throws InterruptedException {
        real.setProvenance("orb-strategy", null);
        job("pipeline.SlotManager#freeSlot", "Frees a trading slot once its order has ended.");
        area("pipeline", "Where a strategy's slots are claimed, worked and released.");
        real.setProvenance("jawata", null);
        for (int i = 0; i < 3; i++) {
            job("org.jawata.A#m" + i, "A newer foreign job " + i + ".");
            area("org.jawata.pkg" + i, "A newer foreign area " + i + ".");
        }
        real.setProvenance("orb-strategy", null);
    }

    /**
     * A store whose plain listing answers as a FULL window of another workspace's rows would, and
     * whose workspace read, like everything else, is the real store's.
     */
    private ExperienceStore crowdedView() {
        InvocationHandler handler = (proxy, method, args) -> {
            try {
                if (method.getName().equals("listEntries")) {
                    String type = (String) args[0];
                    return real.listEntries(type, null, null, null, 1000).stream()
                        .filter(e -> !e.answersFor("orb-strategy")).limit(3).toList();
                }
                return method.invoke(real, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        };
        return (ExperienceStore) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {ExperienceStore.class}, handler);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> describing(ExperienceStore store) {
        ObjectNode a = new ObjectMapper().createObjectNode();
        a.put("kind", "stats");
        ToolResponse r = new ExperienceTool(() -> null, store).execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("describing");
    }

    @Test
    void theDescribeToolReadsThisWorkspacesRowsThroughTheWorkspaceListingNotThePlainWindow()
            throws InterruptedException {
        crowdedStore();

        Map<String, Object> block = describing(crowdedView());

        @SuppressWarnings("unchecked")
        List<String> areas = (List<String>) block.get("describedAreas");
        assertEquals(List.of("pipeline"), areas,
            "the described-areas list must come from the workspace listing: the plain window here"
                + " holds only foreign rows, so reading it finds nothing of this workspace's");
        assertEquals(1, ((Number) block.get("acceptedRows")).intValue(),
            "and so must the job count, which here is this workspace's one job and not the crowded"
                + " window's three foreign ones: " + block);
    }

    @Test
    void theRecoveringWrapperForwardsTheWorkspaceListingToTheRealStore() throws Exception {
        crowdedStore();
        RecoveringExperienceStore wrapped = new RecoveringExperienceStore(
            "test: the real open failed once", () -> real, 20L);
        long deadline = System.currentTimeMillis() + 10_000L;
        while (wrapped.degradedNotice() != null && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertTrue(wrapped.degradedNotice() == null,
            "CONTROL: the wrapper recovered onto the real store, or this proves nothing about the forward");

        List<String> here = wrapped.listEntriesHere("job", 2).stream().map(StoredEntry::symbolFqn)
            .collect(Collectors.toList());

        assertEquals(List.of("pipeline.SlotManager#freeSlot"), here,
            "a wrapper that cut the window first and filtered after would return nothing here");
    }

    @Test
    void theInterfaceDefaultStillFiltersWhateverPageItIsHanded() throws InterruptedException {
        crowdedStore();
        List<StoredEntry> page = real.listEntries("job", null, null, null, 10);
        InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
            case "listEntries" -> page;
            case "provenanceWorkspaceId" -> "orb-strategy";
            case "listEntriesHere" -> InvocationHandler.invokeDefault(proxy, method, args);
            default -> throw new UnsupportedOperationException(method.getName());
        };
        ExperienceStore store = (ExperienceStore) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {ExperienceStore.class}, handler);

        List<String> here = store.listEntriesHere("job", 10).stream().map(StoredEntry::symbolFqn)
            .collect(Collectors.toList());

        assertEquals(4, page.size(), "PROOF OF LIFE: the page holds the foreign rows too");
        assertEquals(List.of("pipeline.SlotManager#freeSlot"), here,
            "the default must drop the foreign rows from the page it is given");
    }

    @Test
    void aWorkspaceListingWithoutATypeIsRefusedByEveryImplementation() throws InterruptedException {
        crowdedStore();
        assertThrows(IllegalArgumentException.class, () -> real.listEntriesHere(null, 10));
        assertThrows(IllegalArgumentException.class, () -> real.listEntriesHere("  ", 10));
        InvocationHandler handler = (proxy, method, args) -> method.getName().equals("listEntriesHere")
            ? InvocationHandler.invokeDefault(proxy, method, args) : List.of();
        ExperienceStore bare = (ExperienceStore) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {ExperienceStore.class}, handler);
        assertThrows(IllegalArgumentException.class, () -> bare.listEntriesHere(null, 10),
            "the interface default refuses it too: with no type there is no lane to ask the rule of");
    }
}
