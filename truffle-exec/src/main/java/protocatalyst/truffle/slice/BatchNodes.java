package protocatalyst.truffle.slice;

import com.oracle.truffle.api.nodes.Node;

/**
 * Batch-at-a-time node shape over columns — the vectorized form, and the contribution candidate.
 * Nobody has run Truffle on a vectorized engine; the question is whether processing a whole column
 * per {@code execute} (amortizing node dispatch over thousands of rows, SIMD-friendly inner loops)
 * lands close to WSCG rather than the row-shape's predicted 1.5–3× regression.
 *
 * <p>Numeric nodes return a {@code double[]} column; predicate nodes return a {@code boolean[]} mask.
 * The slice materializes full-length intermediates (the plain vectorized model); a selection-vector
 * refinement — DuckDB's trick to avoid materializing filtered-out rows — is a later optimization.
 */
final class BatchNodes {
    private BatchNodes() {}
}

/** A node that produces a whole {@code double[]} column. */
abstract class BatchExpr extends Node {
    abstract double[] executeBatch(ColumnBatch input);
}

/** A node that produces a whole {@code boolean[]} mask. */
abstract class BatchPredicate extends Node {
    abstract boolean[] executeMask(ColumnBatch input);
}

/** Return a column as-is (no copy). */
final class BatchColumn extends BatchExpr {
    private final int col;

    BatchColumn(int col) {
        this.col = col;
    }

    @Override
    double[] executeBatch(ColumnBatch input) {
        return input.cols[col];
    }
}

/** Element-wise multiply of two column subtrees. */
final class BatchMultiply extends BatchExpr {
    @Child private BatchExpr left;
    @Child private BatchExpr right;

    BatchMultiply(BatchExpr left, BatchExpr right) {
        this.left = left;
        this.right = right;
    }

    @Override
    double[] executeBatch(ColumnBatch input) {
        double[] a = left.executeBatch(input);
        double[] b = right.executeBatch(input);
        int n = input.rowCount;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = a[i] * b[i];
        }
        return out;
    }
}

/** Element-wise add of two column subtrees. (Not used by Q6; kept for the §4 op set.) */
final class BatchAdd extends BatchExpr {
    @Child private BatchExpr left;
    @Child private BatchExpr right;

    BatchAdd(BatchExpr left, BatchExpr right) {
        this.left = left;
        this.right = right;
    }

    @Override
    double[] executeBatch(ColumnBatch input) {
        double[] a = left.executeBatch(input);
        double[] b = right.executeBatch(input);
        int n = input.rowCount;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            out[i] = a[i] + b[i];
        }
        return out;
    }
}

/** Compare a column subtree against a constant, producing a mask. */
final class BatchCompareConst extends BatchPredicate {
    @Child private BatchExpr input;
    private final Cmp op;
    private final double constant;

    BatchCompareConst(BatchExpr input, Cmp op, double constant) {
        this.input = input;
        this.op = op;
        this.constant = constant;
    }

    @Override
    boolean[] executeMask(ColumnBatch batch) {
        double[] a = input.executeBatch(batch);
        int n = batch.rowCount;
        boolean[] mask = new boolean[n];
        for (int i = 0; i < n; i++) {
            mask[i] = op.apply(a[i], constant);
        }
        return mask;
    }
}

/** Element-wise AND of two masks. */
final class BatchAnd extends BatchPredicate {
    @Child private BatchPredicate left;
    @Child private BatchPredicate right;

    BatchAnd(BatchPredicate left, BatchPredicate right) {
        this.left = left;
        this.right = right;
    }

    @Override
    boolean[] executeMask(ColumnBatch input) {
        boolean[] a = left.executeMask(input);
        boolean[] b = right.executeMask(input);
        int n = input.rowCount;
        boolean[] mask = new boolean[n];
        for (int i = 0; i < n; i++) {
            mask[i] = a[i] && b[i];
        }
        return mask;
    }
}
