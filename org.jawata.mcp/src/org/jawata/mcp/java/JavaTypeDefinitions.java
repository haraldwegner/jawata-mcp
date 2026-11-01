package org.jawata.mcp.java;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;
import org.eclipse.jdt.core.WorkingCopyOwner;

/**
 * The type a name is declared to have (Sprint 28h, S16b): for a variable, a field or a parameter its declared type, for a method its
 * return type, for a type name the type itself. No published tool answers this (the door that names the type at a position names the
 * enclosing type), so the language server asks JDT's code select directly, as it does for completion.
 */
public final class JavaTypeDefinitions {

    private JavaTypeDefinitions() {
    }

    /**
     * The type declared for the name at a position of a unit's text, or null when it has none the model can find: a primitive, a type
     * variable, a name that does not resolve.
     *
     * @param unit   the working copy of an open file, or the saved unit
     * @param line   zero-based line
     * @param column zero-based UTF-16 column
     */
    public static IType at(ICompilationUnit unit, int line, int column) throws JavaModelException {
        int offset = JavaRanges.linesOf(unit).offsetOf(line, column);
        for (IJavaElement element : unit.codeSelect(offset, 0)) {
            if (element instanceof IType type) {
                return type;
            }
            String signature = switch (element) {
                case IField field -> field.getTypeSignature();
                case ILocalVariable variable -> variable.getTypeSignature();
                case IMethod method -> method.getReturnType();
                default -> null;
            };
            IType owner = (IType) element.getAncestor(IJavaElement.TYPE);
            IType declared = signature == null || owner == null ? null : resolve(owner, signature);
            if (declared != null) {
                return declared;
            }
        }
        return null;
    }

    /** The type a signature names, seen from {@code owner}: arrays and type arguments are set aside, the element type is what is looked up. */
    private static IType resolve(IType owner, String signature) throws JavaModelException {
        String name = Signature.toString(Signature.getTypeErasure(Signature.getElementType(signature)));
        if (name.isEmpty() || Character.isLowerCase(name.charAt(0)) && name.indexOf('.') < 0) {
            return null; // a primitive or void
        }
        WorkingCopyOwner buffers = org.jawata.core.BufferStore.owner(); // so a type declared in unsaved text is found
        String[][] candidates = owner.resolveType(name, buffers);
        if (candidates != null) {
            for (String[] candidate : candidates) {
                IType found = owner.getJavaProject().findType(candidate[0].isEmpty() ? candidate[1] : candidate[0] + "." + candidate[1], buffers);
                if (found != null) {
                    return found;
                }
            }
        }
        return name.indexOf('.') < 0 ? null : owner.getJavaProject().findType(name, buffers);
    }
}
