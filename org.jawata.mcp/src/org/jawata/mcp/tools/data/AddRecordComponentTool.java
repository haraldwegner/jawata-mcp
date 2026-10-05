package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.RecordDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code data kind=add_record_component} — mcp#63.
 *
 * <h2>Why this operation exists, and why it is NOT a signature change</h2>
 *
 * <p>{@code change_method_signature} refuses a record's canonical constructor, and the
 * refusal is right: the language fixes that constructor's parameter list to be exactly the
 * record's components, so a changed signature there is a constructor that is neither
 * canonical nor delegating, and it does not compile. The constructor is DOWNSTREAM of the
 * header. Editing the header is the operation, and that is this one.</p>
 *
 * <h2>The cascade is smaller than it looks, and it was measured rather than assumed</h2>
 *
 * <p>{@code change_method_signature}'s refusal says a component change "cascades to the
 * accessors, equals/hashCode/toString and every {@code new} call site". Two thirds of that
 * is wrong for a record, and believing it is what made this look like a large operation:</p>
 *
 * <ul>
 *   <li><b>the accessors need nothing</b> — the compiler generates one per component, so a
 *       new component brings its accessor for free;</li>
 *   <li><b>equals/hashCode/toString need nothing</b> — also generated. Measured over this
 *       product's 82 record declarations, ZERO declare any of them by hand;</li>
 *   <li><b>the {@code new} call sites are real</b>, and are the whole of the cross-file work.</li>
 * </ul>
 *
 * <p>So the change is: the header gains the component, and every construction gains an
 * argument. The refusal on the other door is corrected to say this rather than the longer
 * list, and points here.</p>
 *
 * <h2>What it refuses, and why each is a refusal rather than a guess</h2>
 *
 * <ul>
 *   <li>{@code NOT_A_RECORD} — a class's constructor is not fixed by a header, so this
 *       operation has no subject there; {@code change_method_signature} performs it.</li>
 *   <li>{@code COMPONENT_NAME_TAKEN} — a duplicate component does not compile, and choosing
 *       a different name is the caller's decision, not a suffix this should invent.</li>
 *   <li>{@code NO_DEFAULT_FOR_EXISTING_CALLS} — every existing {@code new} needs a value for
 *       the new component. What that value is cannot be derived from a type: {@code 0} and
 *       {@code null} are guesses that compile, which is the worst kind. The caller names it.</li>
 *   <li>{@code EXPLICIT_CANONICAL_CONSTRUCTOR} — an explicitly declared canonical
 *       constructor assigns each component in its body, so a new component needs an
 *       assignment written into it. That is code this operation would be INVENTING rather
 *       than moving, so it declines and says which constructor. A COMPACT constructor is
 *       fine and is handled: its parameter list is implicit, so it needs no edit at all.</li>
 * </ul>
 */
public final class AddRecordComponentTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {
        public static final String NOT_A_RECORD = "NOT_A_RECORD";
        public static final String COMPONENT_NAME_TAKEN = "COMPONENT_NAME_TAKEN";
        public static final String NO_DEFAULT_FOR_EXISTING_CALLS = "NO_DEFAULT_FOR_EXISTING_CALLS";
        public static final String EXPLICIT_CANONICAL_CONSTRUCTOR = "EXPLICIT_CANONICAL_CONSTRUCTOR";
        public static final String SECONDARY_CONSTRUCTOR_EXISTS = "SECONDARY_CONSTRUCTOR_EXISTS";

        private Refusal() {
        }
    }

    public AddRecordComponentTool(Supplier<IJdtService> serviceSupplier,
                                  RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "add_record_component";
    }

    @Override
    public String kindName() {
        return "add_record_component";
    }

    @Override
    public String kindSummary() {
        return """
            add a component to a RECORD, and pass it at every construction (mcp#63).

            The canonical constructor's signature is not its own — the language fixes it to
            the record's components — so a component is added by editing the HEADER, which
            is what change_method_signature refuses and points here for. The accessors and
            equals/hashCode/toString are compiler-generated and need no edit; every `new`
            does, which is why `defaultValue` is REQUIRED whenever a construction exists.

            Refuses: a class rather than a record (NOT_A_RECORD — that IS a signature
            change), a name the record already declares (COMPONENT_NAME_TAKEN), existing
            constructions with no `defaultValue` (NO_DEFAULT_FOR_EXISTING_CALLS — a value
            that compiles is the worst thing to guess), and an EXPLICITLY declared canonical
            constructor (EXPLICIT_CANONICAL_CONSTRUCTOR), whose body would need an assignment
            this operation would be inventing. A COMPACT constructor is handled: its
            parameter list is implicit, so it needs no edit.

            viaSecondaryConstructor:true changes the shape instead of the call sites — the
            record gains the component AND an overload taking the old component list that
            delegates with defaultValue, so no construction is rewritten. mcp D10: asked
            three times to add an optional factor with default NaN, this correctly rewrote
            78 sites in 24 files, and all three were undone — the wanted shape was one
            four-argument constructor in one file.""";
    }

    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("componentType", Map.of("type", "string",
            "description", "REQUIRED. The new component's type, as the source spells it."));
        props.put("componentName", Map.of("type", "string",
            "description", "REQUIRED. The new component's name — it becomes the accessor "
                + "every reader calls, so it is the caller's decision and has no default."));
        props.put("defaultValue", Map.of("type", "string",
            "description", "The value passed at EXISTING constructions. Required whenever "
                + "any exist: a value cannot be derived from a type, and one that compiles "
                + "is the worst kind of guess."));
        props.put("viaSecondaryConstructor", Map.of("type", "boolean",
            "description", "Default false. True adds the component and a SECONDARY "
                + "constructor taking the OLD component list, delegating to the canonical "
                + "one with defaultValue — so every existing construction is left untouched. "
                + "One file changes instead of all of them. Use it when the default is a "
                + "sentinel (NaN, null, -1): a value nobody wanted at any site is a value no "
                + "site should have to spell."));
        return props;
    }

    @Override
    public String getDescription() {
        return "Add a component to a record — edit the header, pass it at every "
            + "construction. Delegate of data (mcp#63).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>(parameterSchema());
        properties.put("typeName", Map.of("type", "string",
            "description", "Fully-qualified name of the record."));
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the record (with line/column)."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));
        schema.put("properties", properties);
        schema.put("required", List.of("componentType", "componentName"));
        return schema;
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String componentType = getStringParam(arguments, "componentType");
        String componentName = getStringParam(arguments, "componentName");
        String defaultValue = getStringParam(arguments, "defaultValue");
        if (componentType == null || componentType.isBlank()) {
            return ToolResponse.invalidParameter("componentType", "required");
        }
        if (componentName == null || componentName.isBlank()) {
            return ToolResponse.invalidParameter("componentName", "required");
        }

        IType record;
        try {
            String typeName = getStringParam(arguments, "typeName");
            if (typeName != null && !typeName.isBlank()) {
                record = service.findType(typeName);
            } else {
                String filePathStr = getStringParam(arguments, "filePath");
                if (filePathStr == null || filePathStr.isBlank()) {
                    return ToolResponse.invalidParameter("typeName",
                        "give typeName, or filePath with line and column on the record");
                }
                Path path = service.getPathUtils().resolve(filePathStr);
                IJavaElement at = service.getElementAtPosition(path,
                    arguments.path("line").asInt(0), arguments.path("column").asInt(0));
                record = at == null ? null : (IType) at.getAncestor(IJavaElement.TYPE);
            }
        } catch (Exception error) {
            return ToolResponse.error("SYMBOL_NOT_FOUND",
                "could not resolve the record: " + error.getMessage(),
                "give typeName as a fully-qualified name");
        }
        if (record == null) {
            return ToolResponse.symbolNotFound("No record found — give typeName, or "
                + "filePath with line and column on the record declaration");
        }

        try {
            if (!record.isRecord()) {
                return ToolResponse.invalidParameter("typeName",
                    "'" + record.getElementName() + "' is a CLASS, not a record. A class's "
                        + "constructor is not fixed by a header, so adding a parameter there "
                        + "IS a signature change and change_method_signature performs it. "
                        + "This operation exists only because a record's canonical "
                        + "constructor cannot be changed at the constructor.",
                    Refusal.NOT_A_RECORD);
            }

            ICompilationUnit unit = record.getCompilationUnit();
            CompilationUnit ast = parse(unit);
            RecordDeclaration declaration = recordNamed(ast, record.getElementName());
            if (declaration == null) {
                return ToolResponse.symbolNotFound(
                    "could not locate the declaration of " + record.getElementName());
            }

            List<String> existing = new ArrayList<>();
            for (Object component : declaration.recordComponents()) {
                existing.add(((SingleVariableDeclaration) component).getName().getIdentifier());
            }
            if (existing.contains(componentName)) {
                return ToolResponse.invalidParameter("componentName",
                    "'" + record.getElementName() + "' already declares a component named '"
                        + componentName + "'. Two components of one name do not compile, and "
                        + "choosing the other name is yours — this will not invent a suffix. "
                        + "It declares: " + existing,
                    Refusal.COMPONENT_NAME_TAKEN);
            }

            MethodDeclaration explicit = explicitCanonicalConstructor(declaration, existing.size());
            if (explicit != null) {
                return ToolResponse.invalidParameter("typeName",
                    "'" + record.getElementName() + "' declares its canonical constructor "
                        + "EXPLICITLY, and its body assigns each component. A new component "
                        + "needs an assignment written into that body — code this operation "
                        + "would be INVENTING rather than moving, so it declines rather than "
                        + "guessing. Add the component by hand there, or make the constructor "
                        + "COMPACT (no parameter list), which this operation handles: a "
                        + "compact constructor's parameters are implicit and need no edit.",
                    Refusal.EXPLICIT_CANONICAL_CONSTRUCTOR);
            }

            // Every construction needs the new argument. Find them before deciding whether a
            // default is owed, so the refusal can say HOW MANY are waiting on it.
            Set<ICompilationUnit> units = new LinkedHashSet<>();
            units.add(unit);
            for (SearchMatch match : service.getSearchService().findAllReferences(record, 10000)) {
                if (match.getElement() instanceof IJavaElement referencing) {
                    ICompilationUnit owner = (ICompilationUnit) referencing
                        .getAncestor(IJavaElement.COMPILATION_UNIT);
                    if (owner != null && owner.getResource() instanceof IFile) {
                        units.add(owner);
                    }
                }
            }

            String fqn = record.getFullyQualifiedName('.');
            // PARSED ONCE PER UNIT, and the map is why. The first version parsed each unit
            // twice — once to find the constructions and once to rewrite them — so the nodes
            // collected belonged to a DIFFERENT AST than the rewrite was created against, and
            // every success failed with "Node is not inside the AST". An ASTRewrite is bound
            // to one AST, so the nodes it is handed must come from that same parse.
            Map<ICompilationUnit, CompilationUnit> parsed = new LinkedHashMap<>();
            parsed.put(unit, ast);
            for (ICompilationUnit referencing : units) {
                if (!parsed.containsKey(referencing)) {
                    parsed.put(referencing, parse(referencing));
                }
            }

            Map<ICompilationUnit, List<ClassInstanceCreation>> sites = new LinkedHashMap<>();
            int constructions = 0;
            for (ICompilationUnit referencing : units) {
                List<ClassInstanceCreation> found =
                    constructionsOf(parsed.get(referencing), fqn);
                if (!found.isEmpty()) {
                    sites.put(referencing, found);
                    constructions += found.size();
                }
            }

            boolean viaOverload = arguments != null && arguments.has("viaSecondaryConstructor")
                && arguments.get("viaSecondaryConstructor").asBoolean(false);

            if ((constructions > 0 || viaOverload)
                    && (defaultValue == null || defaultValue.isBlank())) {
                return ToolResponse.invalidParameter("defaultValue",
                    (viaOverload
                        ? "the secondary constructor's whole body is a delegation passing the "
                            + "new component, so it has nothing to pass without a "
                            + "`defaultValue`. "
                        : constructions + " existing construction(s) of '"
                            + record.getElementName() + "' must pass the new component, and "
                            + "no `defaultValue` was given. ")
                        + "The value cannot be derived from the type: a 0 or a null is a "
                        + "guess that COMPILES, which is the worst kind — it ships a wrong "
                        + "value that nothing refuses. Name the value.",
                    Refusal.NO_DEFAULT_FOR_EXISTING_CALLS);
            }

            // D7g: an overload taking the OLD list only exists if that list is not already
            // somebody's constructor. Refused rather than generated, because two
            // constructors of one signature do not compile and which one should survive is
            // not this operation's call.
            if (viaOverload) {
                MethodDeclaration clash = constructorWithArity(declaration, existing.size());
                if (clash != null) {
                    return ToolResponse.invalidParameter("viaSecondaryConstructor",
                        "'" + record.getElementName() + "' already declares a constructor "
                            + "taking " + existing.size() + " argument(s), which is exactly "
                            + "the signature the secondary constructor would have. Two "
                            + "constructors of one signature do not compile, and deciding "
                            + "which survives is yours. Add the delegation to that "
                            + "constructor by hand, or rewrite the call sites instead by "
                            + "omitting viaSecondaryConstructor.",
                        Refusal.SECONDARY_CONSTRUCTOR_EXISTS);
                }
            }

            Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
            for (ICompilationUnit referencing : units) {
                AST nodeFactory = parsed.get(referencing).getAST();
                ASTRewrite rewrite = ASTRewrite.create(nodeFactory);
                boolean touched = false;

                if (referencing.equals(unit)) {
                    ListRewrite components = rewrite.getListRewrite(declaration,
                        RecordDeclaration.RECORD_COMPONENTS_PROPERTY);
                    components.insertLast(rewrite.createStringPlaceholder(
                        componentType + " " + componentName,
                        ASTNode.SINGLE_VARIABLE_DECLARATION), null);
                    touched = true;

                    if (viaOverload) {
                        ListRewrite body = rewrite.getListRewrite(declaration,
                            RecordDeclaration.BODY_DECLARATIONS_PROPERTY);
                        body.insertLast(rewrite.createStringPlaceholder(
                            secondaryConstructor(record.getElementName(), declaration,
                                defaultValue),
                            ASTNode.METHOD_DECLARATION), null);
                    }
                }
                // viaOverload is the WHOLE point: the old call sites keep their old
                // signature, so nothing is rewritten at them.
                for (ClassInstanceCreation site :
                        viaOverload ? List.<ClassInstanceCreation>of()
                                    : sites.getOrDefault(referencing, List.of())) {
                    ListRewrite arguments2 = rewrite.getListRewrite(site,
                        ClassInstanceCreation.ARGUMENTS_PROPERTY);
                    arguments2.insertLast(rewrite.createStringPlaceholder(
                        defaultValue, ASTNode.SIMPLE_NAME), null);
                    touched = true;
                }
                if (!touched) {
                    continue;
                }
                Document document = new Document(referencing.getSource());
                // mcp#75: OPTIONS, not just a Document. A Document carries the file's line
                // DELIMITER; only the options map carries its INDENTATION, and `null` means
                // JDT falls back to its own default - tabs - into a space-indented file.
                // Found by the C12 audit: the batch's population query asked "does it pass a
                // Document", which this site answers yes, so it read as clean while being a
                // member of the class actually being closed.
                TextEdit edit = rewrite.rewriteAST(document,
                    org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(
                        referencing, null));
                if (edit.hasChildren() && referencing.getResource() instanceof IFile file) {
                    edits.computeIfAbsent(file, key -> new ArrayList<>()).add(edit);
                }
            }

            String label = viaOverload
                ? "add component " + componentType + " " + componentName + " to record "
                    + record.getElementName() + " with a secondary constructor passing "
                    + defaultValue + " (" + constructions + " existing construction(s) across "
                    + sites.size() + " file(s) left UNCHANGED)"
                : "add component " + componentType + " " + componentName + " to record "
                    + record.getElementName() + " (" + constructions + " construction(s) across "
                    + sites.size() + " file(s))";
            ToolResponse response = runPreCheckedRefactoring(service,
                new PreparedRefactoring(ChangeEngine.fromFileEdits(label, edits), label),
                "add_record_component", arguments);
            return withOverloadAlternative(response, record.getElementName(), componentType,
                componentName, defaultValue, constructions, sites.size(), viaOverload);
        } catch (Exception error) {
            return ToolResponse.error("REFACTORING_FAILED",
                "add_record_component failed: " + error.getMessage(),
                "check the record declaration and the construction sites");
        }
    }

    /** The record's own declaration node, by name. */
    private static RecordDeclaration recordNamed(CompilationUnit ast, String simpleName) {
        RecordDeclaration[] found = new RecordDeclaration[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(RecordDeclaration node) {
                if (found[0] == null && node.getName().getIdentifier().equals(simpleName)) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    /**
     * An EXPLICITLY declared canonical constructor, or null.
     *
     * <p>Canonicalness is by ARITY against the component count, which is what makes a
     * COMPACT constructor answer null here: a compact one declares no parameter list at all,
     * so its arity is zero and it is never mistaken for the explicit form. That is the whole
     * distinction this operation turns on — the compact form needs no edit, the explicit one
     * needs an assignment nobody can write for the caller.</p>
     */
    private static MethodDeclaration explicitCanonicalConstructor(RecordDeclaration declaration,
                                                                  int componentCount) {
        // A zero-component record's canonical constructor cannot be told from a COMPACT one
        // by arity, so it answers null here rather than guessing — which is the one case
        // where this question and constructorWithArity's differ.
        return componentCount == 0 ? null : constructorWithArity(declaration, componentCount);
    }

    /**
     * A declared constructor taking exactly {@code arity} parameters, or null.
     *
     * <p>D7g needed this WITHOUT the compact-form exclusion above: the secondary constructor
     * it generates takes the OLD component list, and when a record had no components that
     * list is empty — a signature an explicitly declared no-arg constructor would already
     * occupy, which the canonical-constructor question is deliberately blind to. Two
     * predicates, one implementation, and the single difference stated where it is made.</p>
     */
    private static MethodDeclaration constructorWithArity(RecordDeclaration declaration,
                                                          int arity) {
        for (Object member : declaration.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method
                    && method.isConstructor()
                    && method.parameters().size() == arity) {
                return method;
            }
        }
        return null;
    }

    /**
     * D7g — offer the other mode when the default is a SENTINEL, which is the signal the
     * field report named.
     *
     * <p>Asked three times to add an optional factor with default {@code Double.NaN}, this
     * operation correctly rewrote 78 construction sites in 24 files, and all three runs were
     * undone. The tool did what it says; what it did not do is notice that the ASK carried
     * the answer. <strong>A sentinel default is a value no site wanted</strong> — that is
     * what a sentinel means — so making 78 sites spell it is work with nothing on the other
     * side of it.</p>
     *
     * <p><strong>A DECLARED DEVIATION from the report's own wording, and the reason is that
     * the alternative is semantic rather than economic.</strong> It says to suggest the
     * overload when the default is a sentinel "and the site count is large". The sentinel
     * alone is the signal; a threshold on the count would be a number invented here, and the
     * argument does not depend on one — at two sites the overload is still the shape that
     * says "nobody passes this". So the suggestion fires on the sentinel at ANY site count,
     * and carries the count so the caller can weigh the cost themselves.</p>
     *
     * <p>It is a key on a SUCCESS response and is named for what it is. A {@code nextStep}
     * would read as unfinished work on a response whose operation completed; this is the
     * call a caller might have preferred, not one they still owe.</p>
     */
    private static ToolResponse withOverloadAlternative(ToolResponse response, String recordName,
                                                        String componentType,
                                                        String componentName,
                                                        String defaultValue, int constructions,
                                                        int files, boolean viaOverload) {
        if (viaOverload || constructions == 0 || !isSentinel(defaultValue)
                || !response.isSuccess() || !(response.getData() instanceof Map<?, ?> raw)) {
            return response;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) raw;
        Map<String, Object> alternative = new LinkedHashMap<>();
        alternative.put("operation", "data kind=add_record_component");
        alternative.put("arguments", Map.of(
            "typeName", recordName,
            "componentType", componentType,
            "componentName", componentName,
            "defaultValue", defaultValue,
            "viaSecondaryConstructor", true));
        alternative.put("why", "'" + defaultValue + "' is a sentinel, so no one of the "
            + constructions + " construction(s) across " + files + " file(s) this run rewrote "
            + "actually wanted the value — they now all spell a value that means absent. "
            + "viaSecondaryConstructor:true adds an overload taking the old component list "
            + "instead, changing ONE file and leaving every call site as it was. mcp D10 is "
            + "this exact case asked three times and undone three times.");
        data.put("overloadAlternative", alternative);
        return response;
    }

    /** A value whose meaning is "absent", which is what makes the overload the better shape. */
    private static boolean isSentinel(String defaultValue) {
        if (defaultValue == null) {
            return false;
        }
        String trimmed = defaultValue.trim();
        return trimmed.equals("null")
            || trimmed.equals("-1")
            || trimmed.endsWith("NaN")          // Double.NaN, Float.NaN, a static import
            || trimmed.endsWith("MIN_VALUE")
            || trimmed.endsWith(".empty()")     // Optional.empty()
            || trimmed.endsWith("EMPTY");
    }

    /**
     * D7g — the overload that keeps every existing construction compiling: the record's OLD
     * component list as parameters, delegating to the canonical constructor with the new
     * component's default.
     *
     * <p>The parameter list is copied from the declaration's own component nodes rather than
     * rebuilt from the names, so a generic or array type arrives exactly as the source
     * spells it — {@code List<String> names} and {@code int[] slots} are the cases a
     * name-plus-type reconstruction gets wrong.</p>
     *
     * <p>It is generated {@code public} deliberately. A record's canonical constructor is as
     * visible as the record, so an overload that was less visible would be unreachable from
     * precisely the call sites this mode exists to leave alone.</p>
     */
    private static String secondaryConstructor(String recordName,
                                               RecordDeclaration declaration,
                                               String defaultValue) {
        StringBuilder parameters = new StringBuilder();
        StringBuilder arguments = new StringBuilder();
        for (Object component : declaration.recordComponents()) {
            SingleVariableDeclaration declared = (SingleVariableDeclaration) component;
            if (parameters.length() > 0) {
                parameters.append(", ");
                arguments.append(", ");
            }
            parameters.append(declared.getType()).append(' ').append(declared.getName());
            arguments.append(declared.getName());
        }
        if (arguments.length() > 0) {
            arguments.append(", ");
        }
        arguments.append(defaultValue);
        return "public " + recordName + "(" + parameters + ") {\n"
            + "    this(" + arguments + ");\n"
            + "}";
    }

    /** Every {@code new <record>(...)} in this unit. */
    private static List<ClassInstanceCreation> constructionsOf(CompilationUnit ast, String fqn) {
        List<ClassInstanceCreation> found = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(ClassInstanceCreation node) {
                ITypeBinding binding = node.resolveTypeBinding();
                // DOTTED on both sides. IType.getFullyQualifiedName() separates a nested type
                // with '$' and ITypeBinding.getQualifiedName() with '.', so comparing the two
                // spellings silently matches nothing for a nested record — and a no-op that
                // reports success is the shape this codebase has been bitten by before.
                if (binding != null && fqn.equals(binding.getQualifiedName())) {
                    found.add(node);
                }
                return true;
            }
        });
        return found;
    }

    private static CompilationUnit parse(ICompilationUnit unit) throws Exception {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(unit);
        parser.setResolveBindings(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
