package org.jawata.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.change.ChangeEngine;
import org.jawata.mcp.change.PreparedRefactoring;
import org.jawata.mcp.models.CodeAddress;
import org.jawata.mcp.models.NextStep;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.shared.FormatterOptions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28g S5 / D9 row 3 — delete a NON-PRIVATE member nothing in the workspace references.
 *
 * <p>The census counts <b>seven</b> hand edits of this shape across two rows, and both name the
 * same cause. Row 75, five edits: <em>"the cleanup covers private members only; these were
 * public with 0 references (`find_references` proves it, git proved `#312` dead at the start
 * commit)"</em>. Row 76 adds two more on a public nested interface's members. The individual
 * sites are a pair of public constructors, {@code setCallback}, {@code setCancelDrains},
 * {@code routeFillToAlgo}/{@code routeCancelToAlgo}, and {@code startSLExitForPartialFill} —
 * every one deleted by a human who had already proved it dead.</p>
 *
 * <h2>Why this is not a kind on {@code apply_cleanup}, which the census names</h2>
 *
 * <p>Because that door's own law forbids it, in {@link
 * org.jawata.mcp.tools.statements.CleanupRule}'s words: <em>"apply_cleanup is a sweep. It takes
 * a kind and an optional file path, and no rule adds a parameter of its own — which is exactly
 * why row 8 could not live on this door"</em>. A rule receives one parsed file and returns an
 * edit; it cannot refuse, and it cannot be told anything. So an unnarrowed call would delete
 * every zero-reference public member in the workspace in one sweep, and the rule could not
 * decline. {@code remove_dead_code} is also the WRONG MECHANISM rather than merely the wrong
 * shape: it delegates to the compiler's unused-problem set, which by construction only sees
 * private members — the compiler cannot know whether a public member has a caller.</p>
 *
 * <p>It lands on {@code inline} instead, which is the door whose shipped kinds already DELETE —
 * {@code class}, {@code subclass} and {@code middle_man} all end in a deletion — and which
 * already gates on a reference COUNT: {@link InlineClassTool} requires exactly ONE user and
 * refuses more. This is the n=0 case of that family. With no references there is nothing to
 * fold into, so the fold is the empty operation and the deletion is all that remains.</p>
 *
 * <h2>A reference search is a weaker instrument than the compiler, and says so</h2>
 *
 * <p>This is the whole safety argument, and it is why the operation asks a question rather than
 * trusting its own result. "Zero references" here means zero in the projects that are LOADED.
 * It does not mean zero in the world: a consumer in another repository, a published artifact, a
 * reflective lookup or a framework binding is invisible to any search this tool can run, and no
 * amount of searching will reveal it. That is not a limitation to be improved away — it is a
 * fact about scope.</p>
 *
 * <p>So {@code consumersAreInWorkspace} is REQUIRED and has no default. The caller is the only
 * party who can know whether the enumeration is complete, and making them say it converts an
 * assumption the tool would otherwise make silently into a claim somebody made on purpose. The
 * response records it, so the decision is attributable afterwards.</p>
 *
 * <p>The refusals that remain are the cases where the search result is misleading rather than
 * incomplete, and each names what it saw:</p>
 *
 * <ul>
 *   <li><b>A private member</b> → {@code apply_cleanup kind=remove_dead_code}, where the
 *       COMPILER proves it dead. A search is the weaker proof and should not be used where the
 *       stronger one applies.</li>
 *   <li><b>A method that overrides or implements something</b> — refused BY NAME, so the caller
 *       is told to start at the supertype rather than being left to work out why a member with
 *       one reference is undeletable. <b>MEASURED, after two mutations predicted otherwise:
 *       this branch buys precision, not safety.</b> The reference search never returns zero for
 *       an override — JDT matches the supertype's own declaration, so {@code Service.shutdown}
 *       reports one reference at {@code Lifecycle.java}, and {@code Registry.toString} reports
 *       TEN because calls to {@code toString()} match polymorphically across the workspace.
 *       With this branch disabled both are still refused, one check later and less usefully.
 *       The first version of this entry claimed a zero-reference override was
 *       indistinguishable from a dead member; that was asserted rather than measured, and it
 *       is false of this engine.</li>
 *   <li><b>A method with implementors</b> — the census's interface-member case. Removing the
 *       declaration leaves every implementor's {@code @Override} dangling.</li>
 *   <li><b>Any reference at all</b>, with the sites listed, because the useful part of a
 *       refusal is where to look.</li>
 *   <li><b>A field sharing its declaration</b> with other fragments. Removing the declaration
 *       would take the siblings with it, and splitting a shared declaration is a different
 *       edit.</li>
 * </ul>
 */
public class RemoveUnreferencedMemberTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /** How many reference sites a refusal lists before it stops. */
    private static final int SITES_IN_A_REFUSAL = 10;

    /** Reached as {@code inline kind=unreferenced_member}. */
    @Override
    public String kindName() {
        return "unreferenced_member";
    }

    @Override
    public String kindSummary() {
        return """
            delete a NON-PRIVATE member — a method, constructor, field or
            nested type — that nothing in the loaded workspace references.
            The n=0 case of this door's family: with no references there is
            nothing to fold into, so only the deletion remains.
            Needs: the member's symbol (pkg.Type#member) or position, plus
            consumersAreInWorkspace, which has NO DEFAULT. "Zero references"
            means zero in the projects that are loaded — a consumer in
            another repository, a published artifact or a reflective lookup
            is invisible to any search, so the caller states whether the
            enumeration is complete rather than this tool assuming it.
            Refuses a PRIVATE member (apply_cleanup kind=remove_dead_code
            proves those with the compiler, which is the stronger proof), a
            method that overrides or implements anything — named as such so
            you start at the supertype, rather than being told it has one
            reference at the interface that declares it — a method with
            implementors, a field sharing its declaration with other
            fragments, and any member with a reference, listing the sites.""";
    }

    /** Structural: a published member stops existing, so every consumer's compile changes. */
    @Override
    public boolean isStructural() {
        return true;
    }

    public RemoveUnreferencedMemberTool(Supplier<IJdtService> serviceSupplier,
                                        RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "remove_unreferenced_member";
    }

    @Override
    public String getDescription() {
        return kindSummary();
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the member."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the member's name."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of the member's name."));
        properties.put("consumersAreInWorkspace", Map.of("type", "boolean",
            "description", "REQUIRED, no default. Your assertion that every consumer of this "
                + "member is in the loaded workspace. A reference search can only see what is "
                + "loaded, so a consumer in another repository, a published artifact, a "
                + "reflective lookup or a framework binding would not appear in it and no "
                + "search can find one. Passing true is a claim you are making, and the "
                + "response records it."));
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "member to delete"));
        schema.put("properties", properties);
        schema.put("required", List.of());
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
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
        // NO DEFAULT, deliberately: a boolean defaulted to false would read as "the caller said
        // no" and a boolean defaulted to true would make the assumption this operation exists to
        // stop making. Absent means absent, and the refusal says what is being asked.
        if (!arguments.has("consumersAreInWorkspace")
                || !arguments.get("consumersAreInWorkspace").isBoolean()) {
            return ToolResponse.invalidParameter("consumersAreInWorkspace",
                "required, and there is no default. This operation's proof is a reference search, "
                    + "which can only see the projects that are loaded — a consumer in another "
                    + "repository, a published artifact, a reflective lookup or a framework "
                    + "binding is invisible to it, and no search will reveal one. Pass true to "
                    + "state that every consumer is in this workspace; pass false to decline, "
                    + "which refuses the deletion.");
        }
        boolean consumersHere = arguments.get("consumersAreInWorkspace").asBoolean();

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IMember member)) {
                return ToolResponse.invalidParameter("position",
                    "Position does not resolve to a member; got "
                        + (element == null ? "null" : element.getClass().getSimpleName())
                        + ". This deletes a method, constructor, field or nested type.");
            }

            ToolResponse refusal = refuseIfUnsafe(service, member, consumersHere);
            if (refusal != null) {
                return refusal;
            }

            ICompilationUnit unit = member.getCompilationUnit();
            if (unit == null) {
                return ToolResponse.symbolNotFound(
                    "the member has no compilation unit, so it is not source this can edit");
            }

            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(unit);
            parser.setResolveBindings(true);
            parser.setBindingsRecovery(true);
            if (!(parser.createAST(null) instanceof CompilationUnit ast)) {
                return ToolResponse.symbolNotFound("could not parse " + unit.getElementName());
            }
            BodyDeclaration declaration = declarationOf(ast, member);
            if (declaration == null) {
                return ToolResponse.symbolNotFound("could not locate the declaration of "
                    + member.getElementName() + " in " + unit.getElementName());
            }
            // A field sharing its declaration: removing the node takes the siblings with it, and
            // splitting a shared declaration is a different edit than deleting a member.
            if (declaration instanceof FieldDeclaration field && field.fragments().size() > 1) {
                return ToolResponse.preconditionNotMet(
                    member.getElementName() + " shares one declaration with "
                        + (field.fragments().size() - 1) + " other field(s), so removing the "
                        + "declaration would delete them too. Split the declaration first.",
                    "SHARED_FIELD_DECLARATION");
            }

            ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
            rewrite.remove(declaration, null);
            Document document = new Document(unit.getSource());
            TextEdit edit = rewrite.rewriteAST(document, FormatterOptions.forGeneratedCode(ast));
            if (edit == null || edit.getChildrenSize() == 0) {
                return ToolResponse.preconditionNotMet(
                    "the rewrite produced no edit for " + member.getElementName(),
                    "NOTHING_TO_REMOVE");
            }

            Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
            editsByFile.put((IFile) unit.getResource(), List.of(edit));
            Change change = ChangeEngine.fromFileEdits(
                "remove " + member.getElementName(), editsByFile);

            return runRefactoring(service,
                new PreparedRefactoring(change, "remove unreferenced member "
                    + member.getElementName()),
                "inline kind=unreferenced_member", arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * The cases where deleting would be wrong, or where the search result does not mean what it
     * appears to. Null when none of them holds.
     */
    private ToolResponse refuseIfUnsafe(IJdtService service, IMember member,
                                        boolean consumersHere) throws Exception {
        if (Flags.isPrivate(member.getFlags())) {
            // The stronger proof exists for this case, so it should be used. D3a: the refusal
            // names the operation that does apply, with the address THAT operation takes — a
            // file, because remove_dead_code is a sweep. The coordinates are -1 rather than 0:
            // this step has no position, and 0 would name line one of the file.
            ICompilationUnit declaringUnit = member.getCompilationUnit();
            String declaringFile = declaringUnit == null || declaringUnit.getResource() == null
                ? null
                : declaringUnit.getResource().getLocation().toOSString();
            return ToolResponse.preconditionNotMet(
                member.getElementName() + " is private, and the COMPILER proves a private "
                    + "member dead — which is a stronger proof than any reference search. "
                    + "apply_cleanup kind=remove_dead_code removes it, and will also keep the "
                    + "side effect of an unused local's initializer, which a node deletion "
                    + "drops.",
                "MEMBER_IS_PRIVATE")
                .withNextStep(new NextStep("apply_cleanup kind=remove_dead_code",
                    new CodeAddress(declaringFile, -1, -1, null),
                    "the compiler's own unused-member set, not a search"));
        }

        if (!consumersHere) {
            return ToolResponse.preconditionNotMet(
                "consumersAreInWorkspace=false, so the reference search cannot prove "
                    + member.getElementName() + " is unused: a consumer outside the loaded "
                    + "projects would not appear in it. Nothing was written.",
                "CONSUMER_SCOPE_NOT_DECLARED");
        }

        if (member instanceof IMethod method) {
            // Asked FIRST so the refusal names the right thing. The reference check below
            // would refuse an override too — measured: JDT matches the supertype's own
            // declaration, so an override never reports zero — but it would report "one
            // reference at Lifecycle.java", which tells the caller nothing about why the
            // member cannot go. This branch is the difference between that and "start at the
            // supertype".
            IType overridden = overriddenDeclarer(service, method);
            if (overridden != null) {
                return ToolResponse.preconditionNotMet(
                    method.getElementName() + " overrides or implements "
                        + overridden.getElementName() + ", so its callers invoke it through "
                        + "that declaration and deleting it changes dispatch. A reference count "
                        + "cannot tell you that: an override always has at least the supertype's "
                        + "own declaration pointing at it. If the whole contract is dead, start "
                        + "at " + overridden.getElementName() + ".",
                    "MEMBER_OVERRIDES_A_SUPERTYPE");
            }
            List<SearchMatch> implementors =
                service.getSearchService().findOverridingMethods(method, SITES_IN_A_REFUSAL);
            if (implementors != null && !implementors.isEmpty()) {
                return ToolResponse.preconditionNotMet(
                    method.getElementName() + " is implemented or overridden in "
                        + implementors.size() + " place(s): " + sites(implementors)
                        + ". Removing the declaration leaves each one overriding nothing.",
                    "MEMBER_HAS_IMPLEMENTORS");
            }
        }

        List<SearchMatch> references =
            service.getSearchService().findAllReferences(member, SITES_IN_A_REFUSAL);
        if (references != null && !references.isEmpty()) {
            return ToolResponse.preconditionNotMet(
                member.getElementName() + " is referenced " + references.size()
                    + " time(s) — the useful half of this refusal is where: " + sites(references)
                    + ". A member with a caller is not dead code.",
                "MEMBER_IS_REFERENCED");
        }
        return null;
    }

    /**
     * The supertype that declares what this method overrides, or null when it overrides nothing.
     * Derived from the hierarchy rather than from an {@code @Override} annotation, which is
     * optional and so proves nothing by its absence.
     */
    private static IType overriddenDeclarer(IJdtService service, IMethod method) throws Exception {
        IType declaring = method.getDeclaringType();
        if (declaring == null) {
            return null;
        }
        IType[] supertypes = service.getSearchService().getAllSupertypes(declaring);
        if (supertypes == null) {
            return null;
        }
        for (IType supertype : supertypes) {
            for (IMethod candidate : supertype.getMethods()) {
                if (candidate.getElementName().equals(method.getElementName())
                        && candidate.getParameterTypes().length
                            == method.getParameterTypes().length) {
                    return supertype;
                }
            }
        }
        return null;
    }

    /** The declaration node of a member held as a model element, joined on its name range. */
    private static BodyDeclaration declarationOf(CompilationUnit ast, IMember member)
            throws Exception {
        ISourceRange range = member.getNameRange();
        if (range == null || range.getOffset() < 0) {
            return null;
        }
        ASTNode node = NodeFinder.perform(ast, range.getOffset(), range.getLength());
        while (node != null && !(node instanceof BodyDeclaration)) {
            node = node.getParent();
        }
        return (BodyDeclaration) node;
    }

    private static String sites(List<SearchMatch> matches) {
        List<String> out = new ArrayList<>();
        for (SearchMatch match : matches) {
            String where = match.getResource() == null ? "?"
                : match.getResource().getName();
            out.add(where + ":" + match.getOffset());
        }
        return String.join(", ", out);
    }
}
