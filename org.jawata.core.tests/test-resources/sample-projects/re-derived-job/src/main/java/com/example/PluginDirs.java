package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 28g D29 — HALF OF THE FALSE POSITIVE, and it is modelled on the real one.
 *
 * <p>On jawata's own tree the detector's first group named 21 methods, among them
 * {@code ExternalBundlePool#defaultPoolDirs}, {@code WorkspaceFileWatcher#readWorkspacePaths}
 * and {@code ResidentDegradation#notices} — plugin directories, workspace paths and
 * degradation notices, reported as one job derived 21 times. They have nothing in
 * common except that each returns a list.</p>
 *
 * <p>This class and {@link DegradationNotices} are that pair: same shape
 * {@code ()->List}, each building an {@code ArrayList}, and NOT one shared type
 * outside the platform. Before D29 that counted as two shared collaborators —
 * {@code java.util.List} and {@code java.util.ArrayList} — which is exactly the
 * threshold, so the shape group stood.</p>
 */
public class PluginDirs {

    private final String root;

    public PluginDirs(String root) {
        this.root = root;
    }

    public List<String> defaultPoolDirs() {
        List<String> out = new ArrayList<>();
        out.add(root + "/plugins");
        out.add(root + "/dropins");
        return out;
    }
}
