package protocatalyst.truffle.slice;

import com.oracle.truffle.api.nodes.Node;

/**
 * Selection-vector batch shape — the "vectorized done right" form (DuckDB/Photon/Velox). Instead of
 * materializing a full-length mask per predicate and AND-ing them, a single selection vector (the
 * indices of rows still alive) is threaded through the pipeline: the first predicate scans all rows
 * and emits the initial selection; each later predicate *refines* it, touching only survivors; the
 * projection computes only over the final selection. This is the fair test of the vectorized
 * hypothesis the naive {@link BatchExpr} batch shape failed — it cuts both the memory traffic (no
 * full masks, no AND passes) and the work (later passes shrink with selectivity).
 *
 * <p>Buffers are node-owned and reused (allocation-free steady state), so an AST instance is
 * single-threaded — same contract as {@link BatchNodes}.
 */
final class SelNodes {
    private SelNodes() {}

    static double[] ensureDouble(double[] buf, int n) {
        return (buf == null || buf.length < n) ? new double[n] : buf;
    }
}

/** A predicate that refines a selection vector in place, returning the new survivor count. */
abstract class SelPredicate extends Node {
    /** Scan all rows, writing survivor indices into {@code sel}; returns the survivor count. */
    abstract int filterAll(ColumnBatch input, int[] sel);

    /** Refine {@code sel[0..count)} in place, keeping survivors; returns the new count. */
    abstract int filter(ColumnBatch input, int[] sel, int count);
}

/** A projection that gathers values for the selected rows into a reused buffer of length {@code count}. */
abstract class SelExpr extends Node {
    abstract double[] gather(ColumnBatch input, int[] sel, int count);
}

/** Column-vs-constant comparison as a selection refinement. */
final class SelCompareConst extends SelPredicate {
    private final int col;
    private final Cmp op;
    private final double constant;

    SelCompareConst(int col, Cmp op, double constant) {
        this.col = col;
        this.op = op;
        this.constant = constant;
    }

    @Override
    int filterAll(ColumnBatch input, int[] sel) {
        double[] a = input.cols[col];
        int n = input.rowCount;
        int w = 0;
        for (int i = 0; i < n; i++) {
            if (op.apply(a[i], constant)) {
                sel[w++] = i;
            }
        }
        return w;
    }

    @Override
    int filter(ColumnBatch input, int[] sel, int count) {
        double[] a = input.cols[col];
        int w = 0;
        for (int j = 0; j < count; j++) {
            int i = sel[j];
            if (op.apply(a[i], constant)) {
                sel[w++] = i;
            }
        }
        return w;
    }
}

/** Gather one column's values for the selected rows. */
final class SelColumn extends SelExpr {
    private final int col;
    private double[] scratch;

    SelColumn(int col) {
        this.col = col;
    }

    @Override
    double[] gather(ColumnBatch input, int[] sel, int count) {
        double[] a = input.cols[col];
        double[] out = scratch = SelNodes.ensureDouble(scratch, count);
        for (int j = 0; j < count; j++) {
            out[j] = a[sel[j]];
        }
        return out;
    }
}

/** Multiply two gathered column subtrees over the selection. */
final class SelMultiply extends SelExpr {
    @Child private SelExpr left;
    @Child private SelExpr right;
    private double[] scratch;

    SelMultiply(SelExpr left, SelExpr right) {
        this.left = left;
        this.right = right;
    }

    @Override
    double[] gather(ColumnBatch input, int[] sel, int count) {
        double[] a = left.gather(input, sel, count);
        double[] b = right.gather(input, sel, count);
        double[] out = scratch = SelNodes.ensureDouble(scratch, count);
        for (int j = 0; j < count; j++) {
            out[j] = a[j] * b[j];
        }
        return out;
    }
}
