package org.jawata.core;

import org.jawata.spi.host.LanguageBinding;

/**
 * What a host supplies to the Java provider (Sprint 28k, E14; contract §4.1): the Java model, the runtime a project runs on, and
 * whether the host already did the process-wide JDT UI setup.
 */
public interface JavaBinding extends LanguageBinding {

    /** Where the Java model comes from and how a project comes into it. */
    ModelSource model();

    /** The Java runtime a project runs on. */
    JavaRuntimes runtimes();

    /**
     * Whether the host has done the process-wide JDT UI setup itself. False when the engine runs alone, so
     * {@code HeadlessJdtConfig} initialises it; true inside an IDE, whose own JDT UI is already running.
     */
    boolean providesJdtUiSetup();
}
