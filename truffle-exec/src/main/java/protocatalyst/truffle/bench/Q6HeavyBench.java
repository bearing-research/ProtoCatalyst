package protocatalyst.truffle.bench;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.oracle.truffle.api.CallTarget;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import protocatalyst.truffle.slice.ColumnBatch;
import protocatalyst.truffle.slice.Q6SliceAst;

/**
 * Crossover experiment (see {@code docs/compiler/TRUFFLE_EXPLORATION.md} §4 Phase 2): the same Q6
 * filter, but a heavier <b>branchless</b> projection ({@code r = ep*d; r *= 2-d; r *= 1 + d*d}).
 *
 * <p>This is the scenario most favorable to vectorization in our model: {@code row} and {@code sel}
 * both compute the heavy kernel only for survivors, but {@code sel}'s loop is branchless/gather
 * (SIMD-friendly) while {@code row}'s is scalar inside the filter branch — so if selection-vector
 * vectorization ever overtakes fused row, it should be here. Naive {@code batch} should fare *worst*:
 * it computes the heavy kernel for ALL rows and gathers afterward, wasting work on filtered-out rows.
 *
 * <p>Run: {@code sbt 'truffleExec/Jmh/run -f2 -wi5 -i8 -t1 Q6HeavyBench'}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 8, time = 1)
@Fork(2)
public class Q6HeavyBench {

    @Param({"65536"})
    public int rows;

    /** {@code selective} ≈ 13% pass (filter-dominated); {@code allpass} ≈ 100% (projection-dominated). */
    @Param({"selective", "allpass"})
    public String dataset;

    private ColumnBatch batch;
    private CallTarget rowTarget;
    private CallTarget batchTarget;
    private CallTarget selTarget;
    private double[] out;
    private double[] ep;
    private double[] disc;
    private double[] qty;

    @Setup(Level.Trial)
    public void setup() {
        batch = "allpass".equals(dataset) ? Q6SliceAst.syntheticAllPass(rows) : Q6SliceAst.synthetic(rows);
        rowTarget = Q6SliceAst.rowAstHeavy().getCallTarget();
        batchTarget = Q6SliceAst.batchAstHeavy().getCallTarget();
        selTarget = Q6SliceAst.selAstHeavy().getCallTarget();
        out = new double[rows];
        ep = batch.cols[Q6SliceAst.EXTENDEDPRICE];
        disc = batch.cols[Q6SliceAst.DISCOUNT];
        qty = batch.cols[Q6SliceAst.QUANTITY];
        assertParity();
    }

    /** Guard: all four implementations must produce identical results before we trust timings. */
    private void assertParity() {
        double[] r = new double[rows];
        double[] b = new double[rows];
        double[] s = new double[rows];
        double[] j = new double[rows];
        int rk = (int) rowTarget.call(batch, r);
        int bk = (int) batchTarget.call(batch, b);
        int sk = (int) selTarget.call(batch, s);
        int jk = rawHeavy(j);
        if (rk != bk || rk != sk || rk != jk
                || !Arrays.equals(Arrays.copyOf(r, rk), Arrays.copyOf(b, bk))
                || !Arrays.equals(Arrays.copyOf(r, rk), Arrays.copyOf(s, sk))
                || !Arrays.equals(Arrays.copyOf(r, rk), Arrays.copyOf(j, jk))) {
            throw new AssertionError("heavy-projection shapes disagree");
        }
    }

    @Benchmark
    public int row() {
        return (int) rowTarget.call(batch, out);
    }

    @Benchmark
    public int batch() {
        return (int) batchTarget.call(batch, out);
    }

    @Benchmark
    public int sel() {
        return (int) selTarget.call(batch, out);
    }

    @Benchmark
    public int rawJava() {
        return rawHeavy(out);
    }

    /** The fused heavy loop a code generator would emit. */
    private int rawHeavy(double[] dst) {
        int n = rows;
        int k = 0;
        for (int i = 0; i < n; i++) {
            double d = disc[i];
            if (d >= 0.05 && d <= 0.07 && qty[i] < 24.0) {
                double v = ep[i] * d;
                v *= (2.0 - d);
                v *= (1.0 + d * d);
                dst[k++] = v;
            }
        }
        return k;
    }
}
