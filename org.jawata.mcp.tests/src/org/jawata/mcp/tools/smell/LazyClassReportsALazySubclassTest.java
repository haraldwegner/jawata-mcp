package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code lazy_class} MUST BE ABLE TO REPORT THE SHAPE ITS SECOND CURE FIXES.
 *
 * <h2>The defect, which was structural rather than occasional</h2>
 *
 * <p>The cure table gives this smell two runnable cures — {@code inline kind=class} "when the
 * class stands BESIDE its user" and {@code inline kind=subclass} "when it stands UNDER a
 * parent and overrides nothing" — and the signed spec assigns row 38, Remove Subclass, to
 * this detector. But the detector returned early for ANY declared supertype, and
 * {@code RemoveSubclassTool} refuses exactly the complement, saying "A class with no
 * superclass is Inline Class's case". The SAME AST predicate, {@code getSuperclassType()},
 * read with opposite polarity — so no finding this detector could emit was ever one that cure
 * could act on, and an agent handed both was sent between two operations pointing at each
 * other. Found by dogfooding v4.2.0 on a static utility class, where BOTH cures refuse.</p>
 *
 * <p>The fixture is written into the COPY rather than added to {@code simple-maven}: that
 * project is shared and monotonically growing, and adding to it has moved a counted
 * population four times in this sprint.</p>
 */
class LazyClassReportsALazySubclassTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** A parent, a subclass that adds nothing, and a subclass that genuinely overrides. */
    private static final String SOURCE =
        "package com.example;\n"
            + "\n"
            + "public class LazySubclassProbe {\n"
            + "    public static class Base {\n"
            + "        public int rate() { return 1; }\n"
            + "    }\n"
            + "    /** Adds nothing at all — Remove Subclass's case, and row 38's. */\n"
            + "    public static class IdleHeir extends Base {\n"
            + "    }\n"
            + "    /** Overrides, so it earns its place and must NOT be reported. */\n"
            + "    public static class RealHeir extends Base {\n"
            + "        @Override public int rate() { return 2; }\n"
            + "    }\n"
            + "}\n";

    @SuppressWarnings("unchecked")
    private Set<String> lazySymbols(JdtServiceImpl service) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "lazy_class");
        ToolResponse r = new FindQualityIssueTool(() -> service).execute(args);
        assertTrue(r.isSuccess(), "lazy_class must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a subclass that overrides nothing is reported; one that overrides is not")
    void aSubclassThatOverridesNothingIsReported() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path root = service.allProjects().iterator().next().projectRoot();
        Path file = root.resolve("src/main/java/com/example/LazySubclassProbe.java");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        ResourcesPlugin.getWorkspace().getRoot()
            .refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        Set<String> hits = lazySymbols(service);

        assertTrue(hits.contains("com.example.LazySubclassProbe.IdleHeir"),
            "a subclass adding nothing is Lazy Element in Fowler's own terms, and it is the"
                + " ONLY shape `inline kind=subclass` can act on — while this detector"
                + " excluded every subclass, that cure was unreachable from every finding"
                + " this smell could emit. Got: " + hits);

        // THE CONTROL, and it is what stops the widening from becoming "report every
        // subclass". Without it the assertion above passes against a detector that dropped
        // the hierarchy check altogether, which is a different defect wearing the same green.
        assertFalse(hits.contains("com.example.LazySubclassProbe.RealHeir"),
            "a subclass that OVERRIDES is doing the job inheritance is for and must not be"
                + " reported: " + hits);
    }

    @Test
    @DisplayName("the standalone case still works — the widening added a shape, it did not swap one")
    void theStandaloneCaseIsUnchanged() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Set<String> hits = lazySymbols(service);
        assertTrue(hits.contains("com.example.LazyLeaf"),
            "the empty standalone class this detector was written for must still be found: "
                + hits);
        assertFalse(hits.contains("com.example.BusyLeaf"),
            "and the threshold must still exclude a class above it: " + hits);
    }
}
