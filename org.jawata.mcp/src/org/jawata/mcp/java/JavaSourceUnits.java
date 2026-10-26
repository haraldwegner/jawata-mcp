package org.jawata.mcp.java;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.IJdtService;
import org.jawata.spi.lang.SourceUnits;

/**
 * Java's {@link SourceUnits}: the compilation units of the loaded model, read for the experience store's describe, jobs_for and
 * duplicate_check verbs (Sprint 28k, Stage 9, moved here from the tool, which read them itself).
 */
public final class JavaSourceUnits implements SourceUnits {

    private final IJdtService service;

    /** @param service the loaded Java model */
    public JavaSourceUnits(IJdtService service) {
        this.service = service;
    }

    @Override
    public List<Path> files() {
        return service.getAllJavaFiles();
    }

    @Override
    public Optional<SourceFacts> facts(Path file) throws IOException {
        ICompilationUnit cu = unitAt(file);
        if (cu == null) {
            return Optional.empty();
        }
        try {
            IPackageDeclaration[] declared = cu.getPackageDeclarations();
            String pkg = declared.length == 0 ? "" : declared[0].getElementName();
            String bundle = cu.getJavaProject() == null ? null : cu.getJavaProject().getElementName();
            List<TypeFacts> types = new ArrayList<>();
            for (IType type : cu.getTypes()) {
                List<String> members = new ArrayList<>();
                for (IMethod m : type.getMethods()) {
                    members.add(m.getElementName());
                }
                for (IField f : type.getFields()) {
                    members.add(f.getElementName());
                }
                types.add(new TypeFacts(type.getFullyQualifiedName('.'), members));
            }
            return Optional.of(new SourceFacts(pkg, bundle, cu.getSource(), types));
        } catch (JavaModelException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<List<MemberAt>> methods(Path file, boolean withLines) throws IOException {
        ICompilationUnit cu = unitAt(file);
        if (cu == null) {
            return Optional.empty();
        }
        try {
            String source = withLines ? cu.getSource() : null;
            List<MemberAt> methods = new ArrayList<>();
            for (IType type : cu.getAllTypes()) {
                String owner = type.getFullyQualifiedName('.');
                for (IMethod m : type.getMethods()) {
                    int first = 0;
                    int last = 0;
                    if (source != null) {
                        ISourceRange where = m.getSourceRange();
                        first = lineAt(source, where.getOffset());
                        last = lineAt(source, where.getOffset() + Math.max(0, where.getLength() - 1));
                    }
                    methods.add(new MemberAt(owner, m.getElementName(), first, last));
                }
            }
            return Optional.of(methods);
        } catch (JavaModelException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    /** The unit at the path, or null when the model holds none there or the file does not exist yet. */
    private ICompilationUnit unitAt(Path file) {
        ICompilationUnit cu = service.getCompilationUnit(file);
        return cu == null || !cu.exists() ? null : cu;
    }

    /** The 1-based line a character offset falls on. */
    private static int lineAt(String source, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
