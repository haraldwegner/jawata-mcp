package com.example.ns;

/**
 * Lives in a source folder named {@code test/} — the Eclipse plug-in layout,
 * which matches none of the Maven/Gradle path conventions.
 */
public class GreeterTest {

    public void testGreet() {
        Greeter greeter = new Greeter();
        String actual = greeter.greet("world");
        if (!"hello world".equals(actual)) {
            throw new AssertionError(actual);
        }
    }
}
