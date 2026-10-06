package com.example;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Sprint 28g D29 — the other half of the false positive. See {@link PluginDirs} for
 * what the pair is modelled on and why the first version of it proved nothing.
 *
 * <p>Deliberately NOT a near-copy in prose or structure: a reader should see at a
 * glance that these two answer different questions. That is the whole claim — a shape
 * match plus a platform-type overlap is not evidence of one job.</p>
 */
public class DegradationNotices {

    private String reason;

    public void failedToLoad(String why) {
        this.reason = why;
    }

    public List<String> notices() {
        List<String> out = new ArrayList<>();
        out.add(Optional.ofNullable(reason).orElse("the workspace is still loading"));
        return out;
    }
}
