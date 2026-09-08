package org.jawata.mcp.tools.smell;

import org.jawata.mcp.knowledge.CatalogueAddresses;
import org.jawata.mcp.knowledge.CatalogueOrigin;
import org.jawata.mcp.knowledge.CatalogueSeeder;
import org.jawata.mcp.knowledge.CatalogueSources;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#67 — <b>the drift sweep re-resolved KEYS and never looked at the ADDRESS it
 * would hand a reader.</b>
 *
 * <p>{@code CureLookup.audit} asked {@code addresses.resolves(operation)} — does any live row
 * still carry this key. {@code CatalogueOrigin} states the obligation it was built for: a FOREIGN
 * source is pinned to somebody else's commit, so its content can change under us and its
 * addresses must be re-resolved when the pin moves. A pin that moves and renames a path while
 * keeping the pattern's operation key satisfies the key check exactly, so the sweep reported
 * {@code clean: true} while every cure for that kind pointed at a dead address.</p>
 *
 * <p><b>The issue reports two gaps and they are one gap seen twice.</b> The second — nothing
 * detects the move, because {@code authorities()} was reported into the stats block and compared
 * against nothing (measured: ONE reference, the line that writes it) — has the same cause. A
 * renamed path and a correct path are indistinguishable by inspection, so neither an address nor
 * an authority can be judged to have moved without a previous value. The fix is therefore one
 * thing, not two: a baseline.</p>
 *
 * <p><b>What makes each case falsifiable is the {@code unresolved == 0} clause.</b> It asserts
 * that the KEY still resolves — the issue's own premise — so the move is being caught by
 * something the old check provably could not see. Without it, a case could pass because the key
 * had broken, which is the state the sweep already reported.</p>
 */
class TheDriftSweepSeesAMovedAddressTest {

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        for (CatalogueOrigin o : CatalogueSources.all()) {
            CatalogueSeeder.seed(store, o);
        }
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /** The sweep as it stands, with no baseline — also the "before" half of every case below. */
    private CureLookup.Audit sweep() {
        CureLookup.Audit audit = CureLookup.audit(store);
        CatalogueAddresses live = CatalogueAddresses.of(store);
        assertAll(
            // PROOF OF LIFE before any zero. A sweep over an empty catalogue reports nothing
            // moved for a reason that has nothing to do with the fix.
            () -> assertFalse(audit.addresses().isEmpty(),
                "the sweep must carry the addresses it re-resolved: " + audit),
            () -> assertFalse(audit.authorities().isEmpty(),
                "and the authorities it read: " + audit),
            () -> assertEquals(audit.resolved(), audit.addresses().size(),
                "one address per resolved cure: " + audit),
            // And the value must be the ROW'S OWN source_ref rather than the key it was found
            // by — which is the whole distinction this issue is about. Every case below builds
            // its baseline from this map, so a map of keys wearing an address's name would let
            // them all pass while proving nothing about addresses.
            () -> audit.addresses().forEach((operation, sourceRef) -> assertEquals(
                live.address(operation).sourceRef(), sourceRef,
                () -> "the address carried for '" + operation + "' must be the row's source_ref")),
            () -> assertTrue(audit.addresses().entrySet().stream()
                    .anyMatch(e -> !e.getKey().equals(e.getValue())),
                "and an address is not its own key: " + audit.addresses()));
        return audit;
    }

    @Test
    @DisplayName("mcp#67: a pin that renames a path while keeping the key is NAMED, not reported clean")
    void aMovedAddressIsNamedWhereAKeyCheckSeesNothing() {
        CureLookup.Audit before = sweep();
        String operation = before.addresses().keySet().iterator().next();
        String wasAt = before.addresses().get(operation);

        // The upstream rename, expressed exactly as the issue describes it: same operation key,
        // different path. The store is untouched — what changed is what we knew LAST time.
        Map<String, String> pinned = new LinkedHashMap<>(before.addresses());
        pinned.put(operation, wasAt + "-RENAMED-UPSTREAM");

        CureLookup.Audit after = CureLookup.audit(store, CureCatalog.declaredOperations(),
            new CureLookup.Baseline(pinned, before.authorities()));

        assertAll(
            // The premise, and the discriminator: the KEY still resolves. This is the exact
            // state in which the old sweep answered "clean".
            () -> assertEquals(0, after.unresolved(),
                "every key still resolves — the move is invisible to a key check: " + after),
            () -> assertTrue(before.clean(),
                "and the same store with no baseline still reports clean, which is the defect"),
            () -> assertEquals(List.of(operation), after.movedOperations(),
                "the moved cure is NAMED: " + after),
            () -> assertFalse(after.clean(),
                "so the sweep is no longer clean: " + after));
    }

    @Test
    @DisplayName("mcp#67: a moved authority is named — the pin itself, which nothing compared before")
    void aMovedAuthorityIsNamed() {
        CureLookup.Audit before = sweep();
        String namespace = before.authorities().keySet().iterator().next();

        Map<String, String> pinned = new LinkedHashMap<>(before.authorities());
        pinned.put(namespace, "some-other-commit");

        CureLookup.Audit after = CureLookup.audit(store, CureCatalog.declaredOperations(),
            new CureLookup.Baseline(before.addresses(), pinned));

        assertAll(
            () -> assertEquals(0, after.unresolved(),
                "resolution is untouched — this is the axis it never covered: " + after),
            () -> assertEquals(List.of(namespace), after.movedAuthorities(),
                "the namespace whose authority moved is NAMED: " + after),
            () -> assertFalse(after.clean(), "and the sweep is not clean: " + after));
    }

    /**
     * The control. Without it, a sweep that reported everything as moved would satisfy both
     * cases above and look exactly like the fix.
     */
    @Test
    @DisplayName("mcp#67 control: with no baseline nothing is moved, and clean means what it did")
    void withNoBaselineTheAnswerIsUnchanged() {
        CureLookup.Audit audit = sweep();

        assertAll(
            () -> assertEquals(List.of(), audit.movedOperations(),
                "nothing can have moved against no baseline: " + audit),
            () -> assertEquals(List.of(), audit.movedAuthorities(), "the same for authorities"),
            () -> assertTrue(audit.clean(),
                "so the two existing entry points answer exactly what they always did"));
    }

    /**
     * The boundary the move rule claims: only a key present on BOTH sides with a different value
     * is a move. A key the baseline alone carries has gone, and the unresolved count already
     * speaks for that — reporting it twice would make one repair look like two problems.
     */
    @Test
    @DisplayName("mcp#67: a key the current answer no longer carries is unresolved, not moved")
    void aVanishedKeyIsNotReportedAsAMove() {
        CureLookup.Audit before = sweep();

        Map<String, String> pinned = new LinkedHashMap<>(before.addresses());
        pinned.put("design:retired-upstream", "catalogue:foreign/retired/README.md");

        CureLookup.Audit after = CureLookup.audit(store, CureCatalog.declaredOperations(),
            new CureLookup.Baseline(pinned, before.authorities()));

        assertEquals(List.of(), after.movedOperations(),
            "a key the store no longer answers with has not MOVED, it is gone: " + after);
    }
}
