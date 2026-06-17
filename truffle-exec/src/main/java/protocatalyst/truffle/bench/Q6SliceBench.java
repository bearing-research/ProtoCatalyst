package protocatalyst.truffle.bench;

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
 * Phase-2 measurement (see {@code docs/compiler/TRUFFLE_EXPLORATION.md} §4 Phase 2) — the core
 * research question, isolated: <b>row-at-a-time vs batch-over-columns Truffle, over the same Q6
 * filter→project</b>, with a hand-written fused Java loop as the WSCG-proxy ceiling.
 *
 * <ul>
 *   <li>{@code row}     — the canonical Truffle shape (per-row node dispatch).</li>
 *   <li>{@code batch}   — the vectorized shape (whole-column node dispatch) — the contribution.</li>
 *   <li>{@code rawJava} — the fused loop a code generator (WSCG) would emit; the ceiling both shapes
 *       are measured against. Truffle's published "1.5–3× of hand-tuned" predicts how far {@code row}
 *       sits below this; whether {@code batch} closes that gap is the open question.</li>
 * </ul>
 *
 * Run: {@code sbt 'truffleExec/Jmh/run -f1 -wi 3 -i 5 -t1 Q6SliceBench'}. The Scala-interpreter
 * baseline (the Phase-2 go/no-go reference) is added once the ProtoPhysicalPlan→AST builder lands.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class Q6SliceBench {

    @Param({"4096", "65536"})
    public int rows;

    private ColumnBatch batch;
    private CallTarget rowTarget;
    private CallTarget batchTarget;
    private double[] out;
    private double[] ep;
    private double[] disc;
    private double[] qty;

    @Setup(Level.Trial)
    public void setup() {
        batch = Q6SliceAst.synthetic(rows);
        rowTarget = Q6SliceAst.rowAst().getCallTarget();
        batchTarget = Q6SliceAst.batchAst().getCallTarget();
        out = new double[rows];
        ep = batch.cols[Q6SliceAst.EXTENDEDPRICE];
        disc = batch.cols[Q6SliceAst.DISCOUNT];
        qty = batch.cols[Q6SliceAst.QUANTITY];
    }

    @Benchmark
    public int row() {
        return (int) rowTarget.call(batch, out);
    }

    @Benchmark
    public int batch() {
        return (int) batchTarget.call(batch, out);
    }

    /** The fused loop WSCG would emit, hand-written. The ceiling row/batch are compared against. */
    @Benchmark
    public int rawJava() {
        int n = rows;
        int k = 0;
        for (int i = 0; i < n; i++) {
            double d = disc[i];
            if (d >= 0.05 && d <= 0.07 && qty[i] < 24.0) {
                out[k++] = ep[i] * d;
            }
        }
        return k;
    }
}
