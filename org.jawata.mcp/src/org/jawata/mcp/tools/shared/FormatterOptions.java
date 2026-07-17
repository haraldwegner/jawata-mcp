package org.jawata.mcp.tools.shared;

import org.eclipse.core.resources.ProjectScope;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;

import java.util.HashMap;
import java.util.Map;

/**
 * Sprint 25 (v2.14.1, finding #5) — the formatter options a CODE-GENERATING
 * rewrite should use, so generated/inserted code matches the target file's
 * indentation instead of JDT's built-in tab default.
 *
 * <p>The tab character is resolved by PRECEDENCE (most specific wins):</p>
 * <ol>
 *   <li><b>per-call override</b> — an explicit {@code indentChar} ("space" |
 *       "tab") passed by the caller, for the rare deliberate exception;</li>
 *   <li><b>the project's own formatter config</b> — an explicit
 *       {@code org.eclipse.jdt.core.formatter.tabulation.char} in the project
 *       scope ({@code .settings/org.eclipse.jdt.core.prefs}). A repo that says
 *       tabs GETS tabs — the tool never overrides a committed convention;</li>
 *   <li><b>a config-less default</b> — spaces, size 4 (what our code and
 *       IntelliJ use) when the project declares nothing;</li>
 *   <li>otherwise whatever {@code cu.getOptions(true)} already carries.</li>
 * </ol>
 *
 * <p>The studio-level default (tier 3 ergonomics — a manager setting for
 * config-less projects) is a flagged Sprint-28 follow-up; here we hardcode the
 * spaces/4 fallback.</p>
 */
public final class FormatterOptions {

    private FormatterOptions() {}

    /**
     * A formatter options map for {@code ASTRewrite.rewriteAST(doc, options)}
     * on a code-generating rewrite over {@code cu}. {@code overrideChar} is the
     * caller's explicit {@code indentChar} ("space"/"tab") or {@code null}.
     */
    public static Map<String, String> forGeneratedCode(ICompilationUnit cu, String overrideChar) {
        Map<String, String> options = new HashMap<>(cu.getOptions(true));
        if (overrideChar != null && !overrideChar.isBlank()) {
            options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR,
                "tab".equalsIgnoreCase(overrideChar) ? JavaCore.TAB : JavaCore.SPACE);
        } else if (!projectDeclaresTabChar(cu)) {
            // Config-less project: apply the spaces/4 default. A project WITH a
            // formatter config is left untouched — cu.getOptions(true) already
            // carries its (winning) values.
            options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
            options.putIfAbsent(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, "4");
            options.putIfAbsent(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, "4");
        }
        return options;
    }

    /** True when the project scope explicitly declares a formatter tab char. */
    private static boolean projectDeclaresTabChar(ICompilationUnit cu) {
        try {
            IJavaProject project = cu.getJavaProject();
            if (project == null || project.getProject() == null) {
                return false;
            }
            String declared = new ProjectScope(project.getProject())
                .getNode(JavaCore.PLUGIN_ID)
                .get(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, null);
            return declared != null;
        } catch (Exception e) {
            return false; // unknown → treat as config-less, apply the default
        }
    }
}
