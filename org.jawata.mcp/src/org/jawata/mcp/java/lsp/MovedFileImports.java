package org.jawata.mcp.java.lsp;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * What a Java file moved to another package must import. In its old package the file saw the types beside it without writing an import; in
 * the new one it does not, so each of them it uses needs one. The engine's own move adds these as part of the move; the editor performs
 * the file move itself, so the answer to the editor carries them as one text insertion.
 */
final class MovedFileImports {

    /** Where in the file's text to insert, and the import lines to put there. */
    record Insertion(int offset, String text) { }

    private MovedFileImports() { }

    /**
     * The insertion that imports every type of the file's own package that the file uses, or null when it uses none (or sits in the
     * default package, whose types cannot be imported).
     */
    static Insertion neededBy(ICompilationUnit unit) {
        CompilationUnit ast = parse(unit);
        PackageDeclaration declared = ast.getPackage();
        if (declared == null) {
            return null;
        }
        String oldPackage = declared.getName().getFullyQualifiedName();
        Set<String> declaredHere = new TreeSet<>();
        for (Object type : ast.types()) {
            declaredHere.add(oldPackage + "." + ((AbstractTypeDeclaration) type).getName().getIdentifier());
        }
        Set<String> imported = new TreeSet<>();
        for (Object each : ast.imports()) {
            ImportDeclaration declaration = (ImportDeclaration) each;
            if (!declaration.isStatic()) {
                imported.add(declaration.isOnDemand() ? declaration.getName().getFullyQualifiedName() + ".*" : declaration.getName().getFullyQualifiedName());
            }
        }
        Set<String> needed = new TreeSet<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName name) {
                if (name.getParent() instanceof QualifiedName qualified && qualified.getName() == name) {
                    return true; // the last part of a name written in full already says where the type is
                }
                if (name.resolveBinding() instanceof ITypeBinding type && !type.isTypeVariable()) {
                    ITypeBinding outer = type.getTypeDeclaration();
                    while (outer.getDeclaringClass() != null) {
                        outer = outer.getDeclaringClass();
                    }
                    if (!outer.isRecovered() && outer.getPackage() != null && oldPackage.equals(outer.getPackage().getName())) {
                        String qualifiedName = outer.getQualifiedName();
                        if (!declaredHere.contains(qualifiedName) && !imported.contains(qualifiedName) && !imported.contains(oldPackage + ".*")) {
                            needed.add(qualifiedName);
                        }
                    }
                }
                return true;
            }
        });
        if (needed.isEmpty()) {
            return null;
        }
        List<?> imports = ast.imports();
        if (imports.isEmpty()) {
            return new Insertion(declared.getStartPosition() + declared.getLength(),
                "\n\n" + needed.stream().map(name -> "import " + name + ";").collect(Collectors.joining("\n")));
        }
        ImportDeclaration last = (ImportDeclaration) imports.get(imports.size() - 1);
        return new Insertion(last.getStartPosition() + last.getLength(),
            needed.stream().map(name -> "\nimport " + name + ";").collect(Collectors.joining()));
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setStatementsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
