package org.jawata.mcp.java.lsp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The refactorings an editor offers beyond rename and extract (Sprint 28i, Stage 25, step A5; ARCHITECTURE-28i-s25.md), as one table: for each, the
 * engine tool that stages it, the arguments that never change, the kinds of code element it applies to, and the inputs a person must give first.
 * An editor never holds a list of refactoring names of its own; it asks the language server what is offered at the cursor and runs what comes back.
 */
final class RefactoringCatalogue {

    /** What a person is asked before a refactoring can be staged: its name in the staged call, the question, and the answers to choose from when there are fixed ones. */
    record Input(String name, String prompt, List<String> choices) {
        Input(String name, String prompt) {
            this(name, prompt, List.of());
        }
    }

    /**
     * One refactoring.
     *
     * @param id        what an editor sends back to run it
     * @param title     what the person reads
     * @param kind      the code action kind it is offered under
     * @param tool      the engine tool that stages it
     * @param fixed     the arguments that are always the same
     * @param appliesTo the kinds of code element it can be asked at: {@code type}, {@code method}, {@code field}, {@code variable}
     * @param inputs    what the person must supply; none means it is staged at once
     */
    record Entry(String id, String title, String kind, String tool, Map<String, Object> fixed, Set<String> appliesTo, List<Input> inputs) {

        boolean needsInput() {
            return !inputs.isEmpty();
        }
    }

    private static final List<Entry> ENTRIES = List.of(
        new Entry("inline.method", "Inline method", "refactor.inline", "inline", Map.of("kind", "method"), Set.of("method"), List.of()),
        new Entry("inline.variable", "Inline variable", "refactor.inline", "inline", Map.of("kind", "variable"), Set.of("variable"), List.of()),
        new Entry("lambda.anonymous", "Convert anonymous class to lambda", "refactor.rewrite", "convert_anonymous_to_lambda", Map.of(),
            Set.of("method", "variable"), List.of()),
        new Entry("cleanup.final", "Make variables that are never reassigned final", "refactor.rewrite", "apply_cleanup", Map.of("kind", "add_final"),
            Set.of("method"), List.of()),
        new Entry("hierarchy.up", "Pull member up", "refactor.move", "hierarchy", Map.of("direction", "up"), Set.of("method", "field"), List.of()),
        new Entry("hierarchy.down", "Push member down", "refactor.move", "hierarchy", Map.of("direction", "down"), Set.of("method", "field"), List.of()),
        new Entry("pattern.singleton", "Replace singleton with an ordinary class", "refactor.rewrite", "refactor_to_pattern",
            Map.of("kind", "inline_singleton"), Set.of("type"), List.of()),
        new Entry("signature.visibility", "Change method visibility", "refactor.rewrite", "change_method_signature", Map.of("kind", "change_signature"),
            Set.of("method"), List.of(new Input("visibility", "Make the method which visibility?", List.of("public", "protected", "package", "private")))),
        new Entry("pattern.factory", "Replace constructor with factory method", "refactor.rewrite", "refactor_to_pattern",
            Map.of("kind", "replace_constructor_with_factory"), Set.of("method"), List.of(new Input("factoryMethodName", "Name the factory method:"))));

    private RefactoringCatalogue() {
    }

    /** Every refactoring the editor can be offered, in the order it is offered. */
    static List<Entry> entries() {
        return ENTRIES;
    }

    /** The refactoring an editor asked to run by the id it was offered under. */
    static Optional<Entry> byId(String id) {
        return ENTRIES.stream().filter(entry -> entry.id().equals(id)).findFirst();
    }

    /** The code action kinds the table offers, for the capability that tells an editor what to ask for. */
    static List<String> kinds() {
        return ENTRIES.stream().map(Entry::kind).distinct().toList();
    }
}
