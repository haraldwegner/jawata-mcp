package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.EncapsulationAudit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 22a P1-c — {@code analyze(kind="encapsulation")}: the composed
 * encapsulation audit.
 *
 * <p>{@code find_field_writes} alone answers "who writes this field directly",
 * which reports INTERNAL-ONLY for the classic leak: a private field guarded by
 * a public setter. The field is only ever written inside its own class (by the
 * setter), yet any external caller of that setter is effectively mutating it.
 * This audit closes the gap by composing, per field, the EFFECTIVE external
 * mutators:</p>
 *
 * <pre>
 *   poke-set(field) = { external types that write the field directly }
 *                   ∪ { external types that call a method of the class
 *                       whose body writes the field }
 * </pre>
 *
 * <p>A field with a non-empty poke-set is flagged as an encapsulation leak —
 * external code can change its value, directly or through a mutator — the
 * owner/poke partition the Sprint 6d book-flatten postmortem needed.</p>
 */
public class AnalyzeEncapsulationTool extends AbstractTool {

    public AnalyzeEncapsulationTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "analyze_encapsulation";
    }

    @Override
    public String getDescription() {
        return """
            Composed encapsulation audit for a type. Per field, computes the
            EFFECTIVE external mutators — external direct writers UNION external
            callers of the class's field-writing methods — so a private field
            behind a public setter that outside code calls is flagged, where
            find_field_writes alone reports internal-only.

            Needs: typeName. Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("typeName", Map.of("type", "string",
            "description", "Fully qualified or simple type name to audit."));
        schema.put("properties", properties);
        schema.put("required", List.of("typeName"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Required parameter missing");
        }
        try {
            IType type = service.findType(typeName);
            if (type == null) {
                return ToolResponse.symbolNotFound("Type not found: " + typeName);
            }

            List<Map<String, Object>> fieldReports = new ArrayList<>();
            int leakingFields = 0;

            // The computation itself lives in EncapsulationAudit, shared with the
            // `encapsulation` sweep kind (Sprint 28d). `true` keeps THIS tool's
            // Sprint 22a behaviour exactly: a constructor that assigns a field
            // counts as a mutator of it, so its external callers are in the poke
            // set. The sweep passes false — see EncapsulationAudit's javadoc.
            for (EncapsulationAudit.FieldAudit audit
                    : EncapsulationAudit.auditType(type, service, true)) {
                if (audit.leak()) {
                    leakingFields++;
                }
                Map<String, Object> fr = new LinkedHashMap<>();
                fr.put("field", audit.field());
                fr.put("private", audit.isPrivate());
                fr.put("directExternalWriters", new ArrayList<>(audit.directExternalWriters()));
                fr.put("mutatingMethods", audit.mutatingMethods());
                fr.put("externalMutatorCallers", new ArrayList<>(audit.externalMutatorCallers()));
                fr.put("pokeSetCount", audit.pokeSetCount());
                fr.put("encapsulationLeak", audit.leak());
                fieldReports.add(fr);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("type", type.getFullyQualifiedName());
            data.put("fields", fieldReports);
            data.put("leakingFields", leakingFields);
            return ToolResponse.success(data);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    // enclosingType / enclosingMethod / sameType moved with the computation into
    // EncapsulationAudit (Sprint 28d) — they were only ever this audit's helpers.
}
