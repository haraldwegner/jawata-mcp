package org.jawata.mcp.embed;

import java.util.Arrays;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * The SIMD {@link MatMul}, built on the incubating Vector API.
 *
 * <p>Incubating is the whole difficulty: the module is only present when the
 * JVM was launched with {@code --add-modules jdk.incubator.vector}, and a class
 * referencing it fails to LINK otherwise. So nothing may reference this class
 * directly — {@link MatMuls} loads it reflectively and falls back to
 * {@link ScalarMatMul} when linkage fails. A flagless run is therefore slower
 * and completely correct, never broken.</p>
 *
 * <p>Same i-k-j order as the scalar backend, with the inner accumulation over
 * {@code j} done a vector lane at a time and a scalar tail for the remainder.</p>
 */
public final class VectorApiMatMul implements MatMul {

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    @Override
    public void multiply(float[] a, float[] b, float[] c, int m, int k, int n) {
        MatMul.checkShapes(a, b, c, m, k, n);
        Arrays.fill(c, 0, m * n, 0f);
        final int upper = SPECIES.loopBound(n);
        for (int i = 0; i < m; i++) {
            final int aRow = i * k;
            final int cRow = i * n;
            for (int p = 0; p < k; p++) {
                final float av = a[aRow + p];
                if (av == 0f) {
                    continue;
                }
                final int bRow = p * n;
                final FloatVector va = FloatVector.broadcast(SPECIES, av);
                int j = 0;
                for (; j < upper; j += SPECIES.length()) {
                    FloatVector vb = FloatVector.fromArray(SPECIES, b, bRow + j);
                    FloatVector vc = FloatVector.fromArray(SPECIES, c, cRow + j);
                    vb.fma(va, vc).intoArray(c, cRow + j);
                }
                for (; j < n; j++) {
                    c[cRow + j] += av * b[bRow + j];
                }
            }
        }
    }

    @Override
    public String name() {
        return "vector-api(" + SPECIES.length() + " lanes)";
    }
}
