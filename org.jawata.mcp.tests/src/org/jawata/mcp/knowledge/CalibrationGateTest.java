package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 27 C4 — <b>the E2 gate</b>: does semantic recall actually find the
 * right knowledge, on the REAL corpus, through the REAL union?
 *
 * <p>Judged by the ACCEPT-SET rule (Harald's option A, frozen at C0 in
 * {@code accept-sets.json} before any retrieval code existed): a cue PASSES when
 * the winning entry is in that cue's accept set AND the designated entry is
 * inside the nomination window. Bar: <b>≥10 of 12</b>, against a keyword
 * baseline of 1 of 12.</p>
 *
 * <p>This test needs the corpus export it was calibrated against — a dump of a
 * real 2054-entry store, which is NOT committed (it is personal knowledge, and
 * pinned by sha256 rather than vendored). Point it at one with
 * {@code -Djawata.embed.corpus=/path/to/export.json}. Without it the test
 * reports LOUDLY that the gate did not run rather than passing quietly: a gate
 * that silently skips is indistinguishable from one that passed, and that is
 * exactly how a headline claim goes unverified.</p>
 */
class CalibrationGateTest {

    private static final String CORPUS_PROPERTY = "jawata.embed.corpus";
    private static final String ACCEPT_SETS = "/test-resources/embed-goldens/accept-sets.json";
    private static final int BAR = 10;
    /** The frozen contract's nomination window: the designated entry must be inside it. */
    private static final int FROZEN_K = 12;
    /** C2 clause (i): the meaning path alone, by the frozen contract. */
    private static final int EMBEDDINGS_ALONE_BAR = 9;

    @Test
    void the_calibration_cues_are_answered_from_the_real_corpus() throws Exception {
        String corpusPath = System.getProperty(CORPUS_PROPERTY);
        if (corpusPath == null || !Files.exists(Path.of(corpusPath))) {
            System.out.println("[E2 GATE] NOT RUN — no corpus at -D" + CORPUS_PROPERTY
                + ". This is the sprint's headline gate; it is unverified in this run.");
            return;
        }
        EmbeddingService svc = EmbeddingService.shared();
        if (!svc.available()) {
            System.out.println("[E2 GATE] NOT RUN — embedder unavailable: "
                + svc.unavailableReason());
            return;
        }

        JsonNode accept;
        try (InputStream in = CalibrationGateTest.class.getResourceAsStream(ACCEPT_SETS)) {
            accept = new ObjectMapper().readTree(in);
        }

        H2ExperienceStore store = H2ExperienceStore.openMemory();
        try {
            // The corpus is SAMPLED, not truncated, and every entry named in an
            // accept set is force-included so the gate is never made easier by
            // dropping the answer. The bound exists because embedding a real
            // entry costs 124-660 ms (C4-F5) and the in-framework runner halts
            // a test with no event for 5 minutes - it cannot tell a long test
            // from a hang, and that safeguard is worth more than a bigger N.
            // Fewer rivals is WEAKER evidence than the full corpus; the sample
            // size is printed and recorded rather than glossed.
            int rivals = Integer.getInteger("jawata.embed.corpus.sample", 700);
            int loaded = loadCorpus(store, Path.of(corpusPath), rivals, acceptedIds(accept));
            EmbeddingIndex index = new EmbeddingIndex(store, svc);
            int embedded = 0;
            // Embed in slices so a large corpus cannot stall on one call.
            for (int pass = 0; pass < 200; pass++) {
                int n = index.backfill(200);
                embedded += n;
                if (n == 0) {
                    break;
                }
            }
            System.out.printf("[E2 GATE] corpus=%d loaded, %d embedded, identity=%s%n",
                loaded, embedded, svc.identityKey());
            // A gate that cannot find its own answers is measuring its fixture,
            // not the product. Prove every accept-set entry actually landed.
            Set<String> presentIds = new LinkedHashSet<>();
            for (StoredEntry e : store.all()) {       // ONE pass, not one per id
                if (e.id() != null) {
                    presentIds.add(e.id());
                }
            }
            List<String> missing = new ArrayList<>();
            for (String want : acceptedIds(accept)) {
                if (presentIds.stream().noneMatch(id -> id.startsWith(want))) {
                    missing.add(want);
                }
            }
            System.out.printf("[E2 GATE] accept-set entries present: %d of %d%s%n",
                acceptedIds(accept).size() - missing.size(), acceptedIds(accept).size(),
                missing.isEmpty() ? "" : " — MISSING " + missing);

            ExperienceRetrieval union = new ExperienceRetrieval(store, () -> null, index);
            ExperienceRetrieval keyword = ExperienceRetrieval.keywordOnly(store, () -> null);

            int passed = 0;
            int keywordPassed = 0;
            int embeddingsPassed = 0;
            List<String> embeddingsFailing = new ArrayList<>();
            List<String> rows = new ArrayList<>();
            for (JsonNode cue : accept.get("cues")) {
                String text = cue.get("cue").asText();
                Set<String> ok = new LinkedHashSet<>();
                for (JsonNode a : cue.get("accept_set")) {
                    ok.add(a.asText());
                }
                Map<String, Object> unionAnswer = union.recall(query(cue, text));
                Map<String, Object> kwAnswer = keyword.recall(query(cue, text));
                boolean semantic = winnerIsAcceptable(unionAnswer, ok);
                boolean kw = winnerIsAcceptable(kwAnswer, ok);

                // THE EMBEDDINGS-ONLY ARM, and the frozen contract's second
                // half. The C2 gate is written as "embeddings alone >=9/12 AND
                // union >=10/12" precisely so the symbol path cannot mask a
                // meaning regression — cue-11 is already union-only. Until the
                // C2 audit, only the union was ever measured, so the clause was
                // satisfied by a number that could not see what it guards
                // against. This scores the meaning ranking directly, and checks
                // BOTH halves the contract names: winner acceptable, AND the
                // designated entry inside K.
                if (embeddingsAlonePasses(index, text, ok, cue.get("designated").asText())) {
                    embeddingsPassed++;
                } else {
                    embeddingsFailing.add(cue.path("id").asText());
                }
                if (!semantic || !kw) {
                    // DIAGNOSIS (C4 investigation): show what each arm actually
                    // returned, so a fixture defect cannot masquerade as a
                    // retrieval verdict.
                    System.out.printf("  ? %-12s union=%s %s | kw=%s %s%n",
                        cue.path("id").asText(),
                        unionAnswer.get("result"), top3(unionAnswer),
                        kwAnswer.get("result"), top3(kwAnswer));
                }
                if (semantic) {
                    passed++;
                }
                if (kw) {
                    keywordPassed++;
                }
                rows.add(String.format("  [%s] kw=%-5s %-14s %s",
                    semantic ? "PASS" : "MISS", kw ? "hit" : "miss",
                    cue.get("class").asText(), text.length() > 56
                        ? text.substring(0, 56) + "…" : text));
            }
            rows.forEach(System.out::println);
            System.out.printf("[E2 GATE] semantic %d/%d · embeddings-alone %d/%d · "
                + "keyword %d/%d · bar %d%n",
                passed, accept.get("cues").size(),
                embeddingsPassed, accept.get("cues").size(),
                keywordPassed, accept.get("cues").size(), BAR);
            if (!embeddingsFailing.isEmpty()) {
                System.out.println("[E2 GATE] embeddings-alone failing: " + embeddingsFailing);
            }

            assertTrue(passed >= BAR,
                "E2: semantic recall answered " + passed + " of "
                + accept.get("cues").size() + " calibration cues; the bar is " + BAR
                + " (C0 baseline). This is the sprint's central claim.");
            assertTrue(passed > keywordPassed,
                "E2: semantic (" + passed + ") must strictly beat keyword ("
                + keywordPassed + ") — otherwise the sprint bought nothing.");
            assertTrue(embeddingsPassed >= EMBEDDINGS_ALONE_BAR,
                "E2 clause (i): the MEANING path alone answered " + embeddingsPassed
                + " of " + accept.get("cues").size() + " by the frozen contract "
                + "(winner in accept_set AND designated within K=" + FROZEN_K + "); "
                + "the bar is " + EMBEDDINGS_ALONE_BAR + ". Gating only the union "
                + "would let the symbol path mask exactly this. Failing: "
                + embeddingsFailing);
        } finally {
            store.close();
        }
    }

    /** The cue as the agent would pose it: a symbol cue by symbol, prose by symptom. */
    private static RecallQuery query(JsonNode cue, String text) {
        return "symbol".equals(cue.path("keyword_cue_field").asText("symptom"))
            ? new RecallQuery(text, null, null, null, null)
            : new RecallQuery(null, null, null, text, null);
    }

    /**
     * The accept-set rule: whatever the answer leads with — a gated entry or the
     * top analogy — must be an entry the frozen set says legitimately answers
     * this cue.
     */
    @SuppressWarnings("unchecked")
    private static boolean winnerIsAcceptable(Map<String, Object> answer, Set<String> acceptable) {
        List<Map<String, Object>> entries =
            (List<Map<String, Object>>) answer.getOrDefault("entries", List.of());
        List<Map<String, Object>> analogies =
            (List<Map<String, Object>>) answer.getOrDefault("analogies", List.of());
        List<Map<String, Object>> ranked = new ArrayList<>(entries);
        ranked.addAll(analogies);
        for (Map<String, Object> r : ranked) {
            String id = String.valueOf(r.get("id"));
            for (String a : acceptable) {
                if (id.startsWith(a)) {
                    return true;
                }
            }
            break;                            // only the WINNER counts
        }
        return false;
    }

    /**
     * The frozen contract measured on the MEANING RANKING ALONE — no keyword,
     * no symbol path: the winner must be in the accept set AND the designated
     * entry must sit inside the top {@link #FROZEN_K}.
     *
     * <p>This reads the ranking straight off the index rather than through
     * {@code recall()}, because every {@code recall()} answer is a union by
     * construction. A union-only number cannot fail when the symbol path
     * carries a cue the embeddings lost, which is the masking the C2 clause
     * was written to prevent.</p>
     */
    private static boolean embeddingsAlonePasses(EmbeddingIndex index, String cueText,
                                                 Set<String> acceptable, String designated) {
        List<EmbeddingIndex.Hit> ranked = index.nearestEntries(cueText, FROZEN_K, 0.0);
        if (ranked.isEmpty()) {
            return false;
        }
        String winner = ranked.get(0).id();
        boolean winnerOk = acceptable.stream().anyMatch(winner::startsWith);
        boolean designatedInWindow = ranked.stream()
            .anyMatch(h -> h.id().startsWith(designated));
        return winnerOk && designatedInWindow;
    }

    /** The first three ranked ids of an answer (entries then analogies), for diagnosis. */
    @SuppressWarnings("unchecked")
    private static String top3(Map<String, Object> answer) {
        List<Map<String, Object>> ranked = new ArrayList<>(
            (List<Map<String, Object>>) answer.getOrDefault("entries", List.of()));
        ranked.addAll((List<Map<String, Object>>) answer.getOrDefault("analogies", List.of()));
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < ranked.size() && i < 3; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.valueOf(ranked.get(i).get("id")), 0, 8);
        }
        return sb.append(']').toString();
    }

    /** Every id any accept set names — these must always be in the corpus. */
    private static Set<String> acceptedIds(JsonNode accept) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode cue : accept.get("cues")) {
            for (JsonNode a : cue.get("accept_set")) {
                ids.add(a.asText());
            }
        }
        return ids;
    }

    /** Load a deterministic SAMPLE of an {@code experience export} into the store. */
    private static int loadCorpus(H2ExperienceStore store, Path export,
                                  int rivals, Set<String> mustInclude) throws Exception {
        JsonNode root = new ObjectMapper().readTree(Files.readString(export));
        JsonNode all = root.path("data").path("entries");
        List<JsonNode> chosen = new ArrayList<>();
        List<JsonNode> others = new ArrayList<>();
        for (JsonNode e : all) {
            String id = e.path("id").asText("");
            boolean required = mustInclude.stream().anyMatch(id::startsWith);
            (required ? chosen : others).add(e);
        }
        // A deterministic spread rather than the first N, so the rivals are not
        // all drawn from one era of the store.
        int want = Math.max(1, rivals - chosen.size());
        int stride = Math.max(1, others.size() / want);
        for (int i = 0; i < others.size() && chosen.size() < rivals; i += stride) {
            chosen.add(others.get(i));
        }
        // IMPORT rather than put(): put() mints a NEW id, which would make every
        // accept-set comparison fail no matter how good retrieval was. The
        // export/import pair round-trips the original ids, and those ids are
        // what the frozen accept sets are written against.
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode e : chosen) {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = mapper.convertValue(e, Map.class);
            // The export shape carries the anchor inside `body`, while the
            // import writes the `symbol_fqn` COLUMN — and that column is what
            // the symbol cue matches on. Without this lift every anchor imports
            // as null, the symbol path finds nothing, and the gate would blame
            // retrieval for a defect in its own fixture.
            @SuppressWarnings("unchecked")
            Map<String, Object> body = row.get("body") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
            if (row.get("symbol_fqn") == null && body.get("symbol") != null) {
                row.put("symbol_fqn", String.valueOf(body.get("symbol")));
            }
            if (row.get("package_name") == null && body.get("package") != null) {
                row.put("package_name", String.valueOf(body.get("package")));
            }
            rows.add(row);
        }
        Map<String, Object> report = store.importEntries(rows);
        // The REPORT is the truth, not rows.size(): an insert that failed is a
        // smaller corpus, and a gate over a silently smaller corpus lies.
        System.out.println("[E2 GATE] import report: " + report);
        return (int) (Integer) report.getOrDefault("imported", 0);
    }
}
