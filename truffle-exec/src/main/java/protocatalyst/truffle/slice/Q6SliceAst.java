package protocatalyst.truffle.slice;

/**
 * Public factory for the Q6 slice ASTs and its synthetic data, so both the {@link Q6Slice} demo and
 * the JMH benchmark can build identical row/batch trees without exposing the package-private nodes.
 *
 * <p>The query is TPC-H Q6's filter→project core: {@code WHERE discount >= .05 AND discount <= .07
 * AND quantity < 24}, projecting {@code extendedprice * discount}.
 */
public final class Q6SliceAst {

    public static final int EXTENDEDPRICE = 0;
    public static final int DISCOUNT = 1;
    public static final int QUANTITY = 2;

    private Q6SliceAst() {}

    public static RowFilterProjectRoot rowAst() {
        RowPredicate predicate =
                new RowAnd(
                        new RowAnd(
                                new RowCompareConst(new RowColumn(DISCOUNT), Cmp.GE, 0.05),
                                new RowCompareConst(new RowColumn(DISCOUNT), Cmp.LE, 0.07)),
                        new RowCompareConst(new RowColumn(QUANTITY), Cmp.LT, 24.0));
        RowExpr projection = new RowMultiply(new RowColumn(EXTENDEDPRICE), new RowColumn(DISCOUNT));
        return new RowFilterProjectRoot(null, predicate, projection);
    }

    public static BatchFilterProjectRoot batchAst() {
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

    public static SelFilterProjectRoot selAst() {
        SelPredicate[] predicates = {
            new SelCompareConst(DISCOUNT, Cmp.GE, 0.05),
            new SelCompareConst(DISCOUNT, Cmp.LE, 0.07),
            new SelCompareConst(QUANTITY, Cmp.LT, 24.0)
        };
        SelExpr projection = new SelMultiply(new SelColumn(EXTENDEDPRICE), new SelColumn(DISCOUNT));
        return new SelFilterProjectRoot(null, predicates, projection);
    }

    /** Deterministic columnar data (no RNG), reproducible across runs and node shapes. */
    public static ColumnBatch synthetic(int rows) {
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
}
