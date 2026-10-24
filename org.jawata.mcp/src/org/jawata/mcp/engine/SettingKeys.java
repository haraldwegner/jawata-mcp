package org.jawata.mcp.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Every setting the engine reads through its host (Sprint 28k, E17), in one list: the registry a host builds its settings screen from
 * and is checked against (Stage 17 compares the preference pages with {@link #all()}).
 *
 * <p>A key that is read through the host is listed here. Five readers (StoreBackups, SeatRules, StoryWriter, TranscriptStore,
 * SchemaMigrations) still spell the key as a constant of their own beside this list, and the environment variables and
 * {@code Boolean.getBoolean} switches the engine reads straight from the process are not here at all (ledger 28k-C5-11, homed to
 * Stage 6): the list says what a host can set today, not everything the engine reads.</p>
 */
public final class SettingKeys {

    private static final List<SettingKey> ALL = new ArrayList<>();

    /** How many copies of the knowledge store are kept before a destructive operation. */
    public static final SettingKey BACKUPS_DEPTH = register("jawata.backups.depth", "10",
        "How many copies of the knowledge store are kept for the operations that can destroy it; the least is 1, since 0 would "
            + "switch the safety copy off.");

    /** How many of the daily scheduled copies are kept. */
    public static final SettingKey BACKUPS_SCHEDULED_DEPTH = register("jawata.backups.scheduled.depth", "14",
        "How many of the daily scheduled copies of the knowledge store are kept; the least is 1.");

    /** The most promoted rules one seat's brief carries. */
    public static final SettingKey RULES_LIMIT = register("jawata.rules.limit", "0, which is no limit",
        "The most promoted rules one seat's brief carries; 0, or a value that is not a number, injects every rule that binds the seat.");

    /** Where accepted memory is also written as story files. */
    public static final SettingKey STORIES_DIR = register("jawata.stories.dir", "the knowledge store's own story folder",
        "The folder accepted memory is also written to as story files; 'off' writes none.");

    /** Whether the discussion is captured and recall can answer from it. */
    public static final SettingKey TRANSCRIPT_ENABLED = register("jawata.transcript.enabled", "on",
        "Whether the discussion with the person is captured and can be recalled; false, off, 0 or no switches it off and removes what "
            + "was captured. The environment variable JAWATA_TRANSCRIPT is read when this is not set.");

    /** Whether this resident may upgrade the shared knowledge store's schema. */
    public static final SettingKey ALLOW_SCHEMA_UPGRADE = register("jawata.experience.allowSchemaUpgrade", "false",
        "Whether this resident may upgrade the schema of the knowledge store shared by every resident on the machine; an upgrade "
            + "changes the store for all of them, so it is refused unless this is true.");

    /** Which knowledge store this resident opens. */
    public static final SettingKey EXPERIENCE_STORE = register("jawata.experience.store", "shared",
        "Which knowledge store the resident opens: shared (one per user, spanning workspaces), workspace, memory (nothing is kept), "
            + "or the path of a folder.");

    /** The folders and files memory is loaded from. */
    public static final SettingKey MEMORY_ROOTS = register("jawata.memory.roots", "discovered from the project folders",
        "The folders and files memory is loaded from when no path is given, separated by the path separator; when set they are the "
            + "whole answer and nothing else is discovered.");

    /** Where debug and profile artifacts are kept. */
    public static final SettingKey RUNTIME_DIR = register("jawata.runtime.dir",
        "<workspace>/.jawata/runtime, or jawata-runtime in the temporary folder",
        "Where heap dumps and recordings from debugging and profiling are kept.");

    /** Where coverage evidence is kept. */
    public static final SettingKey COVERAGE_DIR = register("jawata.coverage.dir",
        "<workspace>/.jawata/coverage, or jawata-coverage in the temporary folder",
        "Where coverage evidence from test runs is kept.");

    /** Where the test runner's jars are found. */
    public static final SettingKey TOOLS_DIR = register("jawata.tools.dir", "tools/ in the distribution folder",
        "Where the jars the forked test runner needs are found; for a development run that has no distribution folder.");

    /** The distribution folder. */
    public static final SettingKey DIST_ROOT = register("jawata.dist.root", "set by the launcher",
        "The folder the distribution was started from; the launcher sets it.");

    /** The stable workspace folder. */
    public static final SettingKey WORKSPACE_ROOT = register("jawata.workspace.root", "set by the launcher",
        "The workspace folder the launcher was started with, which outlives its per-session folder.");

    private SettingKeys() {
    }

    private static SettingKey register(String name, String fallback, String meaning) {
        SettingKey key = new SettingKey(name, fallback, meaning);
        ALL.add(key);
        return key;
    }

    /** Every setting the engine reads through its host, in the order they are declared. */
    public static List<SettingKey> all() {
        return Collections.unmodifiableList(ALL);
    }
}
