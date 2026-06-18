package protocatalyst.truffle.exec;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;

import protocatalyst.truffle.slice.ColumnBatch;

/**
 * The general fused-row node library the Scala {@code ProtoTruffleCompiler} builds plans from — the
 * Phase-3 step from the hand-built Q6 slice to real {@code ProtoPhysicalPlan}s (see
 * {@code docs/compiler/TRUFFLE_EXPLORATION.md} §4 Phase 3). Fused row-at-a-time is used because Phase 2
 * established it as the AOT-safe winning shape.
 *
 * <p>Scope: the <b>numeric subset</b> — column reads, literals, +−×÷, the six comparisons, AND/OR/NOT,
 * over a {@code double[][]} column model (the Scala side decodes numeric Arrow columns to
 * {@code double[]}). Decimal/string/temporal types and the rest of Catalyst's ~100 expressions are
 * deliberately out of scope for this milestone; the builder rejects them rather than guessing.
 *
 * <p>Public nested classes so the cross-module Scala builder can construct them directly.
 */
public final class GNodes {

    private GNodes() {}

    /** Mutable per-row cursor over the decoded numeric columns. */
    public static final class Ctx {
        public final double[][] cols;
        public int row;

        public Ctx(double[][] cols) {
            this.cols = cols;
        }
    }

    /** A node producing a {@code double} for the current row. */
    public abstract static class Expr extends Node {
        public abstract double exec(Ctx ctx);
    }

    /** A node producing a {@code boolean} for the current row. */
    public abstract static class Pred extends Node {
        public abstract boolean exec(Ctx ctx);
    }

    public static final class Column extends Expr {
        private final int idx;

        public Column(int idx) {
            this.idx = idx;
        }

        @Override
        public double exec(Ctx ctx) {
            return ctx.cols[idx][ctx.row];
        }
    }

    public static final class Lit extends Expr {
        private final double value;

        public Lit(double value) {
            this.value = value;
        }

        @Override
        public double exec(Ctx ctx) {
            return value;
        }
    }

    public static final class Add extends Expr {
        @Child Expr left;
        @Child Expr right;

        public Add(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public double exec(Ctx ctx) {
            return left.exec(ctx) + right.exec(ctx);
        }
    }

    public static final class Sub extends Expr {
        @Child Expr left;
        @Child Expr right;

        public Sub(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public double exec(Ctx ctx) {
            return left.exec(ctx) - right.exec(ctx);
        }
    }

    public static final class Mul extends Expr {
        @Child Expr left;
        @Child Expr right;

        public Mul(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public double exec(Ctx ctx) {
            return left.exec(ctx) * right.exec(ctx);
        }
    }

    public static final class Div extends Expr {
        @Child Expr left;
        @Child Expr right;

        public Div(Expr left, Expr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public double exec(Ctx ctx) {
            return left.exec(ctx) / right.exec(ctx);
        }
    }

    public static final class Cmp extends Pred {
        @Child Expr left;
        @Child Expr right;
        private final CmpOp op;

        public Cmp(Expr left, Expr right, CmpOp op) {
            this.left = left;
            this.right = right;
            this.op = op;
        }

        @Override
        public boolean exec(Ctx ctx) {
            return op.apply(left.exec(ctx), right.exec(ctx));
        }
    }

    public static final class And extends Pred {
        @Children private final Pred[] children;

        public And(Pred[] children) {
            this.children = children;
        }

        @Override
        @ExplodeLoop
        public boolean exec(Ctx ctx) {
            for (Pred child : children) {
                if (!child.exec(ctx)) {
                    return false;
                }
            }
            return true;
        }
    }

    public static final class Or extends Pred {
        @Children private final Pred[] children;

        public Or(Pred[] children) {
            this.children = children;
        }

        @Override
        @ExplodeLoop
        public boolean exec(Ctx ctx) {
            for (Pred child : children) {
                if (child.exec(ctx)) {
                    return true;
                }
            }
            return false;
        }
    }

    public static final class Not extends Pred {
        @Child Pred child;

        public Not(Pred child) {
            this.child = child;
        }

        @Override
        public boolean exec(Ctx ctx) {
            return !child.exec(ctx);
        }
    }

    /**
     * Fused filter→project root. Args: {@code [ColumnBatch input, double[][] out]}, where {@code out}
     * has one array per projection. Returns the survivor count {@code k}; {@code out[c][0..k)} holds
     * projection {@code c}'s values for matching rows, in row order. A null {@code filter} means no
     * predicate (all rows pass).
     */
    public static final class FilterProjectRoot extends RootNode {
        @Child Pred filter;
        @Children private final Expr[] projections;

        public FilterProjectRoot(TruffleLanguage<?> language, Pred filter, Expr[] projections) {
            super(language);
            this.filter = filter;
            this.projections = projections;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            ColumnBatch input = (ColumnBatch) args[0];
            double[][] out = (double[][]) args[1];

            Ctx ctx = new Ctx(input.cols);
            int n = input.rowCount;
            int k = 0;
            for (int i = 0; i < n; i++) {
                ctx.row = i;
                if (filter == null || filter.exec(ctx)) {
                    writeRow(ctx, out, k);
                    k++;
                }
            }
            return k;
        }

        @ExplodeLoop
        private void writeRow(Ctx ctx, double[][] out, int k) {
            for (int c = 0; c < projections.length; c++) {
                out[c][k] = projections[c].exec(ctx);
            }
        }
    }

    /**
     * Fused filter→global-aggregate root (no GROUP BY). Args: {@code [ColumnBatch input, double[][]
     * out]}, where {@code out} has one length-1 array per aggregate. Returns 1 (the single result
     * row); {@code out[a][0]} is aggregate {@code a}'s value over the rows passing the filter.
     *
     * <p>{@code inputs[a]} is the inner expression of aggregate {@code a} (e.g. {@code ep*disc} for
     * {@code SUM(ep*disc)}); it is {@code null} for COUNT, which just counts surviving rows (null
     * tracking is a later layer). Single-pass: every aggregate accumulates in the one row scan.
     */
    public static final class GlobalAggRoot extends RootNode {
        @Child Pred filter;
        @Children private final Expr[] inputs;
        @CompilationFinal(dimensions = 1) private final AggKind[] kinds;

        public GlobalAggRoot(
                TruffleLanguage<?> language, Pred filter, Expr[] inputs, AggKind[] kinds) {
            super(language);
            this.filter = filter;
            this.inputs = inputs;
            this.kinds = kinds;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            Object[] args = frame.getArguments();
            ColumnBatch input = (ColumnBatch) args[0];
            double[][] out = (double[][]) args[1];

            int na = kinds.length;
            double[] sum = new double[na];
            long[] count = new long[na];
            double[] min = new double[na];
            double[] max = new double[na];
            for (int a = 0; a < na; a++) {
                min[a] = Double.POSITIVE_INFINITY;
                max[a] = Double.NEGATIVE_INFINITY;
            }

            Ctx ctx = new Ctx(input.cols);
            int n = input.rowCount;
            for (int i = 0; i < n; i++) {
                ctx.row = i;
                if (filter == null || filter.exec(ctx)) {
                    accumulate(ctx, sum, count, min, max);
                }
            }
            finish(out, sum, count, min, max);
            return 1;
        }

        @ExplodeLoop
        private void accumulate(Ctx ctx, double[] sum, long[] count, double[] min, double[] max) {
            for (int a = 0; a < kinds.length; a++) {
                if (kinds[a] == AggKind.COUNT) {
                    count[a]++;
                    continue;
                }
                double v = inputs[a].exec(ctx);
                switch (kinds[a]) {
                    case SUM:
                        sum[a] += v;
                        break;
                    case AVG:
                        sum[a] += v;
                        count[a]++;
                        break;
                    case MIN:
                        if (v < min[a]) {
                            min[a] = v;
                        }
                        break;
                    case MAX:
                        if (v > max[a]) {
                            max[a] = v;
                        }
                        break;
                    default:
                        break;
                }
            }
        }

        @ExplodeLoop
        private void finish(double[][] out, double[] sum, long[] count, double[] min, double[] max) {
            for (int a = 0; a < kinds.length; a++) {
                double r;
                switch (kinds[a]) {
                    case SUM:
                        r = sum[a];
                        break;
                    case COUNT:
                        r = (double) count[a];
                        break;
                    case AVG:
                        r = count[a] == 0 ? 0.0 : sum[a] / count[a];
                        break;
                    case MIN:
                        r = min[a];
                        break;
                    case MAX:
                        r = max[a];
                        break;
                    default:
                        r = 0.0;
                        break;
                }
                out[a][0] = r;
            }
        }
    }
}
