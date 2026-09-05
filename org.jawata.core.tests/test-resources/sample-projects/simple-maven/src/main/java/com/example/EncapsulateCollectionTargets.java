package com.example;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Row 9 (Encapsulate Collection) fixtures — the canonical case and each shape refused.
 *
 * <p>A new file in an EXISTING package, for the reason the row 22 and row 54 fixtures state:
 * adding a package to this shared sample project once took it over the minimum sample
 * {@code AnalyzeNamingToolTest} relies on. Method bodies are distinct on the clone axis.</p>
 *
 * <p><b>No comment here quotes the operation's OUTPUT.</b> Row 54's cross-file test passed on
 * a fixture comment rather than on the rewrite, and the rule earned was that a fixture must
 * not contain the string its own test searches for.</p>
 */
public class EncapsulateCollectionTargets {

    /** THE CANONICAL CASE: a list handed straight back, so every caller owns it. */
    public static class Course {
        private final List<String> students = new ArrayList<>();

        public List<String> getStudents() {
            return students;
        }

        public int enrolled() {
            return students.size();
        }
    }

    /** A MAP, whose mutators are a different pair and whose view is a different wrapper. */
    public static class Registry {
        private final Map<String, Integer> scores = new HashMap<>();

        public Map<String, Integer> getScores() {
            return scores;
        }
    }

    /** A SET declared through its interface, so the Set wrapper applies. */
    public static class Roster {
        private final Set<String> members = new LinkedHashSet<>();

        public Set<String> getMembers() {
            return members;
        }
    }

    /** REFUSAL — already safe: it copies, which is the state this operation produces. */
    public static class Syllabus {
        private final List<String> topics = new ArrayList<>();

        public List<String> getTopics() {
            return List.copyOf(topics);
        }
    }

    /** REFUSAL — declared to return the concrete class, which has no read-only view. */
    public static class Ledger {
        private final ArrayList<String> entries = new ArrayList<>();

        public ArrayList<String> getEntries() {
            return entries;
        }
    }

    /** REFUSAL — an array has clone(), which is a copy rather than a view. */
    public static class Board {
        private final String[] cells = new String[9];

        public String[] getCells() {
            return cells;
        }
    }

    /** REFUSAL — static mutable state belongs to the global_data kind. */
    public static class Cache {
        private static final Map<String, String> SHARED = new HashMap<>();

        public Map<String, String> getShared() {
            return SHARED;
        }
    }

    /**
     * ALREADY HALF DONE — the class has the adder and still leaks. NOT a refusal.
     *
     * <p>Upstream's own {@code WorkCenter} is this shape: somebody took Fowler's second step
     * and never closed the accessor, which is exactly what the detector reports.</p>
     */
    public static class Playlist {
        private final List<String> tracks = new ArrayList<>();

        public List<String> getTracks() {
            return tracks;
        }

        public void addTrack(String name) {
            tracks.add(name.trim());
        }
    }
}
