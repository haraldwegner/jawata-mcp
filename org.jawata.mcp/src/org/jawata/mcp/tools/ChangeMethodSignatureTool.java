package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.api.ChangeSignatureTool;
import org.jawata.mcp.tools.api.IntroduceParameterObjectTool;
import org.jawata.mcp.tools.api.ReplaceQueryWithParameterTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code change_method_signature} — the front door for operations that change a method's
 * SIGNATURE and the call sites that follow it.
 *
 * <p>It shipped as a single operation and Stage 4 of sprint 28d-rescue gives it kinds: ten
 * Fowler rows belong on this door beside the signature edit it already performs. This class is
 * now a router rather than an operation, and the operation it used to be lives unchanged in
 * {@link ChangeSignatureTool} as {@code kind=change_signature}.</p>
 *
 * <h2>It adopts the seam AS it grows, which is the plan's own ordering</h2>
 *
 * <p>Stage 6a converted six front doors to {@link KindedTool} and left this one out on purpose:
 * it was not a front door then. Converting it first and growing it afterwards would do the work
 * twice, and growing it by hand for a later stage to migrate would do it twice AND let the two
 * drift in between. So the conversion happens here, in the change that gives it its kinds.</p>
 *
 * <p><b>A published contract changes with it, stated rather than discovered.</b> This tool took
 * no discriminator; it now requires {@code kind}, like every other routing door. A caller
 * passing {@code change_method_signature(filePath=…, line=…, column=…, newName=…)} must pass
 * {@code kind="change_signature"} as well. Two things make that acceptable rather than a
 * silent break: {@code data} made the identical change one stage earlier and this door
 * following it is worth more than a compatibility default that would leave one door with an
 * implicit kind; and the schema announces it, since {@code kind} is published as required and
 * an agent reads {@code tools/list} before calling. The consumers are MCP clients rather than
 * callers of a symbol, so no search in this repository can enumerate them — Harald confirmed
 * 2026-09-05 that the calling agent and jawata-studio are the only ones.</p>
 */
public class ChangeMethodSignatureTool extends AbstractRefactoringTool implements KindedTool {

    private final ChangeSignatureTool changeSignature;
    private final IntroduceParameterObjectTool introduceParameterObject;
    private final ReplaceQueryWithParameterTool replaceQueryWithParameter;

    public ChangeMethodSignatureTool(Supplier<IJdtService> serviceSupplier,
                                     RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
        this.changeSignature = new ChangeSignatureTool(serviceSupplier, changeCache);
        this.introduceParameterObject =
            new IntroduceParameterObjectTool(serviceSupplier, changeCache);
        this.replaceQueryWithParameter =
            new ReplaceQueryWithParameterTool(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "change_method_signature";
    }

    @Override
    public String discriminator() {
        return "kind";
    }

    /**
     * Built from the typed fields, keyed by what each delegate calls itself.
     *
     * <p>One entry today, and Stage 4's ten rows are the rest. The list is a
     * {@code List.of(...)} of fields for the same reason {@code data} and {@code inline} hold
     * theirs that way — the delegates are typed fields rather than a map.</p>
     */
    @Override
    public Map<String, KindDelegate> delegates() {
        Map<String, KindDelegate> published = new LinkedHashMap<>();
        for (KindDelegate delegate : List.of(changeSignature, introduceParameterObject,
            replaceQueryWithParameter)) {
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
            Change a method's SIGNATURE and the call sites that follow it — its name,
            return type, parameters and visibility (behaviour-preserving, reversible).""";
    }

    @Override
    public String usageTail() {
        return ", filePath=..., line=..., column=...";
    }

    @Override
    public String kindBlockLeadIn() {
        return "Kinds (ZERO-BASED coordinates; a method may also be named as "
            + "symbol=pkg.Type#method):";
    }

    @Override
    public String footer() {
        return """
            Applies by default; returns filesModified/diff/undoChangeId/summary. Pass
            auto_apply=false to stage only.

            COUPLED CHANGES: some changes cannot leave the code compiling on their own —
            removing a parameter the body still uses, or a return-type change a
            value-returning body cannot satisfy. These ARE APPLIED anyway; the response
            marks coupledChange: true and lists every introduced compiler error as your
            worklist. The reported error locations ARE the edits to make — do not fall
            back to a text search.

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
        // DERIVED from the routing table: publishedKinds() IS delegates().keySet(), so a kind
        // cannot be dispatched and left out of the enum a client reads in tools/list.
        kind.put("enum", publishedKinds());
        kind.put("description", "Which signature change to apply. See the tool description.");
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
            case "change_signature" -> changeSignature.executeWithService(service, arguments);
            case "introduce_parameter_object" ->
                introduceParameterObject.executeWithService(service, arguments);
            case "replace_query_with_parameter" ->
                replaceQueryWithParameter.executeWithService(service, arguments);
            // Unreachable: the lookup above already refused an unrouted kind. It is here so
            // that a delegate added to the routing table and forgotten HERE fails loudly at
            // the call rather than being dispatched to whichever branch happened to be last.
            default -> ToolResponse.invalidParameter("kind",
                "'" + kind + "' is in the routing table and has no dispatch branch");
        };
    }
}
