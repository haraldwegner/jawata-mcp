package com.example;

/** POPULATION ONE, member 2 of 3 — the same job, derived with a fallback level. */
public class BetaTool {

    public Tree build(Source src) {
        return parse(src);
    }

    private static Tree parse(Source src) {
        int level = Grammar.latest();
        if (src == null) {
            level = Grammar.oldest();
        }
        Parser working = Parser.newParser(level);
        working.setResolve(true);
        working.setSource(src);
        Tree built = working.createTree();
        return built;
    }
}
