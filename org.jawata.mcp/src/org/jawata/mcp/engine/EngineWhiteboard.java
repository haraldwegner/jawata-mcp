package org.jawata.mcp.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jawata.spi.lang.LanguageProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place the engine learns of what plugs into it (Sprint 28k, E19; contract §7, the Whiteboard pattern): the faces that
 * attach to a running engine and the language providers that add a language to it. The engine does not look for them and does not
 * change when one more arrives; whoever finds a service, the OSGi tracker in a host or a test calling these methods directly,
 * says so here.
 *
 * <p>A face is attached when the engine has started and detached when it stops or the face is withdrawn, once each. A provider is
 * only listed, in arrival order, and whoever asked to be told ({@link #whenProvidersChange}) hears each arrival and departure.</p>
 */
public final class EngineWhiteboard {

    private static final Logger log = LoggerFactory.getLogger(EngineWhiteboard.class);

    /** Every face that is bound, in arrival order. */
    private final List<EngineFace> faces = new CopyOnWriteArrayList<>();

    /** The faces that are attached now, so none is detached twice or detached without having attached. */
    private final List<EngineFace> attached = new CopyOnWriteArrayList<>();

    private final CopyOnWriteArrayList<LanguageProvider> providers = new CopyOnWriteArrayList<>();
    private final List<Runnable> providerListeners = new CopyOnWriteArrayList<>();

    /** The handle faces are given, or null while the engine is not running. */
    private volatile EngineApi running;

    /** A face has arrived; it is attached at once when the engine is already running, otherwise when it starts. */
    public synchronized void bindFace(EngineFace face) {
        if (faces.contains(face)) {
            return;
        }
        faces.add(face);
        if (running != null) {
            attach(face, running);
        }
    }

    /** A face has gone; it is detached now if it was attached. */
    public synchronized void unbindFace(EngineFace face) {
        faces.remove(face);
        detach(face);
    }

    /** A language provider has arrived. Binding one already listed changes nothing. */
    public void bindProvider(LanguageProvider provider) {
        if (providers.addIfAbsent(provider)) {
            changed();
        }
    }

    /** A language provider has gone. */
    public void unbindProvider(LanguageProvider provider) {
        if (providers.remove(provider)) {
            changed();
        }
    }

    /** The providers bound now, in the order they arrived. */
    public List<LanguageProvider> providers() {
        return List.copyOf(providers);
    }

    /** Asks to be told after each provider arrives or leaves. */
    public void whenProvidersChange(Runnable listener) {
        providerListeners.add(listener);
    }

    /** The engine has started: every bound face attaches, and a face bound from now on attaches as it arrives. */
    public synchronized void started(EngineApi api) {
        running = api;
        for (EngineFace face : new ArrayList<>(faces)) {
            attach(face, api);
        }
    }

    /** The engine is stopping: every attached face detaches. Calling it again does nothing. */
    public synchronized void stopping() {
        running = null;
        for (EngineFace face : new ArrayList<>(attached)) {
            detach(face);
        }
    }

    private void attach(EngineFace face, EngineApi api) {
        try {
            face.attach(api);
            attached.add(face);
            log.info("Face '{}' attached", face.name());
        } catch (RuntimeException e) {
            log.error("Face '{}' failed to attach; the others still attach", face.name(), e);
        }
    }

    private void detach(EngineFace face) {
        if (!attached.remove(face)) {
            return;
        }
        try {
            face.detach();
            log.info("Face '{}' detached", face.name());
        } catch (RuntimeException e) {
            log.error("Face '{}' failed to detach", face.name(), e);
        }
    }

    private void changed() {
        for (Runnable listener : providerListeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                log.error("A listener of the language providers failed", e);
            }
        }
    }
}
