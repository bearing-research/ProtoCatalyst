package protocatalyst.truffle.slice;

import com.oracle.truffle.api.nodes.Node;

/**
 * Row-at-a-time node shape — the canonical Truffle interpreter form, and the one the literature
 * predicts will regress vs WSCG (each node executes once per row; dispatch is amortized over nothing).
 *
 * <p>Numeric nodes return a {@code double} for the current row; predicate nodes return a
 * {@code boolean}. The "current row" lives in {@link RowCtx}, which the driving {@link
 * RowFilterProjectRoot} advances as it loops. Children are {@code @Child} so Graal partial evaluation
 * inlines them into the loop body.
 *
 * <p>This file groups the slice's row nodes (all package-private); the public driver is the root node.
 */
final class RowNodes {
    private RowNodes() {}
}

/** The mutable per-row cursor shared by an AST during one execution. */
final class RowCtx {
    final double[][] cols;
    int row;

    RowCtx(double[][] cols) {
        this.cols = cols;
    }
}

/** A node that produces a {@code double} for the current row. */
abstract class RowExpr extends Node {
    abstract double executeDouble(RowCtx ctx);
}

/** A node that produces a {@code boolean} for the current row. */
abstract class RowPredicate extends Node {
    abstract boolean executeBool(RowCtx ctx);
}

/** Read one column's value for the current row. */
final class RowColumn extends RowExpr {
    private final int col;

    RowColumn(int col) {
        this.col = col;
    }

    @Override
    double executeDouble(RowCtx ctx) {
        return ctx.cols[col][ctx.row];
    }
}

/** Multiply two numeric subtrees, one row at a time. */
final class RowMultiply extends RowExpr {
    @Child private RowExpr left;
    @Child private RowExpr right;

    RowMultiply(RowExpr left, RowExpr right) {
        this.left = left;
        this.right = right;
    }

    @Override
    double executeDouble(RowCtx ctx) {
        return left.executeDouble(ctx) * right.executeDouble(ctx);
    }
}

/** Add two numeric subtrees, one row at a time. (Not used by Q6; kept for the §4 op set.) */
final class RowAdd extends RowExpr {
    @Child private RowExpr left;
    @Child private RowExpr right;

    RowAdd(RowExpr left, RowExpr right) {
        this.left = left;
        this.right = right;
    }

    @Override
    double executeDouble(RowCtx ctx) {
        return left.executeDouble(ctx) + right.executeDouble(ctx);
    }
}

/** Compare a numeric subtree against a constant, one row at a time. */
final class RowCompareConst extends RowPredicate {
    @Child private RowExpr input;
    private final Cmp op;
    private final double constant;

    RowCompareConst(RowExpr input, Cmp op, double constant) {
        this.input = input;
        this.op = op;
        this.constant = constant;
    }

    @Override
    boolean executeBool(RowCtx ctx) {
        return op.apply(input.executeDouble(ctx), constant);
    }
}

/** Short-circuiting AND of two predicates, one row at a time. */
final class RowAnd extends RowPredicate {
    @Child private RowPredicate left;
    @Child private RowPredicate right;

    RowAnd(RowPredicate left, RowPredicate right) {
        this.left = left;
        this.right = right;
    }

    @Override
    boolean executeBool(RowCtx ctx) {
        return left.executeBool(ctx) && right.executeBool(ctx);
    }
}
