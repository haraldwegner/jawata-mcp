package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.InlineTool;
import org.jawata.mcp.tools.MoveTool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every Stage 6 fork slice needs, written once.
 *
 * <p>Eleven slices were built in one pass. The alternative was eleven copies of the same
 * six helpers, and the copies would have drifted — one slice would end up with a weaker
 * provenance check than its neighbours and nothing would say which.</p>
 *
 * <p>The provenance assertion is the one that matters and it is not ceremony: a fork slice
 * is evidence only while it is still upstream's file. Edit it into a shape that suits the
 * operation and the clause it satisfies is void, so every slice asserts the licence header
 * is still there before it asserts anything else. The MIT terms require the attribution
 * retained besides.</p>
 */
final class ForkSliceSupport {

    private ForkSliceSupport() {
    }

    /** A loaded fork slice: the service, the package directory, and a door factory. */
    record Slice(JdtServiceImpl service, RefactoringChangeCache cache, Path pkg) {

        AbstractTool door(String name) {
            return switch (name) {
                case "extract" -> new ExtractTool(() -> service, cache);
                case "inline" -> new InlineTool(() -> service, cache);
                default -> new MoveTool(() -> service, cache);
            };
        }

        String read(String fixture) throws Exception {
            Path file = pkg.resolve(fixture);
            return Files.exists(file)
                ? Files.readString(file, StandardCharsets.UTF_8) : "(absent)";
        }

        /** The zero-based line of the first occurrence — the caret is found, never counted. */
        int lineOf(String fixture, String marker) throws Exception {
            String[] lines = read(fixture).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(marker)) {
                    return i;
                }
            }
            throw new AssertionError("the slice's " + fixture + " no longer contains: "
                + marker);
        }

        ObjectNode at(String kind, String fixture, String marker, int column)
                throws Exception {
            ObjectNode args = new ObjectMapper().createObjectNode();
            args.put("kind", kind);
            args.put("filePath", pkg.resolve(fixture).toString());
            args.put("line", lineOf(fixture, marker));
            args.put("column", column);
            return args;
        }
    }

    /**
     * Load a vendored slice and assert it is still upstream's code.
     *
     * @param project the fixture project name, e.g. {@code fork-map-reduce-mr}
     * @param pkgPath the package directory under {@code src/main/java}
     * @param witness a line only upstream's own file carries, so an edit that quietly
     *                reshaped the slice to suit an operation goes red here
     */
    static Slice load(TestProjectHelper helper, String project, String pkgPath,
                      String witnessFile, String witness) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy(project);
        Path pkg = helper.getTempDirectory().resolve(project + "/src/main/java/" + pkgPath);
        Slice slice = new Slice(service, new RefactoringChangeCache(), pkg);
        String source = slice.read(witnessFile);
        assertTrue(source.contains("The MIT License") && source.contains("Ilkka Seppälä"),
            project + "/" + witnessFile + " must still carry upstream's licence header."
                + " Without it this is no longer evidence about anybody's code but our own,"
                + " and the MIT terms require the attribution retained besides");
        assertTrue(source.contains(witness),
            "PROOF OF LIFE: " + witnessFile + " must still contain \"" + witness + "\", the"
                + " shape this slice exists to demonstrate. If it is gone, so is the"
                + " evidence, and the test below would pass over nothing");
        return slice;
    }
}
