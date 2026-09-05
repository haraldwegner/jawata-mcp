package org.jawata.mcp.tools.data;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;

/**
 * Find a type again, by the FILE it lives in and its own simple name.
 *
 * <p>The composed rows on {@code data} re-resolve their target between recipe steps, because
 * each step rewrites the file the next one reads. The obvious key is the fully-qualified name,
 * and it is the wrong one: {@code IType.getFullyQualifiedName()} separates an enclosing type
 * from a member with {@code $} while {@code ITypeBinding.getQualifiedName()} uses {@code .},
 * so a name-keyed lookup is committed to ONE of two spellings and finds nothing for a nested
 * class under the other. Row 54 met that as a silent no-op — references found, none matched,
 * a change generated that migrated nothing — and its fix was to settle on one spelling. This
 * avoids the question: a file has one type tree, and walking it needs no spelling at all.</p>
 *
 * <p>The search is depth-first over member types, so a member and a top-level class are found
 * the same way. A simple name that occurs twice in one file cannot happen in Java, so the
 * first hit is the only hit.</p>
 */
final class TypeInFile {

    private TypeInFile() {
    }

    /** The type of this simple name declared in this file, or null. */
    static IType find(IJdtService service, String filePath, String simpleName) throws Exception {
        ICompilationUnit unit =
            service.getCompilationUnit(service.getPathUtils().resolve(filePath));
        if (unit == null) {
            return null;
        }
        return search(unit.getTypes(), simpleName);
    }

    private static IType search(IType[] types, String simpleName) throws Exception {
        for (IType type : types) {
            if (type.getElementName().equals(simpleName)) {
                return type;
            }
            IType nested = search(type.getTypes(), simpleName);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }
}
