package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CreateCompilationUnitChange;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Move Accumulation to Visitor</b> / introduce Visitor
 * (toward pattern). Generates the Visitor <em>infrastructure</em> over a type
 * hierarchy: a {@code <Base>Visitor<R>} interface (in a new file) plus {@code accept}
 * double-dispatch added to the abstract base and every subtype. This is the invasive,
 * tedious, mechanical part; migrating an existing {@code instanceof}-chain accumulator
 * into a {@code Visitor} implementation is then a follow-up the agent drives (the cast
 * rewriting is not mechanically safe in general — v1.4 stays conservative).
 *
 * <p>The change composes a {@link CreateCompilationUnitChange} (the interface) with a
 * {@link ChangeEngine#fromFileEdits} change (the {@code accept} methods) into one
 * {@link CompositeChange} — one atomic apply, one undo. Requires the base to be
 * {@code abstract} with &ge; 2 subtypes in the same file. Find candidates via
 * {@code find_quality_issue(kind=switch_statements)} (an instanceof/type-code chain).</p>
 */
public class RefactorToVisitorTool extends AbstractApplyingRefactoringTool {

    public RefactorToVisitorTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "refactor_to_visitor";
    }

    @Override
    public String getDescription() {
        return "Introduce Visitor — generate a <Base>Visitor interface + accept() double-dispatch across an "
            + "abstract hierarchy (the invasive boilerplate). Delegate of refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file of the hierarchy."),
            "line", Map.of("type", "integer", "description", "Zero-based line on the abstract base type."),
            "column", Map.of("type", "integer", "description", "Zero-based column on the base type.")));
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }
        java.nio.file.Path path = java.nio.file.Path.of(filePath);
        IType baseType = service.getTypeAtPosition(path, line, column);
        if (baseType == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "No type at " + line + ":" + column));
        }
        ICompilationUnit cu = baseType.getCompilationUnit();
        if (cu == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Source not available"));
        }
        CompilationUnit ast = PatternSupport.parse(cu);
        TypeDeclaration base = findType(ast, baseType.getElementName());
        if (base == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Cannot locate " + baseType.getElementName()));
        }
        if (!Modifier.isAbstract(base.getModifiers()) || base.isInterface()) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "The base type must be an abstract class to host the accept double-dispatch."));
        }
        ITypeBinding baseBinding = base.resolveBinding();

        List<TypeDeclaration> subtypes = new ArrayList<>();
        for (Object o : ast.types()) {
            if (o instanceof TypeDeclaration t && t != base && t.getSuperclassType() != null
                && baseBinding != null && baseBinding.isEqualTo(t.getSuperclassType().resolveBinding())) {
                subtypes.add(t);
            }
        }
        if (subtypes.size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("hierarchy",
                "refactor_to_visitor (v1.4) needs the abstract base + >= 2 subtypes in the same file (found "
                    + subtypes.size() + ")."));
        }

        String baseName = base.getName().getIdentifier();
        String visitorName = baseName + "Visitor";
        String pkg = ast.getPackage() != null ? ast.getPackage().getName().getFullyQualifiedName() : null;

        // 1. the Visitor interface (new compilation unit).
        StringBuilder iface = new StringBuilder();
        if (pkg != null && !pkg.isBlank()) {
            iface.append("package ").append(pkg).append(";\n\n");
        }
        iface.append("/**\n * Visitor over the ").append(baseName)
            .append(" hierarchy. Generated by refactor_to_pattern (refactor_to_visitor).\n */\n");
        iface.append("public interface ").append(visitorName).append("<R> {\n");
        for (TypeDeclaration sub : subtypes) {
            String s = sub.getName().getIdentifier();
            iface.append("    R visit").append(s).append("(").append(s).append(" node);\n");
        }
        iface.append("}\n");

        IContainer parent = (IContainer) cu.getResource().getParent();
        IFile visitorFile = parent.getFile(new Path(visitorName + ".java"));
        if (visitorFile.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "A file named " + visitorName + ".java already exists."));
        }

        // 2. accept() insertions on the hierarchy file.
        List<TextEdit> edits = new ArrayList<>();
        edits.add(new InsertEdit(base.getStartPosition() + base.getLength() - 1,
            "    public abstract <R> R accept(" + visitorName + "<R> visitor);\n"));
        for (TypeDeclaration sub : subtypes) {
            String s = sub.getName().getIdentifier();
            String accept = "\n    public <R> R accept(" + visitorName + "<R> visitor) {\n"
                + "        return visitor.visit" + s + "(this);\n"
                + "    }\n";
            edits.add(new InsertEdit(sub.getStartPosition() + sub.getLength() - 1, accept));
        }

        IFile hierarchyFile = (IFile) cu.getResource();
        CompositeChange composite = new CompositeChange("refactor to visitor " + baseName);
        composite.add(new CreateCompilationUnitChange(visitorFile, iface.toString()));
        composite.add(ChangeEngine.fromFileEdits("add accept to " + baseName, Map.of(hierarchyFile, edits)));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("visitorInterface", visitorName);
        extras.put("baseType", baseName);
        extras.put("subtypes", subtypes.stream().map(t -> t.getName().getIdentifier()).toList());
        extras.put("note", "Visitor infrastructure added (accept double-dispatch). Migrate an existing "
            + "instanceof-chain accumulator into a " + visitorName + " implementation as the next step.");
        String summary = "refactor to visitor: " + visitorName + " + accept() over " + baseName
            + " and " + subtypes.size() + " subtypes";
        return Preparation.of(composite, summary, extras);
    }

    private static TypeDeclaration findType(CompilationUnit ast, String simpleName) {
        for (Object t : ast.types()) {
            if (t instanceof TypeDeclaration td && simpleName.equals(td.getName().getIdentifier())) {
                return td;
            }
        }
        return null;
    }
}
