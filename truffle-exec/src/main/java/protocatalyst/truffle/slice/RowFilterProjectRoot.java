package protocatalyst.truffle.slice;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Row-shape driver: a fused filter→project loop, the row-at-a-time analogue of WSCG's single fused
 * loop. The loop lives inside {@link #execute}, advancing {@link RowCtx#row} and invoking the
 * {@code @Child} predicate/projection per row, so Graal partial evaluation inlines the whole AST into
 * one compiled loop.
 *
 * <p>Call arguments: {@code [ColumnBatch input, double[] out]}. Returns the match count {@code k};
 * the first {@code k} entries of {@code out} hold the projected values for matching rows, in row order.
 */
public final class RowFilterProjectRoot extends RootNode {

    @Child private RowPredicate predicate;
    @Child private RowExpr projection;

    public RowFilterProjectRoot(TruffleLanguage<?> language, RowPredicate predicate, RowExpr projection) {
        super(language);
        this.predicate = predicate;
        this.projection = projection;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        ColumnBatch input = (ColumnBatch) args[0];
        double[] out = (double[]) args[1];

        RowCtx ctx = new RowCtx(input.cols);
        int k = 0;
        for (int i = 0; i < input.rowCount; i++) {
            ctx.row = i;
            if (predicate.executeBool(ctx)) {
                out[k++] = projection.executeDouble(ctx);
            }
        }
        return k;
    }
}
