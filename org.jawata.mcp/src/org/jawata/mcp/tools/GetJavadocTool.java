package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Get parsed Javadoc documentation for a symbol.
 */
public class GetJavadocTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetJavadocTool.class);

    // jawata-mcp#8: Javadoc BLOCK tags only ever start a line. Anchoring the
    // boundary detection to a line start (MULTILINE ^) means an '@' that lives
    // mid-line — inside an inline tag, or in prose like "{@code @param}" — is
    // never mistaken for a block-tag boundary. Descriptions span until the next
    // line-anchored block tag or the end (DOTALL lets '.' cross newlines).
    private static final int TAG_FLAGS = Pattern.DOTALL | Pattern.MULTILINE;
    /** A block tag at a line start — the summary/tags boundary and each tag's terminator. */
    private static final Pattern BLOCK_TAG = Pattern.compile("^[ \\t]*@\\w", Pattern.MULTILINE);
    private static final String TAG_END = "(?=^[ \\t]*@|\\z)";
    private static final Pattern PARAM_PATTERN = Pattern.compile("^[ \\t]*@param\\s+(\\w+)\\s+(.*?)" + TAG_END, TAG_FLAGS);
    private static final Pattern RETURN_PATTERN = Pattern.compile("^[ \\t]*@return\\s+(.*?)" + TAG_END, TAG_FLAGS);
    private static final Pattern THROWS_PATTERN = Pattern.compile("^[ \\t]*@throws\\s+(\\S+)\\s+(.*?)" + TAG_END, TAG_FLAGS);
    private static final Pattern SEE_PATTERN = Pattern.compile("^[ \\t]*@see\\s+(.*?)" + TAG_END, TAG_FLAGS);
    private static final Pattern SINCE_PATTERN = Pattern.compile("^[ \\t]*@since\\s+(.*?)" + TAG_END, TAG_FLAGS);
    private static final Pattern DEPRECATED_PATTERN = Pattern.compile("^[ \\t]*@deprecated\\s+(.*?)" + TAG_END, TAG_FLAGS);
    private static final Pattern AUTHOR_PATTERN = Pattern.compile("^[ \\t]*@author\\s+(.*?)" + TAG_END, TAG_FLAGS);
    private static final Pattern VERSION_PATTERN = Pattern.compile("^[ \\t]*@version\\s+(.*?)" + TAG_END, TAG_FLAGS);

    public GetJavadocTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_javadoc";
    }

    @Override
    public String getDescription() {
        return """
            Get parsed Javadoc documentation for a symbol.

            USAGE: Position on any documented symbol
            OUTPUT: Parsed Javadoc with summary, @param, @return, @throws, etc.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            )
        ));
        schema.put("required", List.of("filePath", "line", "column"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required parameter missing");
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Must be >= 0");
        }
        if (column < 0) {
            return ToolResponse.invalidParameter("column", "Must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            IJavaElement element = service.getElementAtPosition(path, line, column);

            if (element == null) {
                return ToolResponse.symbolNotFound("No symbol found at position");
            }

            if (!(element instanceof IMember member)) {
                return ToolResponse.invalidParameter("position", "Symbol at position does not have Javadoc");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("symbol", element.getElementName());
            data.put("kind", getElementKind(element));

            // Get Javadoc source
            String javadoc = getJavadocSource(member);
            if (javadoc == null || javadoc.isBlank()) {
                data.put("hasDocumentation", false);
                return ToolResponse.success(data, ResponseMeta.builder()
                    .suggestedNextTools(List.of("get_hover_info for basic info"))
                    .build());
            }

            data.put("hasDocumentation", true);

            // Parse the Javadoc
            parseJavadoc(javadoc, data, element);

            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "find_references to find usages",
                    "get_type_members for related members"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting javadoc: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private String getJavadocSource(IMember member) {
        try {
            ISourceRange javadocRange = member.getJavadocRange();
            if (javadocRange == null) {
                return null;
            }

            ICompilationUnit cu = member.getCompilationUnit();
            if (cu == null) {
                return null;
            }

            String source = cu.getSource();
            if (source == null) {
                return null;
            }

            int start = javadocRange.getOffset();
            int end = start + javadocRange.getLength();

            if (start >= 0 && end <= source.length()) {
                return source.substring(start, end);
            }

            return null;
        } catch (JavaModelException e) {
            log.debug("Error getting javadoc source: {}", e.getMessage());
            return null;
        }
    }

    private void parseJavadoc(String javadoc, Map<String, Object> data, IJavaElement element) {
        // Clean up the javadoc
        String cleaned = javadoc
            .replaceAll("/\\*\\*", "")
            .replaceAll("\\*/", "")
            .replaceAll("(?m)^\\s*\\*\\s?", "")
            .trim();

        // jawata-mcp#8: split at the first LINE-ANCHORED block tag, with inline
        // tags still intact, so a mid-line '@' — inside an inline tag, or in
        // prose like "{@code @param}" — is never mistaken for a block-tag
        // boundary (which would truncate the summary and mint a bogus @param).
        // Inline tags are rendered to plain text only for what is displayed.
        Matcher boundary = BLOCK_TAG.matcher(cleaned);
        String summary;
        String tagSection;
        if (boundary.find()) {
            summary = cleaned.substring(0, boundary.start());
            tagSection = cleaned.substring(boundary.start());
        } else {
            summary = cleaned;
            tagSection = "";
        }

        summary = renderInlineTags(summary).trim();
        if (!summary.isEmpty()) {
            data.put("summary", summary);
        }

        // Parse @param tags
        List<Map<String, String>> params = new ArrayList<>();
        Matcher paramMatcher = PARAM_PATTERN.matcher(tagSection);
        while (paramMatcher.find()) {
            Map<String, String> param = new LinkedHashMap<>();
            param.put("name", paramMatcher.group(1).trim());
            param.put("description", renderInlineTags(paramMatcher.group(2)).trim());
            params.add(param);
        }
        if (!params.isEmpty()) {
            data.put("params", params);
        }

        // Parse @return
        Matcher returnMatcher = RETURN_PATTERN.matcher(tagSection);
        if (returnMatcher.find()) {
            data.put("returns", renderInlineTags(returnMatcher.group(1)).trim());
        }

        // Parse @throws
        List<Map<String, String>> throwsList = new ArrayList<>();
        Matcher throwsMatcher = THROWS_PATTERN.matcher(tagSection);
        while (throwsMatcher.find()) {
            Map<String, String> throwsEntry = new LinkedHashMap<>();
            throwsEntry.put("type", throwsMatcher.group(1).trim());
            throwsEntry.put("description", renderInlineTags(throwsMatcher.group(2)).trim());
            throwsList.add(throwsEntry);
        }
        if (!throwsList.isEmpty()) {
            data.put("throws", throwsList);
        }

        // Parse @see
        List<String> seeList = new ArrayList<>();
        Matcher seeMatcher = SEE_PATTERN.matcher(tagSection);
        while (seeMatcher.find()) {
            seeList.add(renderInlineTags(seeMatcher.group(1)).trim());
        }
        if (!seeList.isEmpty()) {
            data.put("see", seeList);
        }

        // Parse @since
        Matcher sinceMatcher = SINCE_PATTERN.matcher(tagSection);
        if (sinceMatcher.find()) {
            data.put("since", renderInlineTags(sinceMatcher.group(1)).trim());
        }

        // Parse @deprecated
        Matcher deprecatedMatcher = DEPRECATED_PATTERN.matcher(tagSection);
        if (deprecatedMatcher.find()) {
            data.put("deprecated", renderInlineTags(deprecatedMatcher.group(1)).trim());
        }

        // Parse @author
        List<String> authors = new ArrayList<>();
        Matcher authorMatcher = AUTHOR_PATTERN.matcher(tagSection);
        while (authorMatcher.find()) {
            authors.add(renderInlineTags(authorMatcher.group(1)).trim());
        }
        if (!authors.isEmpty()) {
            data.put("authors", authors);
        }

        // Parse @version
        Matcher versionMatcher = VERSION_PATTERN.matcher(tagSection);
        if (versionMatcher.find()) {
            data.put("version", renderInlineTags(versionMatcher.group(1)).trim());
        }
    }

    private static final Pattern INLINE_TAG = Pattern.compile("\\{@(\\w+)\\s*([^{}]*)\\}");

    /**
     * Replace Javadoc inline tags with readable plain text so no stray {@code @}
     * survives to be mistaken for a block-tag boundary (jawata-mcp#8):
     * {@code @link}/{@code @linkplain} render to their label, else the bare
     * reference; {@code @inheritDoc} renders to nothing; {@code @code},
     * {@code @literal}, {@code @value} and any other tag render to their
     * content verbatim.
     */
    static String renderInlineTags(String text) {
        if (text == null || text.indexOf('{') < 0) {
            return text;
        }
        Matcher m = INLINE_TAG.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String tag = m.group(1);
            String content = m.group(2).trim();
            String replacement = switch (tag) {
                case "link", "linkplain" -> {
                    int sp = content.indexOf(' ');
                    if (sp < 0) {
                        yield content.startsWith("#") ? content.substring(1) : content;
                    }
                    yield content.substring(sp + 1).trim();
                }
                case "inheritDoc" -> "";
                default -> content; // code, literal, value, …
            };
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String getElementKind(IJavaElement element) {
        return switch (element.getElementType()) {
            case IJavaElement.TYPE -> "Type";
            case IJavaElement.METHOD -> "Method";
            case IJavaElement.FIELD -> "Field";
            default -> "Unknown";
        };
    }
}
