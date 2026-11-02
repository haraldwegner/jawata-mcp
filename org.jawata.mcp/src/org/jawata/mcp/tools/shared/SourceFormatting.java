package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.text.edits.TextEdit;

/**
 * The one place the JDT formatter is asked how a Java source text should be laid out (Sprint 28h, S16c): the {@code format} tool applies
 * the answer to files, and the language server hands it to the editor as edits. Both get the project's own formatter settings, so the
 * same file comes out the same whichever way it is asked.
 */
public final class SourceFormatting {

    private SourceFormatting() {
    }

    /**
     * The edit that lays out {@code length} characters of {@code source} from {@code offset} as the unit's project settings say; null
     * when the formatter has nothing to change there. The text may be the saved file's or an editor buffer's: the offsets are in the
     * text given.
     */
    public static TextEdit editFor(ICompilationUnit unit, String source, int offset, int length, String lineSeparator) {
        CodeFormatter formatter = ToolFactory.createCodeFormatter(unit.getJavaProject().getOptions(true));
        TextEdit edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
            source, offset, length, 0, lineSeparator);
        return edit == null || !edit.hasChildren() ? null : edit;
    }
}
