package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.EntryForm;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.ExperienceMaintenance;
import org.jawata.mcp.knowledge.ExperienceRetrieval;
import org.jawata.mcp.knowledge.FormMigration;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.RecallQuery;
import org.jawata.mcp.knowledge.StoryTemplate;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 21 (v2.0): the parametric front door over the local experience/knowledge store —
 * {@code experience(kind=...)}. Stage 1 ships {@code kind=record} (write an observation);
 * later stages add {@code recall} (Stage 2 — needs the JDT service, hence the supplier)
 * and {@code load}/{@code wipe}/{@code refresh} (Stage 4). One tool keeps the surface
 * small while the store's verbs grow. Implements {@link Tool} directly (not
 * {@code AbstractTool}) because a store write does not require a loaded project.
 */
public final class ExperienceTool implements Tool {

    /**
     * The kinds this door accepts — the schema enum and the error messages.
     *
     * <p>v3.3.1: {@code train} / {@code learner_status} / {@code observe_edit} are
     * GONE, along with the {@code /train} command. Sprint 26a (D4) deleted every ML
     * model class, leaving those kinds able to answer nothing but "retired".
     * Advertising them told every agent that a capability exists which does not,
     * and keeping tombstone branches alive for a hypothetical caller who does not
     * exist is dead weight. An unknown kind now answers with the allowed list —
     * the honest response for a kind that no longer exists.</p>
     *
     * <p><b>THE INVARIANT: every kind {@link #execute} dispatches must appear
     * here.</b> This list is the only thing an agent can see — it is what
     * {@code tools/list} publishes AND what the unknown-kind error names — so a
     * verb the switch handles but this list omits is dispatchable and
     * undiscoverable, which for an MCP door is the same as absent. v3.14.0
     * shipped exactly that: {@code review_sweep} and {@code delete} both
     * executed correctly and neither was advertised, and {@code review_sweep} is
     * the ONLY reader of the usage ledger — so the ledger had one consumer and
     * no door handle. No gate caught it, because every test calls the verbs by
     * name and a name works whether or not it is published; it took a dogfood
     * run to find. {@code ExperienceToolKindsTest} pins both directions.</p>
     */
    private static final List<String> KINDS =
        List.of("record", "recall", "nominate", "decide", "primer", "list", "load",
            "reseed", "refresh", "wipe", "promote", "export", "import", "prune", "dedup",
            "compact", "stats", "fallback", "fallback_report", "migrate_form", "review",
            "review_sweep", "delete", "set_form");

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final Supplier<IJdtService> serviceSupplier;
    private final ExperienceStore store;
    private final ExperienceRetrieval retrieval;
    private final ExperienceMaintenance maintenance;
    /**
     * Sprint 28c D2: the open nominations, per tool instance.
     *
     * <p>Per instance and not static, because a nomination is a conversation
     * between one caller and one resident. A static register would let two
     * workspaces' queries share a namespace, and one caller's decide could
     * consume another's nomination — the same shared-mutable-state shape that
     * made one hook's deadline become every later caller's (#37).</p>
     */
    private final org.jawata.mcp.knowledge.ApplicabilityDecision applicability =
        new org.jawata.mcp.knowledge.ApplicabilityDecision();
    /** Sprint 27 D6: measurement (nullable — every other path is unchanged). */
    private org.jawata.mcp.knowledge.QualityLedger quality;

    /**
     * Sprint 28c D14 — shown, chosen, and asked-for-and-not-found.
     *
     * <p>Resolved per call through a supplier rather than held, for the same
     * reason the retrieval paths do it: {@code RecoveringExperienceStore} can
     * replace its delegate after a recovery, and this must follow it.</p>
     */
    private final org.jawata.mcp.knowledge.UsageLedger usage =
        new org.jawata.mcp.knowledge.UsageLedger(this::currentH2Store);

    /**
     * The concrete H2 store behind whatever wrapper is installed, or null when
     * there is none. A method reference rather than a lambda over the field: a
     * lambda in a field initializer READS {@code store} before the constructor
     * assigns it, which is a definite-assignment error, and the method reference
     * defers that read to the first call — which is what was wanted anyway,
     * since the delegate can be replaced after a recovery.
     */
    private org.jawata.mcp.knowledge.H2ExperienceStore currentH2Store() {
        var concrete = store instanceof org.jawata.mcp.knowledge.RecoveringExperienceStore r
            ? r.currentDelegate() : store;
        return concrete instanceof org.jawata.mcp.knowledge.H2ExperienceStore h2 ? h2 : null;
    }

    public ExperienceTool(Supplier<IJdtService> serviceSupplier, ExperienceStore store) {
        this(serviceSupplier, store, List::of);
    }

    /**
     * Sprint 27 D6: install measurement. The ledger is handed to the retrieval
     * this tool owns as well, so the recall surfaces count without the caller
     * having to know they exist.
     */
    public void setQualityLedger(org.jawata.mcp.knowledge.QualityLedger ledger) {
        this.quality = ledger;
        if (retrieval != null) {
            retrieval.setQualityLedger(ledger);
        }
    }

    /**
     * Sprint 28c D9 — the tool lane a declared shell-fallback is recorded in.
     *
     * <p>Installed by the application beside the quality ledger, in the same
     * block, for the same reason: a surface that is built and never handed its
     * collaborator is a surface that answers "nothing has happened yet" forever.
     * When it is absent the {@code fallback} verb SAYS SO rather than silently
     * accepting the write — a hook whose declarations vanish would leave the
     * audit trail simply stopping, which is indistinguishable from nobody
     * declaring a fallback.</p>
     */
    public void setToolExperienceStore(org.jawata.mcp.knowledge.ToolExperienceStore lane) {
        this.toolLane = lane;
    }

    private org.jawata.mcp.knowledge.ToolExperienceStore toolLane;

    /** Sprint 21a (item C): {@code defaultRoots} feed no-path {@code load} / {@code reseed}. */
    public ExperienceTool(Supplier<IJdtService> serviceSupplier, ExperienceStore store,
            Supplier<List<java.nio.file.Path>> defaultRoots) {
        this(serviceSupplier, store, defaultRoots, null);
    }

    /** Sprint 21b (item D): package-private resolver override so tests can simulate staleness. */
    ExperienceTool(Supplier<IJdtService> serviceSupplier, ExperienceStore store,
            Supplier<List<java.nio.file.Path>> defaultRoots,
            ExperienceMaintenance.PointerResolver resolverOverride) {
        this.serviceSupplier = serviceSupplier;
        this.store = store;
        this.retrieval = new ExperienceRetrieval(store, serviceSupplier);
        this.maintenance = new ExperienceMaintenance(store,
            resolverOverride != null ? resolverOverride : this::resolvesViaJdt, defaultRoots,
            serviceSupplier);
    }

    /**
     * Sprint 21b (item D): refresh is MAINTENANCE, not a user decision — run it after
     * project auto-load and after every load/import. Never throws (the startup path must
     * not die on a store/JDT hiccup); failures come back as an {@code error} report.
     */
    public Map<String, Object> autoRefresh() {
        try {
            Map<String, Object> out = new java.util.LinkedHashMap<>(maintenance.refresh());
            // Sprint 21e (item A): refresh first (stale auto-anchors get cleared), then
            // backfill (NULL-anchor entries — incl. freshly cleared ones — re-resolve
            // against the current project set).
            Map<String, Object> backfill = maintenance.backfillAutoAnchors();
            if (backfill.get("checked") instanceof Integer i && i > 0) {
                out.put("anchor_backfill", backfill);
            }
            return out;
        } catch (Exception e) {
            return Map.of("error", "auto-refresh failed: " + e.getMessage());
        }
    }

    /** Attach the automatic post-ingest refresh report to a load/reseed/import response. */
    private Map<String, Object> withRefresh(Map<String, Object> data) {
        Map<String, Object> out = new java.util.LinkedHashMap<>(data);
        out.put("refresh", autoRefresh());
        return out;
    }

    /** Bridge the JDT service to the maintenance resolver (TRUE=resolves, FALSE=stale, null=no project). */
    private Boolean resolvesViaJdt(String symbolFqn) {
        IJdtService s = serviceSupplier.get();
        if (s == null) {
            return null;
        }
        String typeName = symbolFqn.contains("#") ? symbolFqn.substring(0, symbolFqn.indexOf('#')) : symbolFqn;
        try {
            IType t = s.findType(typeName);
            return t != null && t.exists();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getName() {
        return "experience";
    }

    @Override
    public String getDescription() {
        return """
            Local experience/knowledge store — record and recall grounded lessons, domain
            facts, failure modes and hazards for THIS codebase.

            USAGE: experience(kind="record", type, summary, ...)
                   experience(kind="recall", symbol?/package?/operation?/symptom?/external_system?)

            Kinds:
            - record — store an observation as a candidate entry. Needs: type, summary.
              Optional: confidence (low|medium|high); anchor with symbol (FQN) OR
              packages[]/symbols[]; language (anchor language, default java — non-Java
              anchors are never staled by JDT maintenance); details, operation, scope_kind,
              symptoms[], links[{rel,target}], fault_owner, external_system, status,
              exceptions[].
            - recall — TERMINAL retrieval for a cue. Give any of symbol / package / operation
              / symptom / external_system. Returns exactly the fitting node(s) with pointers
              resolved to current code, OR an authoritative absence — never a similarity pile.
              Pass format="text" for flat, injection-ready lines (else structured JSON).
            - primer — the always-on DOMAIN layer: accepted domain nodes for a SessionStart
              orientation. Optional: limit (default 20), format="text".
            - load — seed the store from memory files. Optional: path (a directory of *.md or
              a single file; OMIT to seed from the configured default roots — the layered
              CLAUDE.md set + memory dirs), recursive (default true; false = don't walk
              subdirectories). Ingest FOLLOWS
              the link graph: [[wikilinks]] and relative [x](file.md) links are crawled
              transitively (cycle-safe, bounded, skips reported). Frontmatter
              type/description/symbol/language; entries are accepted/medium; idempotent per
              source (re-load replaces).
            - reseed — the explicit "initial load": wipe EVERYTHING, then load from the
              default roots (or path). REQUIRES confirm:true. Optional: path, recursive.
            - refresh — re-resolve Java symbol pointers through JDT; flag stale (superseded).
              Non-Java anchors are opaque (never staled).
            - wipe — remove everything.
            - promote — set an entry's curation status. Needs: id. Optional: status (default accepted).
            - list — curation view: browse entries by type / status / scope (symbol|package
              prefix) / language, newest first (limit, default 50). Unlike recall this SHOWS
              the set — including rejected/superseded — so candidates can be promoted.
              format="text" for flat lines.
            - export — full-fidelity dump (optionally filtered by status/type). Pass path to
              write a JSON file (backup/sharing), else entries return inline.
            - import — re-ingest exported entries (dedup by id). Pass entries[] inline or
              path to an export file.
            - prune — GC the store: delete rejected/superseded entries older than 'days'
              (default 30; 0 = all of them).
            - dedup — surface near-duplicate active entries (same summary + scope). Reports
              groups; pass confirm:true to MERGE (best survives, rest superseded).
            - compact — reclaim H2 file space after prunes/wipes. Briefly closes the store
              (concurrently attached residents reconnect); run when quiet.
            - stats — store overview: entry counts by status/language + the backing file
              location and size.

            The store is local + workspace-scoped. Record after a surprising failure, a
            discovered invariant, or a hazard the compiler cannot tell you; recall before a
            refactor or before asserting a root cause.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();

        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Which store operation: record (write) or recall (terminal retrieval).");
        props.put("kind", kind);

        // recall cues (singular; record uses packages[]/symbols[]/symptoms[]).
        props.put("package", Map.of("type", "string",
            "description", "recall: a package you are working in (cue)."));
        props.put("symptom", Map.of("type", "string",
            "description", "recall: an observed symptom / cue phrase (alias-normalized)."));

        // maintenance
        props.put("path", Map.of("type", "string",
            "description", "load/reseed: a directory of *.md memory files, or a single file."
                + " Omit to use the configured default roots."));
        props.put("recursive", Map.of("type", "boolean",
            "description", "load/reseed: walk subdirectories of directory roots (default TRUE —"
                + " the crawl finds everything; pass false to stay flat)."));
        props.put("confirm", Map.of("type", "boolean",
            "description", "reseed: REQUIRED true (wipes first). dedup: true = merge the groups."));
        props.put("days", Map.of("type", "integer",
            "description", "prune: age threshold in days for rejected/superseded (default 30)."));
        props.put("id", Map.of("type", "string", "description",
            "promote: the entry id to re-status."));
        props.put("limit", Map.of("type", "integer",
            "description", "primer: max domain nodes (default 20); list: max rows (default 50)."));
        props.put("format", Map.of("type", "string", "enum", List.of("json", "text"),
            "description", "recall/primer/list: text = flat injection-ready lines; default json."));
        props.put("scope", Map.of("type", "string",
            "description", "list: symbol/package prefix filter."));
        props.put("entries", Map.of("type", "array", "items", Map.of("type", "object"),
            "description", "import: exported entries (inline alternative to path)."));

        props.put("type", Map.of("type", "string",
            "description", "record: entry type (domain_fact / lesson / failure_mode / api_contract / naming_convention / ...)."));
        props.put("summary", Map.of("type", "string", "description",
            "record: ONE judgeable sentence of experience — what was learned, stated so a"
            + " later reader can judge whether it transfers. Not a heading, not a title"
            + " (a heading-shaped summary is refused). "
            // Sprint 28c D9: the story template reaches the AUTHOR here, derived from
            // StoryTemplate rather than re-typed — the same rule SITUATION_SHAPES
            // follows two properties down, and for the same reason: the schema
            // teaches before the mistake, the refusal teaches after it, and two
            // hand-written copies drift with nothing comparing them.
            + org.jawata.mcp.knowledge.StoryTemplate.authorGuidance()));
        props.put("confidence", Map.of("type", "string", "enum", List.of("low", "medium", "high"),
            "description", "record: default medium."));
        // Sprint 28c (D3) — the experience form. REQUIRED for lesson and
        // failure_mode, because for those the situation and the outcome ARE the
        // knowledge; optional for a domain fact, an api_contract or a naming
        // convention, which are retrieved by their anchor and did not turn out
        // any way at all.
        // The shape rules are DERIVED from EntryForm, never re-typed here. This
        // description teaches BEFORE the mistake and EntryForm's refusal teaches
        // AFTER it; two copies would drift and no test would compare them — the
        // same drift the verdict enum below was fixed for.
        props.put("situation", Map.of("type", "string", "description",
            "record: WHEN this applies. " + org.jawata.mcp.knowledge.EntryForm.SITUATION_SHAPES
            + " Never a package or a symbol: a location matches everything inside it and"
            + " distinguishes nothing. REQUIRED for lesson and failure_mode."));
        props.put("cause", Map.of("type", "string", "description",
            "record: the DIAGNOSIS — the underlying problem the solution addresses,"
            + " distinct from the symptoms that reveal it. One symptom has many causes"
            + " (a fast heartbeat: running, a heart attack, a virus) and the solution"
            + " binds to the cause; when a symptom recall returns several entries, this"
            + " field is what discriminates between them. Optional; never derived."));
        props.put("question", Map.of("type", "string", "description",
            "nominate: the question in your OWN WORDS, for the anchorless path — no symbol,"
            + " no package, no operation. Say what you are trying to do. The answer comes"
            + " back as RANKED CANDIDATES plus a query_id, never as a match: ranking is an"
            + " ordering, not a claim that anything fits."));
        props.put("query_id", Map.of("type", "string", "description",
            "decide: the id the matching nominate call returned. It is required because a"
            + " selection is only meaningful about the candidates it was offered — without"
            + " it, any entry in the store could be returned as a vouched answer."));
        props.put("selected_ids", Map.of("type", "array", "items",
            Map.of("type", "string"), "description",
            "decide: the candidate ids you judged APPLICABLE, after reading each one's"
            + " situation. An EMPTY list is a real answer and often the right one — it"
            + " records that nothing applied, which is what the store previously could not"
            + " say. Ids the nomination did not offer are refused, not ignored."));
        // The enum is DERIVED from the gate's own set, never re-typed here: a
        // second copy of a closed vocabulary drifts from the first with no test
        // noticing, which is the exact drift EntryForm's javadoc condemns about
        // AdmissionPolicy. Sorted so the served schema is stable across runs
        // (Set.of has no iteration order).
        props.put("verdict", Map.of("type", "string",
            "enum", org.jawata.mcp.knowledge.EntryForm.VERDICTS.stream().sorted().toList(),
            "description", "record: how it turned out. REQUIRED for lesson and failure_mode —"
            + " for those the outcome IS the lesson. Use 'unproven' when it genuinely has not"
            + " been settled; that is an answer, and inventing a verdict is not."));
        props.put("symbol", Map.of("type", "string",
            "description", "record: anchor FQN (mutually exclusive with packages/symbols)."));
        props.put("language", Map.of("type", "string",
            "description", "record: anchor language (default java). Non-Java anchors (rust, ts, ...)"
                + " are opaque to JDT maintenance — stored + recalled, never staled."));
        props.put("packages", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "record: scope packages."));
        props.put("symbols", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "record: scope symbols."));
        props.put("details", Map.of("type", "string", "description",
            "record: the longer story — including artifacts: paths, flags, ids and code"
            + " references live HERE, not in symptoms."));
        props.put("operation", Map.of("type", "string", "description", "record: operation this entry relates to."));
        props.put("scope_kind", Map.of("type", "string",
            "description", "record: symbol|package|operation|symptom|external_system|..."));
        props.put("symptoms", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "record: how the problem LOOKED — each item a prose observation"
                + " in words. NOT paths, flags, tags, code symbols or headings (refused"
                + " with a redirect): artifacts belong in 'details', symbols in"
                + " 'symbol'/'symbols', tool output in the tool lane, which the tools"
                + " record themselves."));
        props.put("links", Map.of("type", "array",
            "items", Map.of("type", "object"),
            // DERIVED from the gate's own set, like the verdict enum below it. The
            // literal that used to sit here named four relations, none of which any
            // writer in the codebase actually used.
            "description", "record: typed edges [{rel, target}]. Allowed: "
                + org.jawata.mcp.knowledge.EntryForm.linkVocabulary()
                + ". cured_by points at a jawata CAPABILITY rather than a code address"
                + " (find_quality_issue(kind=…), seat:refactor) and is filled ONLY when the"
                + " remedy needs no judgement — a cure WILL be run, and one that is right half"
                + " the time is worse than none. A two-phase remedy (read the error, work out"
                + " why, then decide) earns detected_by and no cure."));
        props.put("fault_owner", Map.of("type", "string", "enum", List.of("internal", "external", "shared"),
            "description", "record: who owns the fault."));
        props.put("external_system", Map.of("type", "string",
            "description", "record: the external dependency, when the fault is external."));
        props.put("status", Map.of("type", "string", "description", "record: default candidate."));
        props.put("surface", Map.of("type", "string",
            "description", "recall: which surface is asking — 'seat' when a driven seat"
                + " run recalls, omitted for an ordinary question. Affects only the"
                + " quality counters (stats), never what is retrieved."));
        props.put("exceptions", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "record: exceptions / caveats."));
        props.put(BUDGET_MILLIS, Map.of("type", "integer",
            "description", "recall/primer: the CALLER's deadline in milliseconds — state it"
                + " when your own timeout is shorter than the 15s default, so a wedged store"
                + " answers KNOWLEDGE_UNAVAILABLE inside your window instead of a transport"
                + " timeout. Clamped: a caller may ask for a faster answer, never a longer"
                + " wait."));

        schema.put("properties", props);
        schema.put("required", List.of("kind"));
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode args) {
        String kind = text(args, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "record" -> record(args);
            case "recall" -> recall(args);
            case "nominate" -> nominate(args);
            case "decide" -> decide(args);
            case "primer" -> primer(args);
            case "load" -> load(args);
            case "reseed" -> reseed(args);
            case "refresh" -> ToolResponse.success(maintenance.refresh());
            case "wipe" -> wipe();
            case "promote" -> promote(args);
            case "list" -> list(args);
            case "export" -> exportEntries(args);
            case "import" -> importEntries(args);
            case "prune" -> prune(args);
            case "migrate_form" -> migrateForm(args);
            case "dedup" -> ToolResponse.success(maintenance.dedup(bool(args, "confirm")));
            case "compact" -> ToolResponse.success(store.compact());
            case "stats" -> ToolResponse.success(stats());
            case "review" -> review(args);
            case "review_sweep" -> reviewSweep(args);
            case "delete" -> deleteByIds(args);
            case "set_form" -> setForm(args);
            case "fallback" -> recordFallback(args);
            case "fallback_report" -> fallbackReport();
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }

    /**
     * Sprint 27 D6: the store's stats plus, when measurement is installed, the
     * {@code quality} block — what the recall system has actually been doing.
     *
     * <p>The block is read-only (the 27→33 boundary) and always carries its own
     * how-to-read sentence: these are counts of what happened, never evidence of
     * what caused it.</p>
     */
    /**
     * Sprint 28c D5 — what the pattern catalogue contributed to this store, and
     * the query that reviews it.
     *
     * <p>The catalogue arrives as {@code candidate} rows, which means somebody
     * still has to look at them; a count with no way to act on it is trivia.
     * So the block carries the exact review query rather than describing one —
     * a reader can paste it.</p>
     *
     * <p>Counted by walking the rows rather than by asking the loader, because
     * the honest question is "what is IN the store", not "what did a loader
     * believe it wrote". Those differ precisely when something is wrong.</p>
     */
    private java.util.Map<String, Object> catalogueBlock() {
        java.util.Map<String, Object> block = new java.util.LinkedHashMap<>();
        int rows = 0;
        int candidates = 0;
        for (org.jawata.mcp.knowledge.StoredEntry e : store.all()) {
            String ref = e.sourceRef();
            if (ref != null && ref.startsWith(
                    org.jawata.mcp.knowledge.PatternCatalogueLoader.SOURCE_PREFIX)) {
                rows++;
                if (org.jawata.mcp.knowledge.ExperienceEntry.CANDIDATE.equals(e.status())) {
                    candidates++;
                }
            }
        }
        block.put("entries", rows);
        block.put("awaitingReview", candidates);
        block.put("reviewWith", "experience(kind=list, status=\"candidate\")");
        return block;
    }

    /**
     * Sprint 28c D11 — where a new story FILE belongs, answered by the store
     * rather than guessed by the caller.
     *
     * <p>The store is derived from a file substrate, so `/memorize` writes a
     * story file and lets a reseed carry it in; a direct record has no file
     * behind it and the next rebuild removes it. That leaves one question the
     * skill cannot answer on its own — WHERE the substrate is — and a path an
     * agent invents is the same failure as a value invented to satisfy a rule.
     *
     * <p>Nothing new is stored to answer it. The entries already carry their
     * own {@code memory:} source paths; the substrate is the deepest directory
     * that contains all of them. A store with no ingested entries has no
     * substrate, and says so rather than offering a plausible directory.</p>
     */
    private java.util.Map<String, Object> substrateBlock() {
        java.util.Map<String, Object> block = new java.util.LinkedHashMap<>();
        java.nio.file.Path common = null;
        int from = 0;
        for (org.jawata.mcp.knowledge.StoredEntry e : store.all()) {
            String ref = e.sourceRef();
            if (ref == null || !ref.startsWith("memory:")) {
                continue;
            }
            java.nio.file.Path dir = Path.of(ref.substring("memory:".length())).getParent();
            if (dir == null) {
                continue;
            }
            from++;
            common = common == null ? dir : commonPrefix(common, dir);
        }
        if (common == null) {
            block.put("root", null);
            block.put("note", "no ingested entries, so nothing here came from a file and"
                + " there is no substrate to add one to. Do NOT choose a directory:"
                + " ask, or load a substrate first.");
            return block;
        }
        block.put("root", common.toString());
        block.put("derivedFrom", from + " entries carrying a memory: source path");
        block.put("howToAdd", "write the story as a .md file under this root with a"
            + " `reviewed:` stamp it has EARNED, then experience(kind=reseed, path=<root>,"
            + " recursive=true, confirm=true). A record written straight to the store has"
            + " no file behind it and the next reseed removes it, silently, because the"
            + " count check afterwards asserts the file count and still passes.");
        addDrift(block, common);
        return block;
    }

    /**
     * THE DRIFT CHECK — substrate files the store does not hold (2026-08-27).
     *
     * <p><b>Why this is a mechanism and not another sentence.</b> Writing the
     * story file and reseeding it in are ONE JOB, and the second half lived only
     * in instruction text — the `/memorize` protocol's own final step. It was
     * skipped: four stories were authored, reviewed, stamped, committed and
     * reported as remembered while the store held none of them, and nothing
     * anywhere said otherwise. That is this project's recorded shape twice over
     * — an agent routes around friction without narrating it, so a rule that
     * depends on the agent's goodwill is not a rule — and the answer it has
     * already recorded is that the only channels that hold are the RESPONSE, a
     * hook, or a non-agent watcher. This is the response.</p>
     *
     * <p>So the store reports its own drift: files under the substrate root that
     * no row cites. The number rides every {@code stats} and every
     * {@code review_sweep}, in every client, whether or not the agent that wrote
     * the file thinks to look. An unloaded story stops being invisible.</p>
     *
     * <p>Read-only and bounded: it lists the ROOT's own markdown files, and it
     * reports what it could not read rather than treating an unreadable
     * directory as an empty one — an absence inferred from a failed lookup is
     * the lie this store exists to refuse.</p>
     */
    private void addDrift(java.util.Map<String, Object> block, java.nio.file.Path root) {
        java.util.Set<String> held = new java.util.HashSet<>();
        for (org.jawata.mcp.knowledge.StoredEntry e : store.all()) {
            String ref = e.sourceRef();
            if (ref != null && ref.startsWith("memory:")) {
                held.add(ref.substring("memory:".length()));
            }
        }
        java.util.List<String> unloaded = new java.util.ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> walk =
                java.nio.file.Files.walk(root)) {
            walk.filter(java.nio.file.Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".md"))
                .map(p -> p.toAbsolutePath().normalize().toString())
                .filter(p -> !held.contains(p))
                .forEach(unloaded::add);
        } catch (java.io.IOException e) {
            // NOT silence, and not zero: a directory we could not read is
            // unknown, and reporting it as "no drift" would be a clean verdict
            // about files nobody looked at.
            block.put("drift", "UNKNOWN — could not read the substrate root: " + e.getMessage());
            return;
        }
        java.util.Collections.sort(unloaded);
        block.put("unloadedFiles", unloaded.size());
        if (!unloaded.isEmpty()) {
            block.put("unloaded", unloaded.size() > 20
                ? unloaded.subList(0, 20) : unloaded);
            block.put("unloadedTruncated", unloaded.size() > 20);
            block.put("driftWarning", unloaded.size() + " file(s) under the substrate root"
                + " are NOT in the store — they were written and never reseeded in, so"
                + " nothing recalls them. Run experience(kind=reseed, path=<root>,"
                + " recursive=true, confirm=true) to load them.");
        }
    }

    private static java.nio.file.Path commonPrefix(java.nio.file.Path a,
            java.nio.file.Path b) {
        java.nio.file.Path out = a;
        while (out != null && !b.startsWith(out)) {
            out = out.getParent();
        }
        return out;
    }

    private java.util.Map<String, Object> stats() {
        java.util.Map<String, Object> out =
            new java.util.LinkedHashMap<>(store.stats());
        org.jawata.mcp.knowledge.QualityLedger q = quality;
        if (q != null) {
            out.put("quality", q.statsBlock());
        }
        out.put("catalogue", catalogueBlock());
        out.put("substrate", substrateBlock());
        // Sprint 27a Stage 6 (D5's first half): embedding coverage per lane,
        // live — n of total while the backfill runs, total of total after.
        // Degrades honestly: no embedder → the block says so with the reason;
        // a failed count reads "unknown", never a misleading 0.
        try {
            org.jawata.mcp.knowledge.H2ExperienceStore h2 = currentH2Store();
            if (h2 != null) {
                java.util.Map<String, Object> embedding = new java.util.LinkedHashMap<>();
                org.jawata.mcp.knowledge.EmbeddingService svc =
                    org.jawata.mcp.knowledge.EmbeddingService.shared();
                if (svc.available()) {
                    var index = new org.jawata.mcp.knowledge.EmbeddingIndex(h2, svc);
                    for (String lane : java.util.List.of("experience_entry", "tool_experience")) {
                        long embedded = index.embeddedCount(lane);
                        long total = index.totalCount(lane);
                        java.util.Map<String, Object> l = new java.util.LinkedHashMap<>();
                        l.put("embedded", embedded < 0 ? "unknown" : embedded);
                        l.put("total", total < 0 ? "unknown" : total);
                        embedding.put(lane, l);
                    }
                } else {
                    embedding.put("available", false);
                    embedding.put("reason", svc.unavailableReason());
                }
                out.put("embedding", embedding);
            }
        } catch (RuntimeException e) {
            // String.valueOf: Map.of rejects nulls, and a null-message
            // exception must not turn the safety net into the failure (C6 F2).
            out.put("embedding", java.util.Map.of("error", String.valueOf(e.getMessage())));
        }
        return out;
    }

    private ToolResponse load(JsonNode args) {
        String path = text(args, "path");
        boolean recursive = boolOr(args, "recursive", true);
        if (path == null || path.isBlank()) {
            if (!maintenance.hasDefaultRoots()) {
                return ToolResponse.invalidParameter("path", "load without 'path' needs configured"
                    + " default memory roots (-Djawata.memory.roots) — none found");
            }
            return ToolResponse.success(withRefresh(maintenance.load(null, recursive)));
        }
        return ToolResponse.success(withRefresh(maintenance.load(Path.of(path), recursive)));
    }

    /**
     * Sprint 21a (item G): the explicit "initial load" — atomic wipe → load-from-defaults
     * (or path). The wipe half is destructive, so {@code confirm:true} is REQUIRED: a
     * prompt-driven "reset my store" can never fire half-armed.
     */
    private ToolResponse reseed(JsonNode args) {
        if (!bool(args, "confirm")) {
            return ToolResponse.invalidParameter("confirm",
                "reseed WIPES the whole store before reloading — pass confirm:true to proceed");
        }
        String path = text(args, "path");
        boolean recursive = boolOr(args, "recursive", true);
        if ((path == null || path.isBlank()) && !maintenance.hasDefaultRoots()) {
            return ToolResponse.invalidParameter("path", "reseed without 'path' needs configured"
                + " default memory roots (-Djawata.memory.roots) — none found (store NOT wiped)");
        }
        // Sprint 28c (v14): what a reseed deliberately does NOT reload is
        // TOMBSTONED, so no later crawl — the studio's deploy-time auto-seed
        // above all — re-imports it. The before-set carries the EXISTING
        // tombstones forward too: a second reseed must not amnesty what the
        // first one removed (without this line, reseed #2's wipe would erase
        // reseed #1's curation and the next deploy would re-pollute).
        java.util.Set<String> before = new java.util.HashSet<>(store.fileSourceRefs());
        before.addAll(store.tombstonedRefs());
        Map<String, Object> wiped = maintenance.wipe();
        // D10: a reseed admits stamped stories only. load() does not require it —
        // loading is how notes reach the store, reseeding is how the store is
        // REBUILT, and only the second is a claim that what went in was checked.
        Map<String, Object> loaded = maintenance.load(
            path == null || path.isBlank() ? null : Path.of(path), recursive, true);
        // Revival is the same deliberate act as removal: whatever this reseed
        // re-ingested is alive by definition, so only refs it did NOT bring
        // back get (or keep) a tombstone.
        java.util.Set<String> after = store.fileSourceRefs();
        int tombstoned = 0;
        String why = "reseed excluded this source (path="
            + (path == null || path.isBlank() ? "<default roots>" : path) + ")";
        for (String ref : before) {
            if (!after.contains(ref)) {
                store.tombstone(ref, why);
                tombstoned++;
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("removed", wiped.get("removed"));
        data.putAll(loaded);
        data.put("tombstoned", tombstoned);
        return ToolResponse.success(withRefresh(data));
    }

    /**
     * Sprint 21b: wipe compacts afterwards — H2's MVStore never shrinks on deletes, and
     * "I wiped and the file is still 800k" reads as a bug (Harald, 2026-07-06). Attached
     * peer residents survive the shutdown via the store's self-healing connection.
     */
    private ToolResponse wipe() {
        Map<String, Object> data = new LinkedHashMap<>(maintenance.wipe());
        data.put("compact", store.compact());
        return ToolResponse.success(data);
    }

    private static boolean bool(JsonNode n, String field) {
        return n != null && n.has(field) && n.get(field).asBoolean(false);
    }

    /** Sprint 21b (item C): a boolean with an explicit default when the field is absent. */
    private static boolean boolOr(JsonNode n, String field, boolean absent) {
        return n != null && n.has(field) ? n.get(field).asBoolean(absent) : absent;
    }

    private ToolResponse prune(JsonNode args) {
        int days = args != null && args.has("days") && args.get("days").isInt()
            ? args.get("days").asInt() : 30;
        int removed = store.pruneAged(days);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("removed", removed);
        data.put("days", days);
        return ToolResponse.success(data);
    }

    /**
     * Sprint 28c D4 — {@code migrate_form}: give the store's legacy rows the 28c
     * form, or say why each one cannot have it.
     *
     * <p>DRY RUN BY DEFAULT, and that is the whole safety design. Without
     * {@code confirm:true} nothing is written and the full disposition is
     * returned for a human to read; the write happens only on a second call
     * from someone who has read it. There is no disposal outcome at either
     * setting — every row is {@code migrated} or {@code legacy_kept}.</p>
     *
     * <p>{@code dispositions} is capped in the response because a real store
     * holds thousands of rows and a tool response is read by an agent. The cap
     * is stated in the payload rather than applied silently: a truncated list
     * that does not say it is truncated reads as a complete one, which is this
     * project's own recorded failure. The COUNTS are always the true totals.</p>
     */
    private ToolResponse migrateForm(JsonNode args) {
        boolean confirm = bool(args, "confirm");
        FormMigration migration = new FormMigration(store);
        FormMigration.Report report = confirm ? migration.apply() : migration.plan();

        int limit = args != null && args.has("limit") && args.get("limit").isInt()
            ? args.get("limit").asInt() : 50;
        List<Map<String, Object>> shown = new ArrayList<>();
        for (FormMigration.Disposition d : report.dispositions()) {
            if (shown.size() >= limit) {
                break;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", d.id());
            row.put("outcome", d.outcome());
            if (d.situation() != null) {
                row.put("situation", d.situation());
                row.put("verdict", d.verdict());
            }
            if (d.reason() != null) {
                row.put("reason", d.reason());
            }
            shown.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applied", report.applied());
        data.put("sourceEntries", report.sourceEntries());
        data.put("migrated", report.migrated());
        data.put("legacyKept", report.legacyKept());
        data.put("keptReasons", report.keptReasons());
        data.put("provenanceKinds", report.provenanceKinds());
        data.put("dispositions", shown);
        data.put("dispositionsShown", shown.size());
        data.put("dispositionsTruncated", shown.size() < report.sourceEntries());
        if (!report.applied()) {
            data.put("note", "DRY RUN — nothing was written. Read the dispositions,"
                + " then re-run with confirm:true to apply them.");
        }
        return ToolResponse.success(data);
    }

    // --- Sprint 21a (item G): curation verbs -------------------------------------------

    /** Curation browse — recall is terminal-single; promoting needs to SEE the set. */
    private ToolResponse list(JsonNode args) {
        int limit = args != null && args.has("limit") && args.get("limit").isInt()
            ? args.get("limit").asInt() : 50;
        List<org.jawata.mcp.knowledge.StoredEntry> rows = store.listEntries(
            text(args, "type"), text(args, "status"), text(args, "scope"),
            text(args, "language"), limit);
        if ("text".equalsIgnoreCase(text(args, "format"))) {
            return ToolResponse.success(ExperienceRetrieval.renderList(rows));
        }
        List<Map<String, Object>> entries = new ArrayList<>();
        for (org.jawata.mcp.knowledge.StoredEntry e : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("type", e.type());
            m.put("status", e.status());
            if (e.language() != null) {
                m.put("language", e.language());
            }
            if (e.symbolFqn() != null) {
                m.put("symbol", e.symbolFqn());
            }
            m.put("summary", e.summary());
            if (e.createdAt() != null) {
                m.put("created_at", e.createdAt().toString());
            }
            entries.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", entries.size());
        data.put("entries", entries);
        return ToolResponse.success(data);
    }

    /**
     * Sprint 22a R.2 — the bottom-up half of upstream parity: tally every
     * {@code jawata-fallback slip:} declaration the observer recorded (a real-use
     * "jawata couldn't do X" signal) by reason, ranked by frequency → a
     * capability-gap backlog grounded in actual usage.
     */
    /**
     * Sprint 28c D9 — a declared shell-fallback, recorded in the TOOL lane.
     *
     * <p>Until now the hook wrote one {@code failure_mode} ENTRY per slip, into
     * the knowledge lane. There are thousands of them, each saying which tool was
     * reached for and why, and every one of them competes for the eight slots an
     * answer has while telling a reader nothing they could act on. Harald read his
     * own store back and found them crowding real answers.</p>
     *
     * <p>They keep every bit of their value: the capability-gap tally is what
     * {@link #fallbackReport()} is for, and it reads them here. What changes is
     * the lane — {@code ToolExperience.OUTCOME_FALLBACK} has existed since Sprint
     * 26a for exactly this, with its own javadoc noting that no code path wrote
     * it. This is that path.</p>
     *
     * @return the recorded row, or a REFUSAL naming the missing lane — never a
     *     quiet success, because a hook whose declarations vanish leaves an audit
     *     trail that simply stops, and a stopped trail looks exactly like nobody
     *     declaring a fallback
     */
    /**
     * Sprint 28c — the cold-reader step, given a production caller at last.
     *
     * <p>Hand it a DRAFT entry and it returns two things: what the deterministic
     * gate says, and the brief to give a zero-context agent. The caller spawns that
     * agent, reads its verdict, and compares it with its own. Two agreements store
     * or drop the entry silently; a DISAGREEMENT is the only case that reaches the
     * human, which is what makes a human gate affordable at all.</p>
     *
     * <p><b>It reviews a draft, not a stored row, and that is the point.</b> The
     * expensive correction is the one that arrives after an entry is written and
     * filed — twenty of those in one day, almost all of them saying the entry told
     * the wrong story. Reviewing before the write is where the cost is small.</p>
     *
     * <p>The gate's verdict travels BESIDE the prompt rather than inside it. A
     * reader told the gate refused something grades the refusal; a reader given only
     * the entry grades the entry.</p>
     */
    private ToolResponse review(JsonNode args) {
        String summary = text(args, "summary");
        if (summary == null || summary.isBlank()) {
            return ToolResponse.invalidParameter("summary",
                "review needs the candidate's summary — there is nothing to judge without it");
        }
        String type = text(args, "type");
        String situation = text(args, "situation");
        String verdict = text(args, "verdict");
        String details = text(args, "details");
        List<String> symptoms = strings(args, "symptoms");

        Map<String, Object> data = new LinkedHashMap<>();
        Optional<EntryForm.Refusal> refusal =
            EntryForm.check(type, summary, symptoms, situation, verdict);
        data.put("formGate", refusal.isPresent() ? "REFUSED" : "admitted");
        refusal.ifPresent(r -> {
            data.put("refusedField", r.field());
            data.put("refusal", r.message());
        });
        data.put("prompt", StoryTemplate.reviewPrompt(
            type, summary, situation, verdict, details, symptoms));
        data.put("howToUse",
            "Give `prompt` to an agent with NO session context and nothing else."
            + " Read its VERDICT line and compare it with your own."
            + " Agree keep -> record it. Agree drop -> drop it and say so in one line."
            + " DISAGREE -> ask the human, showing the one-line story, both verdicts,"
            + " and nothing more. That disagreement is the only question they owe.");
        data.put("limit",
            "The reader CANNOT check a fact. An entry can be fluent, correctly scoped"
            + " and false, and it will pass. Provenance on the why is what bounds that,"
            + " not this step.");
        return ToolResponse.success(data);
    }

    /**
     * Sprint 28c Stage 15 — the repair verb: rewrite an entry's situation (and,
     * for an experience, its outcome), through the SAME gate {@code record}
     * runs.
     *
     * <p>Until this verb, the store could diagnose a badly-formed entry and not
     * fix one: {@code ExperienceStore#setForm} had three references and none was
     * a tool verb, so an agent could record a new entry and delete an old one
     * but never improve one in place. The mechanical migration is not a
     * substitute — on the real corpus it derived situations reading "when by
     * construction" and "when $8 on one day", because deciding what an entry
     * actually applies to is reading work.</p>
     *
     * <p>Two refusals are this verb's own, not corrections:</p>
     * <ul>
     *   <li><b>The form gate runs here too.</b> One rule, every write surface —
     *       without it this door could store a heading-shaped situation that
     *       {@code record} would refuse, which is how the store filled with
     *       headings the first time.</li>
     *   <li><b>A verdict on a non-experience type is REFUSED, not ignored.</b>
     *       {@code unproven} was invented once so 187 catalogue entries could
     *       pay a debt they did not owe; this refusal is that lesson standing
     *       at a second door.</li>
     * </ul>
     */
    private ToolResponse setForm(JsonNode args) {
        String id = text(args, "id");
        if (id == null || id.isBlank()) {
            return ToolResponse.invalidParameter("id", "which entry to rewrite");
        }
        String situation = text(args, "situation");
        if (situation == null || situation.isBlank()) {
            return ToolResponse.invalidParameter("situation",
                "the corrected condition — this verb exists to give an entry a real"
                + " situation, so calling it without one asks for nothing");
        }
        List<org.jawata.mcp.knowledge.StoredEntry> rows = store.byIds(List.of(id));
        if (rows.isEmpty()) {
            // A refusal, never a silent no-op: "I rewrote it" and "nothing has
            // that id" are different answers, and only the second tells the
            // caller their finding list is stale.
            return ToolResponse.invalidParameter("id",
                "no entry has id '" + id + "'. Nothing was written. If this id came"
                + " from a review_sweep or quality finding, the store has changed"
                + " since — re-run the sweep rather than retrying the id.");
        }
        org.jawata.mcp.knowledge.StoredEntry entry = rows.get(0);
        String type = entry.type();
        boolean experience = type != null
            && org.jawata.mcp.knowledge.EntryForm.EXPERIENCE_TYPES
                .contains(type.toLowerCase(java.util.Locale.ROOT));

        String verdictArg = text(args, "verdict");
        if (!experience && verdictArg != null && !verdictArg.isBlank()) {
            return ToolResponse.invalidParameter("verdict",
                "this entry is a '" + type + "', and a " + type + " never turned out"
                + " any way at all. RULE: an outcome belongs to an experience"
                + " (lesson, failure_mode); attaching one to a fact is the invented"
                + " value this store already refused once. REPHRASE: drop the"
                + " verdict, or — if this row genuinely records an experience —"
                + " say so and it can be retyped, which is a different change.");
        }
        // For an experience, an unsupplied verdict keeps the one the row has:
        // fixing the situation must not force the caller to re-state an outcome
        // that was already right.
        String verdict = !experience ? null
            : verdictArg != null && !verdictArg.isBlank() ? verdictArg
            : entry.facets() == null ? null : entry.facets().verdict();

        var admission = org.jawata.mcp.knowledge.EntryForm.check(
            type, entry.summary(), null, situation, verdict);
        if (admission.isPresent()) {
            return ToolResponse.invalidParameter(
                admission.get().field(), admission.get().message());
        }

        if (!store.rewriteForm(id, situation, verdict)) {
            return ToolResponse.invalidParameter("id",
                "no entry has id '" + id + "' any more — it was removed between the"
                + " lookup and the write. Nothing was written.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("type", type);
        data.put("situation", situation);
        data.put("verdict", verdict);
        data.put("provenance_kind", "seat_rewritten");
        data.put("note", "the row's meaning-lane vectors were cleared so the backfill"
            + " re-embeds the NEW situation; until it does, this entry ranks on words"
            + " alone and the nomination's meaning_coverage will say so.");
        return ToolResponse.success(data);
    }

    private ToolResponse recordFallback(JsonNode args) {
        String tool = text(args, "tool");
        String reason = text(args, "reason");
        if (tool == null || tool.isBlank()) {
            return ToolResponse.invalidParameter("tool",
                "which tool was reached for instead of jawata");
        }
        if (reason == null || reason.isBlank()) {
            return ToolResponse.invalidParameter("reason",
                "the declared reason — a slip with no reason records that something"
                + " happened and nothing about what, which is a hole in the audit trail"
                + " rather than a capability gap");
        }
        if (toolLane == null) {
            return ToolResponse.error("TOOL_LANE_UNAVAILABLE",
                "the tool lane is not installed on this resident, so this fallback"
                + " CANNOT be recorded. It is not being dropped quietly: nothing was"
                + " written, and the caller is being told.",
                "start a resident that wires the tool-experience store; a fallback"
                + " declaration is an audit record and losing it silently would make"
                + " the trail simply stop");
        }
        toolLane.append(new org.jawata.mcp.learn.ToolExperience(
            text(args, "session"), reason.strip(), tool.strip(),
            org.jawata.mcp.learn.ToolExperience.OUTCOME_FALLBACK, null));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recorded", "tool_experience");
        data.put("tool", tool.strip());
        data.put("outcome", org.jawata.mcp.learn.ToolExperience.OUTCOME_FALLBACK);
        data.put("note", "recorded in the TOOL lane, not the knowledge lane —"
            + " read it back with kind=fallback_report");
        return ToolResponse.success(data);
    }

    private ToolResponse fallbackReport() {
        List<org.jawata.mcp.knowledge.StoredEntry> rows =
            store.listEntries("failure_mode", null, null, null, 10000);
        String prefix = "jawata-fallback slip:";
        Map<String, Integer> tally = new LinkedHashMap<>();
        int total = 0;
        int rejected = 0;
        int unexplained = 0;

        for (org.jawata.mcp.knowledge.StoredEntry e : rows) {
            String s = e.summary();
            if (s == null || !s.startsWith(prefix)) {
                continue;
            }

            // A REJECTED entry is one somebody has already judged to be junk. Counting it as
            // a capability gap re-admits, through the back door, exactly what was thrown out.
            if ("rejected".equalsIgnoreCase(String.valueOf(e.status()))) {
                rejected++;
                continue;
            }

            String reason = s.substring(prefix.length()).strip();
            // Strip the leading tool name ("Bash: …") to see whether a REASON was actually
            // given. A row that says only which tool was used records that something happened
            // and nothing about what — it is not a gap, it is a hole in the audit trail.
            String withoutTool = reason.replaceFirst("^[A-Za-z_]+:\\s*", "").strip();
            if (withoutTool.isEmpty()) {
                unexplained++;
                continue;
            }

            tally.merge(reason, 1, Integer::sum);
            total++;
        }

        // Sprint 28c D9: the TOOL lane, which is where slips are written from now
        // on. Both sources are read, and that is a transition rather than a
        // permanent double life — the knowledge-lane rows above are the historical
        // backlog, and they disappear at the reseed. Reading only the new lane
        // today would make the report say the capability gaps had vanished on the
        // day the writer moved; reading only the old one would make it stop
        // growing. Neither is true, so it reads both and says which is which.
        int fromToolLane = 0;
        if (toolLane != null) {
            for (org.jawata.mcp.learn.ToolExperience t
                    : toolLane.recentMatching(null, 10000)) {
                if (!org.jawata.mcp.learn.ToolExperience.OUTCOME_FALLBACK.equals(t.outcome())) {
                    continue;
                }
                String reason = (t.tool() == null ? "" : t.tool() + ": ")
                    + (t.situation() == null ? "" : t.situation().strip());
                if (t.situation() == null || t.situation().isBlank()) {
                    unexplained++;
                    continue;
                }
                tally.merge(reason, 1, Integer::sum);
                total++;
                fromToolLane++;
            }
        }

        List<Map<String, Object>> gaps = new ArrayList<>();
        tally.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
            .forEach(en -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("reason", en.getKey());
                m.put("count", en.getValue());
                gaps.add(m);
            });

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalFallbacks", total);
        data.put("distinctGaps", gaps.size());
        data.put("gaps", gaps);
        // Which lane each half came from, so a reader can tell a migration from a
        // collapse. After the reseed the knowledge-lane count goes to zero and the
        // tool-lane count carries everything — that is the transition completing,
        // not the signal dying, and only these two numbers distinguish them.
        data.put("fromToolLane", fromToolLane);
        data.put("fromLegacyEntries", total - fromToolLane);
        if (toolLane == null) {
            data.put("toolLaneNote", "the tool lane is NOT installed on this resident,"
                + " so this report covers the historical knowledge-lane rows only —"
                + " any fallback declared since the writer moved is not counted here");
        }

        // Say what was left OUT and why — a filtered list that does not admit to filtering is
        // the same lie in a smaller frame.
        if (rejected > 0) {
            data.put("excludedRejected", rejected);
        }
        if (unexplained > 0) {
            data.put("excludedUnexplained", unexplained);
            data.put("unexplainedNote", unexplained + " recorded fallback(s) carry NO reason — "
                + "the declaration was made but its text was lost (an old extractor bug). They "
                + "are excluded from the gap list because they say nothing, and they are "
                + "reported here so their absence is not itself a silence. New ones cannot "
                + "occur: an unexplained declaration is now refused outright.");
        }
        return ToolResponse.success(data);
    }

    private ToolResponse exportEntries(JsonNode args) {
        List<Map<String, Object>> entries =
            store.exportEntries(text(args, "status"), text(args, "type"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", entries.size());
        String path = text(args, "path");
        if (path != null && !path.isBlank()) {
            try {
                writeArchive(entries, Path.of(path));
                data.put("path", path);
                data.put("written", true);
            } catch (Exception e) {
                return ToolResponse.invalidParameter("path",
                    "cannot write export file: " + e.getMessage());
            }
        } else {
            data.put("entries", entries);
        }
        return ToolResponse.success(data);
    }

    @SuppressWarnings("unchecked")
    private ToolResponse importEntries(JsonNode args) {
        List<Map<String, Object>> entries;
        if (args != null && args.has("entries") && args.get("entries").isArray()) {
            entries = (List<Map<String, Object>>) JSON.convertValue(args.get("entries"), List.class);
        } else {
            String path = text(args, "path");
            if (path == null || path.isBlank()) {
                return ToolResponse.invalidParameter("entries",
                    "import needs 'entries' (inline array) or 'path' (an export file)");
            }
            try {
                JsonNode root = JSON.readTree(java.nio.file.Files.readString(Path.of(path)));
                JsonNode arr = root.isArray() ? root : root.path("entries");
                if (!arr.isArray()) {
                    return ToolResponse.invalidParameter("path",
                        "file has no 'entries' array: " + path);
                }
                entries = (List<Map<String, Object>>) JSON.convertValue(arr, List.class);
            } catch (Exception e) {
                return ToolResponse.invalidParameter("path",
                    "cannot read export file: " + e.getMessage());
            }
        }
        return ToolResponse.success(withRefresh(store.importEntries(entries)));
    }

    private ToolResponse promote(JsonNode args) {
        String id = text(args, "id");
        if (id == null || id.isBlank()) {
            return ToolResponse.invalidParameter("id", "promote requires an entry 'id'");
        }
        String status = text(args, "status");
        String target = status == null || status.isBlank() ? ExperienceEntry.ACCEPTED : status;
        boolean changed = store.setStatus(id, target);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", target);
        data.put("changed", changed);
        return ToolResponse.success(data);
    }

    /**
     * Sprint 21e (item B): the classify contract (Recall-Gap §5.5) — a recall MATCH
     * converts the agent's task from GENERATE a cause (fluency wins) to CLASSIFY
     * against the returned closed set (grounding wins). Carried in {@code meta.steering}
     * ONLY: the envelope layer fills steering just when absent, so this per-response
     * line wins over the generic per-tool steering with a single producer; absence
     * responses keep the generic line. The {@code format=text} tail never renders meta
     * — the hooks' peel is byte-identical.
     */
    static final String CLASSIFY_STEERING =
        "Match the observation to ONE of these with evidence, or declare it genuinely new"
        + " — do not generate a novel cause.";

    /**
     * #37: the caller's own deadline, in milliseconds.
     *
     * <p>A deadline is only useful to a caller that will still be waiting when it fires.
     * The hook's HTTP call gives up at 1500 ms, so the engine's 15-second default is out
     * of its reach entirely: a wedged store produced an anonymous transport timeout
     * there, exactly as before the fix. A caller that knows its own budget states it and
     * gets the typed answer inside its own window.</p>
     *
     * <p>Read into a LOCAL and passed — never stored. The first shape of this wrote the
     * value onto the shared retrieval instance, and the first hook request's 1200 ms
     * silently became every later caller's budget, including the studio canary's in
     * another process. The clamp (faster yes, longer no) lives in
     * {@code ExperienceRetrieval.clampBudget}.</p>
     */
    static final String BUDGET_MILLIS = "budget_ms";

    private static long budgetIn(JsonNode args) {
        if (args != null && args.hasNonNull(BUDGET_MILLIS) && args.get(BUDGET_MILLIS).isNumber()) {
            return args.get(BUDGET_MILLIS).asLong();
        }
        return ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS;
    }

    private ToolResponse recall(JsonNode args) {
        RecallQuery q = new RecallQuery(
            text(args, "symbol"),
            text(args, "package"),
            text(args, "operation"),
            text(args, "symptom"),
            text(args, "external_system"));
        // Sprint 27 D6: the caller may name the surface it is asking from
        // ("seat" for a seat run's recall). Absent = the ordinary question hook.
        // Retrieval is identical either way; only the counter differs.
        String surface = text(args, "surface");
        String counted = surface == null || surface.isBlank()
            ? org.jawata.mcp.knowledge.QualityLedger.SURFACE_QUESTION_HOOK : surface;
        // Sprint 28c D8: a prompt yields SEVERAL cues, and every one of them must
        // reach the store. The singular fields stay exactly as they were — one cue
        // is still one query — and `symbols`/`symptoms` add the rest, asked in the
        // caller's own precedence order and unioned. The hook used to make one
        // round trip per cue and STOP at the first that answered, so four cues'
        // knowledge was never fetched.
        List<String> moreSymbols = strings(args, "symbols");
        List<String> moreSymptoms = strings(args, "symptoms");
        Map<String, Object> result;
        if (moreSymbols.isEmpty() && moreSymptoms.isEmpty()) {
            result = retrieval.recall(q, counted, budgetIn(args));
        } else {
            List<RecallQuery> cues = new java.util.ArrayList<>();
            if (!q.isEmpty()) {
                cues.add(q);
            }
            String pkg = text(args, "package");
            String op = text(args, "operation");
            String ext = text(args, "external_system");
            for (String s : moreSymbols) {
                cues.add(new RecallQuery(s, pkg, op, null, ext));
            }
            for (String s : moreSymptoms) {
                cues.add(new RecallQuery(null, pkg, op, s, ext));
            }
            result = retrieval.recallAll(cues, counted, budgetIn(args), text(args, "session"));
        }
        ToolResponse response = respond(args, result);
        if (ExperienceRetrieval.RESULT_MATCH.equals(result.get("result"))) {
            response.applySteering(CLASSIFY_STEERING);
        }
        return response;
    }

    /**
     * Sprint 28c D2, half one: rank candidates for a question that carries NO code
     * anchor, and say plainly that ranking is not an answer.
     *
     * <p>The response never claims a match. It returns a {@code query_id} and the
     * ordered candidates, each with the two things a caller needs in order to judge
     * it — the situation it applies under and how it turned out. Deciding is a
     * separate call, because the measured failure was that a pile of near-neighbours
     * rendered into a session IS an answer to the agent reading it, whatever label
     * sits above it.</p>
     */
    private ToolResponse nominate(JsonNode args) {
        String question = text(args, "question");
        if (question == null || question.isBlank()) {
            return ToolResponse.invalidParameter("question",
                "nominate needs the question in your own words. It is the anchorless "
                + "path: no symbol, no package, no operation — say what you are trying "
                + "to do and the store ranks what might apply.");
        }
        Map<String, Object> result = retrieval.nominate(question, budgetIn(args));
        if (ExperienceRetrieval.RESULT_UNAVAILABLE.equals(result.get("result"))) {
            return respond(args, result);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> candidates =
            (List<Map<String, Object>>) result.getOrDefault("candidates", List.of());
        List<String> ids = new java.util.ArrayList<>();
        for (Map<String, Object> c : candidates) {
            ids.add(String.valueOf(c.get("id")));
        }
        String queryId = applicability.nominate(question, ids);
        result.put("query_id", queryId);
        // D14: the demand record opens HERE, including when ids is empty — a
        // question with no candidates is demand without supply, and that row is
        // the writing backlog. Skipping it because there is nothing to count
        // would delete the one signal that says what to write next.
        usage.nominated(queryId, "question", question, ids);
        return respond(args, result);
    }

    /**
     * Sprint 28c D2, half two: the caller's judgement turns a selection into an
     * answer, or into an honest absence.
     *
     * <p>A refusal is an ERROR rather than a success carrying no entries. The two
     * are different claims — "you cannot decide on that" versus "nothing applied" —
     * and a success body cannot carry the first, because every consumer treats a
     * success as an answer.</p>
     */
    private ToolResponse decide(JsonNode args) {
        String queryId = text(args, "query_id");
        // An ABSENT selection means "I chose none", which is a real and useful
        // answer here. A selection that is PRESENT and not an array must not
        // collapse into that same answer: strings() returns empty for anything
        // it cannot read, so a caller who sent one id as a bare string would have
        // their choice recorded as an absence — the store then holds "nothing
        // applied" about a question somebody answered, and it looks exactly like
        // an honest absence. Refuse instead.
        if (args != null && args.has("selected_ids") && !args.get("selected_ids").isNull()
                && !args.get("selected_ids").isArray()) {
            return ToolResponse.invalidParameter("selected_ids",
                "selected_ids must be an ARRAY of entry ids, even for one id."
                    + " It was present but not an array, and reading it as an empty"
                    + " selection would record 'nothing applied' about a question you"
                    + " just answered — indistinguishable, afterwards, from an honest"
                    + " absence. Send [\"<id>\"], or omit it entirely to choose none.");
        }
        List<String> selected = strings(args, "selected_ids");
        Object outcome = applicability.decide(queryId, selected);

        if (outcome instanceof org.jawata.mcp.knowledge.ApplicabilityDecision.Refusal refusal) {
            return ToolResponse.invalidParameter("query_id", refusal.reason());
        }
        var decision = (org.jawata.mcp.knowledge.ApplicabilityDecision.Decision) outcome;
        // Choosing NONE closes the demand row unanswered, deliberately: from the
        // backlog's point of view an honest absence and an unanswered question are
        // the same fact — the store was asked and had nothing that applied.
        usage.decided(queryId, decision.selected());
        return respond(args, retrieval.answerFor(decision));
    }

    /**
     * Sprint 28c D14 — the review sweep's two lists, for a human to rule on.
     *
     * <p>Neither list deletes anything, and that is the design rather than
     * caution: deletion is by evidence PLUS the user's own eyes. The seat that
     * consumes this presents both lists with their counts and removes only what
     * the user names.</p>
     *
     * <p>The lists answer different questions and must not be merged. The
     * DELETION LIST is about entries the store keeps offering and nobody keeps:
     * shown often, chosen never. The WRITING BACKLOG is about entries that do
     * not exist — questions asked repeatedly that nothing answered. One says
     * what to remove, the other says what to write, and only the second can be
     * acted on by writing.</p>
     *
     * <p>A read here THROWS rather than returning empty lists, because "nothing
     * has been shown yet" and "the ledger could not be read" are opposite
     * answers, and rendering the second as the first tells a human their store
     * is healthy when the instrument is broken.</p>
     */
    private ToolResponse reviewSweep(JsonNode args) {
        int minShown;
        int minTimes;
        int limit;
        try {
            minShown = countArg(args, "min_shown", 3);
            minTimes = countArg(args, "min_times", 2);
            limit = countArg(args, "limit", 25);
        } catch (IllegalArgumentException bad) {
            return ToolResponse.invalidParameter(bad.getMessage(),
                "This is a count. It was present but not a number, so the sweep would have"
                    + " silently used its default and reported lists you did not ask for —"
                    + " which reads as evidence about your store rather than as a rejected"
                    + " argument.");
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("deletionList", usage.deletionList(minShown, limit));
        out.put("writingBacklog", usage.writingBacklog(minTimes, limit));
        out.put("droppedWrites", usage.failedWrites());
        // Stage 15 — the QUALITY lane, beside the usage lane. One command, two
        // questions: what does nobody use, and what is badly written. The counts
        // are migrate_form's own dry-run counts by construction (StoreQuality
        // runs that plan and re-projects it), so this response and the dry run
        // can never disagree about the same corpus.
        org.jawata.mcp.knowledge.StoreQuality.Report quality =
            org.jawata.mcp.knowledge.StoreQuality.scan(store, limit);
        java.util.Map<String, Object> q = new java.util.LinkedHashMap<>();
        q.put("entries", quality.entries());
        q.put("mechanicallyMigratable", quality.mechanicallyMigratable());
        q.put("defects", quality.defects());
        q.put("findings", quality.findings());
        q.put("findingsTotal", quality.findingsTotal());
        q.put("findingsTruncated", quality.findingsTruncated());
        out.put("quality", q);
        out.put("howToRead", "Neither list deletes anything. deletionList: shown at least "
            + minShown + " times and chosen never — offer these to the user and remove only"
            + " what they name. writingBacklog: asked at least " + minTimes + " times with"
            + " nothing chosen — this is demand with no supply, and the only item here that"
            + " is acted on by WRITING. droppedWrites is how many ledger writes were lost:"
            + " a low engagement rate over lost rows means 'we failed to record it', not"
            + " 'nobody engaged'. quality: entries whose form cannot be derived"
            + " mechanically — READ each, judge what it actually applies to, and repair"
            + " with kind=set_form (proposing to the human first). A finding with a"
            + " source_ref is durably fixed in THAT FILE and reseeded — a store write"
            + " there is erased by the next reseed; a null source_ref means no file"
            + " exists and set_form IS the durable fix. findingsTruncated=true means"
            + " the list is capped at " + limit + " while the counts cover everything.");
        return ToolResponse.success(out);
    }

    /**
     * A count argument, defaulted when ABSENT and refused when present and
     * unreadable.
     *
     * <p>The usual {@code has(x) && isInt()} idiom collapses those two cases: a
     * caller who sends {@code "1"} as a JSON string gets the default and no
     * indication that their argument was ignored. That is the shape where a
     * guard which substitutes a default for a MISSING field also swallows a
     * field that is present and impossible — and here it would hand back lists
     * computed at thresholds nobody chose, which read as facts about the store.
     * A numeric string is accepted, because clients differ on that; anything
     * else is refused by name.</p>
     */
    private static int countArg(JsonNode args, String name, int fallback) {
        if (args == null || !args.has(name) || args.get(name).isNull()) {
            return fallback;
        }
        JsonNode n = args.get(name);
        if (n.isInt() || n.isLong()) {
            return n.asInt();
        }
        if (n.isTextual()) {
            try {
                return Integer.parseInt(n.asText().trim());
            } catch (NumberFormatException nfe) {
                throw new IllegalArgumentException(name);
            }
        }
        throw new IllegalArgumentException(name);
    }

    /**
     * Sprint 28c D14 — remove exactly the entries a human named, after writing
     * them somewhere they can come back from.
     *
     * <p><b>The archive is written FIRST, and a failure to write it cancels the
     * delete.</b> The review seat runs on every client, and D12's cutover
     * archive exists only on the one machine that ran the reseed — so a delete
     * that leaned on it would be irreversible everywhere else. Exporting after
     * the delete is not an option: the rows are gone by then.</p>
     *
     * <p>Ids that are not in the store are reported rather than treated as
     * success. "I removed what you named" and "some of what you named was
     * already absent" are different answers, and only the second tells the user
     * their list was stale.</p>
     *
     * <p>{@code prune} is untouched by this. It remains the blunt instrument —
     * a threshold sweep with no delete-by-id, which once removed 101 entries for
     * an asked seven.</p>
     */
    private ToolResponse deleteByIds(JsonNode args) {
        if (args == null || !args.has("ids") || !args.get("ids").isArray()
                || args.get("ids").isEmpty()) {
            return ToolResponse.invalidParameter("ids",
                "delete removes exactly the entries you name: pass ids as a non-empty"
                    + " ARRAY. There is deliberately no filter form — a filter is how a"
                    + " deletion of seven becomes a deletion of a hundred and one.");
        }
        List<String> ids = strings(args, "ids");
        org.jawata.mcp.knowledge.H2ExperienceStore h2 = currentH2Store();
        if (h2 == null) {
            return ToolResponse.error(KNOWLEDGE_UNAVAILABLE,
                "delete needs the H2 store and this resident has none",
                "Nothing was deleted. Check experience(kind=stats).");
        }
        List<java.util.Map<String, Object>> archived = h2.exportByIds(ids);
        List<String> missing = new java.util.ArrayList<>(ids);
        for (java.util.Map<String, Object> row : archived) {
            missing.remove(String.valueOf(row.get("id")));
        }
        java.nio.file.Path archive;
        try {
            archive = deletionArchivePath(h2, archived.size());
            writeArchive(archived, archive);
        } catch (Exception e) {
            return ToolResponse.error("DELETE_ARCHIVE_FAILED",
                "the pre-delete archive could not be written: " + e,
                "NOTHING was deleted. The archive is the undo this delete owes, and a"
                    + " delete without one is irreversible on every client but the one"
                    + " that ran the cutover.");
        }
        int removed = h2.deleteByIds(ids);
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("asked", ids.size());
        out.put("removed", removed);
        out.put("archive", archive.toString());
        if (!missing.isEmpty()) {
            out.put("alreadyAbsent", missing);
            out.put("note", "these ids were not in the store, so they are not in the archive"
                + " either — your list was stale, which is worth knowing before you act on"
                + " the rest of it.");
        }
        return ToolResponse.success(out);
    }

    /**
     * The one place an export file is written. Both the export verb and the
     * pre-delete archive come through here: the delete's archive IS an export,
     * and a second writer would drift from the first exactly where it matters
     * least visibly — in the file nobody opens until they need it.
     */
    private static void writeArchive(List<Map<String, Object>> entries, Path target)
            throws java.io.IOException {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("count", entries.size());
        doc.put("entries", entries);
        java.nio.file.Path parent = target.getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        try {
            java.nio.file.Files.writeString(target,
                JSON.writerWithDefaultPrettyPrinter().writeValueAsString(doc));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new java.io.IOException("could not serialise the export", e);
        }
    }

    /**
     * Where a pre-delete archive goes: beside the store, under {@code deleted/},
     * named by the instant and the count.
     *
     * <p>Beside the store because the user must be able to find it without being
     * told, and because a delete happens on any client while D12's cutover
     * archive exists only on the machine that ran the reseed. An in-memory store
     * has no such directory; the archive then goes to the system temp directory
     * and the response says where, rather than the call failing over a location.</p>
     */
    private static Path deletionArchivePath(
            org.jawata.mcp.knowledge.H2ExperienceStore h2, int count) {
        Path dir = h2.storeDir();
        if (dir == null) {
            dir = Path.of(System.getProperty("java.io.tmpdir"), "jawata-deleted");
        } else {
            dir = dir.resolve("deleted");
        }
        String stamp = java.time.Instant.now().toString().replace(':', '-');
        return dir.resolve("deleted-" + stamp + "-" + count + ".json");
    }

    private ToolResponse primer(JsonNode args) {
        int limit = args != null && args.has("limit") && args.get("limit").isInt()
            ? args.get("limit").asInt() : 20;
        return respond(args, retrieval.primer(limit, budgetIn(args)));
    }

    /**
     * Structured JSON by default; {@code format=text} returns flat injection-ready lines.
     *
     * <p>#37: a retrieval that could not be performed leaves here as a typed ERROR in
     * BOTH formats, never as a successful answer carrying no entries. The distinction
     * the consumer needs is "the store said nothing" versus "the store could not
     * answer", and a success body cannot carry the second one — the hook's own parser
     * treats every success as an answer and only an error as a refusal.</p>
     */
    private ToolResponse respond(JsonNode args, Map<String, Object> result) {
        if (ExperienceRetrieval.RESULT_UNAVAILABLE.equals(result.get("result"))) {
            Object reason = result.get("reason");
            return ToolResponse.error(KNOWLEDGE_UNAVAILABLE,
                "Knowledge layer unavailable: "
                    + (reason == null ? "reason not recorded" : reason),
                "This is NOT an absence — nothing has been established about the cue one"
                    + " way or the other. Proceed without recall and SAY that recall was"
                    + " unavailable; do not record a conclusion as if the store had been"
                    + " consulted. Check the resident's store health (experience"
                    + " kind=stats) if it persists.");
        }
        if ("text".equalsIgnoreCase(text(args, "format"))) {
            return ToolResponse.success(ExperienceRetrieval.renderText(result));
        }
        return ToolResponse.success(result);
    }

    /**
     * #37: the error code for "the knowledge layer could not answer".
     *
     * <p>Its own code rather than {@code INTERNAL_ERROR}, because the two mean different
     * things to whoever reads them: an internal error says jawata is broken and the call
     * should be reported, while this says the knowledge layer is temporarily unreadable
     * and the caller should carry on WITHOUT recall and disclose that it did.</p>
     */
    public static final String KNOWLEDGE_UNAVAILABLE = "KNOWLEDGE_UNAVAILABLE";

    private ToolResponse record(JsonNode args) {
        String type = text(args, "type");
        String summary = text(args, "summary");
        if (type == null || type.isBlank()) {
            return ToolResponse.invalidParameter("type", "record requires a 'type'");
        }
        if (summary == null || summary.isBlank()) {
            return ToolResponse.invalidParameter("summary", "record requires a 'summary'");
        }
        // Sprint 27a D10 — admission ROUTING: a record shaped like the wrong
        // KIND (tool artifacts standing as experience prose) is refused with a
        // teaching redirect, at the one call every agent uses. New writes only;
        // importEntries/restore round-trip untouched. Patterns: AdmissionPolicy
        // (derived from the observed misplaced content — dossier-27a).
        //
        // Sprint 28c (D3) — the FORM gate now composes that check and adds the
        // experience requirements on top. It is deliberately type-aware: a
        // lesson owes a situation and an outcome, because the outcome IS the
        // lesson; a domain fact owes neither, and demanding one would teach
        // authors to attach verdicts they never earned (Harald, 2026-08-21:
        // "you cannot just form everything upfront into lessons").
        String situation = text(args, "situation");
        String verdict = text(args, "verdict");
        var admission = org.jawata.mcp.knowledge.EntryForm.check(
            type, summary, strings(args, "symptoms"), situation, verdict);
        if (admission.isPresent()) {
            return ToolResponse.invalidParameter(
                admission.get().field(), admission.get().message());
        }

        SymbolFact.Builder fb = SymbolFact.of(type, summary, confidence(text(args, "confidence")));
        String symbol = text(args, "symbol");
        List<String> packages = strings(args, "packages");
        List<String> symbols = strings(args, "symbols");
        if (symbol != null && !symbol.isBlank()) {
            fb.symbol(symbol);
        } else if (!packages.isEmpty() || !symbols.isEmpty()) {
            fb.scope(packages, symbols);
        }
        String details = text(args, "details");
        if (details != null && !details.isBlank()) {
            fb.details(details);
        }
        List<String> exceptions = strings(args, "exceptions");
        if (!exceptions.isEmpty()) {
            fb.exceptions(exceptions);
        }

        ExperienceEntry.Builder eb = ExperienceEntry.of(fb.build())
            .status(text(args, "status"))
            .scopeKind(text(args, "scope_kind"))
            .operation(text(args, "operation"))
            .symptoms(strings(args, "symptoms"))
            .faultOwner(text(args, "fault_owner"))
            .externalSystem(text(args, "external_system"))
            .language(text(args, "language"))
            // Sprint 28c (D3): the facets the gate just validated. `form` is
            // stamped by the ENGINE, not accepted from the caller — it records
            // whether the entry arrived in the 28c shape, and a caller that
            // could set it could claim a shape it does not have. An entry with
            // a situation is form 1; anything else stays unclassified, which is
            // exactly what distinguishes it from a migrated legacy row.
            .situation(situation)
            // v15: the diagnosis — author-supplied, optional, never derived.
            .cause(text(args, "cause"))
            .verdict(verdict)
            .provenanceKind("recorded")
            .form(org.jawata.mcp.knowledge.EntryForm.formOf(situation));
        if (args != null && args.has("links") && args.get("links").isArray()) {
            for (JsonNode l : args.get("links")) {
                String rel = l.path("rel").asText(null);
                String target = l.path("target").asText(null);
                if (rel == null || target == null) {
                    continue;
                }
                // The vocabulary is CLOSED and checked here, at the one door an
                // author writes through. Stored verbatim, a typo produces a link
                // that is present, plausible and reachable by nothing — and the
                // author is told it worked. That is the failure mode a cure can
                // least afford: `cured_by` is a standing instruction, so an
                // unreachable one is a fix nobody will ever be offered.
                if (!org.jawata.mcp.knowledge.EntryForm.LINK_RELS.contains(rel)) {
                    return ToolResponse.invalidParameter("links",
                        "link rel '" + rel + "' is not one this store records."
                        + " RULE: the vocabulary is closed because links are FOLLOWED —"
                        + " an unrecognised rel is stored, looks right, and is reachable by"
                        + " nothing. Allowed: " + org.jawata.mcp.knowledge.EntryForm.linkVocabulary()
                        + ". Use cured_by ONLY when the remedy needs no judgement: a cure on"
                        + " an entry will be run, and one that is right half the time is worse"
                        + " than none. A two-phase remedy earns detected_by and no cure.");
                }
                eb.addLink(rel, target);
            }
        }

        ExperienceEntry entry = eb.build();
        String id = store.put(entry);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        data.put("status", entry.status());
        data.put("stored", true);
        // Sprint 27 D5 — write-path dedup. The entry is STORED either way (a
        // dedup that can lose knowledge is worse than a duplicate); when its
        // meaning near-duplicates an existing entry (>= DEDUP_THRESHOLD, C0-
        // derived and Stage-6 re-derived from hand labels), the response FLAGS
        // it with a merge/drop
        // proposal. The human decides; nothing is automatic. The C0
        // measurement found 23 byte-identical pairs in the live store - this
        // is the hook that would have caught every one at admission.
        try {
            // Unwrap the resident's recovery wrapper first - a bare instanceof
            // against the H2 class is FALSE in production (the C4-F1 lesson).
            var concrete = store instanceof org.jawata.mcp.knowledge.RecoveringExperienceStore r
                ? r.currentDelegate() : store;
            if (concrete instanceof org.jawata.mcp.knowledge.H2ExperienceStore h2) {
                org.jawata.mcp.knowledge.EmbeddingService svc =
                    org.jawata.mcp.knowledge.EmbeddingService.shared();
                if (svc.available()) {
                    var index = new org.jawata.mcp.knowledge.EmbeddingIndex(h2, svc);
                    // The probe embeds the CANDIDATE and compares it against stored
                    // rows, so it must use the same recipe with the same fields —
                    // including the situation. Omit it here and the candidate's
                    // vector is computed from less text than every row it is scored
                    // against, which does not fail: it quietly lowers every
                    // similarity and reports fewer duplicates than exist.
                    for (var hit : index.nearestEntries(
                            org.jawata.mcp.knowledge.EmbeddingService.documentOf(
                                args.path("situation").asText(null),
                                args.path("summary").asText(""), args.path("details").asText(null)), 2,
                            org.jawata.mcp.knowledge.EmbeddingIndex.DEDUP_THRESHOLD)) {
                        if (!hit.id().equals(id)) {
                            data.put("duplicate_of", hit.id());
                            data.put("duplicate_note", "this entry near-duplicates an"
                                + " existing one - consider MERGING into it (promote/"
                                + "edit the older entry) or DROPPING this one (prune)."
                                + " Stored regardless; nothing was lost.");
                            break;
                        }
                    }
                }
            }
        } catch (RuntimeException e) {
            // Stored and unflagged is the honest degrade; say so IN the response
            // rather than a log nobody reads.
            data.put("dedup_check", "failed: " + e.getMessage());
        }
        return ToolResponse.success(data);
    }

    private static Confidence confidence(String s) {
        if (s == null || s.isBlank()) {
            return Confidence.MEDIUM;
        }
        try {
            return Confidence.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Confidence.MEDIUM;
        }
    }

    private static String text(JsonNode n, String field) {
        if (n == null || !n.has(field) || n.get(field).isNull()) {
            return null;
        }
        return n.get(field).asText();
    }

    private static List<String> strings(JsonNode n, String field) {
        List<String> out = new ArrayList<>();
        if (n != null && n.has(field) && n.get(field).isArray()) {
            for (JsonNode item : n.get(field)) {
                if (!item.isNull()) {
                    out.add(item.asText());
                }
            }
        }
        return out;
    }
}
