package org.jawata.mcp.embed;

/**
 * Chooses the {@link MatMul} backend once, at class-init, and reports which one
 * won so the active path is an observable fact rather than an assumption
 * ({@code health_check} surfaces {@link #activeName()}).
 *
 * <p>{@link VectorApiMatMul} is instantiated REFLECTIVELY on purpose. It
 * references {@code jdk.incubator.vector}, which is absent unless the JVM was
 * launched with {@code --add-modules jdk.incubator.vector}; a direct reference
 * would make this class fail to link on a flagless JVM and take the whole
 * embedder down with it. Reflection turns that hard failure into the intended
 * silent degrade — the same answers, computed more slowly.</p>
 *
 * <p>{@link Throwable} is caught rather than {@link Exception} deliberately: a
 * missing module surfaces as {@link NoClassDefFoundError}, which is an Error,
 * and catching only Exception would miss precisely the case this exists for.</p>
 */
public final class MatMuls {

    private static final MatMul ACTIVE = select();

    private MatMuls() {
    }

    /** The best backend available in THIS JVM. */
    public static MatMul active() {
        return ACTIVE;
    }

    /** Name of the active backend, for honest capability reporting. */
    public static String activeName() {
        return ACTIVE.name();
    }

    /** The always-correct backend, for parity tests and forced-scalar paths. */
    public static MatMul scalar() {
        return new ScalarMatMul();
    }

    private static MatMul select() {
        try {
            Class<?> c = Class.forName("org.jawata.mcp.embed.VectorApiMatMul");
            MatMul candidate = (MatMul) c.getDeclaredConstructor().newInstance();
            // Exercise it once: loading the class can succeed while the first
            // real use fails, and a backend that breaks mid-corpus is worse
            // than one that never starts.
            float[] a = {1f, 2f};
            float[] b = {3f, 4f};
            float[] out = new float[1];
            candidate.multiply(a, b, out, 1, 2, 1);
            if (out[0] != 11f) {
                return new ScalarMatMul();
            }
            return candidate;
        } catch (Throwable t) {
            return new ScalarMatMul();
        }
    }
}
