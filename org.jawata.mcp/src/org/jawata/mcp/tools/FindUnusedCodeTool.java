package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Find unused private methods and fields.
 * Uses AST analysis to detect private members that are never referenced.
 *
 * AI-centric: Helps with cleanup, refactoring, and dead code detection.
 */
public class FindUnusedCodeTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindUnusedCodeTool.class);

    public FindUnusedCodeTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_unused_code";
    }

    @Override
    public String getDescription() {
        return """
            Find unused private methods and fields in the project.

            USAGE: find_unused_code()
            USAGE: find_unused_code(filePath="path/to/File.java")
            OUTPUT: List of unused private members

            Detects:
            - Unused private methods
            - Unused private fields
            - Write-only fields (set but never read)

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
                "description", "Optional: specific file to check (default: all files)"
            ),
            "includeFields", Map.of(
                "type", "boolean",
                "description", "Include unused fields (default true)"
            ),
            "includeMethods", Map.of(
                "type", "boolean",
                "description", "Include unused methods (default true)"
            )
        ));
        schema.put("required", List.of());
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath", null);
        boolean includeFields = getBooleanParam(arguments, "includeFields", true);
        boolean includeMethods = getBooleanParam(arguments, "includeMethods", true);

        try {
            List<Map<String, Object>> unusedItems = new ArrayList<>();

            List<Path> files;
            if (filePath != null) {
                Path file = service.getProjectRoot().resolve(filePath).normalize();
                files = List.of(file);
            } else {
                files = service.getAllJavaFiles();
            }

            for (Path file : files) {
                try {
                    ICompilationUnit cu = service.getCompilationUnit(file);
                    if (cu == null) continue;

                    // Parse to AST with bindings
                    ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                    parser.setSource(cu);
                    parser.setResolveBindings(true);
                    parser.setBindingsRecovery(true);

                    CompilationUnit ast = (CompilationUnit) parser.createAST(null);
                    if (ast == null) continue;

                    findUnusedInFile(ast, file, service, unusedItems, includeFields, includeMethods);
                } catch (Exception e) {
                    log.debug("Error finding unused code in file {}: {}", file, e.getMessage());
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalUnused", unusedItems.size());

            long unusedFields = unusedItems.stream()
                .filter(m -> "Field".equals(m.get("kind")))
                .count();
            long unusedMethods = unusedItems.stream()
                .filter(m -> "Method".equals(m.get("kind")))
                .count();

            data.put("unusedFieldCount", unusedFields);
            data.put("unusedMethodCount", unusedMethods);
            data.put("unusedItems", unusedItems);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(unusedItems.size())
                .returnedCount(unusedItems.size())
                .suggestedNextTools(unusedItems.isEmpty()
                    ? List.of("No unused code found")
                    : List.of("Consider removing unused code to improve maintainability"))
                .build());

        } catch (Exception e) {
            log.error("Error finding unused code: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private void findUnusedInFile(CompilationUnit ast, Path file, IJdtService service,
                                   List<Map<String, Object>> unusedItems,
                                   boolean includeFields, boolean includeMethods) {

        // Collect all private members and their usages
        Map<IBinding, ASTNode> privateMembers = new HashMap<>();
        Set<IBinding> usedBindings = new HashSet<>();

        // First pass: collect private members
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration node) {
                if (includeFields && isPrivate(node.getModifiers())) {
                    for (Object fragment : node.fragments()) {
                        VariableDeclarationFragment vdf = (VariableDeclarationFragment) fragment;
                        IVariableBinding binding = vdf.resolveBinding();
                        if (binding != null) {
                            privateMembers.put(binding, vdf);
                        }
                    }
                }
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                if (includeMethods && isPrivate(node.getModifiers()) && !node.isConstructor()) {
                    IMethodBinding binding = node.resolveBinding();
                    if (binding != null) {
                        privateMembers.put(binding, node);
                    }
                }
                return true;
            }
        });

        // Second pass: find usages
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding != null) {
                    // Check if this is a usage (not the declaration itself)
                    ASTNode parent = node.getParent();
                    boolean isDeclaration = (parent instanceof VariableDeclarationFragment vdf && vdf.getName() == node)
                                          || (parent instanceof MethodDeclaration md && md.getName() == node);

                    if (!isDeclaration) {
                        usedBindings.add(binding);
                    }
                }
                return true;
            }

            @Override
            public boolean visit(MethodInvocation node) {
                IMethodBinding binding = node.resolveMethodBinding();
                if (binding != null) {
                    usedBindings.add(binding);
                }
                return true;
            }
        });

        // Find unused members
        for (Map.Entry<IBinding, ASTNode> entry : privateMembers.entrySet()) {
            IBinding binding = entry.getKey();
            if (!usedBindings.contains(binding)) {
                ASTNode node = entry.getValue();

                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", binding.getName());
                item.put("kind", binding instanceof IMethodBinding ? "Method" : "Field");
                item.put("filePath", service.getPathUtils().formatPath(file));
                // THE ADDRESS ITS OWN CURE CAN BE POINTED AT. `unused` is a ROUTED kind —
                // apply_cleanup kind=remove_dead_code — and until C8b round 2 its rows
                // carried a bare `name`, which no door can resolve, so the route was an
                // instruction nobody could follow. The binding is right here; symbolOf is the
                // one renderer, the same one step 5 gave the other thirteen detectors. The
                // key is `symbol` because that is what every door reads and what the cure
                // join looks for; `name` stays for the readers that already use it.
                String symbol = org.jawata.mcp.models.CodeAddress.symbolOf(binding);
                if (symbol != null) {
                    item.put("symbol", symbol);
                }

                if (node instanceof VariableDeclarationFragment vdf) {
                    // 1-BASED, BOTH, because that is what `Finding` documents and what the
                    // single conversion site subtracts from. This tool pre-converted —
                    // `getLineNumber(...) - 1` plus a raw `getColumnNumber(...)`, which JDT
                    // answers 0-based — so once C8b round 2 widened the cure join past the
                    // `findings` key, every `unused` cure was decremented a SECOND time and
                    // named the line above the member. `CodeAddress`'s own javadoc describes
                    // that outcome one paragraph from the code that caused it, written about
                    // the other factory.
                    //
                    // IT WAS NOT THE ONLY ONE, AND THE COMMIT THAT FIXED IT SAID IT WAS.
                    // "The sole outlier" was a population claim made by reading four detectors
                    // instead of searching for the expression — the same mistake that commit
                    // was correcting one section earlier, repeated in the correction. Searched
                    // properly the pre-decrement appears at 29 sites in 14 files, and MOST are
                    // right: the base a coordinate needs depends on where it is GOING, and a
                    // value bound for a door is 0-based and correctly pre-converted
                    // (`ApplyQuickFixTool`, `ReplaceTempWithQueryTool`, `SplitPhaseTool`).
                    //
                    // The population that matters is the rows `FindQualityIssueTool` MERGES
                    // into one response. `RESULT_LIST_KEYS` holds SIX — `findings` plus five
                    // others — and of those five, `cycles` and `largeClasses` emit no line at
                    // all, so the three that carried one are `unusedItems` (here),
                    // `violations` and `issues`. All three are 1-based as of C8b round 4.
                    //
                    // AND THE REASON IT MUST BE 1-BASED IS `Cures.attach`, not a convention I
                    // inferred: it rebuilds a `Finding` from the RAW row and calls
                    // `CodeAddress.of(Finding)`, which subtracts one. A 0-based row therefore
                    // renders every cure address a line above its subject. A round-4 version
                    // of this comment argued instead that `largeClasses` "never pre-converted",
                    // which round 5 disproved by reading that tool: it emits no line, so it
                    // could not have witnessed anything.
                    int line = lineOf(ast, vdf.getName());
                    int column = columnOf(ast, vdf.getName());
                    item.put("line", line);
                    item.put("column", column);

                    IVariableBinding vb = vdf.resolveBinding();
                    if (vb != null && vb.getType() != null) {
                        item.put("type", vb.getType().getName());
                    }
                } else if (node instanceof MethodDeclaration md) {
                    int line = lineOf(ast, md.getName());
                    int column = columnOf(ast, md.getName());
                    item.put("line", line);
                    item.put("column", column);
                    item.put("signature", getMethodSignature(md));
                }

                unusedItems.add(item);
            }
        }
    }

    /**
     * The 1-based line of a name — which is what {@code getLineNumber} already answers.
     *
     * <p>Wrapped rather than inlined so the base is stated ONCE instead of at each of the two
     * emission sites. Two sites each spelling their own arithmetic is how this tool came to
     * disagree with the rows it is merged beside, and go unnoticed for as long as nothing
     * joined those rows to a door.</p>
     */
    private static int lineOf(CompilationUnit ast, ASTNode name) {
        return ast.getLineNumber(name.getStartPosition());
    }

    /**
     * The 1-based column of a name. {@code getColumnNumber} answers 0-BASED, so this is the
     * one place that adds the one — and the asymmetry with {@link #lineOf}, which adds
     * nothing, is exactly why both are wrapped: two JDT calls beside each other disagreeing
     * about their base is not something a reader should have to remember.
     */
    private static int columnOf(CompilationUnit ast, ASTNode name) {
        return ast.getColumnNumber(name.getStartPosition()) + 1;
    }

    private boolean isPrivate(int modifiers) {
        return (modifiers & Modifier.PRIVATE) != 0;
    }

    private String getMethodSignature(MethodDeclaration md) {
        StringBuilder sig = new StringBuilder();
        sig.append(md.getName().getIdentifier()).append("(");
        List<?> params = md.parameters();
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sig.append(", ");
            SingleVariableDeclaration param = (SingleVariableDeclaration) params.get(i);
            sig.append(param.getType().toString());
        }
        sig.append(")");
        return sig.toString();
    }
}
