package org.jawata.mcp.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

/**
 * Sprint 27 C1 — the {@link MatMul} backends must agree.
 *
 * <p>A fast-but-subtly-wrong backend is the dangerous failure here: it would
 * corrupt every vector written to the store while every other gate stayed
 * green. So the backends are compared on random matrices of awkward shapes,
 * including sizes that are NOT a multiple of the SIMD lane count (which is
 * where a mishandled loop tail shows up).</p>
 *
 * <p>When the JVM runs without {@code --add-modules jdk.incubator.vector} the
 * active backend IS the scalar one and this test compares it with itself —
 * still meaningful (it pins the degrade path), and the assertion on
 * {@link MatMuls#activeName()} records which path actually ran, so a silent
 * loss of SIMD is visible in the output rather than invisible.</p>
 */
class MatMulParityTest {

    /** Float accumulation reorders between backends; this is rounding, not drift. */
    private static final float EPS = 1e-4f;

    @Test
    void the_active_backend_agrees_with_scalar_on_random_matrices() {
        MatMul scalar = MatMuls.scalar();
        MatMul active = MatMuls.active();
        assertNotNull(active);
        Random rnd = new Random(27);

        // Shapes chosen to straddle SIMD lane boundaries (7, 13, 17, 31 are
        // prime; 384 and 1536 are the real model's dimensions).
        int[][] shapes = {
            {1, 1, 1}, {1, 7, 13}, {3, 17, 31}, {8, 8, 8},
            {2, 384, 384}, {1, 384, 1536}, {4, 1536, 384}, {5, 31, 7},
        };
        for (int[] s : shapes) {
            int m = s[0];
            int k = s[1];
            int n = s[2];
            float[] a = randomMatrix(rnd, m * k);
            float[] b = randomMatrix(rnd, k * n);
            float[] expected = new float[m * n];
            float[] actual = new float[m * n];

            scalar.multiply(a, b, expected, m, k, n);
            active.multiply(a, b, actual, m, k, n);

            for (int i = 0; i < expected.length; i++) {
                assertEquals(expected[i], actual[i], EPS,
                    "backend " + active.name() + " diverges at index " + i
                    + " for " + m + "x" + k + " * " + k + "x" + n);
            }
        }
        System.out.println("[MatMulParityTest] active backend = " + MatMuls.activeName());
    }

    @Test
    void a_known_product_is_computed_correctly() {
        // [1 2; 3 4] * [5 6; 7 8] = [19 22; 43 50] - a hand-checkable anchor, so
        // "both backends agree" can never mean "both are wrong the same way".
        float[] a = {1, 2, 3, 4};
        float[] b = {5, 6, 7, 8};
        for (MatMul mm : new MatMul[] {MatMuls.scalar(), MatMuls.active()}) {
            float[] c = new float[4];
            mm.multiply(a, b, c, 2, 2, 2);
            assertArrayEqualsWithin(new float[] {19, 22, 43, 50}, c, mm.name());
        }
    }

    @Test
    void the_output_buffer_is_overwritten_not_accumulated() {
        // A reused buffer is the norm in a batched encoder; silent accumulation
        // would produce garbage only on the second call.
        float[] a = {1, 1};
        float[] b = {2, 3};
        float[] c = {999, 999};
        MatMuls.active().multiply(a, b, c, 1, 2, 1);
        assertEquals(5f, c[0], EPS, "stale contents must not survive");
    }

    @Test
    void a_buffer_too_small_is_refused_rather_than_read_out_of_bounds() {
        float[] a = new float[4];
        float[] b = new float[4];
        float[] tooSmall = new float[1];
        assertThrows(IllegalArgumentException.class,
            () -> MatMuls.active().multiply(a, b, tooSmall, 2, 2, 2));
    }

    @Test
    void the_active_backend_names_itself_honestly() {
        String name = MatMuls.activeName();
        assertTrue(name.equals("scalar") || name.startsWith("vector-api"),
            "the reported backend must be one we actually ship, got: " + name);
    }

    private static float[] randomMatrix(Random rnd, int n) {
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = rnd.nextFloat() * 2f - 1f;
        }
        return out;
    }

    private static void assertArrayEqualsWithin(float[] expected, float[] actual, String who) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], EPS, who + " at " + i);
        }
    }
}
