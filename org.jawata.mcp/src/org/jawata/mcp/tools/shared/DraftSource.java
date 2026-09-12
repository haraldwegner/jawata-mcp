package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ASTVisitor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sprint 28f Stage 8 D3 — TEXT THAT IS NOT ON DISK YET, READ AS JAVA.
 *
 * <p>The duplicate gate has to answer a question about code that does not exist: an agent is
 * about to write a method, and the useful moment to say "we already do that" is BEFORE the
 * write. So the input is a draft — sometimes a whole file, sometimes the fragment an
 * {@code Edit} carries — and neither is a compilation unit on disk.</p>
 *
 * <h2>Why this is a shared class and not a private helper</h2>
 *
 * <p>It is one method and the temptation to inline it is exactly the temptation that put ~34
 * private {@code parse} helpers in this codebase, which the detector shipped beside this
 * class now reports as one re-derived job. Writing the 35th while building the machinery
 * that finds the first 34 would have been the joke that writes itself. There is no existing
 * owner for "turn source TEXT into an AST" — {@link SourceScan#parse} takes a compilation
 * unit, and the syntax tool does it inline — so this is that owner, and the next caller
 * finds it here.</p>
 *
 * <h2>A FRAGMENT is wrapped, and the wrapping is visible in the answer</h2>
 *
 * <p>An {@code Edit} carries a body fragment, which is not a compilation unit and does not
 * parse as one. It is wrapped in a synthetic type so the method declarations inside it
 * become reachable. What that CANNOT do is resolve bindings — the fragment names types the
 * synthetic unit has no imports for — so this reads NAMES and DOC COMMENTS and never types.
 * That is stated rather than discovered: a caller that needed resolved types from a draft
 * would be asking a question a draft cannot answer.</p>
 */
public final class DraftSource {

    /** The synthetic type a fragment is wrapped in, named so it is obvious in any output. */
    private static final String WRAPPER = "__JawataDraft__";

    private DraftSource() {
    }

    /**
     * One method a draft declares: its name, and what its doc comment says it is for.
     *
     * <p>The comment travels because the lane is asked BY MEANING. A name alone is a poor
     * question — {@code process}, {@code handle}, {@code run} name nothing — and the sentence
     * above the method is usually the only place the intent is written down.</p>
     */
    public record DraftMethod(String name, String comment) {

        /** The question to put to the lane: what this method is called and what it is for. */
        public String asQuestion() {
            return comment == null || comment.isBlank() ? name : name + " — " + comment;
        }
    }

    /**
     * The methods a draft declares, whether it is a whole file or a fragment.
     *
     * <p>A whole file is parsed as itself. If that yields nothing — which is what a bare
     * fragment does — it is wrapped and parsed again. The order matters: wrapping first
     * would put a valid file inside a class and lose its own type declarations.</p>
     */
    public static List<DraftMethod> methodsIn(String draft) {
        if (draft == null || draft.isBlank()) {
            return List.of();
        }
        List<DraftMethod> found = collect(draft);
        if (!found.isEmpty()) {
            return found;
        }
        return collect("class " + WRAPPER + " {\n" + draft + "\n}\n");
    }

    private static List<DraftMethod> collect(String source) {
        List<DraftMethod> out = new ArrayList<>();
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(source.toCharArray());
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            // Bindings are deliberately NOT requested. A draft has no project, no
            // classpath and no imports it can be trusted about, so asking for them would
            // buy nulls at the price of a resolve. Names and comments are what this reads.
            Map<String, String> options = org.eclipse.jdt.core.JavaCore.getOptions();
            options.put(org.eclipse.jdt.core.JavaCore.COMPILER_SOURCE, "21");
            parser.setCompilerOptions(options);
            Object ast = parser.createAST(null);
            if (!(ast instanceof CompilationUnit unit)) {
                return out;
            }
            unit.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodDeclaration node) {
                    if (node.isConstructor()) {
                        return false;
                    }
                    String comment = node.getJavadoc() == null
                        ? "" : node.getJavadoc().toString().replaceAll("[*/\\n\\r]", " ").trim();
                    out.add(new DraftMethod(node.getName().getIdentifier(), comment));
                    return false;
                }
            });
        } catch (Exception e) {
            // A draft that will not parse is not a draft with no methods in it, and the
            // caller is told by getting an empty list back from a text it can see. It is
            // not an error: half-written code is the normal state of the thing this reads.
            return out;
        }
        return out;
    }
}
