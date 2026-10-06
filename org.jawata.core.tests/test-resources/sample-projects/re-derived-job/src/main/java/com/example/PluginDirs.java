package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 28g D29 — HALF OF THE FALSE POSITIVE, modelled on the real one.
 *
 * <p>On jawata's own tree the detector's first group named 21 methods, among them
 * {@code ExternalBundlePool#defaultPoolDirs}, {@code WorkspaceFileWatcher#readWorkspacePaths}
 * and {@code ResidentDegradation#notices} — plugin directories, workspace paths and
 * degradation notices, reported as one job derived 21 times. They have nothing in
 * common except that each returns a list.</p>
 *
 * <p><b>The first version of this fixture did not reproduce the defect, and a mutation
 * is what said so.</b> It built an {@code ArrayList} and called {@code add}, which
 * gives ONE collaborator: the detector collects the declaring classes of methods
 * CALLED, and {@code new ArrayList<>()} is a creation rather than an invocation. One is
 * below the two-collaborator minimum, so the pair never grouped under the old rule
 * either and the test passed for the wrong reason.</p>
 *
 * <p>So each method here now CALLS methods on two distinct platform types —
 * {@code java.util.List} and {@code java.util.Optional} — which is the overlap the
 * real group had and which says nothing whatever about what either method is for.</p>
 */
public class PluginDirs {

    private final String root;

    public PluginDirs(String root) {
        this.root = root;
    }

    public List<String> defaultPoolDirs() {
        List<String> out = new ArrayList<>();
        out.add(Optional.ofNullable(root).orElse(".") + "/plugins");
        out.add(Optional.ofNullable(root).orElse(".") + "/dropins");
        return out;
    }
}
