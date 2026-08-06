Sprint 28 D-IMPORTER fixture: no pom.xml, no build.gradle, no MODULE.bazel,
no META-INF/MANIFEST.MF, no .classpath. detectBuildSystem must return UNKNOWN.
The point is the FALLBACK: an unrecognised project must still load its sources.
Loading empty while reporting success is the failure this fixture guards.
