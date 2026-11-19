package org.jawata.mcp.tools.fqn;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.eclipse.jdt.core.IType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 27, finding 28h-C27-16: a type name that two loaded projects both hold, one as source and one as a class in a jar of the engine's own
 * (the Eclipse bundle ships one), resolves to the source, whichever project is asked first. A stub has no bodies and cannot be a target.
 */
class FqnResolverPrefersSourceTest {

    private static IType type(boolean binary) {
        return (IType) Proxy.newProxyInstance(IType.class.getClassLoader(), new Class<?>[] {IType.class}, (proxy, method, args) -> switch (method.getName()) {
            case "isBinary" -> binary;
            case "exists" -> true;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            case "toString" -> binary ? "binary" : "source";
            default -> null;
        });
    }

    @Test
    @DisplayName("the source type is chosen over a binary one whichever came first, a binary one only when no project has the source, and none when none was found")
    void theSourceIsChosenOverTheJarsStub() {
        IType stub = type(true);
        IType source = type(false);
        IType otherSource = type(false);

        assertAll(
            () -> assertSame(source, FqnResolver.preferringSource(List.of(stub, source)).orElseThrow(), "the stub came first and was chosen"),
            () -> assertSame(source, FqnResolver.preferringSource(List.of(source, stub)).orElseThrow()),
            () -> assertSame(source, FqnResolver.preferringSource(List.of(source, otherSource)).orElseThrow(), "of two sources the first is kept"),
            () -> assertSame(stub, FqnResolver.preferringSource(List.of(stub)).orElseThrow(), "a type only a jar holds is still found"),
            () -> assertTrue(FqnResolver.preferringSource(List.of()).isEmpty(), "a name nobody holds was found"));
    }

    private static org.eclipse.jdt.core.IJavaProject holding(IType held) {
        return (org.eclipse.jdt.core.IJavaProject) Proxy.newProxyInstance(IType.class.getClassLoader(),
            new Class<?>[] {org.eclipse.jdt.core.IJavaProject.class}, (proxy, method, args) -> switch (method.getName()) {
                case "findType" -> held;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    @Test
    @DisplayName("C27 audit: the resolver's own entry point, not only its helper, takes the source of a type two projects hold when the project with the jar's stub is listed first")
    void theResolverTakesTheSourceWhenTheStubsProjectIsFirst() {
        IType stub = type(true);
        IType source = type(false);

        assertSame(source, FqnResolver.resolveType("org.demo.Observer", List.of(holding(stub), holding(source))).orElseThrow(),
            "the project that holds only the jar's stub was asked first and answered");
    }
}
