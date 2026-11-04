package org.jawata.mcp.java.lsp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.mcp.java.JavaRanges;

/**
 * The edits to a Java file that the editor's own file operation does not make (Sprint 28h, S16f 5): the type and its constructors take the
 * new name of a renamed file, the package line names the package of a moved file, and a moved file imports the types of its old package
 * that it used without an import. The engine's rename and move stage the edits to the OTHER files; these three are made here, in the file as
 * it is, by the rename request and by the file operations alike.
 */
final class MovedFileEdits {

    private final LspEdits edits;

    MovedFileEdits(LspEdits edits) {
        this.edits = edits;
    }

    /**
     * A file renamed with its type: the type and its constructors take the new name. The engine's own rename leaves these to the file
     * rename, which renames the type itself; the editor's file rename does not, so the edits are made here, in the file as it is.
     */
    void declaration(Path from, Path to, Map<String, List<Object>> changes, String what) {
        String uri = from.toUri().toString();
        ICompilationUnit unit = edits.unit(uri);
        if (unit == null) {
            return;
        }
        edits.refuseIfUnsaved(uri, from, what);
        try {
            IType type = primaryTypeOf(unit);
            if (type == null) {
                return;
            }
            String newName = to.getFileName().toString().replaceFirst("\\.java$", "");
            String text = unit.getSource();
            JavaRanges.Lines lines = JavaRanges.linesOf(text);
            List<Object> found = changes.computeIfAbsent(uri, key -> new ArrayList<>());
            ISourceRange name = type.getNameRange();
            edits.add(found, text, lines, name.getOffset(), name.getLength(), newName);
            for (IMethod method : type.getMethods()) {
                if (method.isConstructor()) {
                    ISourceRange at = method.getNameRange();
                    edits.add(found, text, lines, at.getOffset(), at.getLength(), newName);
                }
            }
        } catch (JavaModelException e) {
            throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
        }
    }

    /** A file moved to another package folder: its package declaration names the new package, which the editor's file move leaves as it was. */
    void packageLine(ICompilationUnit unit, Path from, String target, Map<String, List<Object>> changes) {
        String uri = from.toUri().toString();
        edits.refuseIfUnsaved(uri, from, "file operation");
        try {
            String text = unit.getSource();
            JavaRanges.Lines lines = JavaRanges.linesOf(text);
            List<Object> found = changes.computeIfAbsent(uri, key -> new ArrayList<>());
            IPackageDeclaration[] declared = unit.getPackageDeclarations();
            if (declared.length == 0) {
                edits.add(found, text, lines, 0, 0, "package " + target + ";\n\n");
                return;
            }
            ISourceRange declaration = declared[0].getSourceRange();
            int name = text.indexOf(declared[0].getElementName(), declaration.getOffset() + "package".length());
            edits.add(found, text, lines, name, declared[0].getElementName().length(), target);
        } catch (JavaModelException e) {
            throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
        }
    }

    /** A file moved to another package imports the types of its old package that it uses, which it saw before without an import. */
    void neighbourImports(ICompilationUnit unit, Path from, Map<String, List<Object>> changes) {
        MovedFileImports.Insertion needed = MovedFileImports.neededBy(unit);
        if (needed == null) {
            return;
        }
        try {
            String text = unit.getSource();
            edits.add(changes.computeIfAbsent(from.toUri().toString(), key -> new ArrayList<>()), text, JavaRanges.linesOf(text),
                needed.offset(), 0, needed.text());
        } catch (JavaModelException e) {
            throw new IllegalStateException("the file could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * The type the unit is named for. While the engine syncs or builds the project, the model can answer "none" for a unit that has one,
     * for a moment; the request waits for it up to two seconds rather than answer that nothing needs to change.
     */
    static IType primaryTypeOf(ICompilationUnit unit) throws JavaModelException {
        for (int attempt = 0; attempt < 20; attempt++) {
            IType type = unit.findPrimaryType();
            if (type != null) {
                return type;
            }
            unit.close(); // a request cancelled while JDT parsed can leave an empty structure behind; closing makes the next ask parse again
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }
}
