`libexample.jar` stands in for Bazel's build output. It holds one class,
`com.example.lib.BazelLib`, source in `BazelLib.java.src` beside it. Rebuilt the
same way as `plain-eclipse/lib/example-lib.jar`.

This directory is also the OUTPUT-EXCLUSION trap: it deliberately contains a
`BUILD.bazel` and a `.java` under `java/com/example/`, so a scan that fails to
exclude `bazel-*` mounts generated output as source and produces duplicate types.
