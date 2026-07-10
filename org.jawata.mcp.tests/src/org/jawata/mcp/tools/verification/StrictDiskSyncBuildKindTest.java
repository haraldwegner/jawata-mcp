package org.jawata.mcp.tools.verification;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.workspace.StrictDiskSync;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 22a P2-d — StrictDiskSync build-kind decision: a body-only modify takes
 * the fast INCREMENTAL path (and — the empirical check post-Stage-0 — its error
 * still surfaces); adding a .java escalates to a clean FULL build.
 */
class StrictDiskSyncBuildKindTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private boolean hasError(JdtServiceImpl service) throws Exception {
        IProject prj = service.getJavaProject().getProject();
        for (IMarker m : prj.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
            if (m.getAttribute(IMarker.SEVERITY, 0) == IMarker.SEVERITY_ERROR) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a body-only modify stays INCREMENTAL and still surfaces the error")
    void bodyModify_incremental_surfacesError() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("compile-clean");
        StrictDiskSync sync = new StrictDiskSync(() -> service);
        Path root = service.getProjectRoot();

        // Prime: establish the root as known + green.
        sync.syncBeforeCall();

        // Body-only edit: break a method body (drop the semicolon) — no declaration change.
        Path clean = root.resolve("src/main/java/com/example/Clean.java");
        String src = Files.readString(clean);
        Files.writeString(clean, src.replace("return \"Hello, \" + name;", "return \"Hello, \" + name"));

        StrictDiskSync.SyncReport r = sync.syncBeforeCall();
        assertFalse(r.fullBuild(), "a modify-only, green pass takes the incremental fast path");
        assertTrue(hasError(service),
            "the incremental build must still surface the modified file's syntax error");
    }

    @Test
    @DisplayName("adding a .java escalates to a clean FULL build")
    void addFile_escalatesToFull() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("compile-clean");
        StrictDiskSync sync = new StrictDiskSync(() -> service);
        Path root = service.getProjectRoot();

        sync.syncBeforeCall();   // prime, green

        Path added = root.resolve("src/main/java/com/example/Added22a.java");
        Files.writeString(added, "package com.example;\npublic class Added22a {\n    int v() { return 1; }\n}\n");

        StrictDiskSync.SyncReport r = sync.syncBeforeCall();
        assertTrue(r.fullBuild(), "adding a .java (type universe changed) escalates to FULL");
    }
}
