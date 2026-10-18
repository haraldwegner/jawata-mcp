package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jawata.mcp.catalogue.RepoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ARCH-C22-3 — every lane list the engine builds in its own code is DECLARED here, with the
 * occasion it answers.
 *
 * <p>The hook crate holds the table of which lanes each occasion asks
 * ({@code jawata_hook::lanes}), and a sweep there holds every literal list in the studio to it
 * (tests/every_lane_list_is_a_row_of_the_table.rs). The engine builds its own lane scopes in a
 * handful of places, and some of them differ from the hook's rows ON PURPOSE: the advisor asks the
 * contracts and rules that bind a member before a refactoring, and the engine's own verbs ask the
 * code lane alone. What was missing was anything that notices a SIXTH list appearing. This test
 * reads the production sources, finds every {@code LaneScope.of(...)} and
 * {@code LaneScope.parse("...")}, and refuses a list that is not in the table below — so a new
 * lane choice is made in a place somebody reviews, with a reason written next to it.</p>
 *
 * <p>The two rows that ARE a hook row are marked, and the hook's rows this engine never builds
 * (the tool call that asks {@code experience, code}, the discussion after a compaction) are not
 * listed: the check runs one way, from the engine's sites to this table.</p>
 *
 * <p>OPEN DESIGN NOTE (recorded in the findings ledger): the alternative to two sweeps is for the
 * engine to own the table and answer "which lanes for this occasion", so no client spells a list.
 * That is a change to the request shape and is not made here.</p>
 */
class EveryLaneScopeTheEngineBuildsIsDeclaredTest {

    /** Each lane list the engine may build, and why. Keyed by the set of lanes. */
    private static final Map<Set<KnowledgeLane>, String> DECLARED = Map.of(
        EnumSet.of(KnowledgeLane.EXPERIENCE),
        "the lessons alone — a hook row (a typed message); the advisor's duplicate-lesson check asks"
            + " only what was already learned about the target",
        EnumSet.of(KnowledgeLane.EXPERIENCE, KnowledgeLane.DOMAIN),
        "lessons and contracts at exactly one member — a hook row (the recall gate); the debug"
            + " tool's recall of what is known about a symbol",
        EnumSet.of(KnowledgeLane.CODE),
        "the code lane alone, by job — asked INSIDE the engine through its own verbs (the duplicate"
            + " check on a drafted method, describing the area of a package); the hook never asks"
            + " it alone",
        EnumSet.of(KnowledgeLane.EXPERIENCE, KnowledgeLane.DOMAIN, KnowledgeLane.RULES,
            KnowledgeLane.CODE),
        "the advisor's tool call — more than the hook's tool-call row ON PURPOSE: before a"
            + " refactoring it also asks the contracts and rules that bind the member");

    private static final Pattern OF = Pattern.compile("LaneScope\\.of\\(([^;]*?)\\)\\s*[;,)]", Pattern.DOTALL);
    private static final Pattern PARSE = Pattern.compile("LaneScope\\.parse\\(\\s*\"([^\"]*)\"");
    private static final Pattern LANE = Pattern.compile("KnowledgeLane\\.(\\w+)");

    /** The lane lists a source text builds, one entry per construction. */
    static List<Set<KnowledgeLane>> listsIn(String source) {
        List<Set<KnowledgeLane>> out = new ArrayList<>();
        Matcher of = OF.matcher(source);
        while (of.find()) {
            Set<KnowledgeLane> lanes = EnumSet.noneOf(KnowledgeLane.class);
            Matcher lane = LANE.matcher(of.group(1));
            while (lane.find()) {
                lanes.add(KnowledgeLane.valueOf(lane.group(1)));
            }
            if (!lanes.isEmpty()) {
                out.add(lanes);
            }
        }
        Matcher parse = PARSE.matcher(source);
        while (parse.find()) {
            out.add(LaneScope.parse(parse.group(1)).lanes());
        }
        return out;
    }

    @Test
    @DisplayName("every lane list the engine builds in production code is declared, with its reason")
    void everyListIsDeclared() throws IOException {
        Path root = RepoFiles.sourceOf("org.jawata.mcp/src");
        assertNotNull(root, "looked for org.jawata.mcp/src above " + Path.of("").toAbsolutePath());
        Map<Set<KnowledgeLane>, List<String>> sitesOf = new java.util.HashMap<>();
        int parsed = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                parsed++;
                String rel = root.relativize(file).toString().replace('\\', '/');
                if (rel.endsWith("knowledge/LaneScope.java")) {
                    continue; // the type itself builds scopes from a caller's words, not a list of its own
                }
                for (Set<KnowledgeLane> list : listsIn(Files.readString(file))) {
                    sitesOf.computeIfAbsent(list, k -> new ArrayList<>()).add(rel);
                }
            }
        }
        // PROOF OF LIFE. A sweep over nothing passes whatever the sources say.
        assertTrue(parsed > 400, "only " + parsed + " production sources parsed — the walk is wrong");
        int sites = sitesOf.values().stream().mapToInt(List::size).sum();
        assertTrue(sites >= 5, "the sweep found " + sites + " lane lists; it is not reading the code");

        TreeMap<String, List<String>> undeclared = new TreeMap<>();
        sitesOf.forEach((list, files) -> {
            if (!DECLARED.containsKey(list)) {
                undeclared.put(list.toString(), files);
            }
        });
        assertTrue(undeclared.isEmpty(), () -> "a lane list nobody declared — a lane choice made at one"
            + " site. Declare it in this test with the occasion it answers, or use a declared list: "
            + undeclared);

        // A declaration no site uses is stale, and a stale row hides the next drift.
        Set<Set<KnowledgeLane>> unused = new java.util.HashSet<>(DECLARED.keySet());
        unused.removeAll(sitesOf.keySet());
        assertTrue(unused.isEmpty(), "declared but built nowhere — remove the row: " + unused);
    }

    @Test
    @DisplayName("the reader finds an of(...) and a parse(...) list and refuses to invent one")
    void theReaderReadsBothFormsAndNothingElse() {
        String text = """
            new RecallQuery(a, null, null, null, null, LaneScope.of(KnowledgeLane.EXPERIENCE,
                KnowledgeLane.PATTERNS));
            var s = LaneScope.parse("domain,code");
            var none = LaneScope.everyLane();
            """;
        List<Set<KnowledgeLane>> got = listsIn(text);
        assertEquals(List.of(EnumSet.of(KnowledgeLane.EXPERIENCE, KnowledgeLane.PATTERNS),
            EnumSet.of(KnowledgeLane.DOMAIN, KnowledgeLane.CODE)), got,
            "both forms read, and everyLane() is not a list the call site wrote");
        assertTrue(!DECLARED.containsKey(got.get(0)),
            "DISCRIMINATOR: a list outside the table is not in it, so the sweep above would refuse it");
    }
}
