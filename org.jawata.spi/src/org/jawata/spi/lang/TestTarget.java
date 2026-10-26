package org.jawata.spi.lang;

/**
 * A test the person pointed at (Sprint 28k, Stage 9): the class that holds it and, when they pointed at one test inside the class, its name.
 *
 * @param typeName   the class's fully qualified name
 * @param methodName the test's name, or null when the position named only the class
 */
public record TestTarget(String typeName, String methodName) {
}
