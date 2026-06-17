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

    // Column ordinals in the synthetic batch.
    private static final int EXTENDEDPRICE = 0;
    private static final int DISCOUNT = 1;
    private static final int QUANTITY = 2;

    public static void main(String[] args) {
        System.out.println("Truffle runtime : " + Truffle.getRuntime().getName());

        int rows = 4096;
        ColumnBatch batch = syntheticBatch(rows);

        CallTarget rowTarget = buildRowAst().getCallTarget();
        CallTarget batchTarget = buildBatchAst().getCallTarget();

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

    /** {@code discount >= .05 AND discount <= .07 AND quantity < 24}, project {@code ep * discount}. */
    private static RowFilterProjectRoot buildRowAst() {
        RowPredicate predicate =
                new RowAnd(
                        new RowAnd(
                                new RowCompareConst(new RowColumn(DISCOUNT), Cmp.GE, 0.05),
                                new RowCompareConst(new RowColumn(DISCOUNT), Cmp.LE, 0.07)),
                        new RowCompareConst(new RowColumn(QUANTITY), Cmp.LT, 24.0));
        RowExpr projection = new RowMultiply(new RowColumn(EXTENDEDPRICE), new RowColumn(DISCOUNT));
        return new RowFilterProjectRoot(null, predicate, projection);
    }

    private static BatchFilterProjectRoot buildBatchAst() {
        BatchPredicate predicate =
                new BatchAnd(
                        new BatchAnd(
                                new BatchCompareConst(new BatchColumn(DISCOUNT), Cmp.GE, 0.05),
                                new BatchCompareConst(new BatchColumn(DISCOUNT), Cmp.LE, 0.07)),
                        new BatchCompareConst(new BatchColumn(QUANTITY), Cmp.LT, 24.0));
        BatchExpr projection =
                new BatchMultiply(new BatchColumn(EXTENDEDPRICE), new BatchColumn(DISCOUNT));
        return new BatchFilterProjectRoot(null, predicate, projection);
    }

    /** Deterministic data so row/batch parity and reruns are reproducible (no RNG). */
    private static ColumnBatch syntheticBatch(int rows) {
        double[] extendedprice = new double[rows];
        double[] discount = new double[rows];
        double[] quantity = new double[rows];
        for (int i = 0; i < rows; i++) {
            extendedprice[i] = 1000.0 + (i % 9000);
            discount[i] = (i % 11) * 0.01; // 0.00 .. 0.10 — includes .05/.06/.07
            quantity[i] = i % 50; // 0 .. 49 — < 24 selects ~half
        }
        double[][] cols = new double[3][];
        cols[EXTENDEDPRICE] = extendedprice;
        cols[DISCOUNT] = discount;
        cols[QUANTITY] = quantity;
        return new ColumnBatch(cols, rows);
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
