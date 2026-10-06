package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.MoveInstanceMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.structure.MoveStaticMembersProcessor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Sprint 22a P1-a.1 — {@code move_method}: move an instance method onto the
 * type of one of its parameters or fields (JDT "Move Instance Method"),
 * rewriting every call site to invoke it on the new receiver.
 *
 * <h2>Two engines, because a static method and an instance method are not one problem</h2>
 *
 * <p>Sprint 28d-rescue row 23 found the same split in {@link MoveFieldTool} and this is the
 * same answer. An INSTANCE method moves onto a RECEIVER — some parameter or field whose type
 * takes it on — and the call sites are rewritten to invoke it there. A STATIC method has no
 * receiver at all: its call sites name the owning TYPE, so moving it is
 * {@link MoveStaticMembersProcessor}'s job, the IDE's own Move Static Members, and what it
 * needs is a destination type rather than a receiver.</p>
 *
 * <p>Which one runs is read off the method's own modifiers, not asked of the caller. A
 * caller moving a method should not have to know which engine the language implies, and
 * {@code targetType} is required only on the path that has no receiver to infer.</p>
 *
 * <p>Fowler files this as <b>Move Function</b> (row 38 in his numbering; row 24 here); the
 * static half is the one his own example uses.</p>
 *
 * <p>The composition-axis primitive: a method that lives on an owner class but
 * really operates on a collaborator ({@code Owner.reset(Cell c) { c.set(0); }})
 * moves onto the collaborator ({@code Cell.reset() { set(0); }}). Like
 * {@code pull_up} drives {@code PullUpRefactoringProcessor}, this drives the
 * internal {@link MoveInstanceMethodProcessor} directly — the 2024-09
 * descriptor exposes no public target setter. The target must be chosen
 * BETWEEN initial and final conditions (its {@link IVariableBinding} is bound
 * to the initial-conditions parse), so this uses
 * {@link AbstractRefactoringTool#runPreCheckedRefactoring}.</p>
 */
public class MoveMethodTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /** Reached as {@code move kind=method} — the static half included, by design. */
    @Override
    public String kindName() {
        return "method";
    }

    /** The bullet a client reads under {@code move} — moved here from the door (M5). */
    @Override
    public String kindSummary() {
        return """
            move a method to another type. Which engine runs is read off the
            method's own modifiers, not asked of you.
            An INSTANCE method moves onto a RECEIVER — one of its parameters
            or fields — and every call site is rewritten to invoke it there.
            Needs: the method's position (filePath, line, column) or its
            symbol, plus `target`, the parameter/field whose type receives it.
            `target` may be omitted when exactly one candidate exists; with
            several, the call is refused and lists them.
            A STATIC method has no receiver — its call sites name the owning
            TYPE — so it needs `targetType` instead, the destination's
            fully-qualified name, and JDT's Move Static Members repoints every
            qualified reference across the workspace.
            Optional keepDelegate leaves a forwarder behind on either path.""";
    }

    /** Structural: the method's receiver changes, so every call site's shape changes. */
    @Override
    public boolean isStructural() {
        return true;
    }


    public MoveMethodTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "move_method";
    }

    @Override
    public String getDescription() {
        return """
            Move an instance method onto the type of one of its parameters or
            fields (JDT "Move Instance Method"), updating every call site to
            invoke it on the new receiver.

            USAGE:
              move_method(filePath="src/main/java/com/example/Owner.java",
                          line=12, column=17, target="c")

            Inputs:
            - filePath / line / column — position inside the method to move
              (zero-based line/column).
            - target — name of the parameter or field whose type receives the
              method. Optional when the method has exactly one possible target;
              when there are several, the call is rejected with the list of
              options.
            - keepDelegate (default false) — leave a forwarding method on the
              original type instead of removing it and inlining the call sites.

            The target's type must be a source class you can add a method to.
            No valid target, or a conflict on the destination, →
            REFACTORING_FAILED with no files modified.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the method."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line number inside the method."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column number on the line."));
        properties.put("target", Map.of("type", "string",
            "description", "Name of the parameter/field whose type receives the method "
                + "(optional when there is exactly one possible target)."));
        properties.put("keepDelegate", Map.of("type", "boolean",
            "description", "Leave a forwarding method on the original type (default false)."));
        properties.put("targetType", Map.of("type", "string",
            "description", "For a STATIC method: fully-qualified name of the existing class "
                + "it moves to. A static method has no receiver, so `target` does not apply "
                + "to it and this does."));
        properties.put("oldOwnerAccess", Map.of(
            "type", "string",
            "enum", List.of("refuse", "parameter"),
            "description", "Only consulted when the moved body STILL REACHES the type it came "
                + "from, which the engine answers and this tool asks. Default 'refuse': the "
                + "move is declined and names what it would cost, because JDT's own answer is "
                + "to do it silently, and that silence sent eleven of Sprint 8's move edits to "
                + "hand work. 'parameter' takes it deliberately — and MEASURED it costs TWO "
                + "things rather than one: the old owner is added as a parameter, so the "
                + "destination's package comes to depend on the owner's, AND the engine WIDENS "
                + "the members the moved body reads, turning a private field public. The move "
                + "opens the old owner's encapsulation as well as inverting the dependency."));
        properties.put("oldOwnerName", Map.of("type", "string",
            "description", "With oldOwnerAccess=parameter: the name for the added parameter. "
                + "Omit to take the engine's suggestion."));
        properties.put("forbiddenPackage", Map.of("type", "string",
            "description", "A package prefix the DESTINATION must not come to depend on. With "
                + "oldOwnerAccess=parameter, a move that would create exactly that dependency "
                + "is refused before anything is written — the layering check applied to this "
                + "tool's own result, which no detector can do beforehand because the edge "
                + "does not exist yet."));
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "method to move"));
        schema.put("properties", properties);
        // Sprint 24 (D1): position OR name form.
        schema.put("required", List.of());
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 24 (D1): accept the name form — symbol=pkg.Type#method.
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String targetName = getStringParam(arguments, "target");
        boolean keepDelegate = getBooleanParam(arguments, "keepDelegate", false);

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
            if (!(element instanceof IMethod method)) {
                return ToolResponse.invalidParameter("position",
                    "Position does not resolve to a method; got "
                        + (element == null ? "null" : element.getClass().getSimpleName()));
            }

            if (Flags.isStatic(method.getFlags())) {
                return moveStaticMethod(service, method,
                    getStringParam(arguments, "targetType"), keepDelegate, arguments);
            }

            CodeGenerationSettings settings = new CodeGenerationSettings();
            MoveInstanceMethodProcessor processor = new MoveInstanceMethodProcessor(method, settings);
            ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);

            // Initial conditions parse the method and compute the possible targets.
            RefactoringStatus initial = refactoring.checkInitialConditions(new NullProgressMonitor());
            if (initial.hasFatalError()) {
                return ToolResponse.invalidParameter("move kind=method", formatStatus(initial));
            }

            IVariableBinding[] targets = processor.getPossibleTargets();
            if (targets == null || targets.length == 0) {
                return ToolResponse.invalidParameter("target",
                    "no valid move target — the method has no parameter or field of a "
                        + "type that can receive it (the destination must be a source class)");
            }

            IVariableBinding chosen = selectTarget(targets, targetName);
            if (chosen == null) {
                String options = Arrays.stream(targets).map(IVariableBinding::getName)
                    .collect(Collectors.joining(", "));
                if (targetName == null || targetName.isBlank()) {
                    return ToolResponse.invalidParameter("target",
                        "several possible targets — specify one of: " + options);
                }
                return ToolResponse.invalidParameter("target",
                    "target '" + targetName + "' is not a possible target; choose one of: " + options);
            }
            processor.setTarget(chosen);
            // Clean move by default: inline the call sites onto the new receiver
            // and remove the leftover forwarding method from the original type.
            processor.setInlineDelegator(!keepDelegate);
            processor.setRemoveDelegator(!keepDelegate);

            // Stage 5 / D9 row 2 — HOW THE MOVED BODY REACHES ITS OLD OWNER IS ASKED, NOT
            // DECIDED. The census names the defect in this tool's own verdict line: "how the
            // moved body reaches its old owner is MoveInstanceMethodProcessor's SILENT
            // choice", and the cost beside it: JDT adds the old owner as a PARAMETER, and
            // "every one of these bodies had to reach it through a port or a listener
            // instead, because `execution` may not name `pipeline`".
            //
            // THE SIZE, with each number attributed, because this comment has now carried three
            // and two of them were wrong. The signed spec scopes this change at SIXTEEN hand
            // edits (D9's table: "Move-method asks how the moved body reaches its old owner …
            // and checks the package rule on its own result | 16"), which is the contract and
            // covers the package-rule half as well. The census attributes ELEVEN specifically
            // to this cause in its own summary — "the moved body's access to its old owner
            // decided silently (11)" — being the move-with-changed-body row's ten plus the
            // extract-then-move row's fifth edit, whose note says it "needs the same old-owner
            // answer as the row above". The two count different things and both are real; the
            // numbers this comment carried first, ten and sixteen-as-the-cause, were a row
            // count and nothing's count respectively.
            //
            // `needsTargetNode()` is the engine's own answer to that question and it is
            // public, so it is ASKED rather than inferred — and asked AFTER setTarget, because
            // whether the body still needs the old owner depends on which receiver it moves
            // onto.
            // THE VALUE IS VALIDATED BEFORE THE GATE, and a C5 audit is why it had to move.
            // All of this used to sit inside `needsTargetNode()`, so on every move whose body
            // reaches nothing — which is most of them — `oldOwnerAccess=sideways` was accepted
            // in silence. That is exactly the failure the sibling D9 change names in its own
            // comment one file away: "which is how a caller ends up believing a flag did
            // something". A garbage mode is an error whatever the body does; only the two
            // BEHAVIOURAL arms depend on the gate.
            String access = getStringParam(arguments, "oldOwnerAccess");
            String mode = access == null || access.isBlank() ? "refuse" : access.strip();
            if (!"refuse".equals(mode) && !"parameter".equals(mode)) {
                return ToolResponse.invalidParameter("oldOwnerAccess",
                    "'" + access + "' is not a choice; one of: refuse, parameter");
            }

            if (processor.needsTargetNode()) {
                IType destination = chosen.getType() == null ? null
                    : (IType) chosen.getType().getJavaElement();
                String fromPackage = destination == null ? "the destination"
                    : destination.getPackageFragment().getElementName();
                String ownerPackage = method.getDeclaringType().getPackageFragment()
                    .getElementName();

                // TWO arms, not a switch with an unreachable default: the value is already
                // known to be one of the two, because it is validated above the gate.
                if ("parameter".equals(mode)) {
                    // JDT's own behaviour, now CHOSEN rather than defaulted into.
                    String name = getStringParam(arguments, "oldOwnerName");
                    if (name != null && !name.isBlank()) {
                        processor.setTargetName(name.strip());
                    }
                } else {
                    return ToolResponse.invalidParameter("oldOwnerAccess",
                            "the moved body still reaches " + method.getDeclaringType()
                                .getElementName() + ", so it cannot stand alone on "
                                + (destination == null ? "the destination"
                                    : destination.getElementName())
                                + ". JDT would do two things silently: add the old owner as a "
                                + "PARAMETER, making " + fromPackage + " depend on " + ownerPackage
                                + " — the edge behind eleven of Sprint 8's hand-edited move "
                                + "sites — and WIDEN the members the body reads, turning a "
                                + "private field public. Choose: oldOwnerAccess=parameter to "
                                + "take both deliberately, or re-home the body on a port or "
                                + "listener first and move it then.");
                }

                // THE PACKAGE RULE, CHECKED ON THIS TOOL'S OWN RESULT rather than left for a
                // later sweep to find. A caller who knows the layering says which package the
                // destination may not reach, and a move that would create exactly that edge is
                // refused before anything is written — which is the half the census asks for
                // and the half no detector can supply, because the edge does not exist yet.
                //
                // The `"parameter".equals(mode)` clause this condition used to open with is
                // GONE: the only other mode returns above, so it was necessarily true here and
                // read as a condition that could be false.
                String forbidden = getStringParam(arguments, "forbiddenPackage");
                if (forbidden != null && !forbidden.isBlank()
                        && ownerPackage.startsWith(forbidden.strip())) {
                    return ToolResponse.invalidParameter("forbiddenPackage",
                        "the move would add " + method.getDeclaringType().getElementName()
                            + " as a parameter, so " + fromPackage + " would depend on "
                            + ownerPackage + ", which this call declares forbidden. Nothing was "
                            + "written. Re-home the body on a port or listener the destination "
                            + "may already name, and move it then.");
                }
            }

            return runPreCheckedRefactoring(service, refactoring, "move_method", arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Pick the target variable: by name when {@code targetName} is given; else
     * the sole target when there is exactly one. Returns {@code null} when the
     * choice is ambiguous or the name doesn't match — the caller then reports
     * the available options.
     */
    private static IVariableBinding selectTarget(IVariableBinding[] targets, String targetName) {
        if (targetName != null && !targetName.isBlank()) {
            for (IVariableBinding t : targets) {
                if (targetName.equals(t.getName())) {
                    return t;
                }
            }
            return null;
        }
        return targets.length == 1 ? targets[0] : null;
    }

    /**
     * Row 24's half. A static method's call sites name its owning TYPE, so the move is the
     * IDE's Move Static Members: the declaration relocates and every qualified reference
     * across the workspace is repointed. There is no receiver to choose, which is why this
     * path wants a destination type instead and refuses without one.
     */
    private ToolResponse moveStaticMethod(IJdtService service, IMethod method,
                                          String targetType, boolean keepDelegate,
                                          JsonNode arguments) throws Exception {
        if (targetType == null || targetType.isBlank()) {
            return ToolResponse.invalidParameter("targetType",
                method.getElementName() + " is static, so it has no receiver to move onto and"
                    + " `target` does not apply to it. Name the class it moves to in"
                    + " `targetType`, fully qualified.");
        }
        IType declaring = method.getDeclaringType();
        if (declaring != null && targetType.equals(declaring.getFullyQualifiedName())) {
            return ToolResponse.invalidParameter("targetType",
                "The method already lives in " + targetType + "; nothing to move.");
        }

        MoveStaticMembersProcessor processor =
            new MoveStaticMembersProcessor(new IMember[] { method }, new CodeGenerationSettings());
        processor.setDestinationTypeFullyQualifiedName(targetType);
        // Unlike a moved FIELD, a forwarder here is a legitimate choice: it keeps an old
        // published entry point compiling while callers migrate. Off by default, because
        // leaving one silently is how a move stops being a move.
        processor.setDelegateUpdating(keepDelegate);
        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);

        RefactoringStatus initial = refactoring.checkInitialConditions(new NullProgressMonitor());
        if (initial.hasFatalError()) {
            return ToolResponse.invalidParameter("move kind=method", formatStatus(initial));
        }
        return runPreCheckedRefactoring(service, refactoring, "move_method", arguments);
    }

}
