package com.example;

/** Stands in for {@code ASTParser} — the first of the parse population's two collaborators. */
public class Parser {

    private Source source;
    private boolean resolve;
    private int level;

    public static Parser newParser(int level) {
        Parser p = new Parser();
        p.level = level;
        return p;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public void setResolve(boolean resolve) {
        this.resolve = resolve;
    }

    public Tree createTree() {
        return new Tree(source == null ? "empty" : source.text(), resolve, level);
    }
}
