The shape of jawata's own *.tests bundles: sources flat under src/, no pom, no
.classpath, not Gradle, not Bazel. Rules 1 and 2 are silent; only the CONTENT —
classes importing org.junit — says this is test code. (The live-workspace form
of this assertion is C3's scope=main/test tool call against the real repo.)
