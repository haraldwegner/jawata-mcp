package org.jawata.mcp.java.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IPackageBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.jawata.mcp.java.JavaRanges;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The language server's semantic tokens (Sprint 28h, S16d): what each name in a file is, so an editor can colour a field differently
 * from a parameter, a method from a type. Every name is classified by what the compiler binds it to, on the text the editor holds, saved
 * or not. The legend below is the contract with the editor: a token carries the index of its type and a bit for each modifier.
 */
final class LspColour {

    /** What a name is; the index of an entry is the number a token carries. */
    static final List<String> TOKEN_TYPES = List.of("namespace", "class", "interface", "enum", "typeParameter", "parameter", "variable",
        "property", "enumMember", "method", "decorator");

    /** What more is true of it; the position of an entry is the bit a token carries. */
    static final List<String> TOKEN_MODIFIERS = List.of("declaration", "static", "readonly", "deprecated", "abstract");

    private static final int DECLARATION = 1;
    private static final int STATIC = 1 << 1;
    private static final int READONLY = 1 << 2;
    private static final int DEPRECATED = 1 << 3;
    private static final int ABSTRACT = 1 << 4;

    private final Function<String, ICompilationUnit> unitOf;

    /** @param unitOf the unit a request reads for a document: its working copy when open, else the saved unit; null for a file the model does not hold */
    LspColour(Function<String, ICompilationUnit> unitOf) {
        this.unitOf = unitOf;
    }

    /** The legend the capability announces. */
    static Map<String, Object> legend() {
        return Map.of("tokenTypes", TOKEN_TYPES, "tokenModifiers", TOKEN_MODIFIERS);
    }

    /** The tokens of the whole file. */
    Object full(JsonNode params) throws JavaModelException {
        return tokens(params, 0, Integer.MAX_VALUE);
    }

    /** The tokens of the lines asked for. */
    Object range(JsonNode params) throws JavaModelException {
        JsonNode range = params.path("range");
        return tokens(params, range.path("start").path("line").asInt(), range.path("end").path("line").asInt());
    }

    private Object tokens(JsonNode params, int firstLine, int lastLine) throws JavaModelException {
        ICompilationUnit unit = unitOf.apply(params.path("textDocument").path("uri").asText());
        List<Integer> data = new ArrayList<>();
        if (unit == null) {
            return Map.of("data", data);
        }
        JavaRanges.Lines lines = JavaRanges.linesOf(unit.getSource());
        CompilationUnit root = LspStructure.syntaxOf(unit, true);
        List<int[]> found = new ArrayList<>(); // line, column, length, type, modifiers
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name.getParent() instanceof org.eclipse.jdt.core.dom.SimpleType declared && declared.isVar()) {
                    return false; // the keyword var is not a class, whatever type it stands for
                }
                IBinding binding = name.resolveBinding();
                int type = binding == null ? -1 : typeOf(binding);
                if (type >= 0) {
                    JavaRanges.Range span = lines.span(name.getStartPosition(), name.getLength());
                    if (span.line() >= firstLine && span.line() <= lastLine) {
                        found.add(new int[] {span.line(), span.column(), name.getLength(), type, modifiersOf(binding, name.isDeclaration())});
                    }
                }
                return false;
            }
        });
        found.sort(Comparator.<int[]>comparingInt(token -> token[0]).thenComparingInt(token -> token[1]));
        int previousLine = 0;
        int previousColumn = 0;
        for (int[] token : found) {
            data.add(token[0] - previousLine);
            data.add(token[0] == previousLine ? token[1] - previousColumn : token[1]);
            data.add(token[2]);
            data.add(token[3]);
            data.add(token[4]);
            previousLine = token[0];
            previousColumn = token[1];
        }
        return Map.of("data", data);
    }

    /** The index in the legend of what the binding is, or -1 for a name the legend has no entry for. */
    private static int typeOf(IBinding binding) {
        String name = switch (binding) {
            case IVariableBinding variable when variable.isEnumConstant() -> "enumMember";
            case IVariableBinding variable when variable.isField() -> "property";
            case IVariableBinding variable when variable.isParameter() -> "parameter";
            case IVariableBinding variable -> "variable";
            case ITypeBinding named when named.isTypeVariable() -> "typeParameter";
            case ITypeBinding named when named.isAnnotation() -> "decorator";
            case ITypeBinding named when named.isEnum() -> "enum";
            case ITypeBinding named when named.isInterface() -> "interface";
            case ITypeBinding named -> "class";
            case IMethodBinding method -> "method";
            case IPackageBinding pack -> "namespace";
            default -> null;
        };
        return name == null ? -1 : TOKEN_TYPES.indexOf(name);
    }

    private static int modifiersOf(IBinding binding, boolean declaration) {
        int modifiers = declaration ? DECLARATION : 0;
        int flags = binding.getModifiers();
        if (Modifier.isStatic(flags)) {
            modifiers |= STATIC;
        }
        if (binding instanceof IVariableBinding && Modifier.isFinal(flags)) {
            modifiers |= READONLY;
        }
        if (binding.isDeprecated()) {
            modifiers |= DEPRECATED;
        }
        if ((binding instanceof IMethodBinding || binding instanceof ITypeBinding) && Modifier.isAbstract(flags)) {
            modifiers |= ABSTRACT;
        }
        return modifiers;
    }
}
