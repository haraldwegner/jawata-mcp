package org.jawata.spi.lang;

import java.nio.file.Path;
import java.util.List;

/**
 * What a forked test run needs: its classpath, or the reason it cannot be assembled (then the classpath is empty). A refusal is an
 * answer, never an empty classpath that looks like one.
 */
public record RunnerSpec(List<Path> classpath, String refusalReason) {

    public RunnerSpec {
        classpath = List.copyOf(classpath);
    }

    public boolean isRefused() {
        return refusalReason != null;
    }
}
