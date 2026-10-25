package org.jawata.mcp.engine;

import java.nio.file.Files;
import java.nio.file.Path;

import org.jawata.mcp.ShippedTools;
import org.jawata.mcp.ShippedTools.Built;
import org.jawata.mcp.ShippedTools.Wiring;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.execution.SelfEndpoint;
import org.jawata.mcp.knowledge.CatalogueOrigin;
import org.jawata.mcp.knowledge.CatalogueSeeder;
import org.jawata.mcp.knowledge.CatalogueSources;
import org.jawata.mcp.knowledge.EmbeddingIndex;
import org.jawata.mcp.knowledge.ExperienceAdvisor;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.protocol.McpProtocolHandler;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.ExperienceTool;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.jawata.mcp.tools.ToolRegistry;
import org.jawata.mcp.transport.Transport;
import org.jawata.spi.host.EngineHost;

public class JawataEngine {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(JawataEngine.class);

	private RefactoringChangeCache refactoringChangeCache;
	/**
	 * Sprint 24 (D5): the live debug sessions. One registry for the server, so a
	 * session outlives the tool call that made it but never the server itself — a
	 * launched JVM with nobody left to reap it is an orphan.
	 */
	private RuntimeSessionRegistry runtimeSessions;
	private ToolRegistry toolRegistry;
	private ExperienceStore experienceStore;
	/**
	 * The learner-event journal, or NULL on the degraded path (Sprint 28g S16).
	 *
	 * <p>
	 * It rides the experience store's H2 file, so a non-H2 store has none — which
	 * is a state this class already branches on and already logs. It is a field
	 * because {@code hire} needs the same journal a hired run's record lands in and
	 * is registered in another method; a second instance over the same database
	 * would write the same rows and would be a second answer to "where does a
	 * record go".
	 * </p>
	 */
	private LearnerEventStore learnerEvents;
	/** Sprint 26: kept for the watch engine's detector binding. */
	private FindQualityIssueTool findQualityIssueTool;
	/** Sprint 21b (item D): held for the automatic post-project-load refresh. */
	private ExperienceTool experienceTool;
	/** Sprint 27a D2: held so the quality ledger reaches the pre-advice surface. */
	private ExperienceAdvisor experienceAdvisor;
	private McpProtocolHandler protocolHandler;
	private volatile Transport activeTransport;
	/**
	 * Sprint 27a Stage 3b: interrupted at shutdown so the reconciliation loop
	 * exits.
	 */
	private volatile Thread embeddingBackfillThread;
	/**
	 * Sprint 28g S13-9: the daily store copy's schedule; interrupted at shutdown.
	 */
	private volatile Thread scheduledBackupThread;

	/**
	 * Everything only the host knows: its workspace, folders, settings, and the
	 * language bindings it supplies.
	 */
	private final EngineHost host;
	/**
	 * How often the schedule looks at the clock. The copy itself is due once a day.
	 */
	static final java.time.Duration BACKUP_CHECK_EVERY = java.time.Duration.ofHours(1);

	public JawataEngine(EngineHost host, RefactoringChangeCache refactoringChangeCache,
			RuntimeSessionRegistry runtimeSessions) {
		this.host = host;
		this.refactoringChangeCache = refactoringChangeCache;
		this.runtimeSessions = runtimeSessions;
	}

	public RefactoringChangeCache getRefactoringChangeCache() {
		return refactoringChangeCache;
	}

	public void setRefactoringChangeCache(RefactoringChangeCache refactoringChangeCache) {
		this.refactoringChangeCache = refactoringChangeCache;
	}

	public RuntimeSessionRegistry getRuntimeSessions() {
		return runtimeSessions;
	}

	public void setRuntimeSessions(RuntimeSessionRegistry runtimeSessions) {
		this.runtimeSessions = runtimeSessions;
	}

	public ToolRegistry getToolRegistry() {
		return toolRegistry;
	}

	public void setToolRegistry(ToolRegistry toolRegistry) {
		this.toolRegistry = toolRegistry;
	}

	public ExperienceStore getExperienceStore() {
		return experienceStore;
	}

	public void setExperienceStore(ExperienceStore experienceStore) {
		this.experienceStore = experienceStore;
	}

	public LearnerEventStore getLearnerEvents() {
		return learnerEvents;
	}

	public void setLearnerEvents(LearnerEventStore learnerEvents) {
		this.learnerEvents = learnerEvents;
	}

	public FindQualityIssueTool getFindQualityIssueTool() {
		return findQualityIssueTool;
	}

	public void setFindQualityIssueTool(FindQualityIssueTool findQualityIssueTool) {
		this.findQualityIssueTool = findQualityIssueTool;
	}

	public ExperienceTool getExperienceTool() {
		return experienceTool;
	}

	public void setExperienceTool(ExperienceTool experienceTool) {
		this.experienceTool = experienceTool;
	}

	public ExperienceAdvisor getExperienceAdvisor() {
		return experienceAdvisor;
	}

	public void setExperienceAdvisor(ExperienceAdvisor experienceAdvisor) {
		this.experienceAdvisor = experienceAdvisor;
	}

	public McpProtocolHandler getProtocolHandler() {
		return protocolHandler;
	}

	public void setProtocolHandler(McpProtocolHandler protocolHandler) {
		this.protocolHandler = protocolHandler;
	}

	public Transport getActiveTransport() {
		return activeTransport;
	}

	public void setActiveTransport(Transport activeTransport) {
		this.activeTransport = activeTransport;
	}

	public Thread getEmbeddingBackfillThread() {
		return embeddingBackfillThread;
	}

	public void setEmbeddingBackfillThread(Thread embeddingBackfillThread) {
		this.embeddingBackfillThread = embeddingBackfillThread;
	}

	public Thread getScheduledBackupThread() {
		return scheduledBackupThread;
	}

	public void setScheduledBackupThread(Thread scheduledBackupThread) {
		this.scheduledBackupThread = scheduledBackupThread;
	}

	/**
	 * This resident's own endpoint, for a hired brain to be given as its one MCP
	 * server — or null when there is none a second process could reach: a stdio
	 * resident, or an HTTP one whose listener is not bound yet.
	 */
	SelfEndpoint selfEndpoint() {
		if (getActiveTransport() instanceof org.jawata.mcp.transport.HttpTransport http) {
			String url = http.localMcpUrl();
			return url == null ? null
					: http.bearerToken().map(token -> new org.jawata.mcp.execution.SelfEndpoint(url, token))
							.orElse(null);
		}
		return null;
	}

	/**
	 * Sprint 21b (item D): the store-open refresh would be useless — projects load
	 * asynchronously AFTER the store opens, so every pointer would judge as
	 * "unknown". Run the automatic staleness pass here instead, once projects are
	 * in. Already on the async auto-load thread (zero startup cost); autoRefresh
	 * never throws.
	 *
	 * <p>
	 * Sprint 21d (item C, declared boundary): this path runs OUTSIDE the dispatch
	 * disk-sync guard on purpose — it fires immediately after project load, when
	 * the JDT model was just read from this same disk state (fresh by
	 * construction). Every OTHER refresh/recall runs under
	 * {@code ToolRegistry.callTool}'s guard.
	 * </p>
	 *
	 * <p>
	 * Sprint 21e (item A): the same pass now also BACKFILLS symbol auto-anchors
	 * (memory loads before projects — resolution needs the loaded model), and it
	 * fires on BOTH post-load paths: this startup auto-load AND, via
	 * {@code ToolRegistry.setProjectsMutatedHook}, every successful tool-initiated
	 * {@code load_project} / {@code project(action=add|remove)} — add makes new
	 * types anchorable, remove lets refresh CLEAR their auto-anchors. Same
	 * fresh-by-construction footing: it runs immediately after the mutation, inside
	 * the guarded call.
	 * </p>
	 */
	public void refreshExperienceAfterProjectLoad() {
		if (getExperienceTool() != null) {
			log.info("Experience auto-refresh after project load: {}", getExperienceTool().autoRefresh());
		}
	}

	/**
	 * The embedding index over the experience store, or {@code null} when the store
	 * is not an H2 one (the meaning path needs the H2 vector column).
	 *
	 * <p>
	 * One place that builds it, so every surface that runs meaning recall — the
	 * front door, the pre-advice, dispatch — is wired the same way and none
	 * silently gets the keyword-only retriever.
	 * </p>
	 */
	EmbeddingIndex experienceEmbeddingIndex() {
		if (getExperienceStore() instanceof org.jawata.mcp.knowledge.H2ExperienceStore h2) {
			return new org.jawata.mcp.knowledge.EmbeddingIndex(h2, org.jawata.mcp.knowledge.EmbeddingService.shared());
		}
		return null;
	}

	/**
	 * Sprint 28c D5 — put the pattern catalogue into the store this start opened.
	 *
	 * <p>
	 * Deliberately inside {@code openRealStore}, after the store is known good. A
	 * failed open falls back to a recovering store, and seeding that would write
	 * 187 rows into a temporary in-memory store that is discarded when the real one
	 * reconnects — work thrown away, and a row count that jumps and then drops for
	 * no reason a reader could explain.
	 * </p>
	 *
	 * <p>
	 * A seeding failure must not take the resident down with it: the store is open
	 * and every other tool works, so refusing to start would trade a missing
	 * convenience for a dead knowledge layer. It is logged as a WARNING rather than
	 * swallowed, because a catalogue that silently never arrives is exactly the
	 * failure this sprint exists to stop.
	 * </p>
	 * 
	 * @param store the store the catalogue is seeded into
	 */
	void seedCatalogue(H2ExperienceStore store) {
		// ONE LIST, iterated — not one hardcoded loader (Sprint 28d). A source
		// that fails is named and SKIPPED rather than taking the others down
		// with it: one unreadable snapshot must not cost a healthy namespace.
		for (CatalogueOrigin origin : CatalogueSources.all()) {
			try {
				CatalogueSeeder.seed(store, origin);
			} catch (RuntimeException e) {
				log.warn("Catalogue namespace '{}' NOT seeded: {} — design recall will " + "answer without it",
						origin.namespace(), e.toString());
			}
		}
	}

	/**
	 * The real (persistent) open — shared by first attempt and background recovery.
	 *
	 * @param mode          {@code shared}, {@code workspace}, {@code memory}, or a
	 *                      store directory
	 * @param dataDir       the host's data folder, where {@code workspace.json} is
	 *                      looked for when the root has none; may be null
	 * @param workspaceRoot the stable workspace folder; may be null
	 * @return the opened store
	 */
	H2ExperienceStore openRealStore(String mode, Path dataDir, Path workspaceRoot) {
		H2ExperienceStore store = switch (mode) {
		case "memory" -> H2ExperienceStore.openMemory();
		case "workspace" -> H2ExperienceStore.open(workspaceRoot);
		case "", "shared" -> H2ExperienceStore.openShared();
		default -> H2ExperienceStore.openAt(Path.of(mode));
		};
		Path workspaceJson = workspaceRoot != null && Files.isRegularFile(workspaceRoot.resolve("workspace.json"))
				? workspaceRoot.resolve("workspace.json")
				: ResidentDirectories.findWorkspaceJson(dataDir);
		if (workspaceJson != null) {
			String[] prov = ResidentDirectories.readProvenance(workspaceJson);
			store.setProvenance(prov[0], prov[1]);
		}
		// One-time sweep: pre-21a stores stranded in session-isolation dirs (item A).
		store.recoverOrphans(workspaceRoot);
		seedCatalogue(store);
		return store;
	}

	public EngineHost host() {
		return this.host;
	}

	/**
	 * Sprint 21a (items A+B+H): open the experience store, stamp provenance,
	 * recover orphaned session stores, and degrade instead of dying — a store that
	 * refuses to open (e.g. schema written by a NEWER resident) falls back to
	 * in-memory with a loud log, so the server still starts and recall answers with
	 * absence rather than errors.
	 *
	 * <p>
	 * Mode ({@code -Djawata.experience.store}): {@code shared} (DEFAULT — the
	 * user-level store, knowledge spans workspaces) · {@code workspace}
	 * (per-workspace file at the STABLE workspace root — never the launcher's
	 * session dir, which is deleted on clean shutdown) · {@code memory} · any other
	 * value = an explicit store directory.
	 * </p>
	 */
	ExperienceStore openExperienceStore() {
		Path dataDir = host().directories().dataDir().orElse(null);
		Path workspaceRoot = host().directories().workspaceRoot().orElse(null);
		String mode = host().settings().get(SettingKeys.EXPERIENCE_STORE.name()).orElse("shared").trim();
		try {
			return openRealStore(mode, dataDir, workspaceRoot);
		} catch (Exception e) {
			// v3.2.1 (dogfood #1): the old path handed back a BARE in-memory
			// store — a silent, sticky degrade the user had to NOTICE (the
			// 2026-07-19 fleet flip served 367 seed entries as if they were
			// the DB). Now the fallback announces itself on every answer,
			// retries the real open in the background, and replays the
			// degraded window on recovery.
			log.error("Experience store unavailable ({}); continuing with a NON-PERSISTENT "
					+ "in-memory store — recovery retries in the background", e.getMessage());
			return new org.jawata.mcp.knowledge.RecoveringExperienceStore(String.valueOf(e.getMessage()),
					() -> openRealStore(mode, dataDir, workspaceRoot), 30_000);
		}
	}

	/**
	 * Warms recall on a daemon thread. It keys on the SAME store object recall is
	 * handed ({@code experienceStore}, the recovering wrapper in production): the
	 * corpus snapshot is held per store instance, so warming the concrete store
	 * behind it would warm a cache nobody asks.
	 */
	void startRecallWarmUp() {
		final org.jawata.mcp.knowledge.ExperienceStore warmed = getExperienceStore();
		Thread t = new Thread(() -> org.jawata.mcp.knowledge.RecallWarmUp.run(warmed), "jawata-recall-warmup");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	/**
	 * Take the daily store copy when it is due: once at start, then on every hourly
	 * check.
	 *
	 * <p>
	 * Every resident runs this against the one shared file, and each starts by
	 * asking whether the newest scheduled copy is already less than a day old, so
	 * three engines starting together produce one copy, not three, bar a start
	 * inside the same second. A low-priority daemon thread, for the reason the
	 * embedding backfill is one: start-up must not wait for a 90 MB copy.
	 * </p>
	 * 
	 * @param h2 the store whose copy is taken
	 */
	void startScheduledBackups(H2ExperienceStore h2) {
		Thread t = new Thread(() -> {
			org.jawata.mcp.knowledge.StoreBackups backups = new org.jawata.mcp.knowledge.StoreBackups(() -> h2);
			while (!Thread.currentThread().isInterrupted()) {
				try {
					java.nio.file.Path copy = backups.takeIfDue(java.time.Instant.now());
					if (copy != null) {
						log.info("Scheduled experience-store backup taken: {}", copy.getFileName());
					}
				} catch (RuntimeException e) {
					// Never fatal, and never silent: the next hourly check tries again.
					log.warn("Scheduled experience-store backup failed ({}); retrying at the next" + " hourly check",
							e.toString());
				}
				try {
					Thread.sleep(JawataEngine.BACKUP_CHECK_EVERY.toMillis());
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
		}, "jawata-store-backup");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		setScheduledBackupThread(t);
		t.start();
	}

	void startEmbeddingBackfill(H2ExperienceStore h2) {
		final int batch = 1000;
		Thread t = new Thread(() -> {
			try {
				org.jawata.mcp.knowledge.EmbeddingIndex index = new org.jawata.mcp.knowledge.EmbeddingIndex(h2,
						org.jawata.mcp.knowledge.EmbeddingService.shared());
				if (!index.available()) {
					log.info("Embedding backfill skipped — no embedder; recall stays" + " keyword-only for this run");
					return;
				}
				long start = System.currentTimeMillis();
				int total = JawataEngine.reconcileEmbeddings(index, batch,
						() -> Thread.currentThread().isInterrupted());
				// MEASURED, not assumed, and across BOTH reconciled tables. The
				// loop exits when a pass embeds nothing — which means no WRITABLE
				// row remains, NOT that zero rows remain: a row whose text embeds
				// to null (blank, or an embedder failure already logged) stays
				// selected and is skipped every pass. remainingUnembedded()
				// reads experience_entry AND tool_experience, and returns -1
				// ("could not look") rather than a misleading 0 on a read
				// failure — so CONVERGED is claimed only on a measured zero.
				long remaining = index.remainingUnembedded();
				if (Thread.currentThread().isInterrupted()) {
					log.info("Embedding backfill interrupted at shutdown after {} row(s);"
							+ " {} remaining, resumed from the smaller delta next start", total, remaining);
				} else if (remaining == 0) {
					// Stage 6: completion states COVERAGE (total/total), not
					// just this run's row count. The -1 "could not look"
					// sentinel renders as "unknown", matching the stats block
					// (C6 F3).
					log.info(
							"Embedding backfill CONVERGED: {} row(s) in {} ms; 0 remaining"
									+ " — coverage {}/{} entries, {}/{} tool rows",
							total, System.currentTimeMillis() - start,
							JawataEngine.coverageFigure(index.embeddedCount("experience_entry")),
							JawataEngine.coverageFigure(index.totalCount("experience_entry")),
							JawataEngine.coverageFigure(index.embeddedCount("tool_experience")),
							JawataEngine.coverageFigure(index.totalCount("tool_experience")));
				} else if (remaining < 0) {
					log.warn("Embedding backfill: {} row(s) embedded, but the remaining"
							+ " count could not be read — NOT asserting convergence", total);
				} else if (total > 0) {
					log.info(
							"Embedding backfill: {} row(s) in {} ms; {} still unembedded"
									+ " (their text embeds to null — not retried by looping)",
							total, System.currentTimeMillis() - start, remaining);
				} else {
					log.info("Embedding backfill: nothing embeddable — every row either has"
							+ " a current vector or embeds to null");
				}
			} catch (Exception e) {
				// Never fatal: a store without vectors still answers by keyword.
				log.warn("Embedding backfill FAILED ({}); stored entries keep their"
						+ " keyword reachability and the next start retries", e.toString());
			}
		}, "jawata-embedding-backfill");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		setEmbeddingBackfillThread(t);
		t.start();
	}

	void registerTools() {
		// Sprint 21d: strict disk sync — every tool call reconciles external edits
		// before executing; the unchanged-tree fast path is the only skip.
		org.jawata.core.JavaBinding java = this.host().binding(org.jawata.core.JavaBinding.class).orElseThrow();
		org.jawata.core.workspace.StrictDiskSync reconciler = new org.jawata.core.workspace.StrictDiskSync(
				java.model()::current);
		this.getToolRegistry().setReconciler(reconciler);

		// Sprint 21e (item A): tool-initiated project mutations refresh + backfill the
		// store's symbol anchors too (the startup auto-load path calls this directly).
		this.getToolRegistry().setProjectsMutatedHook(() -> this.refreshExperienceAfterProjectLoad());

		// C9a-1: THE SHIPPED SET IS BUILT BY ONE PRODUCER, not written out here. This
		// method used to
		// construct every tool inline (230 lines, 46 callees), so nothing could ask
		// which tools
		// ship and every answer was a hand-kept copy that drifted. What the tools take
		// from this
		// application is the wiring below; everything else about them lives in
		// ShippedTools.
		//
		// The suppliers are read at call time wherever this application's state changes
		// after boot
		// (the workspace service, and the store, which is assigned before this runs but
		// replaced on
		// a degraded reopen).
		Built shipped = ShippedTools.build(new Wiring(java, this.host().workspace(),
				() -> java.model().current() != null, () -> this.getToolRegistry().getToolCount(),
				() -> EngineState.current().loading(), () -> EngineState.current().loadingError(),
				() -> this.host().directories().fieldDir(), () -> this.getToolRegistry().inFlight(),
				this.getRefactoringChangeCache(), this.getExperienceStore(), () -> this.getExperienceStore(),
				() -> this.experienceEmbeddingIndex(), this.getRuntimeSessions(), () -> this.getLearnerEvents(),
				() -> this.getToolRegistry(), () -> this.selfEndpoint(), () -> this.host().directories().memoryRoots(),
				() -> reconciler));

		// Kept for what reads them later: the watch engine's detector binding, the
		// quality ledger's
		// reach to the pre-advice surface (wired in start()), and the automatic refresh
		// after a
		// project load.
		this.setFindQualityIssueTool(shipped.quality());
		this.setExperienceAdvisor(shipped.advisor());
		this.setExperienceTool(shipped.experience());
		// Sprint 28g (D42): the SAME advisor is also asked on every tool response
		// rather than by one
		// tool of forty-odd. Nothing new is constructed here, which is what keeps D-SIX
		// satisfied.
		this.getToolRegistry().setExperienceAdvisor(shipped.advisor());

		for (org.jawata.mcp.tools.Tool tool : shipped.tools()) {
			this.getToolRegistry().register(tool);
		}
	}

	public void assemble() {
		// E18: the engine's readers ask the host, and this is the one place that tells
		// them which host that is.
		org.jawata.mcp.engine.EngineEnvironment.hostedBy(host().settings(), host().directories());
		// Sprint 23 (Stage 5): the headless JDT-manipulation config (preference node
		// id, JDT-UI-style defaults, code-template
		// store) which an IDE gets from JDT-UI activation. E15: it stands down when the
		// host has done the setup itself.
		org.jawata.mcp.tools.shared.HeadlessJdtConfig
				.hostedBy(host().binding(org.jawata.core.JavaBinding.class).orElseThrow());
		org.jawata.mcp.tools.shared.HeadlessJdtConfig.ensureInitialized();
		setToolRegistry(new ToolRegistry());
		// Sprint 21 (v2.0): open the workspace-scoped experience store before
		// registering
		// tools, so store-backed tools + the ExperienceAdvisor can be wired with it.
		// Sprint 21a (item B): a store that cannot open read-write (e.g. written by a
		// NEWER
		// resident) must not kill the server — degrade to in-memory and say so.
		setExperienceStore(openExperienceStore());
		registerTools();

		// v3.2.1 (dogfood #1): while the store is degraded, EVERY answer says so.
		if (getExperienceStore() instanceof org.jawata.mcp.knowledge.RecoveringExperienceStore r) {
			getToolRegistry().setStoreNotice(r::degradedNotice);
		}

		// Sprint 28b D1: the sanitized field recording — file-based (append-only
		// JSONL under <workspace>/field/), independent of the store, so it works
		// in the degraded branch too. Shapes, never content: every value crosses
		// the Token whitelist. Studio reads the same file and folds it itself.
		org.jawata.mcp.field.ClientDirectory clientDirectory = new org.jawata.mcp.field.ClientDirectory();
		String bundleVersion = null;
		org.osgi.framework.Bundle bundle = org.osgi.framework.FrameworkUtil.getBundle(JawataEngine.class);
		if (bundle != null) {
			bundleVersion = bundle.getVersion().toString();
		}
		org.jawata.mcp.field.FieldRecorder fieldRecorder = new org.jawata.mcp.field.FieldRecorder(
				new org.jawata.mcp.field.FieldPile(this.host().directories().fieldDir()), clientDirectory,
				bundleVersion);

		// Sprint 26: the event tap — every tool outcome becomes a learner
		// label as a side effect of the call (D7: training is a side effect
		// of use). Rides the store's H2 file; a non-H2 (degraded) store means
		// ledger-only tapping, stated in the log.
		if (getExperienceStore() instanceof org.jawata.mcp.knowledge.H2ExperienceStore h2) {
			// Sprint 28g S16: a FIELD rather than a local, because `hire` needs the same
			// journal
			// and is registered in another method. It stays NULL on the degraded path,
			// which is
			// the condition this `if` already expresses — and `hire` reports that absence
			// rather
			// than disappearing from the published tool list, where no client could see it.
			setLearnerEvents(new org.jawata.mcp.knowledge.LearnerEventStore(h2));
			org.jawata.mcp.learn.SessionLedger sessionLedger = new org.jawata.mcp.learn.SessionLedger();
			org.jawata.mcp.learn.EventTap eventTap = new org.jawata.mcp.learn.EventTap(sessionLedger,
					getLearnerEvents());
			// Sprint 26a D2: the experience loop's selective capture lane —
			// outcome-bearing events (a mutate's compile result, a tool error) →
			// tool_experience, read back by the baseline retriever (Stage 2).
			org.jawata.mcp.knowledge.ToolExperienceStore toolExperienceStore = new org.jawata.mcp.knowledge.ToolExperienceStore(
					h2);
			org.jawata.mcp.learn.ToolExperienceRecorder toolExperienceRecorder = new org.jawata.mcp.learn.ToolExperienceRecorder(
					toolExperienceStore);
			eventTap.setToolExperienceRecorder(toolExperienceRecorder);
			// Sprint 28b D1: the field recording rides the same tap.
			eventTap.setFieldRecorder(fieldRecorder);
			// Sprint 28c D14 (v13): the origin stamp rides it too — the tap is
			// the one place that has the session id, and the directory is the
			// one place that knows what client that session declared. The store
			// receives the closed-vocabulary token's value, never a raw name.
			final org.jawata.mcp.knowledge.ExperienceStore stampTarget = getExperienceStore();
			eventTap.setOriginStamper((sessionId, entryId) -> stampTarget.setOriginClient(entryId,
					clientDirectory.clientOf(sessionId).value()));
			// Sprint 27 D6: the quality ledger — every counter advances as a side
			// effect of normal use, which is the only way Sprint 33 gets evidence
			// nobody had to remember to collect. Installed on all four producers
			// here, in one place, so no surface can be shipped uncounted.
			org.jawata.mcp.knowledge.QualityLedger qualityLedger = new org.jawata.mcp.knowledge.QualityLedger(h2);
			getToolRegistry().setQualityLedger(qualityLedger);
			toolExperienceRecorder.setQualityLedger(qualityLedger, getToolRegistry().precedentLedger());
			if (getExperienceAdvisor() != null) {
				// Sprint 27a D2: the fourth producer. Without this the pre-advice
				// surface reaches the agent uncounted — built, wired, and
				// invisible to the honesty surface, which is the shape this
				// ledger exists to prevent.
				getExperienceAdvisor().setQualityLedger(qualityLedger);
			}
			if (getExperienceTool() != null) {
				getExperienceTool().setQualityLedger(qualityLedger);
				// Sprint 28c D9: the lane a declared shell-fallback goes to. Wired
				// in the SAME block as the ledger and for the same reason — an
				// unwired collaborator here would make the fallback verb refuse
				// every declaration, which the hook would see as an outage.
				getExperienceTool().setToolExperienceStore(toolExperienceStore);
				// Sprint 28g S21: the transcript lane, installed here for the same reason as
				// the
				// tool lane above. A resident without it answers every call that names the lane
				// with "not installed" and refuses to store a turn: said, never silent.
				getExperienceTool().setTranscriptStore(new org.jawata.mcp.knowledge.TranscriptStore(h2));
			} else {
				// registerTools() runs above, so this cannot happen today — and if
				// a reordering ever makes it happen, the symptom would be a quality
				// block that stays empty and reads like "nothing has happened yet".
				// Say it instead.
				log.error("Quality ledger NOT wired to the experience tool — the recall"
						+ " surfaces will not be counted and stats will show no quality block");
			}
			// Sprint 27 D3: the seam swap this interface was built for — the
			// semantic retriever gathers keyword ∪ meaning-near captures. The
			// identity/advisory split lives at the choke (IdentityMatch); a
			// broken embedder degrades this to exactly the keyword baseline.
			getToolRegistry().setPrecedentRetriever(new org.jawata.mcp.learn.EmbeddingPrecedentRetriever(
					toolExperienceStore, new org.jawata.mcp.knowledge.EmbeddingIndex(h2,
							org.jawata.mcp.knowledge.EmbeddingService.shared())));
			// v3.4.1: EMBED WHAT IS ALREADY THERE. Entries written from now on are
			// embedded as they are stored, but a store that predates this build —
			// which is every real one — holds thousands of rows with no vector, and
			// meaning-based recall simply cannot see them. v3.4.0 shipped with this
			// call existing, tested, and made by nobody: recall by meaning was
			// therefore inert on every corpus that mattered.
			//
			// Bounded and on a daemon thread: embedding ~2000 entries takes minutes,
			// and start-up must not wait for it. Each pass takes a batch and the
			// next start takes the next, so a large store converges over a few
			// restarts while staying keyword-reachable throughout.
			startEmbeddingBackfill(h2);
			// REL44-8 (cold start): the first recall after a restart built the corpus, read
			// the
			// vectors and loaded the embedder while the hook waited — ~710 ms on a
			// 10,189-row
			// store, against a 200 ms budget. Build them here, off the serving path.
			startRecallWarmUp();
			// Sprint 28g S13-9: the daily copy. Harald, 2026-10-14: "it should make backups
			// every day" — on that day the newest copy was a month old, because copies were
			// only ever taken before a destructive verb.
			startScheduledBackups(h2);
			// Sprint 26a D3b: the deterministic architect-involvement gate — the
			// rule that replaces the retired edit-switch model.
			getToolRegistry().setArchitectGate(
					new org.jawata.mcp.learn.ArchitectGate(org.jawata.mcp.learn.ArchitectGate.DEFAULT_LOC_THRESHOLD));
			getToolRegistry().setEventTap(eventTap);
			// D4/D5/D3: the server-side lane — defects file into the store.
			org.jawata.mcp.learn.ServerChecks serverChecks = new org.jawata.mcp.learn.ServerChecks(getLearnerEvents(),
					(summary, details) -> {
						try {
							this.getExperienceStore().put(org.jawata.mcp.knowledge.SymbolFact
									.of("defect", summary + " — " + details, org.jawata.mcp.knowledge.Confidence.HIGH)
									.build());
						} catch (Exception e) {
							log.error("DEFECT FILING FAILED — the relax label is now" + " unaccompanied: {}", summary,
									e);
						}
					});
			getToolRegistry().setServerChecks(serverChecks);
			sessionLedger.setEvictionListener(serverChecks::onSessionEvicted);
			// Sprint 26a D4 / v3.3.1: the edit-switch model is RETIRED and its
			// reporting kinds (train|learner_status|observe_edit) are GONE with the
			// /train command, so the experience tool no longer needs the capture
			// lane. The deterministic architect gate + the experience loop replace
			// the model; the loop's own capture wiring is below, unaffected.
			// D1: the automatic architect — detectors bound to the quality
			// tool's own single-file path (no second detector surface).
			getToolRegistry().setWatchEngine(new org.jawata.mcp.learn.WatchEngine((kind, filePath) -> {
				com.fasterxml.jackson.databind.node.ObjectNode args = new com.fasterxml.jackson.databind.ObjectMapper()
						.createObjectNode();
				args.put("kind", kind);
				args.put("filePath", filePath);
				return this.getFindQualityIssueTool().execute(args);
			}, getLearnerEvents()));
			log.info("Learner event tap + watch engine wired");
		} else {
			org.jawata.mcp.learn.EventTap degradedTap = new org.jawata.mcp.learn.EventTap(
					new org.jawata.mcp.learn.SessionLedger(), null);
			// Sprint 28b D1: the field recording is file-based and survives a
			// degraded store — the utilization signal must not vanish with H2.
			degradedTap.setFieldRecorder(fieldRecorder);
			getToolRegistry().setEventTap(degradedTap);
			log.warn("Learner event tap running LEDGER-ONLY — no persistent store"
					+ " (degraded experience store); the label stream is not persisted");
		}

		// Initialize protocol handler
		setProtocolHandler(new McpProtocolHandler(getToolRegistry()));
		// Sprint 28b D1: session → client attribution for the field recording.
		getProtocolHandler().setClientDirectory(clientDirectory);

		log.info("Registered {} tools", getToolRegistry().getToolCount());

		// Sprint 28d-rescue (P1). Every tool has now fed its operations into the
		// registry, so this is the first moment the question "does anything back this
		// cure step?" has a true answer. It THROWS on a table naming a step nothing
		// publishes — boot fails loudly rather than serving a cure that would refuse at
		// the front door. It used to run in CureCatalog's static initializer, which can
		// no longer work: the registry is populated by registration, and a class-load
		// before that would refuse every step for a reason about ordering.
		org.jawata.mcp.tools.smell.CureCatalog.validateAgainst(org.jawata.mcp.change.OperationRegistry.theRegistry());

		// Sprint 28g D39 / the architecture's P2 step 3. The same moment, and the same
		// reason:
		// every tool has registered, so this is the first point at which the engine's
		// coverage
		// declaration can be checked against what it actually publishes. It THROWS on a
		// declaration that disagrees with the shipped surface, exactly as the cure
		// table above
		// does.
		//
		// THIS COMMENT ALSO CLAIMED "the first point at which a catalogue row key can
		// be
		// answered from production", and that was false: the index it referred to had
		// no
		// reader and is withdrawn. What this call buys is that the declaration is READ
		// by
		// production at all — the catalogue stops being code only its own tests touch —
		// and
		// that the product can STATE what it performs. Answering a row key is §4-B's
		// index,
		// which is owed; see JavaEngineCoverage and OperationRegistry.
		int coveredRows = org.jawata.mcp.catalogue.JavaEngineCoverage.verifyAgainstShippedDoors();
		log.info("This engine performs {} of {} catalogue refactorings", coveredRows,
				org.jawata.catalogue.Refactorings.all().size());
	}

	/**
	 * Sprint 27a D5 (Stage 3b) — the STARTUP RECONCILIATION.
	 *
	 * <p>
	 * It embeds a batch, then the next, then the next, until the delta is ZERO —
	 * every row that lacks a current-identity vector has one. It does not embed one
	 * batch and stop: that left a store larger than the batch permanently
	 * part-embedded (the live store sat at 1,037 of 2,080 all day, with three
	 * frozen calibration entries in the unreachable half), and it is a prerequisite
	 * for Stage 4, where bumping the embedder identity invalidates every vector — a
	 * one-batch cap would leave the store WORSE than before the bump.
	 * </p>
	 *
	 * <p>
	 * Resumable by construction: each row is written as it is embedded (Harald's
	 * design), so an interrupted run leaves a smaller delta for the next start
	 * rather than losing work. Scale was never the constraint — one user's store —
	 * so the batch is a unit of work, not a stopping condition.
	 * </p>
	 *
	 * <p>
	 * Two ways it ends, both clean: {@code backfill} returns 0 (the delta is closed
	 * — it does NOT then spin re-checking an empty delta), or the thread is
	 * interrupted at shutdown. It stays a MIN_PRIORITY daemon off the start-up
	 * path, so the resident answers {@code initialize} immediately while this runs.
	 * </p>
	 */
	/**
	 * The reconciliation loop itself, extracted so it can be DRIVEN by a test
	 * rather than only reasoned about inside a daemon thread.
	 *
	 * <p>
	 * Embeds batch after batch until {@code backfill} returns 0 — the delta is
	 * closed — or {@code interrupted} becomes true. It never spins on an empty
	 * delta: {@code backfill} returning 0 IS the loop's exit, not a condition it
	 * re-polls. Returns the total embedded.
	 * </p>
	 *
	 * @param interrupted checked before each pass; {@code true} ends the loop with
	 *                    a clean partial (shutdown), by construction resumable
	 *                    since each row was persisted as it embedded
	 */
	public static int reconcileEmbeddings(org.jawata.mcp.knowledge.EmbeddingIndex index, int batch,
			java.util.function.BooleanSupplier interrupted) {
		// Sprint 28f Stage 3: the loop MOVED to EmbeddingIndex.drain and this forwards.
		// It is not a tidy-up. The stage has to drain at four WRITE paths inside the
		// knowledge layer, and they cannot reach a static on the application without
		// depending upward — so the choice was one loop on the index, or a second copy
		// beside this one. The second copy is the shape that has already cost this
		// repository five duplicate type lookups and six duplicate nested-type
		// searches,
		// each fixed only where somebody happened to look.
		//
		// This signature is kept because tests drive it by name; what changes is that
		// there is now one implementation under it. drain() ALSO checks
		// remainingUnembedded(), so it can tell "converged" from "the backfill is
		// skipping rows it cannot embed" — a distinction this loop could not make,
		// because both looked like backfill returning 0.
		return index.drain(batch, interrupted);
	}

	/**
	 * Stage 6 (C6 F3): the -1 "could not look" sentinel renders as "unknown" in the
	 * coverage log, matching the stats block.
	 */
	static String coverageFigure(long count) {
		return count < 0 ? "unknown" : String.valueOf(count);
	}
}