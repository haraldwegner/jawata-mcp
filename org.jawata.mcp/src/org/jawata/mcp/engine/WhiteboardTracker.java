package org.jawata.mcp.engine;

import java.util.function.Consumer;

import org.jawata.spi.lang.LanguageProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.util.tracker.ServiceTracker;
import org.osgi.util.tracker.ServiceTrackerCustomizer;

/**
 * Finds the OSGi services that plug into the engine and tells the {@link EngineWhiteboard} (Sprint 28k, E19; contract §7): every
 * {@link EngineFace} and every {@link LanguageProvider} registered in the framework, those already there when it opens and those
 * that arrive or leave afterwards. The whiteboard itself knows nothing of OSGi, so a test drives it directly and a host that is not
 * OSGi could drive it another way.
 */
public final class WhiteboardTracker implements AutoCloseable {

    private final ServiceTracker<EngineFace, EngineFace> faces;
    private final ServiceTracker<LanguageProvider, LanguageProvider> providers;

    /** A tracker that reports into {@code board}, for the services visible from {@code context}. Nothing is tracked until it is opened. */
    public WhiteboardTracker(BundleContext context, EngineWhiteboard board) {
        this.faces = track(context, EngineFace.class, board::bindFace, board::unbindFace);
        this.providers = track(context, LanguageProvider.class, board::bindProvider, board::unbindProvider);
    }

    /** Starts tracking: the languages first, so a face that attaches at once finds the engine's tools complete. */
    public void open() {
        providers.open();
        faces.open();
    }

    /** Stops tracking and lets go of every service it was holding. */
    @Override
    public void close() {
        faces.close();
        providers.close();
    }

    private static <T> ServiceTracker<T, T> track(BundleContext context, Class<T> type, Consumer<T> arrived, Consumer<T> left) {
        return new ServiceTracker<>(context, type, new ServiceTrackerCustomizer<T, T>() {
            @Override
            public T addingService(ServiceReference<T> reference) {
                T service = context.getService(reference);
                if (service != null) {
                    arrived.accept(service);
                }
                return service;
            }

            @Override
            public void modifiedService(ServiceReference<T> reference, T service) {
                // A change of properties changes nothing the engine reads.
            }

            @Override
            public void removedService(ServiceReference<T> reference, T service) {
                left.accept(service);
                context.ungetService(reference);
            }
        });
    }
}
