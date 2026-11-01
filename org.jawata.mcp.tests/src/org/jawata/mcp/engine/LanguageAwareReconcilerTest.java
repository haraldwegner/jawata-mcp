package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jawata.mcp.contract.FakeHost;
import org.jawata.mcp.java.JavaProvider;
import org.jawata.spi.lang.LanguageProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h S14 (D15): the disk-sync scan's source suffixes come from the language providers. The host's reconciler is handed, at each
 * call, the union of what the languages bound now declare, and Java declares its own suffix; nothing in the scan names one.
 */
class LanguageAwareReconcilerTest {

    /** A language that declares only its suffixes; every other question is one nobody asks of it in these tests. */
    private static LanguageProvider declaring(String... suffixes) {
        return (LanguageProvider) Proxy.newProxyInstance(LanguageProvider.class.getClassLoader(), new Class<?>[] {LanguageProvider.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sourceExtensions")) {
                    return Set.of(suffixes);
                }
                throw new UnsupportedOperationException(method.getName());
            });
    }

    @Test
    @DisplayName("Java declares the suffix of its own source files, so the scan does not have to")
    void javaDeclaresTheSuffixOfItsOwnFiles() {
        assertEquals(Set.of(".java"), new JavaProvider().sourceExtensions());
    }

    @Test
    @DisplayName("the host is told the union of the suffixes the bound languages declare")
    void theHostIsToldTheUnionOfTheBoundLanguages() throws Exception {
        FakeHost.Counting host = new FakeHost.Counting();
        List<LanguageProvider> bound = List.of(new JavaProvider(), declaring(".cpp", ".h"));

        new LanguageAwareReconciler(host, () -> bound).syncBeforeCall();

        assertEquals(List.of(Set.of(".java", ".cpp", ".h")), host.extensionSets(), "every bound language's suffixes, and no suffix nobody declared");
    }

    @Test
    @DisplayName("with no language bound the host is told no suffix, so the scan looks at no file")
    void noLanguageBoundMeansTheHostIsToldNoSuffix() throws Exception {
        FakeHost.Counting host = new FakeHost.Counting();

        new LanguageAwareReconciler(host, List::of).syncBeforeCall();

        assertEquals(List.of(Set.<String>of()), host.extensionSets());
    }

    @Test
    @DisplayName("a language that binds after the engine started is asked on the next call")
    void aLanguageThatBindsLaterIsAskedOnTheNextCall() throws Exception {
        FakeHost.Counting host = new FakeHost.Counting();
        List<LanguageProvider> bound = new CopyOnWriteArrayList<>();
        LanguageAwareReconciler reconciler = new LanguageAwareReconciler(host, () -> bound);

        reconciler.syncBeforeCall();
        bound.add(declaring(".cpp"));
        reconciler.syncBeforeCall();

        assertEquals(List.of(Set.<String>of(), Set.of(".cpp")), host.extensionSets());
    }

    @Test
    @DisplayName("a caller that names the suffixes itself is passed through as it named them")
    void aCallerThatNamesTheSuffixesItselfIsPassedThrough() throws Exception {
        FakeHost.Counting host = new FakeHost.Counting();

        new LanguageAwareReconciler(host, () -> List.of(new JavaProvider())).syncBeforeCall(Set.of(".rs"));

        assertEquals(List.of(Set.of(".rs")), host.extensionSets());
    }

    /** A host that knows only the plain sync, as an Eclipse host does: it waits for its own build and ignores any suffix. */
    private static final class PlainSyncOnly implements org.jawata.spi.host.Reconciler {
        final org.jawata.spi.host.SyncReport answer = new org.jawata.spi.host.SyncReport(true, 0, 0, 0, 0, 7L, false);
        int plainSyncs;
        org.jawata.spi.host.BuildKind rebuiltAs;

        @Override
        public org.jawata.spi.host.SyncReport syncBeforeCall() {
            plainSyncs++;
            return answer;
        }

        @Override
        public Set<String> rebuild(org.jawata.spi.host.BuildScope scope, org.jawata.spi.host.BuildKind kind,
                org.eclipse.core.runtime.IProgressMonitor monitor) {
            rebuiltAs = kind;
            return Set.of("rebuilt");
        }
    }

    @Test
    @DisplayName("a host that implements only the plain sync is still synced through the wrapper, and its answer is the one that comes back")
    void aHostThatKnowsOnlyThePlainSyncIsStillSynced() throws Exception {
        PlainSyncOnly host = new PlainSyncOnly();

        org.jawata.spi.host.SyncReport told = new LanguageAwareReconciler(host, () -> List.of(new JavaProvider())).syncBeforeCall();

        assertEquals(1, host.plainSyncs, "the suffixes it was handed fell through the default to the plain form, which ran once");
        org.junit.jupiter.api.Assertions.assertSame(host.answer, told, "and what it answered is what the caller gets");
    }

    @Test
    @DisplayName("a rebuild is the host's: the wrapper hands it on as asked and returns what the host built")
    void aRebuildIsHandedToTheHost() throws Exception {
        PlainSyncOnly host = new PlainSyncOnly();

        Set<String> built = new LanguageAwareReconciler(host, List::of).rebuild(null, org.jawata.spi.host.BuildKind.FULL, null);

        assertEquals(Set.of("rebuilt"), built);
        assertEquals(org.jawata.spi.host.BuildKind.FULL, host.rebuiltAs);
    }
}
