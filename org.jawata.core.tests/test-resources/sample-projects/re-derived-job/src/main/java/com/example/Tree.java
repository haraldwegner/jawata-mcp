package com.example;

/** Scenery: what a parse helper answers with. */
public class Tree {

    private final String text;
    private final boolean resolved;
    private final int level;

    public Tree(String text, boolean resolved, int level) {
        this.text = text;
        this.resolved = resolved;
        this.level = level;
    }

    public String text() {
        return text;
    }

    public boolean resolved() {
        return resolved;
    }

    public int level() {
        return level;
    }
}
