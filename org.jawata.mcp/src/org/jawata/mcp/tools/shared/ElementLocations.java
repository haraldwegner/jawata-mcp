package org.jawata.mcp.tools.shared;

import java.util.LinkedHashMap;
import java.util.Map;

import org.eclipse.core.runtime.IPath;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.IJdtService;
import org.jawata.mcp.java.JavaRanges;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where an element is declared, in the one shape the doors answer it (Sprint 28h, S16b): the file, the start line and column, the end of
 * the declaration and the range of the name alone. {@code go_to_definition} gave this shape first; the hierarchy doors give it for the
 * types and methods in their rows, so an editor can land on a name without asking again.
 */
public final class ElementLocations {

    private static final Logger log = LoggerFactory.getLogger(ElementLocations.class);

    private ElementLocations() {
    }

    /**
     * The declaration of {@code element}, or null when there is none to show: an element without source, or one outside a compilation unit.
     * The start stays at the declaration's first character, doc comment included, as {@code go_to_definition} always answered it.
     */
    public static Map<String, Object> of(IJavaElement element, IJdtService service) {
        if (!(element instanceof ISourceReference reference)) {
            return null;
        }
        try {
            ISourceRange range = reference.getSourceRange();
            ICompilationUnit cu = (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT);
            if (range == null || cu == null) {
                return null;
            }
            Map<String, Object> location = new LinkedHashMap<>();
            location.put("filePath", filePathOf(cu, service));
            location.put("line", service.getLineNumber(cu, range.getOffset()));
            location.put("column", service.getColumnNumber(cu, range.getOffset()));
            JavaRanges.Lines lines = JavaRanges.linesOf(cu);
            lines.declaration(element).ifPresent(declaration -> declaration.putEndInto(location));
            lines.name(element).ifPresent(name -> location.put("nameRange", name.asMap()));
            return location;
        } catch (JavaModelException | RuntimeException e) {
            log.debug("No location for {}: {}", element.getElementName(), e.getMessage());
            return null;
        }
    }

    private static String filePathOf(ICompilationUnit cu, IJdtService service) {
        if (cu.getResource() != null) {
            IPath location = cu.getResource().getLocation();
            if (location != null) {
                return service.getPathUtils().formatPath(location.toOSString());
            }
        }
        return "";
    }
}
