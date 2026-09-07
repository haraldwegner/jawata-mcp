package com.example.xref;

/**
 * mcp#26 — this project USES {@link Declared} and does not declare it.
 *
 * <p>The reference is what puts {@code com.example.xref.Declared} on this project's classpath,
 * which is what makes the two projects distinguishable: one owns the compilation unit, the
 * other can only see it.</p>
 */
public class Consumer {

    public String borrow() {
        return new Declared().describe();
    }
}
