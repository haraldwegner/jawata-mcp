package org.jawata.mcp.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.jawata.mcp.engine.SettingKey.Audience;

/**
 * Every setting the engine reads through its host (Sprint 28k, E17), in one list: the registry a host builds its settings screen from
 * and is checked against (Stage 17 compares the preference pages with {@link #all()}).
 *
 * <p>A key that is read through the host is listed here, and the readers in {@code org.jawata.mcp} hold the key itself. The
 * environment variables the engine reads for itself are part of the key ({@link SettingKey#environment()}) and the engine that runs
 * alone bridges them in {@code DefaultEngineSettings}; the sentence that says so is put together per host
 * ({@link SettingKey#meaningFor(boolean)}), not written into the meaning. Each key says who it is for ({@link Audience}): a settings
 * screen shows the person the keys meant for the person and no others. What is not here (ledger 28k-C5-11): the variables
 * {@code org.jawata.core} reads straight from the process, and the allow-list of variables the test runner hands to a child JVM. The
 * list says what a host can set today, not everything the engine reads.</p>
 */
public final class SettingKeys {

    private static final List<SettingKey> ALL = new ArrayList<>();

    /** How many copies of the knowledge store are kept before a destructive operation. */
    public static final SettingKey BACKUPS_DEPTH = register("jawata.backups.depth", "10",
        "How many copies of the knowledge store are kept for the operations that can destroy it; the least is 1, since 0 would "
            + "switch the safety copy off.", null, Audience.MEMORY_SCREEN);

    /** How many of the daily scheduled copies are kept. */
    public static final SettingKey BACKUPS_SCHEDULED_DEPTH = register("jawata.backups.scheduled.depth", "14",
        "How many of the daily scheduled copies of the knowledge store are kept; the least is 1.", null, Audience.MEMORY_SCREEN);

    /** The most promoted rules one seat's brief carries. */
    public static final SettingKey RULES_LIMIT = register("jawata.rules.limit", "0, which is no limit",
        "The most promoted rules one seat's brief carries; 0, or a value that is not a number, injects every rule that binds the seat.",
        null, Audience.PERSON);

    /** Where accepted memory is also written as story files. */
    public static final SettingKey STORIES_DIR = register("jawata.stories.dir", "the knowledge store's own story folder",
        "The folder accepted memory is also written to as story files; 'off' writes none.", null, Audience.MEMORY_SCREEN);

    /** Whether the discussion is captured and recall can answer from it. */
    public static final SettingKey TRANSCRIPT_ENABLED = register("jawata.transcript.enabled", "on",
        "Whether the discussion with the person is captured and can be recalled; false, off, 0 or no switches it off and removes what "
            + "was captured.", "JAWATA_TRANSCRIPT", Audience.MEMORY_SCREEN);

    /** Whether this resident may upgrade the shared knowledge store's schema. */
    public static final SettingKey ALLOW_SCHEMA_UPGRADE = register("jawata.experience.allowSchemaUpgrade", "false",
        "Whether this resident may upgrade the schema of the knowledge store shared by every resident on the machine; an upgrade "
            + "changes the store for all of them, so it is refused unless this is true.", null, Audience.PERSON);

    /** Which knowledge store this resident opens. */
    public static final SettingKey EXPERIENCE_STORE = register("jawata.experience.store", "shared",
        "Which knowledge store the resident opens: shared (one per user, spanning workspaces), workspace, memory (nothing is kept), "
            + "or the path of a folder.", null, Audience.MEMORY_SCREEN);

    /** Where the knowledge store shared by every resident of the user is kept. */
    public static final SettingKey EXPERIENCE_SHARED_DIR = register("jawata.experience.shared.dir",
        "jawata in $XDG_DATA_HOME, or in ~/.local/share",
        "The folder of the knowledge store shared by every resident on the machine; used exactly as given.", null, Audience.PERSON);

    /** The user's data folder the shared knowledge store lives under. */
    public static final SettingKey DATA_HOME = register("jawata.data.home", "~/.local/share",
        "The user's data folder, under which the shared knowledge store keeps a folder called jawata when no folder is named for it",
        "XDG_DATA_HOME", Audience.PERSON);

    /** The project the engine loads when no workspace file names any. */
    public static final SettingKey PROJECT_PATH = register("jawata.project.path", "none",
        "The folder of the one project the engine loads when the workspace has no workspace.json", "JAVA_PROJECT_PATH", Audience.LAUNCHER);

    /** How long health_check calls wait on the workspace. */
    public static final SettingKey TIMEOUT_SECONDS = register("jawata.timeout.seconds", "30",
        "How many seconds health_check allows the workspace to answer, kept between 5 and 300", "JAWATA_TIMEOUT_SECONDS",
        Audience.PERSON);

    /** Whether paths in answers are absolute. */
    public static final SettingKey ABSOLUTE_PATHS = register("jawata.absolute.paths", "false",
        "When true the paths in health_check answers are absolute instead of relative to the project", "JAWATA_ABSOLUTE_PATHS",
        Audience.PERSON);

    /** The folders and files memory is loaded from. */
    public static final SettingKey MEMORY_ROOTS = register("jawata.memory.roots", "discovered from the project folders",
        "The folders and files memory is loaded from when no path is given, separated by the path separator; when set they are the "
            + "whole answer and nothing else is discovered.", null, Audience.MEMORY_SCREEN);

    /** Where debug and profile artifacts are kept. */
    public static final SettingKey RUNTIME_DIR = register("jawata.runtime.dir",
        "<workspace>/.jawata/runtime, or jawata-runtime in the temporary folder",
        "Where heap dumps and recordings from debugging and profiling are kept.", null, Audience.PERSON);

    /** Where coverage evidence is kept. */
    public static final SettingKey COVERAGE_DIR = register("jawata.coverage.dir",
        "<workspace>/.jawata/coverage, or jawata-coverage in the temporary folder",
        "Where coverage evidence from test runs is kept.", null, Audience.PERSON);

    /** Where the test runner's jars are found. */
    public static final SettingKey TOOLS_DIR = register("jawata.tools.dir", "tools/ in the distribution folder",
        "Where the jars the forked test runner needs are found; for a development run that has no distribution folder.",
        null, Audience.LAUNCHER);

    /** Where the notes of engines running inside an IDE are looked for. */
    public static final SettingKey ENGINE_NOTES_DIR = register("jawata.engine.notes.dir", "jawata-studio/engines in the OS cache folder",
        "The folder the notes of jawata engines running inside an IDE are read from, so this resident can reach them as siblings; "
            + "studio's own data folder setting does not move it.", null, Audience.LAUNCHER);

    /** The distribution folder. */
    public static final SettingKey DIST_ROOT = register("jawata.dist.root", "set by the launcher",
        "The folder the distribution was started from; the launcher sets it.", null, Audience.LAUNCHER);

    /** The stable workspace folder. */
    public static final SettingKey WORKSPACE_ROOT = register("jawata.workspace.root", "set by the launcher",
        "The workspace folder the launcher was started with, which outlives its per-session folder.", null, Audience.LAUNCHER);

    /** How deep the memory crawl follows links. */
    public static final SettingKey MEMORY_MAX_DEPTH = register("jawata.memory.maxDepth", "32",
        "How many levels of links and folders the memory crawl follows before it stops and says so; a backstop against a runaway tree.",
        null, Audience.PERSON);

    /** How many files the memory crawl reads. */
    public static final SettingKey MEMORY_MAX_FILES = register("jawata.memory.maxFiles", "10000",
        "How many memory files one crawl reads before it stops and says so; a backstop against a runaway tree.", null, Audience.PERSON);

    /** How many bytes the memory crawl reads. */
    public static final SettingKey MEMORY_MAX_BYTES = register("jawata.memory.maxBytes", "268435456",
        "How many bytes of memory files one crawl reads before it stops and says so; a backstop against a runaway tree.",
        null, Audience.PERSON);

    /** How many changed rows the corpus refreshes one by one. */
    public static final SettingKey CORPUS_MAX_INCREMENTAL = register("jawata.corpus.max.incremental", "200",
        "Above this many changed knowledge rows a refresh reads the whole store instead of looking each one up.", null, Audience.PERSON);

    /** Whether semantic embedding is switched off. */
    public static final SettingKey EMBED_DISABLED = register("jawata.embed.disabled", "false",
        "When true the knowledge store answers by keywords only and loads no embedding model.", null, Audience.PERSON);

    /** Whether symptoms are added to the text that is embedded. */
    public static final SettingKey EMBED_SYMPTOMS = register("jawata.embed.symptoms", "false",
        "A measurement arm: when true the recorded symptoms are added to the text of each lesson before it is embedded, under an identity of its own.",
        null, Audience.EXPERIMENT);

    /** The seats switched off. */
    public static final SettingKey SEATS_OFF = register("jawata.seats.off", "none",
        "The seats switched off, separated by commas; a seat that is off is not offered and a hire of it is refused.", null, Audience.OWN_SCREEN);

    /** The brain each seat runs on. */
    public static final SettingKey SEATS_BRAINS = register("jawata.seats.brains", "the brain each seat file names",
        "The brain each seat runs on, as seat=brain or seat=brain/model, separated by semicolons.", null, Audience.OWN_SCREEN);

    /** Where each seat sits in the workflow. */
    public static final SettingKey SEATS_WORKFLOW = register("jawata.seats.workflow", "the signed workflow",
        "Where each seat sits in the workflow, as seat=parent,role or seat=parent,role,escalates-to, separated by semicolons.", null,
        Audience.OWN_SCREEN);

    /** Whether the engine hires only the seats sealed in it (Sprint 28k, Amendment 3, D15): the Eclipse plug-in says so, a studio-started engine does not. */
    public static final SettingKey SEATS_SEALED = register("jawata.seats.sealed", "false",
        "When true, a hire names its seat and takes it from the seats sealed in the engine; a seat file given by path is refused. "
            + "Which seats are on, the brain each runs on and the workflow then come from the three seat settings.", null, Audience.LAUNCHER);

    private SettingKeys() {
    }

    private static SettingKey register(String name, String fallback, String meaning, String environment, Audience audience) {
        SettingKey key = new SettingKey(name, fallback, meaning, environment, audience);
        ALL.add(key);
        return key;
    }

    /** Every setting the engine reads through its host, in the order they are declared. */
    public static List<SettingKey> all() {
        return Collections.unmodifiableList(ALL);
    }

    /** The short words a settings screen labels each setting with, by key (Sprint 28k, Amendment 3, Stage 25k). */
    private static final java.util.Map<String, String> LABELS = java.util.Map.ofEntries(
        java.util.Map.entry("jawata.backups.depth", "Backup copies"),
        java.util.Map.entry("jawata.backups.scheduled.depth", "Daily backup copies"),
        java.util.Map.entry("jawata.rules.limit", "Most rules in one seat's brief"),
        java.util.Map.entry("jawata.stories.dir", "Story export folder"),
        java.util.Map.entry("jawata.transcript.enabled", "Keep the discussion so it can be recalled"),
        java.util.Map.entry("jawata.experience.allowSchemaUpgrade", "Allow upgrading the shared store's format"),
        java.util.Map.entry("jawata.experience.store", "Knowledge store"),
        java.util.Map.entry("jawata.experience.shared.dir", "Folder of the shared knowledge store"),
        java.util.Map.entry("jawata.data.home", "Data folder"),
        java.util.Map.entry("jawata.project.path", "Project folder when there is no workspace.json"),
        java.util.Map.entry("jawata.timeout.seconds", "Seconds a health check waits for the workspace"),
        java.util.Map.entry("jawata.absolute.paths", "Absolute paths in health check answers"),
        java.util.Map.entry("jawata.memory.roots", "Folders to load memory from"),
        java.util.Map.entry("jawata.runtime.dir", "Folder for heap dumps and recordings"),
        java.util.Map.entry("jawata.coverage.dir", "Folder for coverage evidence"),
        java.util.Map.entry("jawata.tools.dir", "Folder of the test runner's jars"),
        java.util.Map.entry("jawata.engine.notes.dir", "Folder of the engines' notes"),
        java.util.Map.entry("jawata.dist.root", "Distribution folder"),
        java.util.Map.entry("jawata.workspace.root", "Workspace folder"),
        java.util.Map.entry("jawata.memory.maxDepth", "Deepest level the memory crawl follows"),
        java.util.Map.entry("jawata.memory.maxFiles", "Most files one memory crawl reads"),
        java.util.Map.entry("jawata.memory.maxBytes", "Most bytes one memory crawl reads"),
        java.util.Map.entry("jawata.corpus.max.incremental", "Changed entries before a full refresh"),
        java.util.Map.entry("jawata.embed.disabled", "Search by keywords only, with no embedding model"),
        java.util.Map.entry("jawata.embed.symptoms", "Embed symptoms with each lesson (measurement)"),
        java.util.Map.entry("jawata.seats.off", "Seats switched off"),
        java.util.Map.entry("jawata.seats.brains", "Brain of each seat"),
        java.util.Map.entry("jawata.seats.workflow", "Place of each seat in the workflow"),
        java.util.Map.entry("jawata.seats.sealed", "Hire only the seats sealed in the engine"));

    /** The settings that are on or off, so a screen shows each as a checkbox rather than a text field. */
    private static final java.util.Set<String> ON_OFF = java.util.Set.of("jawata.transcript.enabled", "jawata.experience.allowSchemaUpgrade",
        "jawata.absolute.paths", "jawata.embed.disabled", "jawata.embed.symptoms", "jawata.seats.sealed");

    /**
     * The short words a settings screen labels a setting with, the same for every host; the dotted key belongs in the mouseover. A host may
     * shorten them where its own heading already says the rest.
     *
     * @param key the setting
     * @return its words, or the key itself for a setting no words are written for (the engine's test refuses that for a listed key)
     */
    public static String labelOf(SettingKey key) {
        return LABELS.getOrDefault(key.name(), key.name());
    }

    /**
     * Whether a setting is on or off, so a screen shows it as a checkbox.
     *
     * @param key the setting
     * @return true for an on-or-off setting
     */
    public static boolean isOnOff(SettingKey key) {
        return ON_OFF.contains(key.name());
    }

    /**
     * The environment variable the engine that runs alone also reads for a setting, when the setting has one.
     *
     * @param name the dotted key
     * @return the variable's name, or empty when the key is not listed or reads none
     */
    public static Optional<String> environmentOf(String name) {
        return ALL.stream().filter(key -> key.name().equals(name)).map(SettingKey::environment).filter(variable -> variable != null)
            .findFirst();
    }
}
