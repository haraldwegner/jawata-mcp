package org.jawata.mcp.tools.shared;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.fqn.FqnResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * Sprint 24 (D1) — <b>navigate by name, everywhere</b>. "Knowing is better than
 * searching": an agent that already knows a symbol's name should go straight to
 * it, exactly as a human hits Open Type (Shift+Ctrl+T) in Eclipse instead of
 * grepping. The FQN is the agent's stable memory key — it survives file moves
 * and package reshuffles; a file position does not.
 *
 * <p>This helper lets ANY position-based tool accept the name form without
 * touching its proven logic: it resolves {@code symbol=} / {@code typeName=} to
 * the element's source position and writes {@code filePath}/{@code line}/
 * {@code column} into the arguments, so the tool's existing position path then
 * runs unchanged. Explicit coordinates always win — a caller who supplies both
 * gets the position they asked for.</p>
 *
 * <p>Scope: only tools whose target is a whole NAMED symbol. Refactorings that
 * target a statement or expression RANGE (extract method/variable/constant,
 * compose_method, the switch-targeted pattern kinds, anonymous-class
 * conversion) keep positions — a range has no name. See the recorded
 * entry-point audit in the Sprint-24 plan.</p>
 */
public final class FqnTarget {

    private static final Logger log = LoggerFactory.getLogger(FqnTarget.class);

    private FqnTarget() {
    }

    /** The shared schema property declaring the name form on a symbol-targeted tool. */
    public static Map<String, Object> symbolSchemaProperty(String what) {
        return Map.of(
            "type", "string",
            "description", "Name form (Sprint 24): address the " + what + " by its "
                + "fully-qualified name instead of a file position — 'com.foo.Bar', "
                + "'com.foo.Bar#member', or 'com.foo.Bar#method(int,java.lang.String)'. "
                + "Explicit filePath/line/column win when both are given.");
    }

    /** The shared schema property for the type-targeted tools ({@code typeName=}). */
    public static Map<String, Object> typeNameSchemaProperty(String what) {
        return Map.of(
            "type", "string",
            "description", "Name form (Sprint 24): address the " + what + " by its "
                + "fully-qualified type name ('com.foo.Bar') instead of a file position. "
                + "Explicit filePath/line/column win when both are given.");
    }

    /**
     * Resolve the caller's name form (if any) into {@code filePath}/{@code line}/
     * {@code column} on {@code arguments}, so the tool's existing position path
     * can proceed untouched.
     *
     * @return empty when there is nothing to do (no name form given, or an
     *         explicit position was supplied) or when materialization succeeded;
     *         a failing {@link ToolResponse} when the name does not resolve or
     *         resolves to something with no source position.
     */
    public static Optional<ToolResponse> materializePosition(IJdtService service, JsonNode arguments) {
        if (!(arguments instanceof ObjectNode args)) {
            return Optional.empty();
        }
        String name = text(args, "symbol");
        if (name == null) {
            name = text(args, "typeName");
        }
        if (name == null) {
            return Optional.empty();
        }
        // An explicit position wins — the caller asked for that exact spot.
        if (text(args, "filePath") != null) {
            return Optional.empty();
        }

        String scopeRaw = text(args, "scope");
        FqnResolver.Scope scope = FqnResolver.Scope.WORKSPACE;
        if (scopeRaw != null) {
            try {
                scope = FqnResolver.Scope.valueOf(scopeRaw.toUpperCase());
            } catch (IllegalArgumentException e) {
                return Optional.of(ToolResponse.invalidParameter("scope",
                    "Must be 'workspace' or 'project'; got '" + scopeRaw + "'"));
            }
        }
        Optional<IJavaElement> resolved =
            FqnResolver.resolve(name, service, scope, text(args, "projectKey"));
        if (resolved.isEmpty()) {
            return Optional.of(ToolResponse.symbolNotFound(
                "Name '" + name + "' not found in " + scope.name().toLowerCase() + " scope."));
        }
        return position(service, resolved.get(), name, args);
    }

    /** Write the element's name-range position into the arguments. */
    private static Optional<ToolResponse> position(IJdtService service, IJavaElement element,
                                                   String name, ObjectNode args) {
        try {
            ICompilationUnit cu = (ICompilationUnit)
                element.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (cu == null || cu.getResource() == null
                || cu.getResource().getLocation() == null) {
                return Optional.of(ToolResponse.symbolNotFound(
                    "Name '" + name + "' resolves to an element with no source "
                        + "(a binary/class-file element cannot be a refactoring target)."));
            }
            ISourceRange nameRange = element instanceof ISourceReference src
                ? src.getNameRange()
                : null;
            if (nameRange == null || nameRange.getOffset() < 0) {
                return Optional.of(ToolResponse.symbolNotFound(
                    "Name '" + name + "' resolves to an element with no name range."));
            }
            int offset = nameRange.getOffset();
            args.put("filePath", cu.getResource().getLocation().toOSString());
            args.put("line", service.getLineNumber(cu, offset));
            args.put("column", service.getColumnNumber(cu, offset));
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Materializing a position for '{}' failed: {}", name, e.getMessage());
            return Optional.of(ToolResponse.internalError(e));
        }
    }

    private static String text(ObjectNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
            return null;
        }
        return node.asText();
    }
}
