package org.jawata.mcp.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.jawata.mcp.engine.SeatSettings;
import org.jawata.mcp.engine.SettingKeys;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.spi.host.EngineSettings;

/**
 * The engine's door to the seats for a host's settings screen (Sprint 28k, Amendment 3, D15). The seat files are read and never written: on or
 * off, the brain and the place in the workflow are the three settings of {@link SettingKeys}, laid over what the files say. A proposed
 * change is judged by the engine's own {@link SeatTree}, so a screen can only say what the engine would say.
 */
public final class SeatSettingsService implements SeatSettings {

    private final Supplier<SeatFiles.Listing> source;
    private final EngineSettings settings;
    private final Set<String> codeChangingTools;

    private SeatSettingsService(Supplier<SeatFiles.Listing> source, EngineSettings settings, Set<String> codeChangingTools) {
        this.source = source;
        this.settings = settings;
        this.codeChangingTools = Set.copyOf(codeChangingTools);
    }

    /**
     * The door over a folder of seat files.
     *
     * @param folder            the folder
     * @param settings          where the three settings are read from, per call
     * @param codeChangingTools the engine's tools that change code, which the tree check needs
     * @return the door
     */
    public static SeatSettings overFolder(Path folder, EngineSettings settings, Set<String> codeChangingTools) {
        return new SeatSettingsService(() -> SeatFiles.read(folder), settings, codeChangingTools);
    }

    /**
     * The door for a running engine: the folder {@code jawata.agent.seatsDir} names, as a studio-started engine has, else the seats sealed
     * in the engine's own bundle (Sprint 28k, Amendment 3, M10), which an engine inside Eclipse uses and nothing extracts.
     *
     * <p>Which seats a screen shows is a different question from whether a hire may name a seat file. A hire takes its seat by name only where
     * the host says so ({@link SettingKeys#SEATS_SEALED}), so a headless engine with no folder still hires the seat files its clients name; the
     * Eclipse plug-in sets both, and the two answers agree there.</p>
     *
     * @param settings the engine's settings
     * @param tools    the engine's tools
     * @return the door
     */
    public static SeatSettings forEngine(EngineSettings settings, ToolCalls tools) {
        Set<String> codeChanging = SeatTree.codeChangingIn(tools.getToolDefinitions());
        Optional<String> folder = settings.get(ScheduledHires.SEATS_SETTING).filter(value -> !value.isBlank());
        return folder.map(path -> overFolder(Path.of(path), settings, codeChanging)).orElseGet(() -> sealed(settings, tools));
    }

    /**
     * The seats sealed in the engine's own bundle with the stored settings laid over them: the door a hire uses on an engine whose host
     * keeps its seats sealed ({@link SettingKeys#SEATS_SEALED}).
     *
     * @param settings the engine's settings
     * @param tools    the engine's tools
     * @return the door
     */
    public static SeatSettingsService sealed(EngineSettings settings, ToolCalls tools) {
        return new SeatSettingsService(() -> SeatFiles.sealed(SeatSettingsService.class), settings, SeatTree.codeChangingIn(tools.getToolDefinitions()));
    }

    /**
     * What a hire takes of one seat: the seat file as written, and the row the stored settings make of it.
     *
     * @param text the seat file's whole text, read as the hire's seat
     * @param row  on or off, the brain and model, and the place in the workflow, with the stored settings laid over the file
     */
    public record Hireable(String text, Seat row) {
    }

    /**
     * The seat of this name, as a hire takes it.
     *
     * @param name the seat's name
     * @return the seat, or empty when no seat of this door has the name
     */
    public Optional<Hireable> hireable(String name) {
        SeatFiles.Listing listing = source.get();
        Overrides overrides = storedOverrides();
        SeatTree tree = SeatTree.fromSeatFiles(withWorkflow(listing.seats(), overrides.workflow()), codeChangingTools).tree();
        return listing.seats().stream().filter(file -> file.name().equals(name)).findFirst()
            .map(file -> new Hireable(file.text(), row(file, overrides, tree, new ArrayList<>())));
    }

    /**
     * Who hires whom: the seats with the stored places in the workflow laid over them, as the engine's own tree judges them.
     *
     * @return the tree, with the rules a stored workflow broke
     */
    public SeatTree.Loaded workflow() {
        return SeatTree.fromSeatFiles(withWorkflow(source.get().seats(), storedOverrides().workflow()), codeChangingTools);
    }

    private Overrides storedOverrides() {
        return Overrides.parse(stored(SettingKeys.SEATS_OFF.name()), stored(SettingKeys.SEATS_BRAINS.name()), stored(SettingKeys.SEATS_WORKFLOW.name()));
    }

    @Override
    public List<Brain> brains() {
        return BrainRoster.onThisMachine().statuses().stream().map(status -> new Brain(status.id(), status.label(), status.installed(),
            switch (status.login()) {
                case LOGGED_IN -> "loggedIn";
                case NOT_LOGGED_IN -> "notLoggedIn";
                case UNKNOWN -> "unknown";
            }, status.reason(), status.loginCommand(), status.models(), status.hirable(), status.takesModel(), status.writesHeldByInstruction())).toList();
    }

    @Override
    public Probe probe(String brain, String model) {
        BrainRoster.Probe run = BrainRoster.onThisMachine().probe(brain, model);
        return new Probe(run.brain(), run.ok(), run.answer(), run.modelsUsed(), run.costUsd(), run.seconds(), run.error(), run.loginCommand());
    }

    @Override
    public View seats(Map<String, String> proposed) {
        SeatFiles.Listing listing = source.get();
        Overrides overrides = Overrides.parse(value(proposed, SettingKeys.SEATS_OFF.name()), value(proposed, SettingKeys.SEATS_BRAINS.name()),
            value(proposed, SettingKeys.SEATS_WORKFLOW.name()));
        List<String> errors = new ArrayList<>(listing.unreadable());
        errors.addAll(overrides.problems());
        SeatTree.Loaded loaded = SeatTree.fromSeatFiles(withWorkflow(listing.seats(), overrides.workflow()), codeChangingTools);
        List<Seat> rows = new ArrayList<>();
        for (SeatFiles.SeatFile file : listing.seats()) {
            rows.add(row(file, overrides, loaded.tree(), errors));
        }
        rows.sort(Comparator.comparing(Seat::name));
        return new View(rows, errors, loaded.refusals());
    }

    @Override
    public Edited edit(Map<String, String> held, String seat, Change change) {
        if (seat == null || seat.isBlank()) {
            throw new IllegalArgumentException("A change names no seat.");
        }
        String name = seat.strip();
        String off = SettingKeys.SEATS_OFF.name();
        String brains = SettingKeys.SEATS_BRAINS.name();
        String workflow = SettingKeys.SEATS_WORKFLOW.name();
        Map<String, String> next = new LinkedHashMap<>();
        for (String key : List.of(off, brains, workflow)) {
            next.put(key, value(held, key));
        }
        switch (change.kind()) {
            case SWITCH -> next.put(off, Entries.withName(next.get(off), ",", name, change.on() ? null : name));
            case BRAIN -> {
                if (change.brain() == null || change.brain().isBlank()) {
                    throw new IllegalArgumentException("A brain change names no brain.");
                }
                String model = change.model() == null || change.model().isBlank() ? "" : "/" + change.model().strip();
                next.put(brains, Entries.withName(next.get(brains), ";", name, name + "=" + change.brain().strip() + model));
            }
            case RESET -> {
                next.put(off, Entries.withName(next.get(off), ",", name, null));
                next.put(brains, Entries.withName(next.get(brains), ";", name, null));
            }
            case PLACE -> {
                if (change.parent() == null || change.parent().isBlank() || change.role() == null || change.role().isBlank()) {
                    throw new IllegalArgumentException("A place in the workflow names the seat that hires it and its role.");
                }
                String escalation = change.escalatesTo() == null || change.escalatesTo().isBlank() ? "" : "," + change.escalatesTo().strip();
                next.put(workflow, Entries.withName(next.get(workflow), ";", name, name + "=" + change.parent().strip() + "," + change.role().strip() + escalation));
            }
            case DEFAULT_PLACE -> next.put(workflow, Entries.withName(next.get(workflow), ";", name, null));
        }
        return new Edited(next, check(next));
    }

    /** A setting's value as a list of entries, each named by its seat: the part before {@code =}, or the whole entry. */
    private static final class Entries {

        private Entries() {
        }

        /**
         * The value with the seat's own entry replaced, or removed when {@code entry} is null; every other entry is kept as written, in order,
         * and a new entry goes last.
         */
        static String withName(String value, String separator, String seat, String entry) {
            List<String> kept = new ArrayList<>();
            boolean placed = false;
            for (String each : value.split(java.util.regex.Pattern.quote(separator))) {
                if (each.isBlank()) {
                    continue;
                }
                String named = each.contains("=") ? each.substring(0, each.indexOf('=')).strip() : each.strip();
                if (!named.equals(seat)) {
                    kept.add(each.strip());
                } else if (entry != null && !placed) {
                    kept.add(entry);
                    placed = true;
                }
            }
            if (entry != null && !placed) {
                kept.add(entry);
            }
            return String.join(separator, kept);
        }
    }

    private Seat row(SeatFiles.SeatFile file, Overrides overrides, SeatTree tree, List<String> errors) {
        String name = file.name();
        Map<String, String> front = file.front();
        boolean on = !overrides.off().contains(name) && declaredOn(name, front, errors);
        String[] picked = overrides.brains().get(name);
        String declaredBrain = Frontmatter.emptyToNull(Frontmatter.unquote(front.getOrDefault("brain", "")));
        String brain = picked != null ? picked[0]
            : declaredBrain == null ? SeatHire.Brain.CLAUDE_CODE.label() : SeatHire.Brain.named(declaredBrain).map(SeatHire.Brain::label).orElse(declaredBrain);
        Optional<String> model = picked != null ? Optional.ofNullable(picked[1]) : textOf(front, "model");
        return new Seat(name, textOf(front, "command"), textOf(front, "purpose").orElse(""), on, brain, model, tree.parentOf(name),
            tree.roleOf(name).map(SeatTree.Role::spelling).orElse(""), tree.escalatesTo(name), textOf(front, "fires").orElse(""),
            overrides.off().contains(name) || overrides.brains().containsKey(name), overrides.workflow().containsKey(name),
            acceptsUncontained(name, front, errors));
    }

    /** The seat file's own word on a brain whose writes only an instruction holds, as a hire reads it ({@code SeatHire}). */
    private static boolean acceptsUncontained(String name, Map<String, String> front, List<String> errors) {
        try {
            return Frontmatter.readSwitch("accepts_uncontained_writes", front.get("accepts_uncontained_writes"), false);
        } catch (IllegalArgumentException misspelled) {
            errors.add(name + ": " + misspelled.getMessage());
            return false;
        }
    }

    private static boolean declaredOn(String name, Map<String, String> front, List<String> errors) {
        try {
            return Frontmatter.readSwitch("enabled", front.get("enabled"), true);
        } catch (IllegalArgumentException misspelled) {
            errors.add(name + ": " + misspelled.getMessage());
            return false;
        }
    }

    private static Optional<String> textOf(Map<String, String> front, String key) {
        return Optional.ofNullable(Frontmatter.emptyToNull(Frontmatter.unquote(front.getOrDefault(key, ""))));
    }

    @Override
    public Optional<String> check(Map<String, String> proposed) {
        Overrides overrides = Overrides.parse(value(proposed, SettingKeys.SEATS_OFF.name()), value(proposed, SettingKeys.SEATS_BRAINS.name()),
            value(proposed, SettingKeys.SEATS_WORKFLOW.name()));
        List<String> problems = new ArrayList<>(overrides.problems());
        List<SeatFiles.SeatFile> files = source.get().seats();
        Set<String> inFiles = new LinkedHashSet<>();
        files.forEach(file -> inFiles.add(file.name()));
        for (String seat : overrides.named()) {
            if (!inFiles.contains(seat) && !SeatTree.standard().knows(seat)) {
                problems.add("no seat named '" + seat + "'");
            }
        }
        if (!problems.isEmpty()) {
            return Optional.of(String.join("; ", problems));
        }
        SeatTree.Loaded loaded = SeatTree.fromSeatFiles(withWorkflow(files, overrides.workflow()), codeChangingTools);
        return loaded.refused() ? Optional.of("the engine would refuse this tree: " + String.join("; ", loaded.refusals())) : Optional.empty();
    }

    private String stored(String key) {
        return settings.get(key).orElse("");
    }

    private String value(Map<String, String> proposed, String key) {
        return proposed.containsKey(key) ? proposed.get(key) : stored(key);
    }

    /** The seat files with each stored place in the workflow laid over them, as the three lines of a seat file the tree reads. */
    private static List<SeatFiles.SeatFile> withWorkflow(List<SeatFiles.SeatFile> files, Map<String, String[]> workflow) {
        Map<String, String[]> left = new LinkedHashMap<>(workflow);
        List<SeatFiles.SeatFile> laid = new ArrayList<>();
        for (SeatFiles.SeatFile file : files) {
            String[] place = left.remove(file.name());
            if (place == null) {
                laid.add(file);
                continue;
            }
            Map<String, String> front = new LinkedHashMap<>(file.front());
            placeInto(front, place);
            laid.add(new SeatFiles.SeatFile(file.name(), file.file(), front, file.body(), file.text()));
        }
        left.forEach((seat, place) -> {
            Map<String, String> front = new LinkedHashMap<>();
            placeInto(front, place);
            laid.add(new SeatFiles.SeatFile(seat, Path.of(seat + ".md"), front, "", ""));
        });
        return laid;
    }

    private static void placeInto(Map<String, String> front, String[] place) {
        front.put(SeatTree.PARENT_KEY, place[0]);
        front.put(SeatTree.ROLE_KEY, place[1]);
        if (place.length > 2) {
            front.put(SeatTree.ESCALATES_KEY, place[2]);
        }
    }

    /**
     * The three settings read: seats that are off, each seat's brain and model, and each seat's place as parent, role and, when stored, where
     * failures go.
     */
    private record Overrides(Set<String> off, Map<String, String[]> brains, Map<String, String[]> workflow, List<String> problems) {

        /** Every seat the three settings name. */
        Set<String> named() {
            Set<String> seats = new LinkedHashSet<>(off);
            seats.addAll(brains.keySet());
            seats.addAll(workflow.keySet());
            return seats;
        }

        static Overrides parse(String offValue, String brainsValue, String workflowValue) {
            List<String> problems = new ArrayList<>();
            Set<String> off = new LinkedHashSet<>();
            for (String seat : offValue.split(",")) {
                if (!seat.isBlank()) {
                    off.add(seat.strip());
                }
            }
            return new Overrides(off, brains(brainsValue, problems), workflow(workflowValue, problems), problems);
        }

        private static Map<String, String[]> brains(String value, List<String> problems) {
            Map<String, String[]> brains = new LinkedHashMap<>();
            for (String entry : value.split(";")) {
                if (entry.isBlank()) {
                    continue;
                }
                int equals = entry.indexOf('=');
                if (equals <= 0) {
                    problems.add("'" + entry.strip() + "' is not seat=brain or seat=brain/model");
                    continue;
                }
                String[] brainAndModel = entry.substring(equals + 1).split("/", 2);
                Optional<SeatHire.Brain> brain = SeatHire.Brain.named(brainAndModel[0]).filter(named -> named != SeatHire.Brain.SCRIPT);
                if (brain.isEmpty()) {
                    problems.add("'" + brainAndModel[0].strip() + "' is not a brain a hire can start (known: " + java.util.Arrays.stream(SeatHire.Brain.values())
                        .filter(known -> known != SeatHire.Brain.SCRIPT).map(SeatHire.Brain::label).collect(java.util.stream.Collectors.joining(", ")) + ")");
                } else if (brainAndModel.length > 1 && brainAndModel[1].isBlank()) {
                    problems.add("an empty model name is not a model; leave it out for the default");
                } else {
                    brains.put(entry.substring(0, equals).strip(), new String[] {brain.get().label(), brainAndModel.length > 1 ? brainAndModel[1].strip() : null});
                }
            }
            return brains;
        }

        private static Map<String, String[]> workflow(String value, List<String> problems) {
            Map<String, String[]> workflow = new LinkedHashMap<>();
            for (String entry : value.split(";")) {
                if (entry.isBlank()) {
                    continue;
                }
                int equals = entry.indexOf('=');
                String[] place = equals > 0 ? entry.substring(equals + 1).split(",", -1) : new String[0];
                if (place.length < 2 || place.length > 3) {
                    problems.add("'" + entry.strip() + "' is not seat=parent,role or seat=parent,role,escalates-to");
                    continue;
                }
                for (int i = 0; i < place.length; i++) {
                    place[i] = place[i].strip();
                }
                workflow.put(entry.substring(0, equals).strip(), place);
            }
            return workflow;
        }
    }
}
