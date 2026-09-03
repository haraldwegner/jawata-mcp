package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture for Replace Loop with Pipeline (Sprint 28d-rescue, row 50).
 *
 * <p>Three loops must become pipelines and four must not. Each refusal fails a different
 * condition, so a failing test names which condition stopped working rather than only
 * that something did.</p>
 */
public class PipelineTargets {

    /** REWRITTEN: filter and map, the full shape. */
    public List<String> activeNames(List<Calculator> people) {
        List<String> names = new ArrayList<>();
        for (Calculator person : people) {
            if (person.multiply(1, 1) > 0) {
                names.add(person.toString());
            }
        }
        return names;
    }

    /** REWRITTEN: no filter, so the pipeline is a map alone. */
    public List<String> allNames(List<Calculator> people) {
        List<String> names = new ArrayList<>();
        for (Calculator person : people) {
            names.add(person.toString());
        }
        return names;
    }

    /** REWRITTEN: adding the element itself needs no map stage at all. */
    public List<Calculator> copyOf(List<Calculator> people) {
        List<Calculator> copy = new ArrayList<>();
        for (Calculator person : people) {
            copy.add(person);
        }
        return copy;
    }

    /**
     * NOT rewritten: the body does two things. Two statements are two jobs, and Split
     * Loop is the refactoring for that.
     */
    public List<String> alsoCounts(List<Calculator> people) {
        List<String> names = new ArrayList<>();
        int seen = 0;
        for (Calculator person : people) {
            names.add(person.toString());
            seen++;
        }
        names.add("seen " + seen);
        return names;
    }

    /**
     * NOT rewritten: a break. A pipeline has no early exit, and the nearest equivalent is
     * a different computation with a different result.
     */
    public List<String> stopsEarly(List<Calculator> people) {
        List<String> names = new ArrayList<>();
        for (Calculator person : people) {
            if (names.size() > 2) {
                break;
            }
            names.add(person.toString());
        }
        return names;
    }

    /**
     * NOT rewritten: the list already holds something. Adding to a non-empty list is not
     * what collect produces.
     */
    public List<String> startsNonEmpty(List<Calculator> people) {
        List<String> names = new ArrayList<>(List.of("header"));
        for (Calculator person : people) {
            names.add(person.toString());
        }
        return names;
    }

    /**
     * NOT rewritten: an array has no stream() method, and Arrays.stream needs an import
     * this tool cannot add.
     */
    public List<String> overAnArray(Calculator[] people) {
        List<String> names = new ArrayList<>();
        for (Calculator person : people) {
            names.add(person.toString());
        }
        return names;
    }
}
