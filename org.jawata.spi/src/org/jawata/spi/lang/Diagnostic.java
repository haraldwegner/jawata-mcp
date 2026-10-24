package org.jawata.spi.lang;

/**
 * One problem a build left: the file it is in (null when it is about a project, not a file), its 1-based line (-1 when there is
 * none), and the language's own message — with how severe it is, which project it is in and whether that is test code (28k E10b, so
 * the compile check exists once: the tools that report problems read these rows instead of sweeping the host's markers
 * themselves).
 *
 * @param severity how severe the language rates it; {@link Severity#ERROR} for a row built from the three-argument constructor
 * @param project  the workspace name of the project it is in; null when not known
 * @param scope    whether it lies in main code, test code or neither (see {@link TestScope})
 * @param detail   the language's own positional and classification detail, {@link Detail#NONE} when it gives none
 */
public record Diagnostic(String filePath, int line, String message, Severity severity, String project, TestScope scope,
                         Detail detail) {

    /** Ascending: a reader that wants "at least a warning" compares with {@link Enum#compareTo}. */
    public enum Severity {
        INFO, WARNING, ERROR
    }

    /** Where a problem lies: in main code, in test code, or in neither (a manifest, a build file) — visible to both views. */
    public enum TestScope {
        MAIN, TEST, CROSS_CUTTING
    }

    /** The character range of the problem and the language's own id and category numbers for its kind; -1 where absent. */
    public record Detail(int charStart, int charEnd, int problemId, int categoryId) {

        public static final Detail NONE = new Detail(-1, -1, -1, -1);
    }

    /** An error row about a file and line, as every build check made before the extra fields existed. */
    public Diagnostic(String filePath, int line, String message) {
        this(filePath, line, message, Severity.ERROR, null, TestScope.CROSS_CUTTING, Detail.NONE);
    }
}
