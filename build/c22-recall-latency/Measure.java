import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.jawata.mcp.knowledge.CatalogueManifest;
import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S22, C22 clause 6 — recall time per OCCASION, on a store shaped like the live one.
 *
 * <p>Run against the engine as it was BEFORE the lane change and as it is after, with the same
 * program: it speaks only the published tool's wire arguments, which both builds accept, and the
 * one place the builds differ is named by the first argument.</p>
 *
 * <pre>  java --add-modules jdk.incubator.vector -cp 'DIST/bundles/*:DIST/slf4j-*.jar' Measure.java old|new [repeats] [scale]</pre>
 *
 * <h2>What the corpus is</h2>
 *
 * <p>FROZEN and synthetic, on purpose: a live store measures the store, and a corpus authored per
 * run measures the author. Row counts are the live store's on 2026-10-17 (11,278 rows): 221 lessons,
 * 189 catalogue patterns, 77 domain facts, 10,791 code descriptions. The words are drawn from a
 * fixed vocabulary with a fixed seed, so the same program builds the same store every time.</p>
 *
 * <h2>What it does NOT measure, said here so a reader does not infer it</h2>
 *
 * <p>The duplicate check (a write that adds a method) is not here: it parses the draft with the
 * JDT, whose classes need the bundle runtime and cannot load from a plain classpath.</p>
 *
 * <p>The corpus has the live store's PROPORTIONS but, at a scale below 1, fewer rows, because every
 * row is embedded on write and the embedder's speed bounds how large a corpus can be built. The
 * scale used is printed with the figures.</p>
 */
public final class Measure {

    /** The live proportions at scale 1; a smaller scale keeps them and shrinks every lane alike. */
    private static double scale = 1.0;
    private static int lessons() { return (int) Math.round(221 * scale); }
    private static int patterns() { return (int) Math.round(189 * scale); }
    private static int domain() { return (int) Math.round(77 * scale); }
    private static int code() { return (int) Math.round(10_791 * scale); }
    private static final int VOCAB = 2_500;

    public static void main(String[] args) throws Exception {
        boolean before = args.length > 0 && args[0].equals("old");
        int repeats = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        scale = args.length > 2 ? Double.parseDouble(args[2]) : 1.0;

        H2ExperienceStore store = H2ExperienceStore.openMemory();
        long built = System.nanoTime();
        fill(store);
        System.out.printf("corpus built in %d ms: %d lessons, %d patterns, %d domain facts, %d code rows%n",
            (System.nanoTime() - built) / 1_000_000, lessons(), patterns(), domain(), code());

        ExperienceTool tool = new ExperienceTool(() -> null, store);
        ObjectMapper mapper = new ObjectMapper();

        String words = word(7) + " " + word(19) + " " + word(311);
        String question = "which part of the product handles " + word(7) + " and " + word(19);
        String member = "com.example.p3.C30#m30";

        // THE OCCASIONS. Before the change a typed message ALSO asked the code lane for the map, on
        // every message; after it, a typed message asks the lessons and nothing else. A tool call
        // naming a member asked every lane before (it could not name two); after, it names two.
        Map<String, List<ObjectNode>> occasions = new java.util.LinkedHashMap<>();
        occasions.put("typed message", before
            ? List.of(call(mapper, "recall", "symptom", words),
                      map(mapper, question))
            : List.of(call(mapper, "recall", "symptom", words, "lane", "experience")));
        occasions.put("tool call naming a member", before
            ? List.of(call(mapper, "recall", "symbol", member))
            : List.of(call(mapper, "recall", "symbol", member, "lane", "experience,code")));
        occasions.put("a design question (nominate, lessons and patterns)", List.of(
            before ? call(mapper, "nominate", "question", question)
                   : call(mapper, "nominate", "question", question, "lane", "experience,patterns")));

        System.out.printf("%-58s %8s %8s %8s%n", "occasion (" + (before ? "BEFORE" : "AFTER") + ")",
            "median", "p95", "chars");
        for (Map.Entry<String, List<ObjectNode>> occasion : occasions.entrySet()) {
            for (int i = 0; i < 5; i++) {
                run(tool, occasion.getValue());                      // warm-up, discarded
            }
            List<Long> millis = new ArrayList<>();
            int chars = 0;
            for (int i = 0; i < repeats; i++) {
                long t = System.nanoTime();
                chars = run(tool, occasion.getValue());
                millis.add((System.nanoTime() - t) / 1_000);
            }
            Collections.sort(millis);
            System.out.printf("%-58s %7.1fms %7.1fms %8d%n", occasion.getKey(),
                millis.get(millis.size() / 2) / 1000.0,
                millis.get((int) Math.floor(millis.size() * 0.95) - 1) / 1000.0, chars);
        }
        store.close();
    }

    /** Runs every call of one occasion and returns how much text came back. */
    private static int run(ExperienceTool tool, List<ObjectNode> calls) {
        int chars = 0;
        for (ObjectNode c : calls) {
            ToolResponse r = tool.execute(c);
            chars += String.valueOf(r.isSuccess() ? r.getData() : r.getError()).length();
        }
        return chars;
    }

    private static ObjectNode call(ObjectMapper m, String kind, String... pairs) {
        ObjectNode n = m.createObjectNode();
        n.put("kind", kind);
        for (int i = 0; i < pairs.length; i += 2) {
            n.put(pairs[i], pairs[i + 1]);
        }
        return n;
    }

    private static ObjectNode map(ObjectMapper m, String question) {
        ObjectNode n = call(m, "nominate", "question", question, "lane", "code");
        n.put("format", "text");
        return n;
    }

    private static String word(int i) {
        return "w" + (i % VOCAB);
    }

    private static String sentence(Random r, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : " ").append(word(r.nextInt(VOCAB)));
        }
        return sb.toString();
    }

    private static void fill(H2ExperienceStore store) {
        Random r = new Random(22);
        for (int i = 0; i < lessons(); i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("lesson", sentence(r, 14), Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED)
                .situation("when " + sentence(r, 6))
                .verdict("worked")
                .build());
        }
        for (int i = 0; i < patterns(); i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("reference", "Pattern " + sentence(r, 14), Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED)
                .provenanceKind(CatalogueManifest.PROVENANCE)
                .build());
        }
        for (int i = 0; i < domain(); i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("domain_fact", sentence(r, 16), Confidence.MEDIUM).build())
                .status(ExperienceEntry.ACCEPTED).build());
        }
        for (int i = 0; i < code(); i++) {
            store.put(ExperienceEntry.of(
                    SymbolFact.of("job", sentence(r, 22), Confidence.MEDIUM)
                        .symbol("com.example.p" + (i % 40) + ".C" + (i % 400) + "#m" + (i % 4000))
                        .build())
                .status(ExperienceEntry.ACCEPTED).build());
        }
    }
}
