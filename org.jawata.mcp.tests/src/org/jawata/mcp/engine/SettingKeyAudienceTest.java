package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.mcp.engine.SettingKey.Audience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, ledger 28k-A17-1, -A17-2 and -A17-3: a setting says who it is for, so a host's settings screen shows the person the settings
 * meant for the person and not the ones the launcher sets or a measurement arm of the engine's own development, and the sentence about
 * the environment variable is put together per host: the engine that runs alone says it, an IDE does not.
 */
class SettingKeyAudienceTest {

    private static SettingKey key(String name) {
        return SettingKeys.all().stream().filter(k -> k.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the two launcher-set keys are for the launcher, the measurement arm is an experiment, and every other key is for the person")
    void theAudienceOfEachKey() {
        assertEquals(Audience.LAUNCHER, SettingKeys.DIST_ROOT.audience());
        assertEquals(Audience.LAUNCHER, SettingKeys.WORKSPACE_ROOT.audience());
        assertEquals(Audience.EXPERIMENT, SettingKeys.EMBED_SYMPTOMS.audience());

        List<String> forThePerson = SettingKeys.all().stream().filter(k -> k.audience() == Audience.PERSON).map(SettingKey::name).toList();
        assertEquals(SettingKeys.all().size() - 3, forThePerson.size(), "the other keys are the person's: " + forThePerson);
        assertFalse(forThePerson.contains("jawata.dist.root") || forThePerson.contains("jawata.workspace.root")
            || forThePerson.contains("jawata.embed.symptoms"));
    }

    @Test
    @DisplayName("a key that names no audience is refused when it is registered")
    void aKeyWithNoAudienceIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> new SettingKey("jawata.some.new.key", "off", "What the new key does, said so a person can act on it.", null, null));

        assertTrue(refused.getMessage().contains("jawata.some.new.key") && refused.getMessage().contains("audience"),
            "it says which key and what it lacks: " + refused.getMessage());
    }

    @Test
    @DisplayName("the environment variable is the key's own field: the five keys the engine that runs alone reads from the environment name it")
    void theEnvironmentVariableIsTheKeysOwn() {
        Map<String, String> named = new LinkedHashMap<>();
        SettingKeys.all().stream().filter(k -> k.environment() != null).forEach(k -> named.put(k.name(), k.environment()));

        assertEquals(Map.of(
            "jawata.transcript.enabled", "JAWATA_TRANSCRIPT",
            "jawata.data.home", "XDG_DATA_HOME",
            "jawata.project.path", "JAVA_PROJECT_PATH",
            "jawata.timeout.seconds", "JAWATA_TIMEOUT_SECONDS",
            "jawata.absolute.paths", "JAWATA_ABSOLUTE_PATHS"), named);
    }

    /** What every key's meaning said, word for word, before the sentence about the variable moved out of it (the engine at 4.4.20). */
    private static final Map<String, String> HEADLESS_WORDING = Map.ofEntries(
        Map.entry("jawata.backups.depth",
            "How many copies of the knowledge store are kept for the operations that can destroy it; the least is 1, since 0 would switch the safety copy off."),
        Map.entry("jawata.backups.scheduled.depth",
            "How many of the daily scheduled copies of the knowledge store are kept; the least is 1."),
        Map.entry("jawata.rules.limit",
            "The most promoted rules one seat's brief carries; 0, or a value that is not a number, injects every rule that binds the seat."),
        Map.entry("jawata.stories.dir",
            "The folder accepted memory is also written to as story files; 'off' writes none."),
        Map.entry("jawata.transcript.enabled",
            "Whether the discussion with the person is captured and can be recalled; false, off, 0 or no switches it off and removes what was captured. The environment variable JAWATA_TRANSCRIPT is read when this is not set."),
        Map.entry("jawata.experience.allowSchemaUpgrade",
            "Whether this resident may upgrade the schema of the knowledge store shared by every resident on the machine; an upgrade changes the store for all of them, so it is refused unless this is true."),
        Map.entry("jawata.experience.store",
            "Which knowledge store the resident opens: shared (one per user, spanning workspaces), workspace, memory (nothing is kept), or the path of a folder."),
        Map.entry("jawata.experience.shared.dir",
            "The folder of the knowledge store shared by every resident on the machine; used exactly as given."),
        Map.entry("jawata.data.home",
            "The user's data folder, under which the shared knowledge store keeps a folder called jawata when no folder is named for it; the environment variable XDG_DATA_HOME is read when this is not set."),
        Map.entry("jawata.project.path",
            "The folder of the one project the engine loads when the workspace has no workspace.json; the environment variable JAVA_PROJECT_PATH is read when this is not set."),
        Map.entry("jawata.timeout.seconds",
            "How many seconds health_check allows the workspace to answer, kept between 5 and 300; the environment variable JAWATA_TIMEOUT_SECONDS is read when this is not set."),
        Map.entry("jawata.absolute.paths",
            "When true the paths in health_check answers are absolute instead of relative to the project; the environment variable JAWATA_ABSOLUTE_PATHS is read when this is not set."),
        Map.entry("jawata.memory.roots",
            "The folders and files memory is loaded from when no path is given, separated by the path separator; when set they are the whole answer and nothing else is discovered."),
        Map.entry("jawata.runtime.dir",
            "Where heap dumps and recordings from debugging and profiling are kept."),
        Map.entry("jawata.coverage.dir",
            "Where coverage evidence from test runs is kept."),
        Map.entry("jawata.tools.dir",
            "Where the jars the forked test runner needs are found; for a development run that has no distribution folder."),
        Map.entry("jawata.engine.notes.dir",
            "The folder the notes of jawata engines running inside an IDE are read from, so this resident can reach them as siblings; studio's own data folder setting does not move it."),
        Map.entry("jawata.dist.root",
            "The folder the distribution was started from; the launcher sets it."),
        Map.entry("jawata.workspace.root",
            "The workspace folder the launcher was started with, which outlives its per-session folder."),
        Map.entry("jawata.memory.maxDepth",
            "How many levels of links and folders the memory crawl follows before it stops and says so; a backstop against a runaway tree."),
        Map.entry("jawata.memory.maxFiles",
            "How many memory files one crawl reads before it stops and says so; a backstop against a runaway tree."),
        Map.entry("jawata.memory.maxBytes",
            "How many bytes of memory files one crawl reads before it stops and says so; a backstop against a runaway tree."),
        Map.entry("jawata.corpus.max.incremental",
            "Above this many changed knowledge rows a refresh reads the whole store instead of looking each one up."),
        Map.entry("jawata.embed.disabled",
            "When true the knowledge store answers by keywords only and loads no embedding model."),
        Map.entry("jawata.embed.symptoms",
            "A measurement arm: when true the recorded symptoms are added to the text of each lesson before it is embedded, under an identity of its own."));

    @Test
    @DisplayName("the wording for the engine that runs alone is, for every key, exactly what it was before the sentence about the variable moved")
    void theHeadlessWordingIsUnchanged() {
        assertEquals(HEADLESS_WORDING.keySet(), SettingKeys.all().stream().map(SettingKey::name).collect(java.util.stream.Collectors.toSet()),
            "a key was added or dropped: this table is the frozen wording of the keys that existed");
        for (SettingKey each : SettingKeys.all()) {
            assertEquals(HEADLESS_WORDING.get(each.name()), each.meaningFor(true), each.name());
        }
    }

    @Test
    @DisplayName("the wording for a host that reads no environment has no sentence about a variable, and still ends in a full stop")
    void aHostThatReadsNoEnvironmentSaysNothingAboutOne() {
        for (SettingKey each : SettingKeys.all()) {
            String said = each.meaningFor(false);
            assertFalse(said.contains("environment variable"), each.name() + ": " + said);
            assertTrue(said.endsWith("."), each.name() + ": " + said);
            assertTrue(HEADLESS_WORDING.get(each.name()).startsWith(said.substring(0, said.length() - 1)),
                each.name() + ": nothing but the sentence about the variable is left out: " + said);
        }
        assertEquals(Optional.of("JAWATA_TRANSCRIPT"), Optional.ofNullable(key("jawata.transcript.enabled").environment()));
    }

    @Test
    @DisplayName("the engine that runs alone reads each registered key from the variable the key names, and the unregistered agent keys from theirs")
    void theEngineThatRunsAloneReadsEachRegisteredKeyFromItsOwnVariable() {
        Map<String, String> environment = Map.of(
            "JAWATA_TRANSCRIPT", "off", "XDG_DATA_HOME", "/data", "JAVA_PROJECT_PATH", "/project",
            "JAWATA_TIMEOUT_SECONDS", "99", "JAWATA_ABSOLUTE_PATHS", "true", "JAWATA_AGENT_BRAIN", "claude");
        DefaultEngineSettings settings = new DefaultEngineSettings(environment::get);

        List<SettingKey> withVariable = SettingKeys.all().stream().filter(k -> k.environment() != null).toList();
        assertEquals(5, withVariable.size(), "the five keys that name a variable");
        for (SettingKey each : withVariable) {
            assertEquals(Optional.of(environment.get(each.environment())), settings.get(each.name()), each.name());
        }
        assertEquals(Optional.of("claude"), settings.get("jawata.agent.brain"), "a key that is not in the registry keeps its own variable");
        assertEquals(Optional.empty(), settings.get("jawata.backups.depth"), "a key with no variable reads none");
    }
}
