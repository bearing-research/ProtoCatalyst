package protocatalyst.truffle.slice;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Selection-vector driver: the first conjunct scans all rows into the selection, each remaining
 * conjunct refines it (touching only survivors), and the projection gathers over the final
 * selection. The projected values are already in selection order, so they are the output directly.
 *
 * <p>Call arguments: {@code [ColumnBatch input, double[] out]}. Returns the survivor count {@code k};
 * {@code out[0..k)} holds the projected values in row order — identical to the row and batch shapes.
 */
public final class SelFilterProjectRoot extends RootNode {

    @Children private final SelPredicate[] predicates;
    @Child private SelExpr projection;
    private int[] sel;

    public SelFilterProjectRoot(
            TruffleLanguage<?> language, SelPredicate[] predicates, SelExpr projection) {
        super(language);
        this.predicates = predicates;
        this.projection = projection;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        ColumnBatch input = (ColumnBatch) args[0];
        double[] out = (double[]) args[1];

        int n = input.rowCount;
        int[] s = sel;
        if (s == null || s.length < n) {
            s = sel = new int[n];
        }

        int count = predicates[0].filterAll(input, s);
        for (int p = 1; p < predicates.length; p++) {
            count = predicates[p].filter(input, s, count);
        }

        double[] projected = projection.gather(input, s, count);
        System.arraycopy(projected, 0, out, 0, count);
        return count;
    }
}
