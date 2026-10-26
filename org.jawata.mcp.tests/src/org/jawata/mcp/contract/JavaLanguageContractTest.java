package org.jawata.mcp.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.java.JavaProvider;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.resident.workspace.OwnedReconciler;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * The language-side contracts (Sprint 28k, Stage 8; contract §18 ring 1) run against the Java provider, over a project loaded from a
 * fixture copy. {@link StandInLanguageContractTest} runs the same contracts against a language with no Java tooling at all.
 */
class JavaLanguageContractTest {

    /** The ports the host hands a provider; the Java provider reads neither for what is asked here. */
    private static final ProviderContext NO_HOST = new ProviderContext() {
        @Override
        public Reconciler reconciler() {
            return null;
        }

        @Override
        public WorkspaceWrites writes() {
            return null;
        }
    };

    /** A fixture project, loaded on first use and let go after the test. */
    abstract static class OverLoadedProject {
        @RegisterExtension
        TestProjectHelper helper = new TestProjectHelper();

        JdtServiceImpl service;
        JavaProvider provider;

        void load() throws Exception {
            if (service == null) {
                service = helper.loadProjectCopy("compile-clean");
                provider = new JavaProvider(() -> service);
            }
        }
    }

    @Nested
    class Anchors extends SymbolAnchorsContract {
        final OverLoadedProject project = new OverLoadedProject() { };

        @RegisterExtension
        TestProjectHelper helper = project.helper;

        @Override
        protected SymbolAnchors anchors() throws Exception {
            project.load();
            return project.provider.anchors(NO_HOST);
        }

        @Override
        protected Optional<String> resolvableSymbol() {
            return Optional.of("com.example.Clean");
        }
    }

    @Nested
    class Paths extends ChangePathsContract {
        final OverLoadedProject project = new OverLoadedProject() { };

        @RegisterExtension
        TestProjectHelper helper = project.helper;

        @Override
        protected org.jawata.spi.lang.ChangePaths paths() throws Exception {
            project.load();
            return project.provider.paths(NO_HOST);
        }
    }

    @Nested
    class Verdict extends BuildVerdictContract {
        final OverLoadedProject project = new OverLoadedProject() { };

        @RegisterExtension
        TestProjectHelper helper = project.helper;

        private Path broken;

        @Override
        protected BuildVerdict verdict() throws Exception {
            project.load();
            if (broken == null) {
                Path source = project.service.getProjectRoot().resolve("src/main/java/com/example");
                broken = source.resolve("BrokenMain.java");
                Files.writeString(broken, "package com.example;\npublic class BrokenMain {\n    void broken() {\n        int x = ;\n    }\n}\n");
                OwnedReconciler reconciler = new OwnedReconciler(() -> project.service);
                reconciler.syncBeforeCall();
                String name = project.service.getJavaProject().getProject().getName();
                reconciler.rebuild(new BuildScope(List.of(name)), BuildKind.FULL, new NullProgressMonitor());
            }
            return project.provider.verdict(NO_HOST);
        }

        @Override
        protected String fileThatBuilds() throws Exception {
            project.load();
            return project.service.getProjectRoot().resolve("src/main/java/com/example/Clean.java").toString();
        }

        @Override
        protected String fileThatDoesNotBuild() throws Exception {
            verdict();
            return broken.toString();
        }
    }

    @Nested
    class Tests extends TestRuntimeContract {
        final OverLoadedProject project = new OverLoadedProject() { };

        @RegisterExtension
        TestProjectHelper helper = project.helper;

        @Override
        protected TestRuntime runtime() throws Exception {
            project.load();
            return project.provider.tests(NO_HOST).orElseThrow();
        }
    }
}
