package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.data.EncapsulateCollectionTool;
import org.jawata.mcp.tools.data.ChangeReferenceToValueTool;
import org.jawata.mcp.tools.data.EncapsulateFieldTool;
import org.jawata.mcp.tools.data.EncapsulateRecordTool;
import org.jawata.mcp.tools.data.HideDelegateTool;
import org.jawata.mcp.tools.data.IntroduceSpecialCaseTool;
import org.jawata.mcp.tools.data.RemoveSettingMethodTool;
import org.jawata.mcp.tools.data.ReplaceDerivedVariableWithQueryTool;
import org.jawata.mcp.tools.data.ReplacePrimitiveWithObjectTool;
import org.jawata.mcp.tools.data.SplitVariableTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code data} — the front door for operations that change the SHAPE OF STATE: how a field
 * is reached, what a collection hands out, where a derived value comes from.
 *
 * <p>Sprint 11 Phase E shipped it as one operation, {@code encapsulate_field}, and Stage 1
 * of sprint 28d-rescue renamed the tool to {@code data} in anticipation of the nine more
 * Fowler rows that belong beside it. Stage 5 is where those arrive, and this class is now
 * what it was renamed for: a router, not an operation.</p>
 *
 * <h2>It adopts the seam AS it grows, and that ordering was a decision</h2>
 *
 * <p>Stage 6a converted six front doors to {@link KindedTool} and {@link FrontDoor}. This one
 * was deliberately left out, because converting a one-kind door and then growing it to ten
 * does the work twice — and growing it by hand first, for a later stage to migrate, does it
 * twice AND lets the two drift in between. So the conversion happens here, in the change that
 * gives it its second kind.</p>
 *
 * <p><b>A published contract changes with it, and it is stated rather than discovered.</b>
 * {@code data} took no discriminator; it now requires {@code kind}, like every other routing
 * door. A caller who was passing {@code data(filePath=…, line=…, column=…)} must pass
 * {@code kind="encapsulate_field"} as well. The consumers are MCP CLIENTS rather than callers
 * of a symbol, so no search in this repository can enumerate them — the population is outside
 * it by construction, and that is a fact about the change rather than a gap in the checking.
 * Stage 1's {@code RENAMED_TOOLS} pointer already answers the older {@code encapsulate_field}
 * tool name; this is the second half of the same move.</p>
 */
public class DataTool extends AbstractRefactoringTool implements KindedTool {

    private final EncapsulateFieldTool encapsulateField;
    private final HideDelegateTool hideDelegate;
    private final IntroduceSpecialCaseTool specialCase;
    private final ReplacePrimitiveWithObjectTool replacePrimitive;
    private final EncapsulateCollectionTool encapsulateCollection;
    private final SplitVariableTool splitVariable;
    private final ReplaceDerivedVariableWithQueryTool replaceDerived;
    private final RemoveSettingMethodTool removeSettingMethod;
    private final EncapsulateRecordTool encapsulateRecord;
    private final ChangeReferenceToValueTool referenceToValue;

    public DataTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
        this.encapsulateField = new EncapsulateFieldTool(serviceSupplier, changeCache);
        // Row 16. The largest population any Stage 5 row answers: message_chains reports 273
        // findings on this repository, measured, and its own message already names this
        // refactoring as the cure.
        this.hideDelegate = new HideDelegateTool(serviceSupplier, changeCache);
        // Row 22. UNROUTED, deliberately and recorded: no shipped detector names Introduce
        // Special Case. See the delegate's own javadoc for the measurement.
        this.specialCase = new IntroduceSpecialCaseTool(serviceSupplier, changeCache);
        // Row 54. ALSO UNROUTED, and measured rather than inherited from the plan: the plan
        // credits it with primitive_obsession's findings, and that detector is a census of
        // PARAMETER LISTS naming two other rows as the cure. See the delegate's javadoc, and
        // CureCatalog.SHIPPED_BUT_UNROUTED, where the guard reads the reason.
        this.replacePrimitive = new ReplacePrimitiveWithObjectTool(serviceSupplier, changeCache);
        // Row 9. THE FIRST ROUTED ROW ON THIS DOOR: mutable_data's own message names
        // Encapsulate Collection in words, and its finding carries the accessor's file, line
        // and Class#method — which is this delegate's whole input.
        this.encapsulateCollection = new EncapsulateCollectionTool(serviceSupplier, changeCache);
        // Row 65, and Fowler's Remove Assignment to Parameter from the same implementation.
        // UNROUTED: no detector reports a variable serving two purposes, and none could run
        // it — its whole input is the NAME the second value should carry.
        this.splitVariable = new SplitVariableTool(serviceSupplier, changeCache);
        // Row 45. UNROUTED: nothing reports a field that duplicates a computation.
        // temporary_field is the nearest and reports something else, and already has one
        // route the tier model would downgrade if a second were bolted on.
        this.replaceDerived = new ReplaceDerivedVariableWithQueryTool(serviceSupplier,
            changeCache);
        // Row 37, and the first of this stage's three COMPOSED rows. UNROUTED: no detector
        // reports a field that should be settled at construction. Its own javadoc records two
        // measured departures from the plan's recipe — add_final cannot make a FIELD final,
        // and the delete atom would put a second engine on the file this row is rewriting.
        this.removeSettingMethod = new RemoveSettingMethodTool(serviceSupplier, changeCache);
        // Row 10, and this stage's first TRUE recipe: encapsulate_field once per public
        // field, through RecipeEngine, so the set reverts through one handle. UNROUTED —
        // find_modernization(class_to_record) reports the adjacent shape (a data class that
        // could BE a record) rather than this one.
        this.encapsulateRecord = new EncapsulateRecordTool(serviceSupplier, changeCache);
        // Row 2, and the last of the three. UNROUTED, and the plan says so in its own C2
        // clause: seven of the eight composed rows are callable from a finding and this one
        // is not, because nothing reports "this class should be a value" — that is a
        // modelling decision about the domain rather than a shape in the code.
        this.referenceToValue = new ChangeReferenceToValueTool(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "data";
    }

    @Override
    public String discriminator() {
        return "kind";
    }

    /**
     * Built from the typed fields, keyed by what each delegate calls itself.
     *
     * <p>Ten entries — the count Stage 9 assigns this door, reached when Stage 5's last three
     * rows landed. The list is a {@code List.of(...)} of fields for the same reason
     * {@code inline} and {@code generate} hold theirs that way: the delegates are typed fields
     * rather than a map. (It said "one entry today" until 2026-09-05, nine rows after that
     * stopped being true — a count in a comment, which nothing derives and nothing checks.)</p>
     */
    @Override
    public Map<String, KindDelegate> delegates() {
        Map<String, KindDelegate> published = new LinkedHashMap<>();
        for (KindDelegate delegate : List.of(encapsulateField, hideDelegate, specialCase,
                replacePrimitive, encapsulateCollection, splitVariable, replaceDerived,
                removeSettingMethod, encapsulateRecord, referenceToValue)) {
            published.put(delegate.kindName(), delegate);
        }
        return java.util.Collections.unmodifiableMap(published);
    }

    /** ASSEMBLED, not written — the seam's description algorithm, held not inherited. */
    @Override
    public String getDescription() {
        return FrontDoorDescription.ASSEMBLER.describe(this);
    }

    @Override
    public String preamble() {
        return """
            Change the SHAPE OF STATE: how a field is reached, what a collection hands
            out, and where a derived value comes from (behaviour-preserving, reversible).""";
    }

    @Override
    public String usageTail() {
        return ", filePath=..., line=..., column=...";
    }

    @Override
    public String kindBlockLeadIn() {
        return "Kinds (ZERO-BASED coordinates; a field may also be named as symbol=pkg.Type#field):";
    }

    @Override
    public String footer() {
        return """
            Applies by default; returns filesModified/diff/undoChangeId/summary. Pass
            auto_apply=false to stage only. A conflict — an accessor name that already
            exists, say — REFUSES and modifies nothing.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        // DERIVED from the routing table, like every other converted door: publishedKinds()
        // IS delegates().keySet(), so a kind cannot be dispatched and left out of the enum a
        // client reads in tools/list.
        kind.put("enum", publishedKinds());
        kind.put("description", "Which state-shape change to apply. See the tool description.");
        properties.put("kind", kind);

        // THE BACKSTOP, inherited rather than written out here: every parameter any delegate
        // declares reaches the published contract whether or not someone curated it above.
        schema.put("properties", withDelegateParameters(properties));
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind",
                "kind is required; one of " + publishedKinds());
        }
        KindDelegate delegate = delegates().get(kind);
        if (delegate == null) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + publishedKinds());
        }
        return switch (kind) {
            case "encapsulate_field" -> encapsulateField.executeWithService(service, arguments);
            case "hide_delegate" -> hideDelegate.executeWithService(service, arguments);
            case "special_case" -> specialCase.executeWithService(service, arguments);
            case "replace_primitive" -> replacePrimitive.executeWithService(service, arguments);
            case "encapsulate_collection" ->
                encapsulateCollection.executeWithService(service, arguments);
            case "split_variable" -> splitVariable.executeWithService(service, arguments);
            case "replace_derived_variable" ->
                replaceDerived.executeWithService(service, arguments);
            case "remove_setting_method" ->
                removeSettingMethod.executeWithService(service, arguments);
            case "encapsulate_record" ->
                encapsulateRecord.executeWithService(service, arguments);
            case "reference_to_value" ->
                referenceToValue.executeWithService(service, arguments);
            // Unreachable: the lookup above already refused an unrouted kind. It is here so
            // that a delegate added to the routing table and forgotten HERE fails loudly at
            // the call rather than being dispatched to whichever branch happened to be last.
            default -> ToolResponse.invalidParameter("kind",
                "'" + kind + "' is in the routing table and has no dispatch branch");
        };
    }
}
