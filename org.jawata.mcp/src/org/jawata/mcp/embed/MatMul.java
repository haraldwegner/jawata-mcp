package org.jawata.mcp.embed;

/**
 * Sprint 27 D1 — the one seam every matrix multiplication in the embedder goes
 * through, so the arithmetic backend can change without the model code
 * knowing. The encoder is almost entirely this operation, so it is the only
 * place worth optimising and the only place worth isolating.
 *
 * <p>Two implementations ship: {@link ScalarMatMul}, which is always available
 * and always correct, and {@link VectorApiMatMul}, which uses the incubating
 * Vector API when the JVM was launched with it. A third (a host BLAS reached
 * through the FFM API) is deliberately NOT written unless the latency gate
 * fails — an unused optimisation is a maintenance cost with no payer.</p>
 *
 * <p>Backends must agree to within float rounding; a parity test pins that on
 * random matrices, because a backend that is fast and subtly wrong would
 * corrupt every vector in the store without ever failing loudly.</p>
 */
public interface MatMul {

    /**
     * {@code C = A · B}, all row-major, all dimensions exact.
     *
     * @param a input, {@code m × k}
     * @param b input, {@code k × n}
     * @param c output, {@code m × n} — fully overwritten, never accumulated
     */
    void multiply(float[] a, float[] b, float[] c, int m, int k, int n);

    /** Backend name, reported by {@code health_check} so the active path is never a guess. */
    String name();

    /** Validate shapes once, in one place, rather than in each backend. */
    static void checkShapes(float[] a, float[] b, float[] c, int m, int k, int n) {
        if (m < 0 || k < 0 || n < 0) {
            throw new IllegalArgumentException(
                "negative dimension: m=" + m + " k=" + k + " n=" + n);
        }
        if (a.length < m * k || b.length < k * n || c.length < m * n) {
            throw new IllegalArgumentException(
                "buffer too small for " + m + "x" + k + " * " + k + "x" + n
                + " (a=" + a.length + " b=" + b.length + " c=" + c.length + ")");
        }
    }
}
