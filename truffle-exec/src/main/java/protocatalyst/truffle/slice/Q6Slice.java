package protocatalyst.truffle.slice;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;

/**
 * Phase-1 vertical slice (see {@code docs/compiler/TRUFFLE_EXPLORATION.md} §4 Phase 1).
 *
 * <p>Builds the TPC-H Q6-shaped filter→project — {@code WHERE discount >= .05 AND discount <= .07 AND
 * quantity < 24}, projecting {@code extendedprice * discount} — as BOTH a row-at-a-time AST and a
 * batch-over-columns AST, runs them over the same synthetic data, and asserts they agree row-for-row.
 * It then warms both call targets enough to trigger Graal partial evaluation.
 *
 * <p>This proves the two node shapes (a) execute correctly, (b) produce identical results, and (c)
 * partial-evaluate — the prerequisite for the Phase-2 benchmark that actually measures row-vs-batch
 * vs the Scala interpreter. Run: {@code sbt truffleExec/runMain protocatalyst.truffle.slice.Q6Slice}.
 * Add {@code -Dpolyglot.engine.TraceCompilation=true} to watch both roots compile.
 */
public final class Q6Slice {

    public static void main(String[] args) {
        System.out.println("Truffle runtime : " + Truffle.getRuntime().getName());

        int rows = 4096;
        ColumnBatch batch = Q6SliceAst.synthetic(rows);

        CallTarget rowTarget = Q6SliceAst.rowAst().getCallTarget();
        CallTarget batchTarget = Q6SliceAst.batchAst().getCallTarget();

        // Warm both targets past the compilation threshold so PE kicks in.
        double[] scratch = new double[rows];
        for (int iter = 0; iter < 20_000; iter++) {
            rowTarget.call(batch, scratch);
            batchTarget.call(batch, scratch);
        }

        double[] rowOut = new double[rows];
        int rowK = (int) rowTarget.call(batch, rowOut);
        double rowSum = sum(rowOut, rowK);

        double[] batchOut = new double[rows];
        int batchK = (int) batchTarget.call(batch, batchOut);
        double batchSum = sum(batchOut, batchK);

        System.out.println("rows in         : " + rows);
        System.out.println("row   matches   : " + rowK + "  sum(ep*disc) = " + rowSum);
        System.out.println("batch matches   : " + batchK + "  sum(ep*disc) = " + batchSum);

        boolean agree = rowK == batchK && rowSum == batchSum && resultsEqual(rowOut, batchOut, rowK);
        System.out.println("PARITY          : " + (agree ? "OK (row == batch)" : "MISMATCH"));
        if (!agree) {
            throw new AssertionError("row and batch shapes disagree");
        }
    }

    private static double sum(double[] values, int count) {
        double total = 0;
        for (int i = 0; i < count; i++) {
            total += values[i];
        }
        return total;
    }

    private static boolean resultsEqual(double[] a, double[] b, int count) {
        for (int i = 0; i < count; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }
}
