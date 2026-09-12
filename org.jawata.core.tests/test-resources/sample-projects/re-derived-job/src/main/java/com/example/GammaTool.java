package com.example;

/** POPULATION ONE, member 3 of 3 — the same job again, derived with a retry. */
public class GammaTool {

    public Tree build(Source src) {
        return parse(src);
    }

    private static Tree parse(Source src) {
        Tree result = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            int level = attempt == 0 ? Grammar.latest() : Grammar.oldest();
            Parser worker = Parser.newParser(level);
            worker.setSource(src);
            worker.setResolve(attempt == 0);
            result = worker.createTree();
            if (result != null) {
                break;
            }
        }
        return result;
    }
}
