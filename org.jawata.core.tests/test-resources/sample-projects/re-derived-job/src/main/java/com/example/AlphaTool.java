package com.example;

/**
 * POPULATION ONE, member 1 of 3 — the parse helper.
 *
 * <p>This population is the fixture's portrait of the one this deliverable was written to
 * find: jawata's own tree carries ~34 private static {@code parse(ICompilationUnit)}
 * helpers, each building a parser, pointing it at a source, asking for bindings and
 * returning the tree. Nobody copied them. Each was written by somebody who needed a parsed
 * unit and did not know that thirty other people had needed one too.</p>
 *
 * <p><b>The three bodies are deliberately UNALIKE</b>, and that is the point rather than
 * decoration: a re-derived job is precisely the duplicate a token comparison cannot see, so
 * if these were three copies of one body the clone detector would find them and this
 * detector would have proved nothing. This one is straight-line; {@link BetaTool} branches
 * on the level; {@link GammaTool} retries in a loop.</p>
 *
 * <p>All three reach through {@link Parser} and {@link Grammar} and nothing else — exactly
 * two collaborators, which is the same number the real population has, so the threshold is
 * exercised at its boundary rather than comfortably above it.</p>
 */
public class AlphaTool {

    public Tree build(Source src) {
        return parse(src);
    }

    private static Tree parse(Source src) {
        Parser parser = Parser.newParser(Grammar.latest());
        parser.setSource(src);
        parser.setResolve(true);
        return parser.createTree();
    }
}
