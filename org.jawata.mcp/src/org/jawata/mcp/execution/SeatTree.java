package org.jawata.mcp.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The seats and who hires whom (Sprint 28h, S7; D8, D9, D48, D51): main at the top; below main the architect, ux, the spec seats and the pm;
 * below the pm the chief engineer, the test manager, the debugger and the profiler; below the chief engineer the developer, the senior
 * developer, the javadoc writer and the cataloguer; below the test manager the test writer. The consultant sits beside main and is hired by
 * the person's request through main, never by a working seat.
 *
 * <p>Leads decide and instruct and may not change code; workers use the engine's tools and may edit directly only where no tool applies;
 * reviewers read and propose. Which of the three a seat is decides what it is allowed to call, and that is read here and nowhere else.</p>
 *
 * <p><b>Configurable (D51).</b> The tree above is the default. A seat file may declare {@code parent:}, {@code role:} (lead, worker,
 * reviewer) and {@code escalates_to:}; {@link #in} lays those over the default and checks the result. A tree that breaks a rule is
 * refused as a whole, each broken rule named with its seat, and the default tree is used in its place, so a bad file never leaves the
 * engine without a hierarchy.</p>
 */
public final class SeatTree {

    /** The seat-file key naming the seat that hires this one. */
    public static final String PARENT_KEY = "parent";
    /** The seat-file key naming what the seat is: lead, worker or reviewer. */
    public static final String ROLE_KEY = "role";
    /** The seat-file key naming the seat a failed task of this seat goes to; blank means it goes nowhere. */
    public static final String ESCALATES_KEY = "escalates_to";

    /** The only seat nobody hires: the top of every tree. */
    public static final String TOP = "main";

    /** What a seat is: leads decide and instruct, workers change code through the tools, reviewers read and propose. */
    public enum Role {
        LEAD, WORKER, REVIEWER;

        /** The spelling a seat file uses for this role. */
        public String spelling() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** The role a seat file's spelling names, or none when it is not one of the three. */
        public static Optional<Role> parse(String spelling) {
            if (spelling == null) {
                return Optional.empty();
            }
            for (Role role : values()) {
                if (role.spelling().equals(spelling.strip().toLowerCase(Locale.ROOT))) {
                    return Optional.of(role);
                }
            }
            return Optional.empty();
        }
    }

    /**
     * One seat as the tree holds it.
     *
     * @param parent      the seat that hires it
     * @param role        what it is
     * @param escalatesTo the seat a task it could not do goes to, or none
     */
    public record Declaration(String parent, Role role, Optional<String> escalatesTo) {
    }

    /**
     * What loading a folder of seat files gave: the tree to use, the broken rules that made the default tree the one in use, and the files
     * that could not be read.
     *
     * @param tree       the tree the declarations make, or the default tree when {@code refusals} is not empty
     * @param refusals   one line for each broken rule, naming the seat and the rule; empty when the tree was accepted
     * @param unreadable one line for each seat file that could not be read, so a file is never skipped quietly
     */
    public record Loaded(SeatTree tree, List<String> refusals, List<String> unreadable) {
        public Loaded {
            refusals = List.copyOf(refusals);
            unreadable = List.copyOf(unreadable);
        }

        /** Whether the declared tree was refused and the default is the one in use. */
        public boolean refused() {
            return !refusals.isEmpty();
        }
    }

    private static final Set<String> BRAIN_EDITORS = Set.of("Edit", "Write", "MultiEdit");

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(SeatTree.class.getName());

    private final Map<String, Declaration> seats;

    private SeatTree(Map<String, Declaration> seats) {
        this.seats = seats;
    }

    /** The hierarchy as signed. */
    public static SeatTree standard() {
        return new SeatTree(Map.copyOf(standardDeclarations()));
    }

    private static Map<String, Declaration> standardDeclarations() {
        Map<String, Declaration> p = new LinkedHashMap<>();
        for (String s : new String[] {"architect", "ux", "spec-editor", "spec-auditor", "consultant"}) {
            p.put(s, new Declaration(TOP, Role.REVIEWER, Optional.empty()));
        }
        p.put("pm", new Declaration(TOP, Role.LEAD, Optional.empty()));
        p.put("chief-engineer", new Declaration("pm", Role.LEAD, Optional.empty()));
        p.put("test-manager", new Declaration("pm", Role.LEAD, Optional.empty()));
        for (String s : new String[] {"debugger", "profiler"}) {
            p.put(s, new Declaration("pm", Role.REVIEWER, Optional.empty()));
        }
        p.put("developer", new Declaration("chief-engineer", Role.WORKER, Optional.of("senior-developer")));
        for (String s : new String[] {"senior-developer", "javadoc-writer"}) {
            p.put(s, new Declaration("chief-engineer", Role.WORKER, Optional.empty()));
        }
        p.put("cataloguer", new Declaration("chief-engineer", Role.REVIEWER, Optional.empty()));
        p.put("test-writer", new Declaration("test-manager", Role.WORKER, Optional.empty()));
        return p;
    }

    /**
     * Lay the seat files' {@code parent:}, {@code role:} and {@code escalates_to:} over the default tree and check the result. A seat file
     * that declares none of the three changes nothing; a seat file for a seat the default does not know joins the tree when it declares a
     * parent and a role.
     *
     * @param folder            the folder of seat files
     * @param codeChangingTools the engine's tools that change code, which no seat that is not a worker may list
     */
    public static Loaded in(Path folder, Set<String> codeChangingTools) {
        SeatFiles.Listing listing = SeatFiles.read(folder);
        Loaded loaded = fromSeatFiles(listing.seats(), codeChangingTools);
        // Whoever asks, a refused tree or an unreadable seat file is said in the log on every load, so it is never only the hire's answer that knows.
        loaded.refusals().forEach(line -> LOG.warning(() -> "seat tree refused, the signed tree is in force: " + line));
        listing.unreadable().forEach(line -> LOG.warning(() -> "seat file not read, so its placement is not applied: " + line));
        return new Loaded(loaded.tree(), loaded.refusals(), listing.unreadable());
    }

    /**
     * The published tools that change code, read from the flag each tool publishes of itself ({@code _meta."jawata/changesCode"}, the
     * engine's own answer to "did this call change code?") instead of from a list kept here. The annotations cannot answer it:
     * {@code destructiveHint} is absent for {@code format}, {@code quick_fix}, {@code refactoring} and {@code dependency}, which change
     * code. A seat that is not a worker holds none of these, so the code is changed by the seats that are allowed to and a tool added
     * later is held back from the others the day it says it changes code.
     */
    @SuppressWarnings("unchecked")
    public static Set<String> codeChangingIn(List<Map<String, Object>> definitions) {
        Set<String> names = new java.util.TreeSet<>();
        for (Map<String, Object> definition : definitions) {
            Object meta = definition.get("_meta");
            if (meta instanceof Map<?, ?> published && Boolean.TRUE.equals(((Map<String, Object>) published).get("jawata/changesCode"))) {
                names.add(String.valueOf(definition.get("name")));
            }
        }
        return names;
    }

    /**
     * The tree for the seats folder the host setting names: the folder's seat files laid over the signed tree, or the signed tree itself when
     * the setting is absent or names no folder.
     */
    public static Loaded forSetting(Optional<String> seatsFolder, Set<String> codeChangingTools) {
        if (seatsFolder.isEmpty() || seatsFolder.get().isBlank() || !java.nio.file.Files.isDirectory(Path.of(seatsFolder.get()))) {
            return new Loaded(standard(), List.of(), List.of());
        }
        return in(Path.of(seatsFolder.get()), codeChangingTools);
    }

    static Loaded fromSeatFiles(List<SeatFiles.SeatFile> files, Set<String> codeChangingTools) {
        Map<String, Declaration> declared = standardDeclarations();
        List<String> refusals = new ArrayList<>();
        Map<String, SeatFiles.SeatFile> byName = new LinkedHashMap<>();
        for (SeatFiles.SeatFile file : files) {
            byName.putIfAbsent(file.name(), file);
            declareFrom(file, declared, refusals);
        }
        checkTree(declared, refusals);
        checkCode(declared, byName, codeChangingTools, refusals);
        if (!refusals.isEmpty()) {
            return new Loaded(standard(), refusals, List.of());
        }
        return new Loaded(new SeatTree(Map.copyOf(declared)), List.of(), List.of());
    }

    private static void declareFrom(SeatFiles.SeatFile file, Map<String, Declaration> declared, List<String> refusals) {
        String name = file.name();
        boolean hasParent = file.front().containsKey(PARENT_KEY);
        boolean hasRole = file.front().containsKey(ROLE_KEY);
        boolean hasEscalation = file.front().containsKey(ESCALATES_KEY);
        if (!hasParent && !hasRole && !hasEscalation) {
            return;
        }
        if (TOP.equals(name)) {
            refusals.add(TOP + ": main is the top of the tree and nobody hires it, so its file may declare no " + PARENT_KEY + ", " + ROLE_KEY
                + " or " + ESCALATES_KEY);
            return;
        }
        Declaration before = declared.get(name);
        String parent = hasParent ? Frontmatter.emptyToNull(Frontmatter.unquote(file.front().get(PARENT_KEY))) : before == null ? null : before.parent();
        Role role = before == null ? null : before.role();
        if (hasRole) {
            String spelling = Frontmatter.unquote(file.front().get(ROLE_KEY));
            Optional<Role> parsed = Role.parse(spelling);
            if (parsed.isEmpty()) {
                refusals.add(name + ": " + ROLE_KEY + " is '" + spelling + "', which is none of lead, worker or reviewer");
                return;
            }
            role = parsed.get();
        }
        Optional<String> escalation = hasEscalation ? Optional.ofNullable(Frontmatter.emptyToNull(Frontmatter.unquote(file.front().get(ESCALATES_KEY))))
            : before == null ? Optional.empty() : before.escalatesTo();
        if (parent == null) {
            refusals.add(name + ": it has no " + PARENT_KEY + ", and main is the only seat with no parent");
            return;
        }
        if (role == null) {
            refusals.add(name + ": it declares a " + PARENT_KEY + " but no " + ROLE_KEY + " (lead, worker or reviewer), and the default tree does not know it");
            return;
        }
        declared.put(name, new Declaration(parent, role, escalation));
    }

    private static void checkTree(Map<String, Declaration> declared, List<String> refusals) {
        for (Map.Entry<String, Declaration> entry : declared.entrySet()) {
            String seat = entry.getKey();
            Declaration d = entry.getValue();
            if (!TOP.equals(d.parent()) && !declared.containsKey(d.parent())) {
                refusals.add(seat + ": its " + PARENT_KEY + " '" + d.parent() + "' is no seat");
            } else if (!reachesTop(seat, declared)) {
                refusals.add(seat + ": its chain of " + PARENT_KEY + " never reaches " + TOP + " (a cycle)");
            }
            d.escalatesTo().ifPresent(target -> {
                if (target.equals(seat)) {
                    refusals.add(seat + ": " + ESCALATES_KEY + " names itself");
                } else if (!TOP.equals(target) && !declared.containsKey(target)) {
                    refusals.add(seat + ": its " + ESCALATES_KEY + " '" + target + "' is no seat");
                }
            });
        }
    }

    private static boolean reachesTop(String seat, Map<String, Declaration> declared) {
        String at = seat;
        for (int steps = 0; steps <= declared.size(); steps++) {
            Declaration d = declared.get(at);
            if (d == null) {
                return TOP.equals(at);
            }
            at = d.parent();
        }
        return false;
    }

    /** A seat that is not a worker lists no tool that changes code and does not set {@code changes_code}. */
    private static void checkCode(Map<String, Declaration> declared, Map<String, SeatFiles.SeatFile> files, Set<String> codeChangingTools,
            List<String> refusals) {
        for (Map.Entry<String, Declaration> entry : declared.entrySet()) {
            SeatFiles.SeatFile file = files.get(entry.getKey());
            if (file == null || entry.getValue().role() == Role.WORKER) {
                continue;
            }
            String role = entry.getValue().role().spelling();
            if (Frontmatter.readSwitch("changes_code", file.front().get("changes_code"), false)) {
                refusals.add(entry.getKey() + ": it is a " + role + " and its file sets changes_code, but only a worker may change code");
            }
            for (String tool : Frontmatter.list(file.front().get("tools"))) {
                if (codeChangingTools.contains(tool) || BRAIN_EDITORS.contains(tool)) {
                    refusals.add(entry.getKey() + ": it is a " + role + " and its file lists '" + tool + "', a tool that changes code, but only a worker may hold one");
                }
            }
        }
    }

    /** Whether {@code hirer} is the seat that hires {@code seat}. */
    public boolean mayHire(String hirer, String seat) {
        Declaration d = seats.get(seat);
        return hirer != null && d != null && hirer.equals(d.parent());
    }

    /** The seat that hires {@code seat}, or none for main and for a seat the tree does not know. */
    public Optional<String> parentOf(String seat) {
        return Optional.ofNullable(seats.get(seat)).map(Declaration::parent);
    }

    /** Where a task that {@code seat} could not do goes next, or none when the seat declares nowhere. */
    public Optional<String> escalatesTo(String seat) {
        return Optional.ofNullable(seats.get(seat)).flatMap(Declaration::escalatesTo);
    }

    /** What the seat is, or none for a seat the tree does not know. Main is the lead at the top. */
    public Optional<Role> roleOf(String seat) {
        return TOP.equals(seat) ? Optional.of(Role.LEAD) : Optional.ofNullable(seats.get(seat)).map(Declaration::role);
    }

    /** Leads decide and instruct; they carry no code-changing tool. */
    public boolean isLead(String seat) {
        return roleOf(seat).filter(role -> role == Role.LEAD).isPresent();
    }

    /** Whether the seat may change code at all: the workers that use the tools and edit where none applies. */
    public boolean mayEditCode(String seat) {
        return roleOf(seat).filter(role -> role == Role.WORKER).isPresent();
    }

    /** The seats that are hired, by name; main is not among them, because nobody hires it. */
    public List<String> seats() {
        return seats.keySet().stream().sorted().toList();
    }

    /** Whether the tree knows the seat. */
    public boolean knows(String seat) {
        return TOP.equals(seat) || seats.containsKey(seat);
    }

    /** Each seat the tree holds, with what the tree says of it; read by the tests that compare a loaded tree with the signed one. */
    Map<String, Declaration> declarations() {
        return seats;
    }
}
