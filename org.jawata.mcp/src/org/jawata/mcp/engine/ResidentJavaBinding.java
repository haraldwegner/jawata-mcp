package org.jawata.mcp.engine;

import java.nio.file.Path;

import org.jawata.core.HeadlessModelSource;
import org.jawata.core.JavaBinding;
import org.jawata.core.JavaRuntimes;
import org.jawata.core.ModelSource;

/**
 * What the engine that runs alone supplies to the Java provider (Sprint 28k, E14; contract §5): a model built from project folders,
 * the runtime the engine itself runs on, and no JDT UI setup done for it.
 */
public final class ResidentJavaBinding implements JavaBinding {

    private final HeadlessModelSource model;

    /** @param model the model the engine's boot paths and {@code load_project} put their service in */
    public ResidentJavaBinding(HeadlessModelSource model) {
        this.model = model;
    }

    @Override
    public ModelSource model() {
        return model;
    }

    @Override
    public JavaRuntimes runtimes() {
        return project -> Path.of(System.getProperty("java.home"));
    }

    @Override
    public boolean providesJdtUiSetup() {
        return false;
    }
}
