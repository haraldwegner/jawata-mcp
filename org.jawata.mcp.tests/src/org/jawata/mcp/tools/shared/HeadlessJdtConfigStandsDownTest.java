package org.jawata.mcp.tools.shared;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.eclipse.jdt.core.manipulation.JavaManipulation;
import org.jawata.core.JavaBinding;
import org.jawata.core.JavaRuntimes;
import org.jawata.core.ModelSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28k, E15: the process-wide JDT setup is the host's to do when the host has a JDT UI of its own. The engine that runs alone
 * does it ({@code HeadlessJdtConfig}); inside an IDE the IDE's own setup is already running and a second one would overwrite it.
 */
class HeadlessJdtConfigStandsDownTest {

    private static JavaBinding binding(boolean providesJdtUiSetup) {
        return new JavaBinding() {
            @Override
            public ModelSource model() {
                return null;
            }

            @Override
            public JavaRuntimes runtimes() {
                return null;
            }

            @Override
            public boolean providesJdtUiSetup() {
                return providesJdtUiSetup;
            }
        };
    }

    @AfterEach
    void backToTheEngineThatRunsAlone() {
        // The setup is process-wide, so whatever a test left is what every later test finds.
        HeadlessJdtConfig.hostedBy(binding(false));
        HeadlessJdtConfig.ensureInitialized();
    }

    @Test
    @DisplayName("ensureInitialized installs nothing when the host provides the JDT UI setup")
    void standsDownWhenTheHostProvidesTheSetup() {
        HeadlessJdtConfig.ensureInitialized();
        HeadlessJdtConfig.hostedBy(binding(true));
        JavaManipulation.setCodeTemplateStore(null);

        HeadlessJdtConfig.ensureInitialized();

        assertNull(JavaManipulation.getCodeTemplateStore(),
            "the host owns the setup, so the engine must not install a code-template store over it");
    }

    @Test
    @DisplayName("ensureInitialized reinstalls the code-template store when the engine runs alone")
    void installsWhenTheEngineRunsAlone() {
        HeadlessJdtConfig.hostedBy(binding(false));
        JavaManipulation.setCodeTemplateStore(null);

        HeadlessJdtConfig.ensureInitialized();

        assertNotNull(JavaManipulation.getCodeTemplateStore(),
            "the engine that runs alone does the setup itself");
    }
}
