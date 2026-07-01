package com.example;

/**
 * Sprint 19 fixture — Singleton detector + inline_singleton transform.
 * {@code Registry} is a GoF singleton (private ctor + static self-holder +
 * getInstance) → flagged, and inline_singleton rewrites its callers to
 * {@code new Registry()} + makes the ctor public + strips the scaffolding.
 * {@code PlainService} is a normal class → not flagged.
 */
public class SingletonTargets {
}

class Registry {
    private static final Registry INSTANCE = new Registry();

    private Registry() {
    }

    static Registry getInstance() {
        return INSTANCE;
    }

    String lookup(String key) {
        return key.toUpperCase();
    }
}

class RegistryUser1 {
    String run(String k) {
        return Registry.getInstance().lookup(k);
    }
}

class RegistryUser2 {
    String run(String k) {
        Registry r = Registry.getInstance();
        return r.lookup(k);
    }
}

class PlainService {
    private int state;

    int next() {
        return ++state;
    }
}
