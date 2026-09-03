package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
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
import java.util.regex.Pattern;

/**
 * Check code against standard Java naming conventions.
 * Reports violations for classes, methods, fields, constants, and parameters.
 */
public class FindNamingViolationsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindNamingViolationsTool.class);

    private static final Pattern PASCAL_CASE = Pattern.compile("^[A-Z][a-zA-Z0-9]*$");
    private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");
    private static final Pattern UPPER_SNAKE_CASE = Pattern.compile("^[A-Z][A-Z0-9_]*$");

    public FindNamingViolationsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_naming_violations";
    }

    @Override
    public String getDescription() {
        return """
            Check code against standard Java naming conventions.

            USAGE: find_naming_violations(filePath="path/to/File.java")
            OUTPUT: List of naming convention violations

            Conventions checked:
            - Classes/interfaces/enums: PascalCase
            - Methods: camelCase
            - Fields: camelCase
            - Constants (static final): UPPER_SNAKE_CASE
            - Parameters: camelCase

            If filePath is omitted, scans all project files.

            TEST SOURCES ARE EXCLUDED unless includeTests=true. Test method names are
            deliberately written to read as sentences, so scanning them buries the real
            findings: on this repository the scan reported 1,533 violations, and the
            sample was entirely intentional test names. This is the same switch, with
            the same default, that every code-smell check already takes.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "File to check (omit to scan all files)");
        properties.put("filePath", filePath);

        Map<String, Object> includeTests = new LinkedHashMap<>();
        includeTests.put("type", "boolean");
        includeTests.put("description",
            "Include test sources (default false). Test method names are deliberately"
                + " unconventional; scanning them hides the production findings.");
        properties.put("includeTests", includeTests);

        schema.put("properties", properties);
        schema.put("required", List.of());

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        // The SAME switch and the SAME classifier the code-smell checks use — not a
        // second derivation of test-ness. This check predates the switch and never
        // received it, which is why it was the one scan still reporting deliberate
        // test names as violations.
        boolean includeTests =
            org.jawata.mcp.tools.smell.AbstractAstDetector.includeTests(arguments);

        try {
            List<Path> files;
            if (filePathStr != null && !filePathStr.isBlank()) {
                Path resolved = service.getPathUtils().resolve(filePathStr);
                files = List.of(resolved);
            } else {
                files = service.getAllJavaFiles();
            }

            List<Map<String, Object>> violations = new ArrayList<>();
            // TWO counts, because the skip below makes one count a lie. `files` is
            // everything the project offers; a test-source skip and an unreadable
            // compilation unit both leave it untouched while examining nothing. On this
            // repository that is the difference between 813 and roughly half of it, and
            // a single conflated number would report the larger one — the exact shape
            // AbstractAstDetector reports filesListed and filesExamined separately to
            // refuse: a scan that looked at nothing and a scan that found nothing must
            // not produce the same answer.
            int filesExamined = 0;

            for (Path file : files) {
                if (!includeTests
                    && org.jawata.mcp.tools.smell.AbstractAstDetector.isTestSource(file, service)) {
                    continue;
                }
                ICompilationUnit cu = service.getCompilationUnit(file);
                if (cu == null) continue;
                filesExamined++;

                ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
                parser.setSource(cu);
                parser.setResolveBindings(true);
                CompilationUnit ast = (CompilationUnit) parser.createAST(null);

                String formattedPath = service.getPathUtils().formatPath(file);

                ast.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(TypeDeclaration node) {
                        checkName(node.getName().getIdentifier(), "class", PASCAL_CASE, "PascalCase",
                            ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        return true;
                    }

                    @Override
                    public boolean visit(EnumDeclaration node) {
                        checkName(node.getName().getIdentifier(), "enum", PASCAL_CASE, "PascalCase",
                            ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        return true;
                    }

                    @Override
                    public boolean visit(MethodDeclaration node) {
                        if (!node.isConstructor()) {
                            checkName(node.getName().getIdentifier(), "method", CAMEL_CASE, "camelCase",
                                ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(FieldDeclaration node) {
                        int modifiers = node.getModifiers();
                        boolean isConstant = Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers);

                        for (Object fragment : node.fragments()) {
                            if (fragment instanceof VariableDeclarationFragment varFrag) {
                                String name = varFrag.getName().getIdentifier();
                                if (isConstant) {
                                    checkName(name, "constant", UPPER_SNAKE_CASE, "UPPER_SNAKE_CASE",
                                        ast.getLineNumber(varFrag.getStartPosition()) - 1, formattedPath, violations);
                                } else {
                                    checkName(name, "field", CAMEL_CASE, "camelCase",
                                        ast.getLineNumber(varFrag.getStartPosition()) - 1, formattedPath, violations);
                                }
                            }
                        }
                        return false;
                    }

                    @Override
                    public boolean visit(SingleVariableDeclaration node) {
                        checkName(node.getName().getIdentifier(), "parameter", CAMEL_CASE, "camelCase",
                            ast.getLineNumber(node.getStartPosition()) - 1, formattedPath, violations);
                        return false;
                    }
                });
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filesListed", files.size());
            data.put("filesExamined", filesExamined);
            // KEPT, and equal to filesExamined rather than to filesListed. Callers read
            // this key; dropping it would break them silently, and pointing it at the
            // larger number is the claim this repair exists to remove.
            data.put("filesScanned", filesExamined);
            data.put("totalViolations", violations.size());
            data.put("violations", violations);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(violations.size())
                .returnedCount(violations.size())
                .suggestedNextTools(List.of(
                    "rename_symbol to fix a naming violation"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private void checkName(String name, String elementType, Pattern convention, String conventionName,
                          int line, String filePath, List<Map<String, Object>> violations) {
        if (!convention.matcher(name).matches()) {
            Map<String, Object> violation = new LinkedHashMap<>();
            // `filePath`, not `file`. Eleven consumer sites across the product read
            // `filePath` and NONE reads `file`; this tool and find_large_classes were
            // the two producers spelling it the other way. find_quality_issue merges
            // every producer's rows into one list, so under the old spelling four
            // consumers silently saw null here: excludePaths excluded nothing from
            // naming rows, conflict arbitration skipped them, multi-project sweeps left
            // them without a project, and the baseline keyed them as "naming|null|<line>"
            // so rows on the same line in different files collapsed into one entry.
            violation.put("filePath", filePath);
            violation.put("line", line);
            violation.put("elementType", elementType);
            violation.put("name", name);
            violation.put("convention", conventionName);
            violations.add(violation);
        }
    }
}
