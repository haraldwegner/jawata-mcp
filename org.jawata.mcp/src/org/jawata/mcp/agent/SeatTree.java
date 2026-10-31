package org.jawata.mcp.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The seats and who hires whom (Sprint 28h, S7; D8, D9, D48): main at the top; below main the architect, ux, the spec seats and the pm;
 * below the pm the chief engineer, the test manager, the debugger and the profiler; below the chief engineer the developer, the senior
 * developer, the javadoc writer and the cataloguer; below the test manager the test writer. The consultant sits beside main and is hired by
 * the person's request through main, never by a working seat.
 *
 * <p>Leads decide and instruct and may not change code; workers use the engine's tools and may edit directly only where no tool applies;
 * reviewers read and propose. Which of the three a seat is decides what it is allowed to call, and that is read here and nowhere else.</p>
 */
public final class SeatTree {

    private static final Set<String> LEADS = Set.of("main", "pm", "chief-engineer", "test-manager");
    private static final Set<String> EDITORS = Set.of("developer", "senior-developer", "javadoc-writer", "test-writer");

    private final Map<String, String> parents;

    private SeatTree(Map<String, String> parents) {
        this.parents = parents;
    }

    /** The hierarchy as signed. */
    public static SeatTree standard() {
        Map<String, String> p = new LinkedHashMap<>();
        for (String s : new String[] {"architect", "ux", "spec-editor", "spec-auditor", "pm", "consultant"}) {
            p.put(s, "main");
        }
        for (String s : new String[] {"chief-engineer", "test-manager", "debugger", "profiler"}) {
            p.put(s, "pm");
        }
        for (String s : new String[] {"developer", "senior-developer", "javadoc-writer", "cataloguer"}) {
            p.put(s, "chief-engineer");
        }
        p.put("test-writer", "test-manager");
        return new SeatTree(Map.copyOf(p));
    }

    /** Whether {@code hirer} is the seat that hires {@code seat}. */
    public boolean mayHire(String hirer, String seat) {
        return hirer != null && hirer.equals(parents.get(seat));
    }

    /** The seat that hires {@code seat}, or none for main and for a seat the tree does not know. */
    public Optional<String> parentOf(String seat) {
        return Optional.ofNullable(parents.get(seat));
    }

    /** Leads decide and instruct; they carry no code-changing tool. */
    public boolean isLead(String seat) {
        return LEADS.contains(seat);
    }

    /** Whether the seat may change code at all: the workers that use the tools and edit where none applies. */
    public boolean mayEditCode(String seat) {
        return EDITORS.contains(seat);
    }

    /** Whether the tree knows the seat. */
    public boolean knows(String seat) {
        return "main".equals(seat) || parents.containsKey(seat);
    }
}
