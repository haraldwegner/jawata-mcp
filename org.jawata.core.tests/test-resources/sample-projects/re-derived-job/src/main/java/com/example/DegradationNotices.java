package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 28g D29 — the other half of the false positive. See {@link PluginDirs}.
 *
 * <p>Deliberately NOT a near-copy in prose or structure: a reader should be able to
 * see at a glance that these two answer different questions. That is the whole
 * claim — a shape match plus a platform-type overlap is not evidence of one job.</p>
 */
public class DegradationNotices {

    private boolean loading;

    public void beginLoading() {
        this.loading = true;
    }

    public List<String> notices() {
        List<String> out = new ArrayList<>();
        if (loading) {
            out.add("the workspace is still loading");
        }
        return out;
    }
}
