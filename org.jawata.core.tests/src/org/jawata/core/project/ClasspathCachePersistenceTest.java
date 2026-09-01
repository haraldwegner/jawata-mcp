package org.jawata.core.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The classpath cache must outlive the process, and must NOT outlive the jars.
 *
 * <p>Both halves are the point. Persisting was the fix — a resident restart used
 * to re-run Maven for every project, and with all residents restarting together
 * on a release that took ten of twenty cores and made the machine unusable for
 * minutes. But the key is a hash of the POMs, and the poms say nothing about the
 * local repository the jars live in, so a persisted entry can survive a purge of
 * that repository and name jars that are gone. In memory that was impossible;
 * on disk it is the new failure mode, and it is the one that would silently
 * produce a classpath missing its dependencies.</p>
 */
class ClasspathCachePersistenceTest {

    @TempDir
    Path cacheDir;
    @TempDir
    Path repo;

    private String previousOverride;

    @BeforeEach
    void pointTheCacheAtATempDir() {
        previousOverride = System.getProperty("jawata.classpath.cache.dir");
        System.setProperty("jawata.classpath.cache.dir", cacheDir.toString());
        ClasspathCache.clearMemory();
    }

    @AfterEach
    void restore() {
        if (previousOverride == null) {
            System.clearProperty("jawata.classpath.cache.dir");
        } else {
            System.setProperty("jawata.classpath.cache.dir", previousOverride);
        }
        ClasspathCache.clearMemory();
    }

    private Path jar(String name) throws Exception {
        Path p = repo.resolve(name);
        Files.writeString(p, "not really a jar, but it EXISTS, which is what the cache checks");
        return p;
    }

    @Test
    @DisplayName("an entry survives the process — a cold memory tier is served from disk")
    void survivesTheProcess() throws Exception {
        List<String> jars = List.of(jar("a.jar").toString(), jar("b.jar").toString());
        ClasspathCache.put("key-survives", jars);

        // THE WHOLE POINT: this is what a restart looks like from the cache's
        // side. Without the disk tier the next line returns null and the caller
        // shells out to Maven — which is the defect this class was written for.
        ClasspathCache.clearMemory();

        List<String> served = ClasspathCache.get("key-survives");
        assertNotNull(served, "a restart must not lose the resolved classpath");
        assertEquals(jars, served);
    }

    @Test
    @DisplayName("a hit whose jar has gone is a MISS, and the stale entry is removed")
    void aVanishedJarIsAMiss() throws Exception {
        Path a = jar("present.jar");
        Path b = jar("about-to-vanish.jar");
        ClasspathCache.put("key-stale", List.of(a.toString(), b.toString()));
        ClasspathCache.clearMemory();

        // The local repository is purged/evicted. The POMs are untouched, so the
        // KEY is still perfectly valid — that is exactly why the hash alone
        // cannot decide this.
        Files.delete(b);

        assertNull(ClasspathCache.get("key-stale"),
            "an entry naming a jar that no longer exists must not be served — a classpath"
                + " silently missing a dependency is the failure this cache must never cause");
        assertTrue(Files.list(cacheDir).noneMatch(p -> p.getFileName().toString().startsWith("key-stale")),
            "and the stale entry is deleted rather than re-checked on every load");
    }

    @Test
    @DisplayName("the control: an entry whose jars are all present IS served, so the check is not just 'always miss'")
    void theControlServesAHealthyEntry() throws Exception {
        List<String> jars = List.of(jar("one.jar").toString(), jar("two.jar").toString());
        ClasspathCache.put("key-healthy", jars);
        ClasspathCache.clearMemory();

        // Without this, the previous test passes just as well against a cache
        // that never serves anything at all.
        assertEquals(jars, ClasspathCache.get("key-healthy"));
    }

    @Test
    @DisplayName("an empty classpath is a real answer and round-trips as one")
    void emptyIsAnAnswerNotAMiss() {
        // A pom with no dependencies resolves to zero jars. Treating that as a
        // miss would re-run Maven forever on exactly the cheapest projects.
        ClasspathCache.put("key-empty", List.of());
        ClasspathCache.clearMemory();

        List<String> served = ClasspathCache.get("key-empty");
        assertNotNull(served, "zero dependencies is an answer, not the absence of one");
        assertTrue(served.isEmpty());
    }

    @Test
    @DisplayName("a null key never hits and never throws")
    void nullKeyIsInert() {
        ClasspathCache.put(null, List.of("x"));
        assertNull(ClasspathCache.get(null),
            "an unhashable tree resolves fresh every time, which is the documented degradation");
    }
}
