package org.jawata.mcp.contract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.standin.FakeBuildVerdict;
import org.jawata.standin.FakeSymbolAnchors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.io.TempDir;

/**
 * The language-side contracts (Sprint 28k, Stage 8; contract §18 ring 1) run against the stand-in language, which imports no Java
 * tooling. The Java provider runs the same contracts in {@link JavaLanguageContractTest}; passing both is what makes a port a contract and
 * not a description of Java.
 */
class StandInLanguageContractTest {

    @Nested
    class Anchors extends SymbolAnchorsContract {
        @Override
        protected SymbolAnchors anchors() {
            return new FakeSymbolAnchors();
        }

        @Override
        protected Optional<String> resolvableSymbol() {
            return Optional.empty();
        }
    }

    @Nested
    class Paths extends ChangePathsContract {
        @Override
        protected org.jawata.spi.lang.ChangePaths paths() {
            return new org.jawata.standin.FakeChangePaths();
        }
    }

    /** The default every language starts from: no element of its own, the absolute path as it is. */
    @Nested
    class NoLanguagePaths extends ChangePathsContract {
        @Override
        protected org.jawata.spi.lang.ChangePaths paths() {
            return org.jawata.spi.lang.ChangePaths.ABSOLUTE;
        }
    }

    @Nested
    class Verdict extends BuildVerdictContract {
        @TempDir
        Path scratch;

        @Override
        protected BuildVerdict verdict() {
            return new FakeBuildVerdict();
        }

        @Override
        protected String fileThatBuilds() throws Exception {
            Path file = scratch.resolve("good.txt");
            Files.writeString(file, "nothing wrong with this");
            return file.toString();
        }

        @Override
        protected String fileThatDoesNotBuild() throws Exception {
            Path file = scratch.resolve("bad.txt");
            Files.writeString(file, "this one is " + FakeBuildVerdict.BROKEN);
            return file.toString();
        }
    }
}
