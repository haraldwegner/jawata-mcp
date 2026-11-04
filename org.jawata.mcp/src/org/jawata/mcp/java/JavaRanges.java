package org.jawata.mcp.java;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.ISourceReference;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one place a source span becomes a start and an end (Sprint 28h, S16a). The tools used to answer a start position only, and an
 * editor protocol wants a range: the whole declaration, and for navigation the name alone. Every tool that gained {@code endLine},
 * {@code endColumn} or {@code nameRange}, and the language server after them, takes it from here, so there is no second way to turn an
 * offset into a position.
 *
 * <p>Lines and columns count from zero. A line ends at a newline, and a carriage return before it belongs to the line it ends, which is
 * how {@code IJdtService.getLineNumber} and {@code getColumnNumber} count, so a start given by those and an end given here never
 * disagree. Columns count UTF-16 units, as both JDT's offsets and the editor protocol do, so no conversion is needed.</p>
 *
 * <p>The ranges come from JDT's element ranges, which are the parse's own declaration extents and name extents; no second parse is made.
 * An element with no source in the workspace has no range, which is an absent value and not a zero.</p>
 */
public final class JavaRanges {

    private static final Logger log = LoggerFactory.getLogger(JavaRanges.class);

    private JavaRanges() {
    }

    /** A span as a start and an end, zero-based. The end is the position just after the last character. */
    public record Range(int line, int column, int endLine, int endColumn) {

        /** The four numbers under the names the tool answers use. */
        public Map<String, Object> asMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("line", line);
            map.put("column", column);
            map.put("endLine", endLine);
            map.put("endColumn", endColumn);
            return map;
        }

        /** The range as the language server protocol spells it: a start and an end, each a line and a character. */
        public Map<String, Object> toProtocol() {
            return Map.of("start", Map.of("line", line, "character", column), "end", Map.of("line", endLine, "character", endColumn));
        }

        /** Adds the end to a tool answer that already carries this range's start. */
        public void putEndInto(Map<String, Object> answer) {
            answer.put("endLine", endLine);
            answer.put("endColumn", endColumn);
        }
    }

    /** The line starts of one text, built once, from which any offset becomes a position. */
    public static final class Lines {

        private final int length;
        private final int[] starts;
        /** The unit these lines were built from (its primary), or null for a bare text; an element of another unit has no range here. */
        private final ICompilationUnit owner;

        private Lines(String text, ICompilationUnit owner) {
            this.owner = owner;
            this.length = text.length();
            int[] found = new int[16];
            int count = 1;
            for (int i = 0; i < length; i++) {
                if (text.charAt(i) == '\n') {
                    if (count == found.length) {
                        found = Arrays.copyOf(found, count * 2);
                    }
                    found[count++] = i + 1;
                }
            }
            this.starts = Arrays.copyOf(found, count);
        }

        /** The range of {@code length} characters from {@code offset}; an offset or end outside the text is moved to the text's edge. */
        public Range span(int offset, int length) {
            int start = clamp(offset);
            int end = clamp((long) offset + Math.max(length, 0));
            return new Range(lineOf(start), start - starts[lineOf(start)], lineOf(end), end - starts[lineOf(end)]);
        }

        /**
         * The offset of a position, the reverse of {@link #span}. A line past the last is the end of the text; a column past the end of
         * its line is that line's end (the protocol says so too); a negative line or column is the text's or the line's start.
         */
        public int offsetOf(int line, int column) {
            if (line < 0) {
                return 0;
            }
            if (line >= starts.length) {
                return length;
            }
            int lineEnd = line + 1 < starts.length ? starts[line + 1] - 1 : length;
            return Math.min(starts[line] + Math.max(column, 0), lineEnd);
        }

        /** The whole declaration of {@code element}, doc comment included, or empty when it has no source here. */
        public Optional<Range> declaration(IJavaElement element) {
            try {
                return element instanceof ISourceReference reference && inThisUnit(element)
                    ? ofRange(reference.getSourceRange()) : Optional.empty();
            } catch (JavaModelException e) {
                log.debug("No declaration range for {}: {}", element.getElementName(), e.getMessage());
                return Optional.empty();
            }
        }

        /** The name of {@code element} alone, or empty when it has no source here or no name in the source. */
        public Optional<Range> name(IJavaElement element) {
            if (!inThisUnit(element)) {
                return Optional.empty();
            }
            try {
                ISourceRange name = switch (element) {
                    case IMember member -> member.getNameRange();
                    case ILocalVariable variable -> variable.getNameRange();
                    case ITypeParameter parameter -> parameter.getNameRange();
                    case IAnnotation annotation -> annotation.getNameRange();
                    default -> null;
                };
                return ofRange(name);
            } catch (JavaModelException e) {
                log.debug("No name range for {}: {}", element.getElementName(), e.getMessage());
                return Optional.empty();
            }
        }

        /**
         * Whether {@code element} is in the unit these lines were built from. An offset of another unit's text is a position in a text
         * this object does not hold, so it would be a wrong answer that looks right; such an element, and a binary one with no unit at
         * all, has no range here. Lines built from a bare text hold no unit and take the caller's word.
         */
        private boolean inThisUnit(IJavaElement element) {
            if (owner == null) {
                return true;
            }
            return element.getAncestor(IJavaElement.COMPILATION_UNIT) instanceof ICompilationUnit unit && owner.equals(unit.getPrimary());
        }

        private Optional<Range> ofRange(ISourceRange range) {
            return range == null || range.getOffset() < 0 ? Optional.empty() : Optional.of(span(range.getOffset(), range.getLength()));
        }

        private int clamp(long offset) {
            return (int) Math.max(0, Math.min(offset, length));
        }

        private int lineOf(int offset) {
            int at = Arrays.binarySearch(starts, offset);
            return at >= 0 ? at : -at - 2;
        }
    }

    /** The lines of a text. */
    public static Lines linesOf(String text) {
        return new Lines(text == null ? "" : text, null);
    }

    /** The lines of a compilation unit's text as JDT holds it now, which for an open file is the unsaved text. */
    public static Lines linesOf(ICompilationUnit unit) throws JavaModelException {
        String source = unit.getSource();
        return new Lines(source == null ? "" : source, unit.getPrimary());
    }
}
