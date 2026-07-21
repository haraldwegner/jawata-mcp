package org.jawata.mcp.embed;

import java.util.Arrays;

/**
 * The always-available {@link MatMul}: plain Java, no flags, no incubating
 * modules, correct everywhere. It is both the fallback and the reference the
 * other backends are checked against.
 *
 * <p>The loop order is i-k-j rather than the textbook i-j-k. Both compute the
 * same result, but i-k-j walks {@code b} and {@code c} along contiguous rows in
 * the inner loop, which is cache-friendly and lets the JIT auto-vectorise the
 * inner accumulation — most of the achievable speed, with none of the Vector
 * API's launch-flag cost.</p>
 */
public final class ScalarMatMul implements MatMul {

    @Override
    public void multiply(float[] a, float[] b, float[] c, int m, int k, int n) {
        MatMul.checkShapes(a, b, c, m, k, n);
        Arrays.fill(c, 0, m * n, 0f);
        for (int i = 0; i < m; i++) {
            final int aRow = i * k;
            final int cRow = i * n;
            for (int p = 0; p < k; p++) {
                final float av = a[aRow + p];
                if (av == 0f) {
                    continue;
                }
                final int bRow = p * n;
                for (int j = 0; j < n; j++) {
                    c[cRow + j] += av * b[bRow + j];
                }
            }
        }
    }

    @Override
    public String name() {
        return "scalar";
    }
}
