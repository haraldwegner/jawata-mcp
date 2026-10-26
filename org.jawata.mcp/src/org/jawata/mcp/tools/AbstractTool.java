package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.core.exceptions.ProjectNotLoadedException;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Abstract base class for JAWATA tools.
 * Provides common functionality to reduce boilerplate in tool implementations.
 *
 * <p>Subclasses should:
 * <ul>
 *   <li>Call super constructor with the service supplier</li>
 *   <li>Implement getName(), getDescription(), getInputSchema()</li>
 *   <li>Override executeWithService() instead of execute()</li>
 * </ul>
 *
 * <p>Example usage:
 * <pre>{@code
 * public class MyTool extends AbstractTool {
 *     public MyTool(Supplier<IJdtService> serviceSupplier) {
 *         super(serviceSupplier);
 *     }
 *
 *     @Override
 *     protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
 *         // Tool implementation - see the nullness contract below
 *         return ToolResponse.success(data);
 *     }
 * }
 * }</pre>
 *
 * <p><b>Is {@code service} non-null? It depends on what the tool DECLARED.</b>
 * For a tool that requires a loaded project — nearly all of them — yes: the
 * gate in {@link #execute} answers for every empty case before the body runs.
 * For a tool that overrides {@link #requiresLoadedProject()} to {@code false}
 * ({@code debug}, {@code profile}, {@code field}), NO: declaring independence
 * buys the body the right to RUN, not a service to run against, and the seam
 * hands it whatever the supplier holds — including {@code null}, which is
 * exactly the state those tools exist to answer in.
 *
 * <p>So an independent tool that reads the model must guard and answer its
 * OWN typed refusal. An unguarded read throws out of {@code execute()} and
 * comes back as INTERNAL_ERROR "this may be a bug" — blaming us for a state
 * the user owns, and leaving the agent nothing to act on. This javadoc used
 * to promise non-null unconditionally, and {@code profile}'s two
 * model-resolving actions were written against that promise.
 */
public abstract class AbstractTool extends WorkspaceTool<IJdtService> {

    protected final Supplier<IJdtService> serviceSupplier;

    protected AbstractTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.serviceSupplier = serviceSupplier;
    }

    /**
     * Get the IJdtService, or null if no project is loaded.
     * Prefer using executeWithService() which handles the null check.
     */
    protected IJdtService getService() {
        return serviceSupplier.get();
    }

    /**
     * Get the IJdtService, throwing if no project is loaded.
     *
     * @throws ProjectNotLoadedException if no project has been loaded
     */
    protected IJdtService requireService() {
        IJdtService service = serviceSupplier.get();
        if (service == null) {
            throw new ProjectNotLoadedException();
        }
        return service;
    }

    /** The gate and the {@code projectKey} scoping are {@link WorkspaceTool}'s; what remains is Java's step after them. */
    @Override
    protected final ToolResponse executeWithWorkspace(IJdtService workspace, JsonNode arguments) {
        return executeWithService(workspace, arguments);
    }

    /**
     * Decorate a tool's input schema with an optional {@code projectKey}
     * property documenting the Sprint 10 multi-project scoping convention.
     * Tools call this from {@link #getInputSchema()} so the parameter shows
     * up in the MCP {@code tools/list} response.
     *
     * <p>Returns a fresh map; the original is not mutated. Works for tools
     * whose properties map is immutable (e.g. {@code Map.of(...)}).
     */
    @SuppressWarnings("unchecked")
    protected static Map<String, Object> withProjectKey(Map<String, Object> schema) {
        Map<String, Object> wrapped = new LinkedHashMap<>(schema);
        Map<String, Object> oldProps = (Map<String, Object>) wrapped.getOrDefault("properties", Map.of());
        Map<String, Object> newProps = new LinkedHashMap<>(oldProps);
        newProps.putIfAbsent("projectKey", Map.of(
            "type", "string",
            "description", "Optional. Restrict the query to a single loaded project (see project(action=list)). Omit to search all projects in the workspace."
        ));
        wrapped.put("properties", newProps);
        return wrapped;
    }

    /**
     * Decorate a refactoring tool's input schema with the Sprint 14b
     * {@code auto_apply} flag. Mirror of {@link #withProjectKey}.
     */
    @SuppressWarnings("unchecked")
    protected static Map<String, Object> withAutoApply(Map<String, Object> schema) {
        Map<String, Object> wrapped = new LinkedHashMap<>(schema);
        Map<String, Object> oldProps = (Map<String, Object>) wrapped.getOrDefault("properties", Map.of());
        Map<String, Object> newProps = new LinkedHashMap<>(oldProps);
        newProps.putIfAbsent("auto_apply", Map.of(
            "type", "boolean",
            "description", "Default true: perform the refactoring immediately and return "
                + "{ filesModified, diff, undoChangeId }. Set false to stage only — returns "
                + "{ changeId, diff } for a later refactoring(action=apply) call."
        ));
        // D7f: the escape from the response cap, declared HERE for the reason auto_apply is
        // — it is the same choice for every refactoring, and a flag each tool spelled for
        // itself is a flag some tool will spell differently.
        newProps.putIfAbsent("fullDiff", Map.of(
            "type", "boolean",
            "description", "Default false: the response carries diffSummary (one row per "
                + "file with its hunk and line counts) and the diff text capped at "
                + org.jawata.mcp.change.DiffView.DEFAULT_MAX_CHARS
                + " characters, setting truncated:true when it was cut. Set true for the "
                + "whole diff — a 144-reference rename answered 167,785 characters, which "
                + "a client could not display at all."
        ));
        wrapped.put("properties", newProps);
        return wrapped;
    }

    /**
     * Writes a unified diff into a response the way every response presents one.
     *
     * <p><strong>This exists because publishing the flag and honouring it were in different
     * places.</strong> D7f capped the diff on the two refactoring BASES and published
     * {@code fullDiff} from {@link #withAutoApply}, which 81 tools call. A tool that extends
     * this class DIRECTLY assembles its own response map and so never reached a base's cap —
     * and the C3 reviews measured the cost independently of one another: <strong>14 sites in
     * 8 classes</strong> ({@code ComposeMethodTool}, {@code DecomposeConditionalTool} and the
     * six {@code codegen} tools) advertised the escape flag and sent the uncapped payload
     * anyway. That is the shape this codebase already records one file away, in
     * {@code ExtractMethodTool}: <em>"the parameter was published, accepted, echoed back, and
     * inert."</em></p>
     *
     * <p>So the cap now lives where the flag is published. A site that writes
     * {@code data.put("diff", …)} for a unified diff bypasses both halves, and after this
     * there is no reason left to — which is what makes a bypass a defect rather than a style.</p>
     *
     * @param data      the response's data map, written in place
     * @param diff      the rendered unified diff; an empty string is legitimate input, which
     *                  is {@code DiffRenderer}'s own documented behaviour for a move or rename
     * @param arguments the call's arguments, read ONLY for {@code fullDiff}
     */
    protected static void putDiff(Map<String, Object> data, String diff, JsonNode arguments) {
        org.jawata.mcp.change.DiffView
            .of(diff, org.jawata.mcp.change.DiffView.fullRequested(arguments))
            .putInto(data);
    }

    /**
     * Says, on a STAGED response, that nothing has been compiled yet.
     *
     * <p><strong>Why this is not left implicit.</strong> Both refactoring bases return from
     * their staged branch BEFORE the compile gate runs — measured at C3:
     * {@code AbstractApplyingRefactoringTool} returns at 218 with the gate at 237, and
     * {@code AbstractRefactoringTool} returns at 322 with the gate at 341. The APPLIED branch
     * writes {@code compileVerified}; the staged branch wrote no verification claim at all,
     * which reads as verified by omission — a caller comparing the two responses finds the key
     * present on one and absent on the other and has no way to tell "not verified" from "we
     * did not say".</p>
     *
     * <p><strong>This is deliberately NOT the expensive fix.</strong> An architect run proposed
     * gating the staged path too; that was declined on measured grounds — on APPLY the D7b
     * defect was never silent (it produced four loud compile errors), and gating staging would
     * buy an apply-check-revert on every staged call, so staging would stop being free.
     * Harald's ruling was <em>"SO make the tiny solution"</em>, and declining the expensive one
     * is not itself a solution. Saying what is true costs nothing and closes the ambiguity,
     * which is the same defect shape five of this stage's eight engine defects share.</p>
     */
    protected static void putStagedUnverified(Map<String, Object> data) {
        data.put("compileVerified", false);
        data.put("verificationNote", "A staged change is not compiled until it is applied, so "
            + "this diff is unverified. refactoring(action=apply) runs the compile gate and will "
            + "refuse or report errors then.");
    }

    /**
     * Execute the tool. Subclasses should override this instead of execute().
     *
     * @param service The IJdtService — non-null for a tool that requires a
     *                loaded project (the gate answered for every empty case
     *                first), but possibly {@code null} for one that overrode
     *                {@link #requiresLoadedProject()} to {@code false}. Such a
     *                tool must guard any read and answer its own typed
     *                refusal; see the class javadoc.
     * @param arguments The tool arguments
     * @return The tool response
     */
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Default implementation for backwards compatibility
        // Subclasses should override this method
        throw new UnsupportedOperationException(
            "Subclass must override executeWithService() or execute()");
    }

    // Common helper methods for parameter extraction

    

    // ========== SearchMatch formatting helpers ==========

    /**
     * Format a list of SearchMatch results into structured output.
     * Extracts file path, line, column, and context for each match.
     *
     * @param matches The search matches to format
     * @param service The JDT service for path and position resolution
     * @return List of formatted match info maps
     */
    protected List<Map<String, Object>> formatMatches(List<SearchMatch> matches, IJdtService service) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (SearchMatch match : matches) {
            Map<String, Object> info = formatMatch(match, service);
            if (info != null) {
                results.add(info);
            }
        }
        return results;
    }

    /**
     * Format a single SearchMatch into structured output.
     *
     * @param match The search match to format
     * @param service The JDT service for path and position resolution
     * @return Formatted match info map, or null if formatting fails
     */
    protected Map<String, Object> formatMatch(SearchMatch match, IJdtService service) {
        try {
            Map<String, Object> info = new LinkedHashMap<>();
            ICompilationUnit cu = null;

            // Try to get ICompilationUnit from the match element
            Object element = match.getElement();
            if (element instanceof org.eclipse.jdt.core.IType type) {
                // For IType, use getCompilationUnit() directly (handles source types properly)
                cu = type.getCompilationUnit();
            } else if (element instanceof org.eclipse.jdt.core.IMember member) {
                // For methods/fields, get the CU from the declaring type
                cu = member.getCompilationUnit();
            } else if (element instanceof IJavaElement javaElement) {
                // Fallback to ancestor traversal
                cu = (ICompilationUnit) javaElement.getAncestor(IJavaElement.COMPILATION_UNIT);
            }

            // For TypeReferenceMatch, also check local element if main element didn't give us a CU
            if (cu == null && match instanceof org.eclipse.jdt.core.search.TypeReferenceMatch typeRefMatch) {
                IJavaElement localElement = typeRefMatch.getLocalElement();
                if (localElement != null) {
                    if (localElement instanceof org.eclipse.jdt.core.IMember member) {
                        cu = member.getCompilationUnit();
                    } else {
                        cu = (ICompilationUnit) localElement.getAncestor(IJavaElement.COMPILATION_UNIT);
                    }
                }
            }

            // Get file path - prefer from ICompilationUnit for accurate path
            if (cu != null && cu.getResource() != null) {
                IPath location = cu.getResource().getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
            } else if (match.getResource() instanceof org.eclipse.core.resources.IFile file) {
                // The compilation unit did not resolve, but the match still
                // names a real FILE — report it, knowing there will be no line
                // (the line/column block below needs the CU).
                IPath location = file.getLocation();
                if (location != null) {
                    info.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
                }
                info.put("unresolved", true);
            } else if (match.getResource() != null) {
                // Sprint 28 (v3.6.1): a match whose resource is a CONTAINER (the
                // project or a folder) has no location to report. This used to
                // be written into `filePath` anyway, so the row read as a
                // result and pointed at a DIRECTORY with no line — the same
                // shape as mcp#5, which v3.6.0 fixed for find_references only.
                // Observed live on a PDE project: one row per search carrying
                // the synthesized-workspace directory.
                //
                // Do not drop the row either — a silently discarded match is
                // this project's recorded deepest bug class. Say what it is:
                // the match is real, its location is not resolvable.
                IPath location = match.getResource().getLocation();
                info.put("unresolved", true);
                info.put("unresolvedReason",
                    "the match could not be tied to a source file, so it has no path or line"
                        + (location == null ? "" : " (container: " + location.toOSString() + ")"));
            }

            // Offset and length
            info.put("offset", match.getOffset());
            info.put("length", match.getLength());

            // Line, column, and context (requires ICompilationUnit)
            if (cu != null && match.getOffset() >= 0) {
                info.put("line", service.getLineNumber(cu, match.getOffset()));
                info.put("column", service.getColumnNumber(cu, match.getOffset()));
                String context = service.getContextLine(cu, match.getOffset());
                if (context != null && !context.isEmpty()) {
                    info.put("context", context.trim());
                }
            }

            return info;
        } catch (Exception e) {
            return null;
        }
    }
}
