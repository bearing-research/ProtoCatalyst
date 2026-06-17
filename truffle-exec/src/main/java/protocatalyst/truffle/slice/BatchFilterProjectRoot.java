package protocatalyst.truffle.slice;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Batch-shape driver: evaluate the predicate to a mask and the projection to a column (each a
 * vectorized pass), then gather matching rows. The per-node work is whole-column; only the final
 * gather is row-shaped.
 *
 * <p>Call arguments: {@code [ColumnBatch input, double[] out]}. Returns the match count {@code k};
 * {@code out[0..k)} holds the projected values for matching rows, in row order (so its result is
 * identical to {@link RowFilterProjectRoot} for the same input — the parity check the slice asserts).
 */
public final class BatchFilterProjectRoot extends RootNode {

    @Child private BatchPredicate predicate;
    @Child private BatchExpr projection;

    public BatchFilterProjectRoot(
            TruffleLanguage<?> language, BatchPredicate predicate, BatchExpr projection) {
        super(language);
        this.predicate = predicate;
        this.projection = projection;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        ColumnBatch input = (ColumnBatch) args[0];
        double[] out = (double[]) args[1];

        boolean[] mask = predicate.executeMask(input);
        double[] projected = projection.executeBatch(input);

        int k = 0;
        for (int i = 0; i < input.rowCount; i++) {
            if (mask[i]) {
                out[k++] = projected[i];
            }
        }
        return k;
    }
}
