package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.core.ScopedJdtService;
import org.jawata.core.exceptions.ProjectNotLoadedException;
import org.jawata.mcp.JawataApplication;
import org.jawata.mcp.ProjectLoadingState;
import org.jawata.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

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
 *         // Tool implementation - service is guaranteed non-null
 *         return ToolResponse.success(data);
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractTool implements Tool {

    protected final Supplier<IJdtService> serviceSupplier;

    protected AbstractTool(Supplier<IJdtService> serviceSupplier) {
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

    /**
     * Whether this tool needs at least one readable project.
     *
     * <p>Nearly every tool does — it answers about code. The exceptions are the
     * RUNTIME tools: {@code debug} discovers and attaches to JVMs and
     * {@code profile} reads a crash log, both of which are meaningful with no
     * project loaded at all. Overriding this to {@code false} is what keeps the
     * gate below a single seam instead of a per-tool condition (mcp#28).
     */
    protected boolean requiresLoadedProject() {
        return true;
    }

    /**
     * Default execute implementation that checks for a readable project, then
     * delegates to executeWithService.
     *
     * <p><b>The gate is "are there projects to answer from", not "is the
     * service object present" (mcp#28).</b> A workspace whose every project
     * FAILED to load still has a service — empty. Tools then ran against
     * nothing: {@code search_symbols} dereferenced a null search service and
     * answered INTERNAL_ERROR "this may be a bug", on exactly the path a
     * redirected agent lands on. Reading emptiness rather than the loading enum
     * also self-heals, because a later successful {@code load_project} makes the
     * predicate false without anyone having to remember to clear a flag.
     *
     * <p>The loading state still chooses the WORDS: loading, failed-with-reason,
     * or nothing-loaded.
     */
    @Override
    public ToolResponse execute(JsonNode arguments) {
        IJdtService service = serviceSupplier.get();

        // Sprint 10: optional projectKey scoping. When the agent passes
        // projectKey on a tool call, narrow the service view to just that
        // project so getSearchService() / getJavaProject() / file lookups
        // operate in single-project scope. Absent: cross-project default.
        String projectKey = null;
        if (arguments != null && arguments.has("projectKey") && !arguments.get("projectKey").isNull()) {
            String raw = arguments.get("projectKey").asText();
            if (raw != null && !raw.isBlank()) {
                projectKey = raw;
            }
        }

        // A DROPPED key is knowledge only the service holds, and it outranks
        // "nothing is loaded" — the caller named a key that WAS valid, and
        // being told when it went away is what lets them recover (bugs.md #11).
        // Answering the emptiness first would replace that with a generic
        // not-loaded, which is exactly the regression the suite caught here.
        if (service != null && projectKey != null && service.getProject(projectKey).isEmpty()) {
            Optional<Long> dropped = service.wasRecentlyDropped(projectKey);
            if (dropped.isPresent()) {
                return ToolResponse.projectKeyDropped(projectKey, dropped.get());
            }
        }

        if (requiresLoadedProject() && (service == null || service.allProjects().isEmpty())) {
            // Check loading state to provide more specific feedback
            ProjectLoadingState loadingState = JawataApplication.getLoadingState();
            return switch (loadingState) {
                case LOADING -> ToolResponse.projectLoading();
                case FAILED -> ToolResponse.projectLoadFailed(JawataApplication.getLoadingError());
                default -> ToolResponse.projectNotLoaded();
            };
        }
        if (service == null) {
            // A project-independent tool with no service at all: nothing to
            // scope, and its own execute() reads the runtime rather than the
            // model. Keep the historical answer rather than invent one.
            return ToolResponse.projectNotLoaded();
        }

        if (projectKey != null) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                // Not dropped (that answered above) and not present: a key that
                // never existed here. Name the recovery route rather than the
                // typo.
                return ToolResponse.invalidParameter(
                    "projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use list_projects to see available keys.");
            }
            return executeWithService(new ScopedJdtService(service, scoped.get()), arguments);
        }

        return executeWithService(service, arguments);
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
            "description", "Optional. Restrict the query to a single loaded project (see list_projects). Omit to search all projects in the workspace."
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
                + "{ changeId, diff } for a later apply_refactoring call."
        ));
        wrapped.put("properties", newProps);
        return wrapped;
    }

    /**
     * Execute the tool with a guaranteed non-null IJdtService.
     * Subclasses should override this instead of execute().
     *
     * @param service The IJdtService (guaranteed non-null)
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

    /**
     * Get a required string parameter.
     *
     * @param arguments The arguments node
     * @param name The parameter name
     * @return The parameter value, or null if missing
     */
    protected String getStringParam(JsonNode arguments, String name) {
        if (arguments == null || !arguments.has(name)) {
            return null;
        }
        return arguments.get(name).asText();
    }

    /**
     * Get an optional string parameter with default.
     */
    protected String getStringParam(JsonNode arguments, String name, String defaultValue) {
        String value = getStringParam(arguments, name);
        return value != null ? value : defaultValue;
    }

    /**
     * Get an optional int parameter with default.
     */
    protected int getIntParam(JsonNode arguments, String name, int defaultValue) {
        if (arguments == null || !arguments.has(name)) {
            return defaultValue;
        }
        return arguments.get(name).asInt(defaultValue);
    }

    /**
     * Get an optional boolean parameter with default.
     */
    protected boolean getBooleanParam(JsonNode arguments, String name, boolean defaultValue) {
        if (arguments == null || !arguments.has(name)) {
            return defaultValue;
        }
        return arguments.get(name).asBoolean(defaultValue);
    }

    /**
     * Check if a required parameter is present.
     */
    protected ToolResponse requireParam(JsonNode arguments, String name) {
        if (arguments == null || !arguments.has(name)) {
            return ToolResponse.invalidParameter(name, "Required parameter missing");
        }
        return null; // No error
    }

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
