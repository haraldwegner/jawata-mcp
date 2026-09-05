package org.jawata.mcp.tools.data;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.internal.corext.refactoring.sef.SelfEncapsulateFieldRefactoring;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@code data kind=encapsulate_field} — generate a getter and setter for a field, rewrite
 * every direct access through them, and tighten the field's visibility.
 *
 * <p>JDT's {@code EncapsulateFieldDescriptor} has no public setters, so this drives the
 * internal {@link SelfEncapsulateFieldRefactoring} directly. See
 * {@code docs/upgrade-checklist.md} for what to verify on Eclipse target-platform bumps.</p>
 *
 * <h2>Why this class exists at all (Stage 5)</h2>
 *
 * <p>This logic was the WHOLE of {@code DataTool}: the tool was one operation, renamed from
 * {@code encapsulate_field} to {@code data} by Stage 1 in anticipation of the nine more
 * operations Stage 5 adds. A door cannot route to itself, so the operation moves out here
 * and the door becomes a router — which is the same shape the six doors converted in Stage
 * 6a already have, reached from the other direction.</p>
 *
 * <p>Nothing about the operation changed in the move. The parameters, the defaults, the
 * refusals and the JDT call are the ones that shipped; what changed is that a caller now
 * names the kind, and that this class rather than the door is what publishes the
 * parameters — which is what lets the door stop describing an operation it no longer
 * performs.</p>
 */
public class EncapsulateFieldTool extends AbstractRefactoringTool implements ToolKindDelegate {

    public EncapsulateFieldTool(Supplier<IJdtService> serviceSupplier,
                                RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "encapsulate_field";
    }

    @Override
    public String getName() {
        return "encapsulate_field";
    }

    @Override
    public String getDescription() {
        return """
            generate a getter and setter for a field, rewrite every direct access
            through them, and tighten the field's visibility. Position on the field
            declaration or any reference to it, or name it with symbol=pkg.Type#field.
            Defaults: getterName is 'get' + Capitalized (or 'is' for a boolean),
            setterName is 'set' + Capitalized, newFieldVisibility is private.
            An accessor name that already exists is a conflict: it REFUSES and
            modifies nothing.""";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the field."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line number on the field."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column number on the line."));
        properties.put("getterName", Map.of("type", "string",
            "description", "Optional. Default: 'get' + Capitalized name; 'is' for boolean."));
        properties.put("setterName", Map.of("type", "string",
            "description", "Optional. Default: 'set' + Capitalized name."));
        properties.put("newFieldVisibility", Map.of("type", "string",
            "enum", List.of("public", "protected", "private", "package"),
            "description", "Visibility for the field after encapsulation (default 'private')."));
        properties.put("generateJavadoc", Map.of("type", "boolean",
            "description", "Emit Javadoc stubs on the generated accessors (default false)."));
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "field to encapsulate"));
        schema.put("properties", properties);
        // Sprint 24 (D1): position OR name form.
        schema.put("required", List.of());
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 24 (D1): accept the name form — symbol=pkg.Type#field.
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String getterName = getStringParam(arguments, "getterName");
        String setterName = getStringParam(arguments, "setterName");
        String visibilityStr = getStringParam(arguments, "newFieldVisibility", "private");
        boolean generateJavadoc = arguments != null && arguments.has("generateJavadoc")
            && arguments.get("generateJavadoc").asBoolean(false);

        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        int visibilityFlag;
        try {
            visibilityFlag = parseVisibility(visibilityStr);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("newFieldVisibility", e.getMessage());
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IField field)) {
                return ToolResponse.invalidParameter("position",
                    "Position does not resolve to a field; got "
                        + (element == null ? "null" : element.getClass().getSimpleName()));
            }

            String fieldName = field.getElementName();
            String resolvedGetter = getterName != null && !getterName.isBlank()
                ? getterName
                : defaultGetterName(field, fieldName);
            String resolvedSetter = setterName != null && !setterName.isBlank()
                ? setterName
                : defaultSetterName(fieldName);

            SelfEncapsulateFieldRefactoring refactoring = refactoringFor(field, resolvedGetter,
                resolvedSetter, visibilityFlag, generateJavadoc);

            // The operation name stays "data" — it is what the undo handle, the change cache
            // and the mechanical-change journal have always recorded, and renaming it here
            // would orphan every handle a caller is already holding.
            return runRefactoring(service, refactoring, "data", arguments);

        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(EncapsulateFieldTool.class)
                .warn("data kind=encapsulate_field failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * The configured refactoring for ONE field — the single construction of it.
     *
     * <p>Public because row 10 ({@code data kind=encapsulate_record}) is this operation run
     * over every public field of a class, and a recipe step owes the engine a {@code Change}
     * rather than a response. Sharing the construction rather than repeating it is what keeps
     * a change to the defaults, the visibility or {@code setEncapsulateDeclaringClass} from
     * reaching one caller and missing the other.</p>
     *
     * <p>{@code getterName}/{@code setterName} may be null or blank, and then the same
     * defaults the direct path uses apply — {@code is} for a boolean, {@code get} otherwise.
     * </p>
     */
    public static SelfEncapsulateFieldRefactoring refactoringFor(IField field, String getterName,
                                                                 String setterName,
                                                                 int visibility,
                                                                 boolean generateJavadoc)
            throws Exception {
        org.jawata.mcp.tools.shared.HeadlessJdtConfig.ensureInitialized();
        SelfEncapsulateFieldRefactoring refactoring = new SelfEncapsulateFieldRefactoring(field);
        refactoring.setGetterName(getterName != null && !getterName.isBlank()
            ? getterName : defaultGetterName(field, field.getElementName()));
        refactoring.setSetterName(setterName != null && !setterName.isBlank()
            ? setterName : defaultSetterName(field.getElementName()));
        refactoring.setVisibility(visibility);
        refactoring.setEncapsulateDeclaringClass(true);
        refactoring.setGenerateJavadoc(generateJavadoc);
        return refactoring;
    }

    private static int parseVisibility(String s) {
        if (s == null) {
            return Flags.AccPrivate;
        }
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "public"    -> Flags.AccPublic;
            case "protected" -> Flags.AccProtected;
            case "private"   -> Flags.AccPrivate;
            case "package"   -> Flags.AccDefault;
            default -> throw new IllegalArgumentException(
                "Unknown visibility '" + s + "'; expected public|protected|private|package");
        };
    }

    private static String defaultGetterName(IField field, String fieldName) throws Exception {
        String prefix = "Z".equals(field.getTypeSignature()) ? "is" : "get";
        return prefix + capitalize(fieldName);
    }

    private static String defaultSetterName(String fieldName) {
        return "set" + capitalize(fieldName);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
