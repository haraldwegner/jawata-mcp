package com.example.undeclared;

/**
 * A record in a project that declares NO build file, which is the whole point of it.
 *
 * <p>An undeclared project inherits the workspace's compiler level, and JDT's built-in
 * level predates records — so a method whose signature names this type fails to resolve
 * until the level is raised. That is jawata-mcp#69, reported from macOS for three
 * releases, and {@code BuildSystemLoadTest} reproduces it here on every platform.
 */
public record Vehicle(String make, int year) {
}
