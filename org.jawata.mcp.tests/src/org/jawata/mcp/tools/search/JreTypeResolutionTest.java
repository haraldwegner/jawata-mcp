package org.jawata.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.IType;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindTypeArgumentsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 22c (2026-07-11) SENTINEL — JRE-type search health. Guards the exact failure
 * class that BLOCKED the Eclipse 2026-06 (4.40) re-base: JDT 4.40's
 * {@code ProblemReporter.previewAPIUsed} dereferences a null {@code FieldBinding}
 * (no guard, runs before any severity check) when the search engine's lookup
 * environment fails to resolve the JDK-internal {@code PreviewFeature.Feature}
 * enum — any {@code SearchEngine} REFERENCES search touching a preview-annotated
 * Java-21 API in hierarchy connection dies, flakily. Reported upstream
 * (eclipse-jdt/eclipse.jdt.core); re-attempt newer trains only when these tests
 * are green there. Expected green on 2025-12 (4.38), the 22c as-built train.
 */
class JreTypeResolutionTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("patched bundle active: ProblemReporter is served from the -jawata5188 repack")
    void patchedBundleActive() throws Exception {
        // While the eclipse.jdt.core#5188 local patch exists, the runtime MUST serve
        // the repacked compiler.batch (higher qualifier -jawata5188 wins in p2) —
        // this pins the wiring for product and test runtime alike. (Historical note:
        // an OSGi patch FRAGMENT was tried first and silently did nothing — current
        // Equinox no longer supports Eclipse-PatchFragment; hence the repack.)
        Class<?> pr = org.eclipse.jdt.core.JavaCore.class.getClassLoader()
            .loadClass("org.eclipse.jdt.internal.compiler.problem.ProblemReporter");
        var src = pr.getProtectionDomain().getCodeSource();
        String where = src == null ? "null" : String.valueOf(src.getLocation());
        assertTrue(where.contains("jawata5188"),
            "ProblemReporter must come from the patched bundle; served from: " + where);
    }

    @Test
    @DisplayName("findType resolves a JRE type and the type_argument tool answers for it")
    void jreTypeResolvesAndToolAnswers() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");

        IType stringType = service.findType("java.lang.String");
        assertNotNull(stringType,
            "findType(java.lang.String) must resolve via the JRE container of the fixture project");

        FindTypeArgumentsTool tool = new FindTypeArgumentsTool(() -> service);
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("typeName", "java.lang.String");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "type_argument tool failed for java.lang.String: " + r.getError());
    }

    @Test
    @DisplayName("raw references search for java.lang.String (full stack on failure)")
    void rawReferencesSearchForString() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        IType stringType = service.findType("java.lang.String");
        assertNotNull(stringType);
        // No tool wrapper — a JDT-internal NPE surfaces with its FULL stack as a test error.
        var matches = service.getSearchService().findAllReferences(stringType, 200);
        assertNotNull(matches);
    }

    @Test
    @DisplayName("JRE types still resolve after the fixture is loaded a second time (cross-test-class contamination probe)")
    void secondLoadStillResolvesJreTypes() throws Exception {
        // Mimic what consecutive test classes do in one JVM: each @BeforeEach creates
        // a fresh JdtServiceImpl over the SAME fixture in the SAME workspace singleton.
        JdtServiceImpl first = helper.loadProject("simple-maven");
        assertNotNull(first.findType("java.lang.String"), "first load must resolve JRE types");

        JdtServiceImpl second = new JdtServiceImpl();
        second.loadProject(helper.getFixturePath("simple-maven"));
        IType viaSecond = second.findType("java.lang.String");
        assertNotNull(viaSecond,
            "second load of the same fixture must STILL resolve JRE types — on Eclipse "
                + "2026-06 this is where java.lang.String resolution died mid-suite");

        FindTypeArgumentsTool tool = new FindTypeArgumentsTool(() -> second);
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("typeName", "java.lang.String");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "type_argument tool failed after reload: " + r.getError());
    }
}
