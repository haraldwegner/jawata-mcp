package com.example;

import java.util.List;
import java.util.Map;

/**
 * Sprint 17 fixture — Incomplete Delegation detector (REFACTORING_LESSONS_LEARNED §7).
 * {@code scanRoute} mirrors the Slot/SlotManager tell: a loop recovering the
 * element's identity via {@code slot.getAlgo().request().symbol()} → flagged.
 * {@code indexedRoute} gives the manager a key->items index → not flagged.
 */
public class IncompleteDelegationTargets {
}

class IdRequest {
    String symbol() {
        return "";
    }
}

class IdAlgo {
    IdRequest request() {
        return new IdRequest();
    }
}

class IdSlot {
    IdAlgo getAlgo() {
        return new IdAlgo();
    }

    void onTick(String symbol) {
    }
}

class IdScanManager {
    List<IdSlot> slots;

    // The §7 anti-pattern: O(n) scan recovering identity from a collaborator chain.
    void scanRoute(String symbol) {
        for (IdSlot slot : slots) {
            IdAlgo algo = slot.getAlgo();
            if (algo != null && algo.request() != null
                    && algo.request().symbol().equals(symbol)) {
                slot.onTick(symbol);
            }
        }
    }
}

class IdIndexedManager {
    Map<String, List<IdSlot>> slotsBySymbol;

    // The §7 fix: the manager owns a key->items index; no scan, no getter archaeology.
    void indexedRoute(String symbol) {
        List<IdSlot> matches = slotsBySymbol.get(symbol);
        if (matches != null) {
            for (IdSlot slot : matches) {
                slot.onTick(symbol);
            }
        }
    }
}
