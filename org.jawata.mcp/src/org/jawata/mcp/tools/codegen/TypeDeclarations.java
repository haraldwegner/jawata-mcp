package org.jawata.mcp.tools.codegen;

import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * Find the declaration of a type by its simple name, ANYWHERE in the file.
 *
 * <h2>What this replaces, and why it is one helper rather than five fixes</h2>
 *
 * <p>Every generator in this package carried its own {@code findTypeDeclaration}, and all five
 * were the same five lines: a loop over {@link CompilationUnit#types()}, which is the file's
 * TOP-LEVEL types only. A member class is not in that list, so each generator answered "not a
 * regular class" for a nested target — {@code generate kind=constructor},
 * {@code equals_hashcode}, {@code getters_setters}, {@code tostring} and
 * {@code override_methods} alike. A data class nested inside its owner is one of the commonest
 * shapes there is, and it was the one shape none of them could write into.</p>
 *
 * <p>Found in Sprint 28d-rescue Stage 5, by row 2 ({@code data kind=reference_to_value})
 * composing the equals/hashCode generator over a member class: the recipe rolled back with
 * <em>step 3: Target is not a regular class</em>. The population was enumerated with
 * {@code search_symbols(query="findTypeDeclaration", kind=Method)} — five declarations in this
 * package — and all five now answer through here, which is what makes this the class being
 * closed rather than the one instance that happened to be in the way.</p>
 *
 * <p>The search is depth-first through member types, and a simple name cannot repeat inside
 * one file, so the first hit is the only hit. Callers that pass a top-level name get exactly
 * what they got before.</p>
 */
final class TypeDeclarations {

    private TypeDeclarations() {
    }

    /** The declaration of this simple name, top-level or nested, or null. */
    static AbstractTypeDeclaration find(CompilationUnit unit, String simpleName) {
        return search(unit.types(), simpleName);
    }

    private static AbstractTypeDeclaration search(java.util.List<?> declarations,
                                                  String simpleName) {
        for (Object each : declarations) {
            if (!(each instanceof AbstractTypeDeclaration declaration)) {
                continue;
            }
            if (simpleName.equals(declaration.getName().getIdentifier())) {
                return declaration;
            }
            AbstractTypeDeclaration nested =
                search(declaration.bodyDeclarations(), simpleName);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
