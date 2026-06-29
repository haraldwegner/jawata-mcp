package org.goja.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Long Parameter List</b>. Flags methods/constructors
 * with more than {@code threshold} parameters (default 4). Pointed refactoring:
 * <b>Introduce Parameter Object</b> (or Preserve Whole Object).
 */
public final class LongParameterListDetector extends AbstractAstDetector {

    public LongParameterListDetector() {
        super("long_parameter_list",
            "Long Parameter List — methods/constructors with more than `threshold` parameters "
                + "(default 4); points to Introduce Parameter Object.",
            4);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                int count = node.parameters().size();
                if (count > threshold) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "long_parameter_list", filePath, line, -1, "warning",
                        (node.isConstructor() ? "Constructor '" : "Method '") + name + "' has "
                            + count + " parameters (threshold " + threshold
                            + "). Consider Introduce Parameter Object.",
                        name));
                }
                return true;
            }
        });
    }
}
