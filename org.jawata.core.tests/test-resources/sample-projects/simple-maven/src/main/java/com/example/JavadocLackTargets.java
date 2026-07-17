package com.example;

/**
 * Sprint 25 javadoc_lack fixture — EXACTLY 15 findings expected:
 * the seed ctor (non-trivial), undocumentedField, firstFragment,
 * secondFragment, undocumentedMethod, protectedUndoc, issue,
 * DocumentedNested#nestedUndoc, NestedApi (type),
 * NestedApi#implicitlyPublic, NestedApi.ImplicitlyPublicNested (type),
 * NestedApi.ImplicitlyPublicNested#hidden, Marker (type), Marker#value,
 * Level#HIGH. Everything else is documented, non-API, inherited-doc, a
 * trivial accessor, or a trivial (empty/delegating) constructor.
 */
public class JavadocLackTargets {

    /** Documented field — no finding. */
    public int documentedField;

    public int undocumentedField;

    public int firstFragment, secondFragment;

    int packageField;

    public JavadocLackTargets() {
    }

    public JavadocLackTargets(int seed) {
        this.undocumentedField = seed;
        this.packageField = seed;
    }

    /** Documented method — no finding. */
    public void documentedMethod() {
    }

    public void undocumentedMethod() {
    }

    protected void protectedUndoc() {
    }

    private void privateUndoc() {
    }

    public void issue() {
        undocumentedField++;
    }

    public int getUndocumentedField() {
        return undocumentedField;
    }

    public void setUndocumentedField(int value) {
        this.undocumentedField = value;
    }

    @Override
    public String toString() {
        return "JavadocLackTargets[" + undocumentedField + "]";
    }

    /** Documented nested class — the type itself is fine. */
    public static class DocumentedNested {

        public void nestedUndoc() {
        }
    }

    static class PackageNested {

        public void notApiBecauseChainIsNot() {
        }
    }

    public interface NestedApi {

        void implicitlyPublic();

        class ImplicitlyPublicNested {

            public void hidden() {
            }
        }
    }

    public @interface Marker {

        String value();
    }

    /** Documented enum — the type itself is fine. */
    public enum Level {
        HIGH,
        /** Documented constant — no finding. */
        LOW
    }
}
