package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code data kind=encapsulate_collection} — Fowler row 9, Encapsulate Collection.
 *
 * <p>A class hands back its own collection by name, so every caller holds the object's
 * insides and can change them without the object ever finding out. There is no setter to
 * remove and no mutator to narrow: the ACCESSOR is the defect. The cure is a read-only view
 * out, and the adds and removes moved onto the class where they can be seen.</p>
 *
 * <h2>THIS IS THE FIRST STAGE 5 ROW THAT A FINDING ACTUALLY OFFERS</h2>
 *
 * <p>Rows 16, 22 and 54 are unrouted, each for its own measured reason. This one is not:
 * {@code find_quality_issue kind=mutable_data} reports exactly this shape, and its message
 * names this refactoring in words — <em>"Consider Encapsulate Collection: return a read-only
 * view and put the modifications on this class."</em> The finding carries the file, the line
 * of the accessor's name and the symbol {@code Class#method}, which is precisely this
 * operation's input, so it is callable straight from the finding by position and by name.</p>
 *
 * <p><b>It acts on the ACCESSOR, not on the field, and that is what makes the above true.</b>
 * A field-addressed operation would have to be told which of the class's methods leaks it;
 * the finding names the method, so the method is the input and the field is derived from it.
 * </p>
 *
 * <h2>The view is EXACT, which is why the return type is checked rather than the field's</h2>
 *
 * <p>{@code Collections.unmodifiableList} returns a {@code List}, not an {@code ArrayList}.
 * So an accessor DECLARED to return {@code ArrayList} cannot return a view of itself, and
 * wrapping it would not compile. Rather than let the compile gate discover that, the mapping
 * below is from the accessor's declared return type to the wrapper whose own return type
 * matches it exactly — and a concrete return type is refused with the operation that widens
 * it named in the refusal.</p>
 *
 * <h2>What it refuses, and why each refusal is not merely caution</h2>
 *
 * <ul>
 *   <li><b>An accessor that does not hand the field back bare.</b> One that already returns
 *       {@code List.copyOf(items)} or {@code new ArrayList<>(items)} is already safe, and
 *       there is nothing here to do. This is the row's own control: the safe shape is refused
 *       BECAUSE it is safe, not by a special case listing the safe forms.</li>
 *   <li><b>A concrete or array return type.</b> No read-only VIEW of an array exists —
 *       {@code clone()} is a copy, and a copy and a view differ in whether later changes are
 *       seen, which is the caller's decision and not this operation's.</li>
 *   <li><b>A static field.</b> Static mutable state is {@code global_data}'s kind, and the
 *       detector splits them the same way; curing it here would fix it under one name and
 *       leave it reported under another.</li>
 * </ul>
 *
 * <h2>A mutator the class already has is a step already taken, NOT a collision</h2>
 *
 * <p>The first version refused when the adder or the remover already existed, and the fork
 * corpus said that was wrong on the commonest real shape. Upstream's {@code WorkCenter}
 * declares {@code addWorker} AND {@code removeWorker}, with exactly the bodies this would
 * generate, and still hands {@code workers} straight back: somebody took Fowler's second step
 * and never closed the accessor. That is precisely what {@code mutable_data} reports, so
 * refusing it declined the case the detector is most likely to find.</p>
 *
 * <p>So each mutator is generated only if it is MISSING, and the view is applied either way —
 * the leak is the defect, and it is still there whatever else the class has. The change's own
 * label says which were added and which were already present, because a caller who asked for
 * two methods and got one should be told why rather than left to diff.</p>
 */
public class EncapsulateCollectionTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /**
     * DECLARED RETURN TYPE to the wrapper whose own return type is exactly it.
     *
     * <p>Every entry is checkable against the JDK signature, which is the point: a mapping
     * that was approximately right would produce a change the compile gate undoes, and a
     * caller would learn only that "it did not work". {@code MutableTypes} in the detector
     * package answers a DIFFERENT question — is this type mutable at all, which is also true
     * of {@code StringBuilder} and {@code Date} — and sharing it would let a wrapper be
     * looked up for a type that has none.</p>
     */
    private static final Map<String, String> VIEW_FOR = Map.of(
        "Collection", "unmodifiableCollection",
        "List", "unmodifiableList",
        "Set", "unmodifiableSet",
        "SortedSet", "unmodifiableSortedSet",
        "NavigableSet", "unmodifiableNavigableSet",
        "Map", "unmodifiableMap",
        "SortedMap", "unmodifiableSortedMap",
        "NavigableMap", "unmodifiableNavigableMap");

    public EncapsulateCollectionTool(Supplier<IJdtService> serviceSupplier,
                                     RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String kindName() {
        return "encapsulate_collection";
    }

    @Override
    public String kindSummary() {
        return """
            stop handing out a collection field: the accessor returns a read-only
            view and the adds and removes move onto the owning class, where the
            object can see them. Point at the ACCESSOR — which is what a
            mutable_data finding names, by file, line and Class#method — not at
            the field. Refuses an accessor that already wraps or copies (it is
            already safe, so there is nothing to do), a concrete or array return
            type (no read-only view of one exists; widen it with
            change_method_signature first), and a static field (that is
            global_data's kind). Each refusal names which. A mutator the class
            ALREADY has is not refused — it is a step already taken, so it is
            skipped and the accessor is still closed, which is upstream's own
            commonest shape. (find_quality_issue kind=mutable_data locates
            candidates.)""";
    }

    /** Structural: it adds methods to the class's published surface. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getName() {
        return "encapsulate_collection";
    }

    @Override
    public String getDescription() {
        return "Encapsulate Collection — return a read-only view and put the modifications "
            + "on the owning class. Delegate of data.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the accessor."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the accessor — the line a mutable_data "
                + "finding reports."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column inside the accessor."));
        properties.put("itemName", Map.of("type", "string",
            "description", "Noun for the generated mutators — itemName=Course gives "
                + "addCourse/removeCourse (default: the field's name with a trailing 's' "
                + "dropped). Every call site reads it."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IMethod accessor)) {
                return ToolResponse.invalidParameter("position",
                    "position does not resolve to a method; got "
                        + (element == null ? "nothing" : element.getClass().getSimpleName())
                        + ". Encapsulate Collection acts on the ACCESSOR that hands the"
                        + " collection out — the symbol a mutable_data finding names.");
            }
            return encapsulate(service, accessor, getStringParam(arguments, "itemName"),
                arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(EncapsulateCollectionTool.class)
                .warn("encapsulate_collection failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse encapsulate(IJdtService service, IMethod accessor, String requestedNoun,
                                     JsonNode arguments) throws Exception {
        IType declaring = accessor.getDeclaringType();
        ICompilationUnit unit = accessor.getCompilationUnit();
        if (declaring == null || unit == null) {
            return ToolResponse.symbolNotFound(
                "'" + accessor.getElementName() + "' has no source declaring type here.");
        }
        CompilationUnit ast = parse(unit);
        MethodDeclaration declaration = methodNamed(ast, accessor.getElementName(),
            accessor.getNumberOfParameters());
        if (declaration == null || declaration.getBody() == null) {
            return ToolResponse.symbolNotFound("could not locate the body of "
                + accessor.getElementName() + " in its own source.");
        }

        // THE SAME SHAPE THE DETECTOR REPORTS, deliberately: a bare `return items;` or
        // `return this.items;`. An accessor that already wraps or copies is not this shape
        // and is refused below — which is the row's control rather than a listed exemption.
        IVariableBinding field = bareReturnedField(declaration);
        if (field == null) {
            return ToolResponse.invalidParameter("position",
                "'" + accessor.getElementName() + "' does not hand a field back bare, so"
                    + " there is nothing to encapsulate. An accessor that already returns"
                    + " List.copyOf(..), Collections.unmodifiableList(..), a new collection"
                    + " or a clone is already safe — that is the state this operation"
                    + " produces, not one it acts on.");
        }
        if (Modifier.isStatic(field.getModifiers())) {
            return ToolResponse.invalidParameter("position",
                "'" + field.getName() + "' is STATIC. Static mutable state is the"
                    + " global_data kind rather than this one, and the detectors split them"
                    + " the same way — curing it here would fix it under one name and leave"
                    + " it reported under the other.");
        }

        ITypeBinding returned = declaration.getReturnType2() == null
            ? null : declaration.getReturnType2().resolveBinding();
        if (returned == null) {
            return ToolResponse.symbolNotFound("could not resolve the return type of "
                + accessor.getElementName() + "; the file may not compile against this"
                + " workspace's classpath.");
        }
        String view = VIEW_FOR.get(returned.getErasure().getName());
        if (view == null) {
            return ToolResponse.invalidParameter("position",
                "'" + accessor.getElementName() + "' is declared to return "
                    + returned.getName() + ", which has no read-only VIEW. "
                    + (returned.isArray()
                        ? "An array has only clone(), which is a COPY — a copy and a view"
                            + " differ in whether the caller sees later changes, and choosing"
                            + " between them is your decision rather than this operation's."
                        : "Collections.unmodifiableList returns a List, not an "
                            + returned.getName() + ", so wrapping would not compile. Widen"
                            + " the return type to the interface first"
                            + " (change_method_signature kind=change_signature), then run"
                            + " this.")
                    + " Wrappable return types: " + VIEW_FOR.keySet().stream().sorted()
                        .toList());
        }

        String noun = requestedNoun != null && !requestedNoun.isBlank()
            ? requestedNoun
            : singularOf(field.getName());
        boolean isMap = returned.getErasure().getName().endsWith("Map");
        String adder = (isMap ? "put" : "add") + noun;
        String remover = "remove" + noun;
        // A MUTATOR THE CLASS ALREADY HAS IS A STEP ALREADY TAKEN, not a collision.
        //
        // The first version refused here, and the fork corpus said that was wrong on the
        // commonest real shape: upstream's `WorkCenter` declares addWorker AND removeWorker
        // with exactly the bodies this would generate, and still hands `workers` straight
        // back. Somebody did Fowler's second step and never closed the accessor — which is
        // precisely the state `mutable_data` reports, so refusing it declined the one case
        // the detector is most likely to find.
        //
        // So each mutator is generated only if it is missing, and the VIEW happens either
        // way: the leak is the defect, and it is still there whatever else the class has.
        // Matched on name and arity rather than exact parameter types, which is the
        // conservative direction — it can skip a legitimate overload, and never emits a
        // signature the class already declares.
        boolean hasAdder = declares(declaring, adder, isMap ? 2 : 1);
        boolean hasRemover = declares(declaring, remover, 1);

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        ImportRewrite imports = ImportRewrite.create(ast, true);
        String collections = imports.addImport("java.util.Collections");

        // The view, replacing only the returned EXPRESSION — the method keeps its signature,
        // its javadoc and everything else about it.
        ReturnStatement statement = bareReturn(declaration);
        rewrite.replace(statement.getExpression(), rewrite.createStringPlaceholder(
            collections + "." + view + "(" + statement.getExpression() + ")",
            ASTNode.METHOD_INVOCATION), null);

        // The mutators, which are the half that gives the class back its own state. Element
        // types go through ImportRewrite for the reason row 16 learned: a type argument from
        // another package written as a bare simple name does not resolve, and the compile
        // gate then declines the whole change.
        ITypeBinding[] elementTypes = returned.getTypeArguments();
        String generated = mutators(field.getName(), adder, remover, isMap, elementTypes,
            imports, hasAdder, hasRemover);
        if (!generated.isEmpty()) {
            AbstractTypeDeclaration owner = enclosingType(declaration);
            ListRewrite members = rewrite.getListRewrite(owner,
                owner.getBodyDeclarationsProperty());
            members.insertLast(rewrite.createStringPlaceholder(generated,
                ASTNode.METHOD_DECLARATION), null);
        }

        List<TextEdit> edits = new java.util.ArrayList<>();
        edits.add(rewrite.rewriteAST(new Document(unit.getSource()),
            FormatterOptions.forGeneratedCode(ast)));
        if (imports.hasRecordedChanges()) {
            edits.add(imports.rewriteImports(null));
        }
        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        byFile.put((IFile) unit.getResource(), edits);

        String label = "encapsulate collection " + declaring.getElementName() + "."
            + field.getName() + " (" + accessor.getElementName() + " returns a view; "
            + (hasAdder && hasRemover
                ? adder + " and " + remover + " were already here"
                : (hasAdder ? remover + " added, " + adder + " was already here"
                    : hasRemover ? adder + " added, " + remover + " was already here"
                        : adder + "/" + remover + " added")) + ")";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, byFile), label),
            "encapsulate_collection", arguments);
    }

    /**
     * The mutators the class is MISSING, typed from the collection's own type arguments.
     *
     * <p>Empty when the class already has both, which is not a failure: the accessor is
     * still being closed, and that was the defect. Upstream's {@code WorkCenter} is exactly
     * that case.</p>
     */
    private static String mutators(String fieldName, String adder, String remover,
                                   boolean isMap, ITypeBinding[] typeArguments,
                                   ImportRewrite imports, boolean hasAdder,
                                   boolean hasRemover) {
        // A RAW collection has no type arguments, and Object is then the only honest
        // element type — it is what `items.add(..)` already accepts on a raw field.
        String first = typeArguments.length > 0 ? imports.addImport(typeArguments[0]) : "Object";
        String second = isMap && typeArguments.length > 1
            ? imports.addImport(typeArguments[1]) : "Object";
        String noun = isMap ? "entry" : "element";
        String subject = isMap ? "map" : "collection";
        List<String> out = new java.util.ArrayList<>();
        if (!hasAdder) {
            out.add("/** Adds one " + noun + "; the " + subject
                + " itself stays this class's own. */\n"
                + "public void " + adder + "("
                + (isMap ? first + " key, " + second + " value" : first + " element") + ") {\n"
                + "    " + fieldName + (isMap ? ".put(key, value);" : ".add(element);")
                + "\n}");
        }
        if (!hasRemover) {
            out.add("/** Removes one " + noun + "; the " + subject
                + " itself stays this class's own. */\n"
                + "public void " + remover + "("
                + (isMap ? first + " key" : first + " element") + ") {\n"
                + "    " + fieldName + (isMap ? ".remove(key);" : ".remove(element);")
                + "\n}");
        }
        return String.join("\n\n", out);
    }

    /** Whether the type already declares a method of this name and arity. */
    private static boolean declares(IType type, String name, int arity) throws Exception {
        for (IMethod method : type.getMethods()) {
            if (method.getElementName().equals(name)
                    && method.getNumberOfParameters() == arity) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code courses} to {@code Course}, which is a GUESS and is why {@code itemName} exists.
     *
     * <p>Dropping a trailing {@code s} is right for the plural English nouns that name most
     * collection fields and wrong for the rest ({@code status}, {@code addresses}). The
     * caller can always say; this is what happens when they do not, and it is stated as a
     * default rather than as a rule.</p>
     */
    private static String singularOf(String fieldName) {
        String base = fieldName.endsWith("s") && fieldName.length() > 1
            ? fieldName.substring(0, fieldName.length() - 1)
            : fieldName;
        return Character.toUpperCase(base.charAt(0)) + base.substring(1);
    }

    /** The field a bare {@code return} hands back, or null — the detector's own shape. */
    private static IVariableBinding bareReturnedField(MethodDeclaration declaration) {
        ReturnStatement statement = bareReturn(declaration);
        if (statement == null) {
            return null;
        }
        IVariableBinding binding = switch (statement.getExpression()) {
            case SimpleName name when name.resolveBinding() instanceof IVariableBinding v -> v;
            case FieldAccess access -> access.resolveFieldBinding();
            case null, default -> null;
        };
        return binding != null && binding.isField() ? binding : null;
    }

    /** The first {@code return} whose expression is a bare name or {@code this.name}. */
    private static ReturnStatement bareReturn(MethodDeclaration declaration) {
        ReturnStatement[] found = new ReturnStatement[1];
        declaration.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                if (found[0] == null
                        && (node.getExpression() instanceof SimpleName
                            || node.getExpression() instanceof FieldAccess)) {
                    found[0] = node;
                }
                return false;
            }
        });
        return found[0];
    }

    private static MethodDeclaration methodNamed(CompilationUnit ast, String name, int arity) {
        MethodDeclaration[] found = new MethodDeclaration[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (found[0] == null && node.getName().getIdentifier().equals(name)
                        && node.parameters().size() == arity) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    private static AbstractTypeDeclaration enclosingType(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof AbstractTypeDeclaration type) {
                return type;
            }
        }
        return null;
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
