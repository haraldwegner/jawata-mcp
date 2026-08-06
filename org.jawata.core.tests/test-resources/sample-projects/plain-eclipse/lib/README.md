`example-lib.jar` is the dependency the project's `.classpath` declares with
`kind="lib"`. It holds one class, `com.example.libs.EclipseLib`, whose source is
`EclipseLib.java.src` beside it (the `.src` suffix keeps it out of any source
scan). Rebuild with:

    javac --release 17 -d out EclipseLib.java.src   # rename to .java first
    (cd out && jar --create --file ../example-lib.jar com/example/libs/EclipseLib.class)

It exists so the test can assert the entry was HONOURED — the type resolves —
rather than that a path string was added to the classpath.
