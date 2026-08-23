package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.h2.jdbcx.JdbcDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 21 (v2.0): H2-backed {@link ExperienceStore}. Embedded, single-file,
 * workspace-scoped — no external daemon. Stage 0 = schema + open/close + entry
 * round-trip; the richer indexed columns (Stage 1) and full-text/fit-gated retrieval
 * (Stage 2) build on this schema. A single connection is held for the store's lifetime
 * and methods are synchronized (the resident is one process; H2 file mode is single-JVM).
 */
public final class H2ExperienceStore implements ExperienceStore {

    private static final Logger log = LoggerFactory.getLogger(H2ExperienceStore.class);

    /**
     * #37: values at or below this ride INSIDE the row instead of being streamed as a
     * remote LOB. An entry's text and its 384-float embedding are both far below it, so
     * in practice nothing streams; 64 KiB leaves headroom without bloating a page.
     */
    static final int MAX_INPLACE_LOB_BYTES = 65_536;

    /**
     * #37: the ceiling on a single store round trip. A read that never returns must fail
     * in seconds — {@code LOCK_TIMEOUT} does NOT cover this, because that is H2's table
     * lock, not the session's {@code ReentrantLock} and not the socket. The measured hang
     * sat in {@code Net.poll} for 3459 s having burned 90 ms of CPU; nothing bounded it.
     */
    static final int NETWORK_TIMEOUT_MILLIS = 15_000;

    private final ObjectMapper json = new ObjectMapper();
    /** Non-final: {@link #compact()} shuts the database down and reopens the connection. */
    private Connection conn;
    private final String url;
    /** The backing {@code .mv.db} file (null for in-memory) — self-exclusion in recovery. */
    private final Path storeFile;

    // Sprint 21a (item B): provenance stamped on every write; set once at store-open from
    // workspace.json. Null when unknown (manual launches, tests).
    private volatile String workspaceId;
    private volatile String projectId;

    /**
     * The live connection — self-heals when the shared AUTO_SERVER database was shut
     * down under us (Sprint 21b: a {@code compact} on ANY attached resident closes the
     * database for ALL of them; the peers must reconnect, not die).
     */
    /**
     * Sprint 26: the learner tables (schema v5) live on this same store file;
     * {@link LearnerEventStore} shares the connection AND this instance as its
     * lock (every access synchronizes on the store object, exactly as the
     * store's own methods do), so the two writers never interleave.
     */
    Connection sharedConnection() {
        return live();
    }

    /** v3.2.1 (dogfood #3): force the NEXT {@link #sharedConnection()} to reopen —
     *  a statement that failed mid-write may sit on a connection {@code isValid}
     *  still blesses (auto-server handoff, lock-timeout aftermath). */
    void invalidateSharedConnection() {
        discardReadPool();
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.debug("closing a suspect connection failed (ignored): {}", e.getMessage());
        }
        conn = null;
    }

    /**
     * #37: put a ceiling on how long ONE store round trip may block. Applied to every
     * connection this class hands out — the initial one and every self-healed re-open —
     * because the resident that wedged was an AUTO_SERVER client whose LOB read parked in
     * a socket read that nothing could ever interrupt.
     *
     * <p>Best-effort by design: a driver that does not implement it must not stop the
     * store from opening. It is logged rather than swallowed, because a store running
     * without a bound is exactly the state that produced the outage, and an operator
     * reading the log should be able to see that it is unbounded.
     */
    /** So the unbounded-connection fact is stated once per process, not once per open. */
    private static final java.util.concurrent.atomic.AtomicBoolean UNBOUNDED_REPORTED =
        new java.util.concurrent.atomic.AtomicBoolean();

    private static void boundNetworkWait(Connection connection) {
        try {
            connection.setNetworkTimeout(Runnable::run, NETWORK_TIMEOUT_MILLIS);
            // VERIFY, DO NOT TRUST THE CALL. H2 2.2.224 ACCEPTS this and discards it —
            // JdbcConnection.setNetworkTimeout's whole body is `return`, and
            // getNetworkTimeout answers 0 forever. Catching the exception was useless
            // because no exception is thrown: the first version of this method reported a
            // bound that did not exist, which is the #37 failure wearing the fix's clothes.
            // So read it back, and if the driver ignored us, SAY SO.
            int applied = connection.getNetworkTimeout();
            if (applied <= 0 && UNBOUNDED_REPORTED.compareAndSet(false, true)) {
                // ONCE per process, and at INFO. H2 2.2.224 ignores this call
                // unconditionally, so a WARN here fired on every connection open forever
                // — an always-on warning is not a signal, it is background. And its
                // original text ("will hang until the caller gives up") is no longer
                // true: the OPERATION is bounded now, at the retrieval boundary. What
                // remains worth saying once is which of the two bounds is in force.
                log.info("Experience store connection is UNBOUNDED — {} ignores"
                        + " setNetworkTimeout (asked {} ms, reports {}). Retrieval is bounded"
                        + " at the operation instead ({} ms); a read that stops returning"
                        + " still holds its H2 session lock. See #37.",
                    connection.getClass().getName(), NETWORK_TIMEOUT_MILLIS, applied,
                    org.jawata.mcp.knowledge.ExperienceRetrieval.RETRIEVAL_BUDGET_MILLIS);
            }
        } catch (SQLException | RuntimeException e) {
            log.warn("Experience store connection is UNBOUNDED — setNetworkTimeout refused: {}",
                e.getMessage());
        }
    }

    /**
     * The ONE place a store connection is created. Three sites used to build their own
     * ({@link #live()}, {@link #compact()}, and the opener) and the bound was a convention
     * repeated by hand — which {@code compact()} already failed to keep.
     */
    private static Connection openBound(String url) throws SQLException {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        Connection connection = ds.getConnection();
        boundNetworkWait(connection);
        return connection;
    }

    private Connection live() {
        try {
            if (conn == null || conn.isClosed() || !conn.isValid(1)) {
                conn = openBound(url);
                log.info("Experience store connection re-established");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to re-open store connection: " + e.getMessage(), e);
        }
        return conn;
    }

    // ---------------------------------------------------------------- reads
    //
    // Stage 3 (#37 structural half) — THE DISPOSITION TABLE. Every consumer of
    // this store, and which connection it uses:
    //
    //   consumer              path    connection            lock
    //   --------------------  ------  --------------------  ----------------------
    //   put/putWithSource,    WRITE   the shared `conn`     this instance's monitor
    //   deleteBySource,wipe,
    //   setStatus, update*,
    //   importEntries,
    //   pruneAged, compact,
    //   recoverOrphans
    //   LearnerEventStore     WRITE   the shared `conn`     this instance's monitor
    //   ToolExperienceStore   WRITE   the shared `conn`     this instance's monitor
    //   QualityLedger         WRITE   the shared `conn`     this instance's monitor
    //   all/get/query/byIds,  READ    a POOLED connection   none (pool slot only)
    //   count/listEntries/
    //   exportEntries/stats
    //   EmbeddingIndex        READ    the shared `conn`     this instance's monitor
    //
    // What replaces "one monitor ⇒ writers never interleave": nothing, because
    // nothing needed replacing. Every WRITER still takes this instance's
    // monitor and still uses the one shared connection, so the invariant
    // LearnerEventStore's javadoc states is untouched, and it is pinned by a
    // test. What changed is that READERS no longer take that monitor at all —
    // which is the whole defect: one reader parked in a socket read used to
    // stop every writer, every learner event, and every tool call on the
    // resident (measured live 2026-08-19: `search_symbols` timing out behind
    // `ToolExperienceStore.append` waiting on a monitor held by a thread in
    // `Net.poll`).
    //
    // A reader that parks now costs ONE pool slot. Concurrent readers proceed
    // in parallel up to the pool size; past it they wait for a slot, and a
    // wait that expires is reported as BUSY — never as "wedged", because eight
    // healthy concurrent reads look identical to eight stuck ones from here
    // (C2 audit), and only the operation deadline at the retrieval boundary
    // can tell those apart.

    /** How many reads may be in flight at once. */
    static final int READ_POOL_SIZE = 4;

    /** How long a read waits for a pool slot before reporting the store busy. */
    static final long READ_BORROW_TIMEOUT_MILLIS = 5_000;

    private final java.util.concurrent.ArrayBlockingQueue<Connection> readPool =
        new java.util.concurrent.ArrayBlockingQueue<>(READ_POOL_SIZE);
    private final java.util.concurrent.atomic.AtomicInteger readConnections =
        new java.util.concurrent.atomic.AtomicInteger();

    /** Work that needs a connection and may fail the way JDBC fails. */
    private interface ReadWork<T> {
        T apply(Connection c) throws SQLException;
    }

    /**
     * Run a read OFF the instance monitor, on its own connection.
     *
     * <p>FALLS BACK to the shared connection under the monitor when a second
     * connection cannot be opened — today every store URL permits one
     * ({@code AUTO_SERVER} for files, {@code DB_CLOSE_DELAY=-1} in memory), but
     * a URL that does not must keep working exactly as it did rather than lose
     * its reads.</p>
     */
    private <T> T withRead(String what, ReadWork<T> work) {
        Connection c = borrowRead();
        if (c == null) {
            synchronized (this) {
                try {
                    return work.apply(live());
                } catch (SQLException e) {
                    throw new IllegalStateException("failed to " + what + ": " + e.getMessage(), e);
                }
            }
        }
        boolean healthy = true;
        try {
            return work.apply(c);
        } catch (SQLException e) {
            healthy = false;
            throw new IllegalStateException("failed to " + what + ": " + e.getMessage(), e);
        } finally {
            releaseRead(c, healthy);
        }
    }

    /** A pooled read connection, or null when the store cannot give one out. */
    Connection borrowRead() {
        Connection pooled = readPool.poll();
        if (pooled != null) {
            return validOrReplaced(pooled);
        }
        if (readConnections.get() < READ_POOL_SIZE
                && readConnections.incrementAndGet() <= READ_POOL_SIZE) {
            try {
                return openBound(url);
            } catch (SQLException e) {
                readConnections.decrementAndGet();
                log.debug("read connection unavailable, falling back to the shared one: {}",
                    e.getMessage());
                return null;
            }
        } else {
            readConnections.decrementAndGet();
        }
        try {
            Connection waited = readPool.poll(READ_BORROW_TIMEOUT_MILLIS,
                java.util.concurrent.TimeUnit.MILLISECONDS);
            if (waited != null) {
                return validOrReplaced(waited);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        throw new IllegalStateException("experience store is BUSY: all " + READ_POOL_SIZE
            + " read slots are in use and none freed within " + READ_BORROW_TIMEOUT_MILLIS
            + " ms. This says the store is saturated, NOT that it is wedged —"
            + " concurrent healthy reads look the same from here.");
    }

    private Connection validOrReplaced(Connection pooled) {
        try {
            if (!pooled.isClosed() && pooled.isValid(1)) {
                return pooled;
            }
        } catch (SQLException e) {
            log.debug("pooled read connection is unusable: {}", e.getMessage());
        }
        closeQuietly(pooled);
        try {
            return openBound(url);
        } catch (SQLException e) {
            readConnections.decrementAndGet();
            return null;
        }
    }

    void releaseRead(Connection c, boolean healthy) {
        if (!healthy || !readPool.offer(c)) {
            closeQuietly(c);
            readConnections.decrementAndGet();
        }
    }

    /** Drop every pooled read connection — the database went away under them. */
    private void discardReadPool() {
        Connection c;
        while ((c = readPool.poll()) != null) {
            closeQuietly(c);
        }
        readConnections.set(0);
    }

    private H2ExperienceStore(Connection conn, String url, Path storeDir, Path storeFile)
            throws SQLException {
        this.conn = conn;
        this.url = url;
        this.storeFile = storeFile;
        boundNetworkWait(conn);
        Map<String, Object> report = SchemaMigrations.migrate(conn, storeDir);
        if (Boolean.TRUE.equals(report.get("migrated"))) {
            log.info("Experience store schema: {}", report);
        }
    }

    /**
     * Open a file-backed store under {@code dir} in the workspace layout
     * ({@code <dir>/jawata-experience/experience.mv.db}). A {@code null} dir — a manual
     * launch without {@code -data} — yields an in-memory store: the seam still works, it
     * just does not persist across restarts.
     */
    public static H2ExperienceStore open(Path dir) {
        if (dir == null) {
            return openMemory();
        }
        return openAt(dir.resolve("jawata-experience"));
    }

    /** In-memory store: unique name per instance so independent stores never share state. */
    public static H2ExperienceStore openMemory() {
        String url = "jdbc:h2:mem:jawata-exp-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
        log.info("Experience store is in-memory (non-persistent)");
        return openUrl(url, null, null);
    }

    /**
     * Sprint 21a (items A+H): open the store file directly at
     * {@code <storeDir>/experience.mv.db} with {@code AUTO_SERVER} — the first resident
     * becomes the H2 auto-server and further residents (other workspaces sharing the
     * user-level store, or concurrent sessions of one workspace) attach transparently
     * through the same URL.
     */
    public static H2ExperienceStore openAt(Path storeDir) {
        try {
            Files.createDirectories(storeDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot create store dir " + storeDir + ": " + e.getMessage(), e);
        }
        Path base = storeDir.resolve("experience");
        // H2 forbids DB_CLOSE_ON_EXIT together with AUTO_SERVER — the auto-server owns the
        // database lifecycle (we still close() explicitly at stop()).
        // F1 (Sprint-26 audit): LOCK_TIMEOUT raised from H2's 1s default to 10s so a
        // cross-resident write that contends on a shared row (two residents MERGE the
        // same learner_state key over the auto-server socket) WAITS for the peer's tiny
        // transaction instead of failing fast and dropping the event.
        // #37: with AUTO_SERVER every resident but the host is an H2 CLIENT, so a LOB read
        // is a lazy per-value round trip over a socket — and one such read, with no
        // timeout, held the session lock for 58 minutes while 66 threads queued behind the
        // store monitor. Entry text and a 384-float embedding are small; keeping them
        // INLINE in the row removes the streaming, so there is nothing left to hang on.
        String url = "jdbc:h2:file:" + base.toAbsolutePath()
            + ";AUTO_SERVER=TRUE;LOCK_TIMEOUT=10000"
            + ";MAX_LENGTH_INPLACE_LOB=" + MAX_INPLACE_LOB_BYTES;
        // v2.2.4: a runtime swap restarts residents seconds apart — the dying peer's lock
        // file is "recently modified" and H2 refuses the open. That is TRANSIENT; retry
        // before the caller degrades to a silent, non-persistent in-memory store.
        H2ExperienceStore store = openWithRetry(
            () -> openUrl(url, storeDir, storeDir.resolve("experience.mv.db")), 5, 1500);
        log.info("Experience store opened (file: {})", storeDir);
        return store;
    }

    /** Retry transient lock contention ({@code attempts} × {@code sleepMs}); rethrow anything else. */
    static H2ExperienceStore openWithRetry(java.util.function.Supplier<H2ExperienceStore> opener,
            int attempts, long sleepMs) {
        IllegalStateException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return opener.get();
            } catch (IllegalStateException e) {
                if (!isTransientLock(e)) {
                    throw e;
                }
                last = e;
                log.info("Experience store lock busy (attempt {}/{}): {}", attempt, attempts, e.getMessage());
                if (attempt < attempts) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    /** The two H2 messages a resident restart race produces — gone within seconds. */
    private static boolean isTransientLock(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("Lock file recently modified")
                    || m.contains("Database may be already in use"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sprint 21a (item H): the user-level shared store — Harald's decision 2026-07-05:
     * this is the DEFAULT. Knowledge is the user's, not the workspace's; symbol/package
     * scope-containment keeps repo-specific recall self-scoped, while methodology/domain
     * entries deliberately cross workspaces.
     */
    public static H2ExperienceStore openShared() {
        Path dir = sharedStoreDir();
        log.info("Experience store mode: user-shared ({})", dir);
        return openAt(dir);
    }

    /** {@code jawata.experience.shared.dir} property › {@code $XDG_DATA_HOME/jawata} › {@code ~/.local/share/jawata}. */
    static Path sharedStoreDir() {
        String override = System.getProperty("jawata.experience.shared.dir");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path base = xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".local", "share");
        return migrateLegacySharedDir(base);
    }

    /**
     * Sprint 22b (the jawata rebrand): one-time move of the pre-rebrand user-shared
     * store dir {@code <base>/goja} to {@code <base>/jawata} — the whole directory
     * (store file, backups, exports) moves as-is, content untouched; the v4 schema
     * migration then rewrites the anchors on first open. Never clobbers: if
     * {@code <base>/jawata} already exists, the legacy dir is left alone. Only the
     * DEFAULT XDG-derived path migrates — an explicit {@code *.experience.shared.dir}
     * override is used as-is. The {@code "goja"} literal is migration code
     * (grep-contract exception class 3).
     */
    static Path migrateLegacySharedDir(Path base) {
        Path dir = base.resolve("jawata");
        Path legacy = base.resolve("goja");
        if (java.nio.file.Files.isDirectory(legacy) && !java.nio.file.Files.exists(dir)) {
            try {
                java.nio.file.Files.move(legacy, dir);
                log.info("Experience store: migrated legacy shared dir {} -> {}", legacy, dir);
            } catch (java.io.IOException e) {
                log.warn("Experience store: could not migrate legacy shared dir {} -> {}: {} "
                    + "(continuing with {})", legacy, dir, e.getMessage(), dir);
            }
        }
        return dir;
    }

    private static H2ExperienceStore openUrl(String url, Path storeDir, Path storeFile) {
        Connection conn = null;
        try {
            conn = openBound(url);
            return new H2ExperienceStore(conn, url, storeDir, storeFile);
        } catch (SQLException e) {
            closeQuietly(conn);
            throw new IllegalStateException("failed to open experience store: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            closeQuietly(conn);      // e.g. a refused from-the-future store must not keep the lock
            throw e;
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // best effort
            }
        }
    }

    @Override
    public String provenanceWorkspaceId() {
        return workspaceId;
    }

    /** Sprint 26: the learner event rows carry the same provenance. */
    String provenanceProjectId() {
        return projectId;
    }

    @Override
    public void setProvenance(String workspaceId, String projectId) {
        this.workspaceId = workspaceId;
        this.projectId = projectId;
    }

    /**
     * A healthy open store is not degraded — stated here rather than inherited, because
     * the interface deliberately has no default: every store answers for itself, and the
     * compiler names the ones that have not (the {@code RecoveringExperienceStore}
     * forwarding hole was invisible for exactly as long as a default answered for it).
     */
    @Override
    public String degradedNotice() {
        return null;
    }

    // Schema DDL lives in SchemaMigrations (Sprint 21a item B) — versioned, additive-first,
    // backed-up-before-migrate. The v2.0.0 initSchema() became its v1 step.

    @Override
    public synchronized String put(SymbolFact fact) {
        return put(ExperienceEntry.candidate(fact));
    }

    @Override
    public synchronized String put(ExperienceEntry entry) {
        return insert(entry, UUID.randomUUID().toString(), null, null);
    }

    @Override
    public synchronized String putWithSource(ExperienceEntry entry, String sourceRef) {
        return insert(entry, UUID.randomUUID().toString(), sourceRef, null);
    }

    @Override
    public synchronized String putWithSource(ExperienceEntry entry, String sourceRef, String sourceHash) {
        return insert(entry, UUID.randomUUID().toString(), sourceRef, sourceHash);
    }

    /** Sprint 21b: skip-unchanged — any entry from this source with this exact hash? */
    @Override
    public synchronized boolean sourceUnchanged(String sourceRef, String sourceHash) {
        if (sourceRef == null || sourceHash == null) {
            return false;
        }
        try (PreparedStatement ps = live().prepareStatement(
                "SELECT 1 FROM experience_entry WHERE source_ref = ? AND source_hash = ? LIMIT 1")) {
            ps.setString(1, sourceRef);
            ps.setString(2, sourceHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to check source hash: " + e.getMessage(), e);
        }
    }

    private String insert(ExperienceEntry entry, String id, String sourceRef, String sourceHash) {
        Map<String, Object> factMap = entry.fact().toMap();
        String body;
        try {
            body = json.writeValueAsString(entry.toMap());
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize entry: " + e.getMessage(), e);
        }
        try {
            try (PreparedStatement ps = live().prepareStatement(
                    "INSERT INTO experience_entry"
                    + "(id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
                    + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at,"
                    + "workspace_id,project_id,language,source_hash,embedding,embedder_identity,"
                    // Sprint 28c (v10): the knowledge-spine facets. Widened HERE
                    // together with ALL_COLUMNS and importEntries — a column that
                    // is written but not exported is a column that survives a
                    // round trip only by accident.
                    + "situation,verdict,provenance_kind,"
                    + "form,evidence_dead) "
                    + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                Timestamp now = Timestamp.from(Instant.now());
                ps.setString(1, id);
                ps.setString(2, str(factMap.get("type")));
                ps.setString(3, entry.scopeKind());
                ps.setString(4, str(factMap.get("symbol")));
                ps.setString(5, firstPackage(factMap));
                ps.setString(6, entry.operation());
                ps.setString(7, entry.status());
                ps.setString(8, str(factMap.get("confidence")));
                ps.setString(9, entry.faultOwner());
                ps.setString(10, entry.externalSystem());
                ps.setString(11, str(factMap.get("summary")));
                ps.setString(12, sourceRef);
                ps.setString(13, body);
                ps.setTimestamp(14, now);
                ps.setTimestamp(15, now);
                ps.setString(16, workspaceId);
                ps.setString(17, projectId);
                String lang = entry.language();
                ps.setString(18, lang == null || lang.isBlank() ? "java" : lang);
                ps.setString(19, sourceHash);
                // Sprint 27 D2: embed on write. The vector is what the entry
                // MEANS, so it is derived from the same text a reader would see
                // (summary + details), not the summary alone - C0 measured a
                // summary-only index scoring true paraphrases down at noise
                // level. A null vector is a legitimate state: the embedder may
                // be absent or disabled, and the row must still land and stay
                // keyword-reachable. The backfill picks it up later.
                //
                // Sprint 28c: the SITUATION leads the document. It is bound to
                // column 22 three lines down, and until now that was the only
                // place it went — mandatory at the front door and absent from
                // the vector, so the anchorless question the store exists to
                // answer could not reach the field written to answer it.
                float[] vector = EmbeddingService.shared().embed(
                    EmbeddingService.documentOf(entry.situation(),
                                            str(factMap.get("summary")), str(entry.toMap().get("details"))));
                ps.setBytes(20, EmbeddingService.toBytes(vector));
                ps.setString(21, vector == null ? null : EmbeddingService.shared().identityKey());
                // Sprint 28c: nulls are the legacy shape and are meaningful — an
                // absent `form` is what distinguishes a pre-28c row from a
                // deliberately form-1 one, so these are set as given and never
                // defaulted on the way in.
                ps.setString(22, entry.situation());
                ps.setString(23, entry.verdict());
                ps.setString(24, entry.provenanceKind());
                setIntOrNull(ps, 25, entry.form());
                // Always NULL on the way in, and that is the semantics, not a
                // gap: evidence_dead means "a human has been told the code this
                // entry points at is gone", which cannot be true of an entry
                // being recorded for the first time. It is written later by
                // markEvidenceDead (an UPDATE, from ExperienceMaintenance.refresh)
                // and carried verbatim by export/import and orphan recovery — all
                // of which bind it from the stored row, never from a builder. A
                // setter here would be a door nothing in production walks through.
                ps.setNull(26, java.sql.Types.BOOLEAN);
                ps.executeUpdate();
            }
            insertSymptoms(id, entry.symptoms());
            insertLinks(id, entry.links());
            return id;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to put entry: " + e.getMessage(), e);
        }
    }

    /** An exported facet may arrive as a number or as its string form; absent stays absent. */
    private static Integer intOrNull(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Sprint 28c: bind a nullable Integer without collapsing null to 0.
     *
     * <p>{@code setInt} takes a primitive, so an unboxed null throws and an
     * eagerly-defaulted one writes a 0 that is indistinguishable from a real
     * value. {@code form = 0} and {@code form = null} mean different things —
     * a row deliberately marked legacy versus a row nobody has classified — so
     * the difference has to survive the write.</p>
     */
    private static void setIntOrNull(PreparedStatement ps, int index, Integer value)
            throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /**
     * Sprint 28c: set {@code evidence_dead} and nothing else — deliberately NOT
     * {@code status}. See {@link ExperienceStore#markEvidenceDead}.
     *
     * <p>The {@code IS DISTINCT FROM TRUE} guard makes it idempotent AND makes
     * the return value mean "newly marked", so the refresh report counts real
     * transitions rather than re-reporting the same dead anchor on every pass —
     * which is how an earlier auto-refresh grew the store file on every click.</p>
     */
    @Override
    public synchronized boolean markEvidenceDead(String id) {
        if (id == null) {
            return false;
        }
        try (PreparedStatement ps = live().prepareStatement(
                "UPDATE experience_entry SET evidence_dead = TRUE, updated_at = ?"
                + " WHERE id = ? AND evidence_dead IS DISTINCT FROM TRUE")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to mark evidence dead: " + e.getMessage(), e);
        }
    }

    /**
     * Sprint 28c D4 — the migration's write. See {@link ExperienceStore#setForm}.
     *
     * <p>{@code form IS NULL} in the WHERE clause is what makes the migration
     * safe to re-run and, more importantly, unable to overwrite: a row an author
     * formed at record time is never re-derived by a mechanical rule, and a
     * second migration run cannot revise the first one's output. The return
     * value therefore means "newly formed", so the disposition report counts
     * real transitions instead of re-counting rows it already did.</p>
     *
     * <p>{@code provenance_kind} becomes {@code migrated} — the fifth value of
     * the closed vocabulary, and the reason the vocabulary has one: a reader
     * asking "who decided this entry's situation?" must be able to tell an
     * author's declaration from a rule's derivation.</p>
     */
    @Override
    public synchronized boolean setForm(String id, String situation, String verdict) {
        if (id == null || situation == null || situation.isBlank()) {
            return false;
        }
        try (PreparedStatement ps = live().prepareStatement(
                "UPDATE experience_entry SET situation = ?, verdict = ?, form = 1,"
                + " provenance_kind = 'migrated', updated_at = ?"
                + " WHERE id = ? AND form IS NULL")) {
            ps.setString(1, situation);
            ps.setString(2, verdict);
            ps.setTimestamp(3, Timestamp.from(Instant.now()));
            ps.setString(4, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to set form: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized int deleteBySource(String sourceRef) {
        if (sourceRef == null) {
            return 0;
        }
        try {
            // Remove children first (no FK cascade in this embedded schema).
            try (PreparedStatement ps = live().prepareStatement(
                    "DELETE FROM experience_symptom WHERE entry_id IN"
                    + " (SELECT id FROM experience_entry WHERE source_ref = ?)")) {
                ps.setString(1, sourceRef);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = live().prepareStatement(
                    "DELETE FROM experience_link WHERE entry_id IN"
                    + " (SELECT id FROM experience_entry WHERE source_ref = ?)")) {
                ps.setString(1, sourceRef);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = live().prepareStatement(
                    "DELETE FROM experience_entry WHERE source_ref = ?")) {
                ps.setString(1, sourceRef);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to delete by source: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized long wipe() {
        try (Statement s = live().createStatement()) {
            long n = count();
            s.execute("DELETE FROM experience_symptom");
            s.execute("DELETE FROM experience_link");
            s.execute("DELETE FROM experience_entry");
            return n;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to wipe store: " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredEntry> all() {
        return withRead("list entries", c -> {
            List<StoredEntry> out = new ArrayList<>();
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery(
                        "SELECT " + ALL_COLUMNS + " FROM experience_entry")) {
                while (rs.next()) {
                    out.add(mapRow(rs, c));
                }
            }
            return out;
        });
    }

    @Override
    public synchronized boolean setStatus(String id, String status) {
        try (PreparedStatement ps = live().prepareStatement(
                "UPDATE experience_entry SET status = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, status);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to set status: " + e.getMessage(), e);
        }
    }

    /** Sprint 21e (item A): {@code symbol_fqn} ONLY — never package_name/source_hash/status. */
    @Override
    public synchronized boolean updateSymbolAnchor(String id, String symbolFqn) {
        try (PreparedStatement ps = live().prepareStatement(
                "UPDATE experience_entry SET symbol_fqn = ?, updated_at = ? WHERE id = ?")) {
            ps.setString(1, symbolFqn);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("failed to update symbol anchor: " + e.getMessage(), e);
        }
    }

    /** Symptoms are alias-normalized (lower/trim/collapse) so paraphrases index together. */
    private void insertSymptoms(String id, List<String> symptoms) throws SQLException {
        if (symptoms.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = live().prepareStatement(
                "MERGE INTO experience_symptom(entry_id, symptom) VALUES (?, ?)")) {
            for (String s : symptoms) {
                String norm = normalize(s);
                if (norm.isEmpty()) {
                    continue;
                }
                ps.setString(1, id);
                ps.setString(2, norm);
                ps.executeUpdate();
            }
        }
    }

    private void insertLinks(String id, List<ExperienceEntry.Link> links) throws SQLException {
        if (links.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = live().prepareStatement(
                "MERGE INTO experience_link(entry_id, rel, target) VALUES (?, ?, ?)")) {
            for (ExperienceEntry.Link l : links) {
                if (l.rel() == null || l.target() == null) {
                    continue;
                }
                ps.setString(1, id);
                ps.setString(2, l.rel());
                ps.setString(3, l.target());
                ps.executeUpdate();
            }
        }
    }

    /** Alias normalization: lowercased, trimmed, whitespace-collapsed. */
    static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> get(String id) {
        return withRead("get entry", c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("SELECT body_json FROM experience_entry WHERE id = ?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    try {
                        return Optional.of((Map<String, Object>)
                            json.readValue(rs.getString(1), LinkedHashMap.class));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IllegalStateException(
                            "failed to get entry: " + e.getMessage(), e);
                    }
                }
            }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<StoredEntry> query(RecallQuery q) {
        if (q == null || q.isEmpty()) {
            return List.of();
        }
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (q.hasSymbol()) {
            // entry symbol equals/encloses the cue, cue's package holds it, or entry symbol
            // is under the cue. Sprint 21e: '#' enclosure variants BOTH directions so a
            // type-level anchor matches a member cue and a member anchor matches its
            // type's cue (member notation never splits type-level matching).
            clauses.add("(symbol_fqn = ? OR ? LIKE symbol_fqn || '.%' OR ? LIKE symbol_fqn || '#%'"
                + " OR ? LIKE package_name || '.%'"
                + " OR symbol_fqn LIKE ? || '.%' OR symbol_fqn LIKE ? || '#%')");
            params.add(q.symbol());
            params.add(q.symbol());
            params.add(q.symbol());
            params.add(q.symbol());
            params.add(q.symbol());
            params.add(q.symbol());
        }
        if (q.hasPackage()) {
            clauses.add("(package_name = ? OR ? LIKE package_name || '.%' OR package_name LIKE ? || '.%'"
                + " OR symbol_fqn LIKE ? || '.%')");
            params.add(q.packageName());
            params.add(q.packageName());
            params.add(q.packageName());
            params.add(q.packageName());
        }
        if (q.hasOperation()) {
            clauses.add("operation = ?");
            params.add(q.operation());
        }
        if (q.hasExternalSystem()) {
            clauses.add("LOWER(external_system) = LOWER(?)");
            params.add(q.externalSystem());
        }
        if (q.hasSymptom()) {
            // v2.2.3: tokenized — each cue token must match a symptom OR the summary;
            // the old single-substring LIKE missed non-adjacent cue words.
            List<String> tokenClauses = new ArrayList<>();
            for (String token : normalize(q.symptom()).split("\\s+")) {
                if (token.isBlank()) {
                    continue;
                }
                tokenClauses.add("(id IN (SELECT entry_id FROM experience_symptom WHERE symptom LIKE ?)"
                    + " OR LOWER(summary) LIKE ?)");
                params.add("%" + token + "%");
                params.add("%" + token + "%");
            }
            if (!tokenClauses.isEmpty()) {
                clauses.add("(" + String.join(" AND ", tokenClauses) + ")");
            }
        }
        String sql = "SELECT " + ALL_COLUMNS + " FROM experience_entry WHERE ("
            + String.join(" OR ", clauses)
            + ") AND status NOT IN ('rejected', 'superseded') ORDER BY created_at DESC";

        List<StoredEntry> out = new ArrayList<>();
        return withRead("query entries", c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapRow(rs, c));
                    }
                }
            }
            return out;
        });
    }

    /** Map a row (selecting the projection columns below) to a {@link StoredEntry}. */
    @SuppressWarnings("unchecked")
    private StoredEntry mapRow(ResultSet rs, Connection c) throws SQLException {
        String id = rs.getString("id");
        Map<String, Object> body;
        try {
            body = json.readValue(rs.getString("body_json"), LinkedHashMap.class);
        } catch (Exception e) {
            body = new LinkedHashMap<>();
        }
        Timestamp ts = rs.getTimestamp("created_at");
        return new StoredEntry(
            id,
            rs.getString("type"),
            rs.getString("symbol_fqn"),
            rs.getString("package_name"),
            rs.getString("operation"),
            rs.getString("status"),
            rs.getString("confidence"),
            rs.getString("language"),
            rs.getString("external_system"),
            rs.getString("summary"),
            loadSymptoms(id, c),
            rs.getString("source_ref"),
            rs.getString("scope_kind"),
            rs.getString("workspace_id"),
            ts == null ? null : ts.toInstant(),
            body,
            facetsOf(rs));
    }

    /**
     * Sprint 28c: project the v10 spine onto the row.
     *
     * <p>Read defensively by NAME with a column-presence check rather than
     * assuming the rung has run: {@code mapRow} is reached from several
     * projections, and a caller that selects a narrower column list would
     * otherwise throw instead of simply having no facets. An absent column and
     * a null value mean the same thing here — unclassified.</p>
     */
    private static StoredEntry.Facets facetsOf(ResultSet rs) throws SQLException {
        java.sql.ResultSetMetaData md = rs.getMetaData();
        java.util.Set<String> cols = new java.util.HashSet<>();
        for (int i = 1; i <= md.getColumnCount(); i++) {
            cols.add(md.getColumnLabel(i).toLowerCase(java.util.Locale.ROOT));
        }
        if (!cols.contains("form")) {
            return StoredEntry.Facets.NONE;
        }
        Boolean dead = rs.getBoolean("evidence_dead");
        if (rs.wasNull()) {
            dead = null;
        }
        return new StoredEntry.Facets(
            rs.getString("situation"),
            rs.getString("verdict"),
            rs.getString("provenance_kind"),
            intOrNull(rs.getObject("form")),
            dead);
    }

    private List<String> loadSymptoms(String id, Connection c) throws SQLException {
        List<String> symptoms = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT symptom FROM experience_symptom WHERE entry_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    symptoms.add(rs.getString(1));
                }
            }
        }
        return symptoms;
    }

    /**
     * Sprint 27 D2: fetch entries by id, for the semantic nominator — it returns
     * ids and scores, and the fit rules need the rows themselves.
     *
     * <p>Order follows the ids given, because that order IS the ranking. Ids
     * that no longer exist are skipped silently: a nominator working from
     * vectors can legitimately name a row deleted since, and that is a smaller
     * set, never a wrong answer.</p>
     */
    @Override
    public List<StoredEntry> byIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        Map<String, StoredEntry> found = new LinkedHashMap<>();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        try {
            withRead("look entries up by id", c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT " + ALL_COLUMNS + " FROM experience_entry WHERE id IN ("
                            + placeholders + ")")) {
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setString(i + 1, ids.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            StoredEntry e = mapRow(rs, c);
                            found.put(e.id(), e);
                        }
                    }
                }
                return null;
            });
        } catch (RuntimeException e) {
            log.error("byIds lookup FAILED; semantic nomination contributes nothing"
                + " for this cue rather than a partial list", e);
            return List.of();
        }
        List<StoredEntry> ordered = new ArrayList<>();
        for (String id : ids) {
            StoredEntry e = found.get(id);
            if (e != null) {
                ordered.add(e);
            }
        }
        return ordered;
    }

    @Override
    public long count() {
        return withRead("count entries", c -> {
            try (Statement s = c.createStatement();
                    ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM experience_entry")) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    // --- Sprint 21a (item G): curation — export / import / list -------------------------

    /**
     * The export/import projection — the lossless round-trip contract.
     *
     * <p>This list, {@code importEntries}' bind list and {@code exportEntries}'
     * row assembly are ONE contract in three places. Widening any of them alone
     * silently drops a column on every round trip, and the identity test would
     * not catch it: it would still pass on legacy rows, whose new columns are
     * null either way, and fail only once a form-1 row existed to lose.</p>
     */
    private static final String ALL_COLUMNS =
        "id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
        + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at,"
        + "workspace_id,project_id,language,"
        // Sprint 28c (v10) — the knowledge-spine facets.
        + "situation,verdict,provenance_kind,"
        + "form,evidence_dead";

    @Override
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> exportEntries(String status, String type) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            clauses.add("status = ?");
            params.add(status);
        }
        if (type != null && !type.isBlank()) {
            clauses.add("type = ?");
            params.add(type);
        }
        String sql = "SELECT " + ALL_COLUMNS + " FROM experience_entry"
            + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
            + " ORDER BY created_at";
        List<Map<String, Object>> out = new ArrayList<>();
        return withRead("export entries", c -> {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    String id = rs.getString("id");
                    row.put("id", id);
                    for (String col : new String[] {"type", "scope_kind", "symbol_fqn",
                            "package_name", "operation", "status", "confidence", "fault_owner",
                            "external_system", "summary", "source_ref", "workspace_id",
                            "project_id", "language",
                            // Sprint 28c — read back as strings like every other
                            // text facet; importEntries parses the numeric one and
                            // the boolean on the way in.
                            "situation", "verdict",
                            "provenance_kind"}) {
                        Object v = rs.getString(col);
                        if (v != null) {
                            row.put(col, v);
                        }
                    }
                    // Nullable non-text facets: absent means absent. Writing a 0
                    // or a false here would turn "nobody classified this row"
                    // into "somebody classified it as legacy", which is a
                    // different claim and would survive the round trip as fact.
                    int form = rs.getInt("form");
                    if (!rs.wasNull()) {
                        row.put("form", form);
                    }
                    boolean evidenceDead = rs.getBoolean("evidence_dead");
                    if (!rs.wasNull()) {
                        row.put("evidence_dead", evidenceDead);
                    }
                    Timestamp created = rs.getTimestamp("created_at");
                    Timestamp updated = rs.getTimestamp("updated_at");
                    if (created != null) {
                        row.put("created_at", created.toInstant().toString());
                    }
                    if (updated != null) {
                        row.put("updated_at", updated.toInstant().toString());
                    }
                    try {
                        row.put("body", json.readValue(rs.getString("body_json"), LinkedHashMap.class));
                    } catch (Exception e) {
                        row.put("body", new LinkedHashMap<>());
                    }
                    List<String> symptoms = loadSymptoms(id, c);
                    if (!symptoms.isEmpty()) {
                        row.put("symptoms", symptoms);
                    }
                    List<Map<String, Object>> links = loadLinks(id, c);
                    if (!links.isEmpty()) {
                        row.put("links", links);
                    }
                    out.add(row);
                }
            }
        }
        return out;
        });
    }

    @Override
    public synchronized Map<String, Object> importEntries(List<Map<String, Object>> entries) {
        int imported = 0;
        int duplicates = 0;
        int invalid = 0;
        for (Map<String, Object> row : entries == null ? List.<Map<String, Object>>of() : entries) {
            String id = str(row.get("id"));
            if (id == null || id.isBlank()) {
                invalid++;
                continue;
            }
            if (idExists(id)) {
                duplicates++;
                continue;
            }
            try {
                String body;
                Object bodyObj = row.get("body");
                body = bodyObj == null ? "{}" : json.writeValueAsString(bodyObj);
                try (PreparedStatement ps = live().prepareStatement(
                        "INSERT INTO experience_entry (" + ALL_COLUMNS
                        // 23 placeholders — one per ALL_COLUMNS entry. Kept in
                        // step BY TEST, not by eye: the count is invisible to the
                        // compiler and a surplus throws only at import time.
                        + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
                        + "?,?,?,?,?)")) {
                    ps.setString(1, id);
                    ps.setString(2, str(row.get("type")));
                    ps.setString(3, str(row.get("scope_kind")));
                    ps.setString(4, str(row.get("symbol_fqn")));
                    ps.setString(5, str(row.get("package_name")));
                    ps.setString(6, str(row.get("operation")));
                    ps.setString(7, row.get("status") == null ? ExperienceEntry.CANDIDATE : str(row.get("status")));
                    ps.setString(8, str(row.get("confidence")));
                    ps.setString(9, str(row.get("fault_owner")));
                    ps.setString(10, str(row.get("external_system")));
                    ps.setString(11, str(row.get("summary")));
                    ps.setString(12, str(row.get("source_ref")));
                    ps.setString(13, body);
                    ps.setTimestamp(14, parseInstant(row.get("created_at")));
                    ps.setTimestamp(15, parseInstant(row.get("updated_at")));
                    ps.setString(16, str(row.get("workspace_id")));
                    ps.setString(17, str(row.get("project_id")));
                    String lang = str(row.get("language"));
                    ps.setString(18, lang == null || lang.isBlank() ? "java" : lang);
                    // Sprint 28c: the facets, bound in the SAME edit as the
                    // export projection and the write path. An export that
                    // carries a column its import cannot bind loses that column
                    // silently on every round trip — the identity test would
                    // still pass on legacy rows and fail only on the new ones.
                    // An OLD export simply has no such keys, and reads as
                    // legacy: nulls throughout, form absent.
                    ps.setString(19, str(row.get("situation")));
                    ps.setString(20, str(row.get("verdict")));
                    ps.setString(21, str(row.get("provenance_kind")));
                    setIntOrNull(ps, 22, intOrNull(row.get("form")));
                    if (row.get("evidence_dead") == null) {
                        ps.setNull(23, java.sql.Types.BOOLEAN);
                    } else {
                        ps.setBoolean(23, Boolean.parseBoolean(String.valueOf(row.get("evidence_dead"))));
                    }
                    ps.executeUpdate();
                }
                if (row.get("symptoms") instanceof List<?> symptoms) {
                    List<String> ss = new ArrayList<>();
                    for (Object s : symptoms) {
                        ss.add(String.valueOf(s));
                    }
                    insertSymptoms(id, ss);
                }
                if (row.get("links") instanceof List<?> links) {
                    List<ExperienceEntry.Link> ls = new ArrayList<>();
                    for (Object l : links) {
                        if (l instanceof Map<?, ?> lm) {
                            ls.add(new ExperienceEntry.Link(
                                str(lm.get("rel")), str(lm.get("target"))));
                        }
                    }
                    insertLinks(id, ls);
                }
                imported++;
            } catch (Exception e) {
                log.warn("import: cannot insert {}: {}", id, e.getMessage());
                invalid++;
            }
        }
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("imported", imported);
        report.put("duplicates", duplicates);
        report.put("invalid", invalid);
        return report;
    }

    @Override
    public List<StoredEntry> listEntries(String type, String status, String scope,
            String language, int limit) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (type != null && !type.isBlank()) {
            clauses.add("type = ?");
            params.add(type);
        }
        if (status != null && !status.isBlank()) {
            clauses.add("status = ?");
            params.add(status);
        }
        if (scope != null && !scope.isBlank()) {
            clauses.add("(symbol_fqn = ? OR symbol_fqn LIKE ? OR package_name = ? OR package_name LIKE ?)");
            params.add(scope);
            params.add(scope + ".%");
            params.add(scope);
            params.add(scope + ".%");
        }
        if (language != null && !language.isBlank()) {
            clauses.add("LOWER(language) = LOWER(?)");
            params.add(language);
        }
        String sql = "SELECT " + ALL_COLUMNS + " FROM experience_entry"
            + (clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses))
            + " ORDER BY created_at DESC LIMIT " + Math.max(1, limit);
        List<StoredEntry> out = new ArrayList<>();
        return withRead("list entries", c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(mapRow(rs, c));
                    }
                }
            }
            return out;
        });
    }

    // --- Sprint 21a (item G): hygiene — prune / compact ---------------------------------

    /**
     * The clock the prune cutoff is measured against.
     *
     * <p>Injectable because {@code Instant.now()} made
     * {@code pruneAged}'s boundary a race with the timestamps written moments
     * earlier by the same test — which reproduced as a Windows-only flake: the
     * SAME commit failed one run and passed the next. A fixed clock makes the
     * boundary exact rather than merely unlikely to be hit.</p>
     */
    private java.time.Clock clock = java.time.Clock.systemUTC();

    /**
     * Pin the clock this store measures age against.
     *
     * <p>A test seam, and deliberately explicit: a test that needs an exact age
     * boundary says so, instead of sleeping and hoping.</p>
     */
    public synchronized void useClock(java.time.Clock fixed) {
        this.clock = fixed == null ? java.time.Clock.systemUTC() : fixed;
    }

    @Override
    public synchronized int pruneAged(int days) {
        Timestamp cutoff =
            Timestamp.from(Instant.now(clock).minusSeconds(Math.max(0, days) * 86400L));
        try {
            for (String child : new String[] {"experience_symptom", "experience_link"}) {
                try (PreparedStatement ps = live().prepareStatement(
                        "DELETE FROM " + child + " WHERE entry_id IN (SELECT id FROM experience_entry"
                        + " WHERE status IN ('rejected','superseded') AND updated_at < ?)")) {
                    ps.setTimestamp(1, cutoff);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = live().prepareStatement(
                    "DELETE FROM experience_entry"
                    + " WHERE status IN ('rejected','superseded') AND updated_at < ?")) {
                ps.setTimestamp(1, cutoff);
                return ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to prune: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized Map<String, Object> compact() {
        Map<String, Object> report = new LinkedHashMap<>();
        if (storeFile == null) {
            report.put("compacted", false);
            report.put("reason", "in-memory store");
            return report;
        }
        long before = fileSize(storeFile);
        try {
            try (Statement s = live().createStatement()) {
                s.execute("SHUTDOWN COMPACT");       // closes the database (and this conn)
            }
            closeQuietly(conn);
            conn = openBound(url);                   // reopen on the compacted file
        } catch (SQLException e) {
            throw new IllegalStateException("failed to compact: " + e.getMessage(), e);
        }
        report.put("compacted", true);
        report.put("bytes_before", before);
        report.put("bytes_after", fileSize(storeFile));
        log.info("Experience store compacted: {}", report);
        return report;
    }

    private static long fileSize(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return -1L;
        }
    }

    @Override
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total", count());
        Map<String, Object> byStatus = withRead("group by status", c -> groupCount("status", c));
        Map<String, Object> byLanguage = withRead("group by language", c -> groupCount("language", c));
        out.put("by_status", byStatus);
        out.put("by_language", byLanguage);
        Map<String, Object> store = new LinkedHashMap<>();
        if (storeFile != null) {
            store.put("file", storeFile.toAbsolutePath().toString());
            store.put("bytes", fileSize(storeFile));
        } else {
            store.put("file", "in-memory");
        }
        out.put("store", store);
        return out;
    }

    private Map<String, Object> groupCount(String column, Connection c) {
        Map<String, Object> counts = new LinkedHashMap<>();
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(
                    "SELECT " + column + ", COUNT(*) FROM experience_entry GROUP BY " + column
                    + " ORDER BY " + column)) {
            while (rs.next()) {
                String key = rs.getString(1);
                counts.put(key == null ? "(none)" : key, rs.getLong(2));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed to count by " + column + ": " + e.getMessage(), e);
        }
        return counts;
    }

    private List<Map<String, Object>> loadLinks(String id, Connection c) throws SQLException {
        List<Map<String, Object>> links = new ArrayList<>();
        try (PreparedStatement ps = live().prepareStatement(
                "SELECT rel, target FROM experience_link WHERE entry_id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    links.add(Map.of("rel", rs.getString(1), "target", rs.getString(2)));
                }
            }
        }
        return links;
    }

    private static Timestamp parseInstant(Object iso) {
        if (iso == null) {
            return Timestamp.from(Instant.now());
        }
        try {
            return Timestamp.from(Instant.parse(String.valueOf(iso)));
        } catch (Exception e) {
            return Timestamp.from(Instant.now());
        }
    }

    // --- Sprint 21a (item A): one-time recovery of orphaned per-session stores ----------

    /**
     * Earlier releases opened the store inside the launcher's session-isolation dir —
     * orphaned on every redeploy and DELETED on clean shutdown. Scan the stable workspace
     * root's session subdirs (newest first) for {@code jawata-experience/experience.mv.db},
     * import their entries into THIS store (dedup by id; provenance stamped; language
     * backfilled), and mark each swept source with a {@code .jawata-recovered} file so the
     * sweep is idempotent. Sources are never deleted (never merge blindly — pruning the
     * files is a human/GC decision, not this sweep's).
     */
    public synchronized Map<String, Object> recoverOrphans(Path workspaceRoot) {
        Map<String, Object> report = new LinkedHashMap<>();
        List<Map<String, Object>> sources = new ArrayList<>();
        int imported = 0;
        int duplicates = 0;
        report.put("sources", sources);
        if (workspaceRoot == null || !Files.isDirectory(workspaceRoot)) {
            report.put("imported", 0);
            report.put("duplicates", 0);
            return report;
        }
        List<Path> candidates = new ArrayList<>();
        try (java.util.stream.Stream<Path> subdirs = Files.list(workspaceRoot)) {
            subdirs.filter(Files::isDirectory)
                .filter(d -> Files.isRegularFile(d.resolve("jawata-experience").resolve("experience.mv.db")))
                .sorted(Comparator.comparingLong(H2ExperienceStore::lastModified).reversed())
                .forEach(candidates::add);
        } catch (IOException e) {
            log.warn("recovery: cannot list {}: {}", workspaceRoot, e.getMessage());
        }
        for (Path sessionDir : candidates) {
            Path expDir = sessionDir.resolve("jawata-experience");
            Path db = expDir.resolve("experience.mv.db");
            Path marker = expDir.resolve(".jawata-recovered");
            if (Files.exists(marker)) {
                continue;
            }
            if (storeFile != null
                    && db.toAbsolutePath().normalize().equals(storeFile.toAbsolutePath().normalize())) {
                continue;          // never sweep ourselves
            }
            String name = sessionDir.getFileName().toString();
            String url = "jdbc:h2:file:" + expDir.resolve("experience").toAbsolutePath()
                + ";DB_CLOSE_ON_EXIT=FALSE;ACCESS_MODE_DATA=r";
            try {
                try (Connection orphan = openBound(url)) {
                    int version = SchemaMigrations.detectVersion(orphan);
                    if (version > SchemaMigrations.LATEST) {
                        log.warn("recovery: {} is schema v{} (> v{}) — skipped, not marked",
                            db, version, SchemaMigrations.LATEST);
                        sources.add(Map.of("source", name, "skipped", "newer schema v" + version));
                        continue;
                    }
                    int[] counts = importFrom(orphan, version);
                    imported += counts[0];
                    duplicates += counts[1];
                    sources.add(Map.of("source", name, "imported", counts[0], "duplicates", counts[1]));
                }
                Files.writeString(marker, Instant.now().toString());
            } catch (Exception e) {
                log.warn("recovery: cannot import {}: {}", db, e.getMessage());
                sources.add(Map.of("source", name, "error", String.valueOf(e.getMessage())));
            }
        }
        report.put("imported", imported);
        report.put("duplicates", duplicates);
        if (imported > 0 || !sources.isEmpty()) {
            log.info("Experience store recovery: {}", report);
        }
        return report;
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Copy every entry (+ symptoms + links) from an orphan store; dedup by id.
     *
     * <p>Sprint 28c: this is the FOURTH place the row shape is written out, after
     * {@code insert}, {@code ALL_COLUMNS} and {@code importEntries} — and the one
     * where dropping a column is not recoverable. {@code recoverOrphans} writes a
     * {@code .jawata-recovered} marker afterwards, so the source is never swept
     * again: whatever this method fails to carry is gone for good, with no error
     * and no second chance. It was missed when the other three were widened
     * together, which is exactly the failure the "widen them together" rule
     * exists to prevent.</p>
     *
     * @param version the orphan's schema version — v2 added the workspace/project
     *                /language facets, v10 the knowledge-spine columns. Reading a
     *                column an older orphan does not have is an error, so each
     *                group is selected only when its rung has run.
     */
    private int[] importFrom(Connection orphan, int version) throws SQLException {
        boolean hasFacets = version >= 2;
        boolean hasForm = version >= 10;
        String cols = "id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
            + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at"
            + (hasFacets ? ",workspace_id,project_id,language" : "")
            + (hasForm ? ",situation,verdict,provenance_kind,"
                + "form,evidence_dead" : "");
        int imported = 0;
        int duplicates = 0;
        try (Statement s = orphan.createStatement();
                ResultSet rs = s.executeQuery("SELECT " + cols + " FROM experience_entry")) {
            while (rs.next()) {
                String id = rs.getString("id");
                if (idExists(id)) {
                    duplicates++;
                    continue;
                }
                try (PreparedStatement ps = live().prepareStatement(
                        "INSERT INTO experience_entry"
                        + "(id,type,scope_kind,symbol_fqn,package_name,operation,status,confidence,"
                        + "fault_owner,external_system,summary,source_ref,body_json,created_at,updated_at,"
                        + "workspace_id,project_id,language,"
                        + "situation,verdict,provenance_kind,"
                        + "form,evidence_dead)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    for (int i = 1; i <= 13; i++) {
                        ps.setString(i, rs.getString(i));
                    }
                    ps.setTimestamp(14, rs.getTimestamp(14));
                    ps.setTimestamp(15, rs.getTimestamp(15));
                    String ws = hasFacets ? rs.getString("workspace_id") : null;
                    String proj = hasFacets ? rs.getString("project_id") : null;
                    String lang = hasFacets ? rs.getString("language") : null;
                    ps.setString(16, ws != null ? ws : workspaceId);
                    ps.setString(17, proj != null ? proj : projectId);
                    ps.setString(18, lang != null ? lang : "java");
                    // A pre-v10 orphan has no facets to carry, and NULL is the
                    // honest value: unclassified, never "classified as legacy".
                    ps.setString(19, hasForm ? rs.getString("situation") : null);
                    ps.setString(20, hasForm ? rs.getString("verdict") : null);
                    ps.setString(21, hasForm ? rs.getString("provenance_kind") : null);
                    setIntOrNull(ps, 22, hasForm ? intOrNull(rs.getObject("form")) : null);
                    if (hasForm) {
                        boolean dead = rs.getBoolean("evidence_dead");
                        if (rs.wasNull()) {
                            ps.setNull(23, java.sql.Types.BOOLEAN);
                        } else {
                            ps.setBoolean(23, dead);
                        }
                    } else {
                        ps.setNull(23, java.sql.Types.BOOLEAN);
                    }
                    ps.executeUpdate();
                }
                copyChildren(orphan, id);
                imported++;
            }
        }
        return new int[] {imported, duplicates};
    }

    /**
     * Copies an orphan's child rows.
     *
     * <p>Orphan recovery is where a dropped child row is PERMANENT: the sweep
     * marks the source {@code .jawata-recovered} and never reads it again. So
     * every child table an entry can own is copied here, and adding one without
     * adding it here loses it silently and irreversibly.</p>
     *
     * <p>The orphan connection is never migrated — it is read at whatever schema
     * version it was left at. Any table this method reads must therefore exist in
     * EVERY version an orphan can be, or the read must be gated on the source's
     * version; an ungated select throws, the throw is caught per-orphan, and the
     * whole recovery then reports zero imported.</p>
     *
     * @param orphan connection to the orphaned store being recovered
     * @param id     the entry whose children to copy
     */
    private void copyChildren(Connection orphan, String id)
            throws SQLException {
        try (PreparedStatement q = orphan.prepareStatement(
                "SELECT symptom FROM experience_symptom WHERE entry_id = ?")) {
            q.setString(1, id);
            try (ResultSet rs = q.executeQuery();
                    PreparedStatement ins = live().prepareStatement(
                        "MERGE INTO experience_symptom(entry_id, symptom) VALUES (?, ?)")) {
                while (rs.next()) {
                    ins.setString(1, id);
                    ins.setString(2, rs.getString(1));
                    ins.executeUpdate();
                }
            }
        }
        try (PreparedStatement q = orphan.prepareStatement(
                "SELECT rel, target FROM experience_link WHERE entry_id = ?")) {
            q.setString(1, id);
            try (ResultSet rs = q.executeQuery();
                    PreparedStatement ins = live().prepareStatement(
                        "MERGE INTO experience_link(entry_id, rel, target) VALUES (?, ?, ?)")) {
                while (rs.next()) {
                    ins.setString(1, id);
                    ins.setString(2, rs.getString(1));
                    ins.setString(3, rs.getString(2));
                    ins.executeUpdate();
                }
            }
        }
    }

    private boolean idExists(String id) {
        try (PreparedStatement ps = live().prepareStatement(
                "SELECT 1 FROM experience_entry WHERE id = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("failed id lookup: " + e.getMessage(), e);
        }
    }

    @Override
    public synchronized void close() {
        discardReadPool();
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException e) {
            log.warn("Error closing experience store: {}", e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static String firstPackage(Map<String, Object> map) {
        if (map.get("scope") instanceof Map<?, ?> scope
                && scope.get("packages") instanceof List<?> packages
                && !packages.isEmpty()) {
            return String.valueOf(packages.get(0));
        }
        return null;
    }
}
