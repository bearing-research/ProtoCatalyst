package protocatalyst.truffle.exec;

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
}
