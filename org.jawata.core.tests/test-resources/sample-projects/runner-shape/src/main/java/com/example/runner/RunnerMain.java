package com.example.runner;

import org.junit.platform.launcher.Launcher;

/** Production code that imports a test framework because its JOB is running
 *  tests — the case that makes the rule ORDER load-bearing. */
public class RunnerMain {
    /** The launcher this runner would drive. */
    private Launcher launcher;

    /** Returns a marker. */
    public String marker() { return "runner"; }
}
