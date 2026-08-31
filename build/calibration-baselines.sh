#!/usr/bin/env bash
# calibration-baselines.sh — Sprint 28d Stage 11 (D9): the two third-party
# baselines, fetched and run WITHOUT touching our build or the developer's
# ~/.m2. Companion to calibration.sh, which measures our own detectors.
#
# THE PINS ARE THE VERSIONS AND CHECKSUMS BELOW. They are the reproducibility
# contract: a later run that produces different numbers must first show that
# these three sha256 values still match.
#
# Usage:  build/calibration-baselines.sh <scratch-dir>
# Exit:   0 = both tools ran · 2 = could not run at all
set -uo pipefail

SCRATCH="${1:?usage: calibration-baselines.sh <scratch-dir>}"
FORK="${JAWATA_FORK:-/home/harald/CursorProjects/java-design-patterns}"
T="$SCRATCH/calib-tools"
M="$SCRATCH/corpus-m2"          # ISOLATED local repository — never ~/.m2
mkdir -p "$T" "$M"

PMD_VER=7.27.0
PMD_SHA=4ae396ffaf2b0d3ef0b73a10b2925e77066f73d57a4ce9078c60e7302bcddec9
EP_VER=2.50.0
EP_SHA=8ec037a6d57c0d880ed78c6a67445e5018a17a89b42cb6847ddef9081c504378
# NOT bundled by error_prone_core-with-dependencies: without it a nullness
# check dies mid-analysis with NoClassDefFoundError on the relocated
# checkerframework dataflow classes. Its version is read from Error Prone's
# own parent pom (`dataflow.version`), never guessed.
DF_VER=3.41.0-eisop1
DF_SHA=10434fba4e53f55fa9c76904cde414b918932548c9dfc4e2d634ac05ff7a7d10

MODULES="command command-query-responsibility-segregation delegation dependency-injection mediator private-class-data singleton state strategy template-method type-object"

fetch() {   # fetch <url> <file> <expected-sha256>
    [ -f "$T/$2" ] || curl -sSL -o "$T/$2" "$1" || return 1
    local got; got="$(sha256sum "$T/$2" | cut -d' ' -f1)"
    [ "$got" = "$3" ] || { echo "PIN MISMATCH for $2: expected $3, got $got" >&2; return 1; }
    echo "  pinned $2"
}

echo "=== fetch the pinned tools ==="
fetch "https://github.com/pmd/pmd/releases/download/pmd_releases%2F$PMD_VER/pmd-dist-$PMD_VER-bin.zip" \
      "pmd-dist-$PMD_VER-bin.zip" "$PMD_SHA" || exit 2
fetch "https://repo1.maven.org/maven2/com/google/errorprone/error_prone_core/$EP_VER/error_prone_core-$EP_VER-with-dependencies.jar" \
      "error_prone_core-$EP_VER-with-dependencies.jar" "$EP_SHA" || exit 2
fetch "https://repo1.maven.org/maven2/io/github/eisop/dataflow-errorprone/$DF_VER/dataflow-errorprone-$DF_VER.jar" \
      "dataflow-errorprone-$DF_VER.jar" "$DF_SHA" || exit 2
[ -d "$T/pmd-bin-$PMD_VER" ] || ( cd "$T" && unzip -q "pmd-dist-$PMD_VER-bin.zip" )

# This sandbox's network policy allows repo.maven.apache.org and DENIES
# jitpack.io, which the corpus's build tries FIRST for every artifact — so each
# attempt HANGS rather than failing and the build wedges. Mirroring * to Central
# took the same build from wedged to BUILD SUCCESS in 12 seconds.
SETTINGS="$SCRATCH/central-only-settings.xml"
cat > "$SETTINGS" <<'XML'
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0">
  <mirrors><mirror>
    <id>central-only</id><name>everything resolves to Maven Central</name>
    <url>https://repo.maven.apache.org/maven2</url><mirrorOf>*</mirrorOf>
  </mirror></mirrors>
</settings>
XML

echo "=== PMD over the whole corpus (it parses source; no build needed) ==="
"$T/pmd-bin-$PMD_VER/bin/pmd" check -d "$FORK" -R rulesets/java/quickstart.xml \
    -f csv -r "$SCRATCH/pmd-all.csv" --no-cache --no-progress > "$SCRATCH/pmd-all.log" 2>&1
echo "  exit=$? (4 = violations found)  main-source findings: $(grep -c '/src/main/java/' "$SCRATCH/pmd-all.csv")"

echo "=== compile the labelled modules into the ISOLATED repo (Error Prone needs a compile) ==="
( cd "$FORK" && mvn -B -pl "${MODULES// /,}" compile -s "$SETTINGS" \
    -Dmaven.repo.local="$M" -DskipTests -Dmaven.test.skip=true \
    -Dmaven.wagon.http.connectionTimeout=15000 -Dmaven.wagon.http.readTimeout=30000 \
  ) > "$SCRATCH/corpus-build.log" 2>&1
echo "  exit=$?  $(grep -cE '^\[INFO\] BUILD SUCCESS' "$SCRATCH/corpus-build.log") success marker(s)"

# LOMBOK MUST BE ON THE PROCESSORPATH. Setting -processorpath at all overrides
# annotation-processor discovery, so with only Error Prone there Lombok never
# runs, every @Slf4j-generated LOGGER becomes "cannot find symbol", and the run
# exits 1 with ZERO warnings — a failure wearing a clean result's clothes.
LOMBOK="$(find "$M" -name 'lombok-*.jar' 2>/dev/null | head -1)"
EPPATH="$T/error_prone_core-$EP_VER-with-dependencies.jar:$T/dataflow-errorprone-$DF_VER.jar:$LOMBOK"

echo "=== Error Prone over the labelled modules ==="
: > "$SCRATCH/ep-findings.txt"
for m in $MODULES; do
    ( cd "$FORK" && mvn -q -B -pl "$m" dependency:build-classpath -s "$SETTINGS" \
        -Dmaven.repo.local="$M" -Dmdep.outputFile="$SCRATCH/$m.cp" ) > /dev/null 2>&1
    SRCS="$(find "$FORK/$m/src/main/java" -name '*.java' 2>/dev/null)"
    [ -n "$SRCS" ] || continue
    mkdir -p "$SCRATCH/ep/$m"
    # shellcheck disable=SC2086
    javac \
      -J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED \
      -J--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
      -J--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED \
      -J--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED \
      -J--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED \
      -J--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED \
      -J--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED \
      -J--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED \
      -J--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED \
      -J--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED \
      -XDcompilePolicy=simple -XDaddTypeAnnotationsToSymbol=true \
      --should-stop=ifError=FLOW \
      -processorpath "$EPPATH" '-Xplugin:ErrorProne -XepAllErrorsAsWarnings' \
      -cp "$(cat "$SCRATCH/$m.cp" 2>/dev/null)" -d "$SCRATCH/ep/$m" $SRCS \
      > "$SCRATCH/ep/$m.log" 2>&1
    RC=$?
    # A NON-ZERO exit with zero warnings is the Lombok signature above, and it
    # is called out rather than counted as a clean module.
    N=$(grep -cE 'warning: \[' "$SCRATCH/ep/$m.log")
    [ "$RC" -ne 0 ] && echo "  $m: javac exited $RC — NOT a clean result; read $SCRATCH/ep/$m.log"
    printf '  %-45s rc=%s warnings=%s\n' "$m" "$RC" "$N"
    grep -oE 'warning: \[[A-Za-z]+\]' "$SCRATCH/ep/$m.log" | sed "s|^|$m |" >> "$SCRATCH/ep-findings.txt"
done
echo "Error Prone total: $(wc -l < "$SCRATCH/ep-findings.txt")"
