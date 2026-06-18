package protocatalyst.truffle.typed;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Typed fused filter→project with null-preserving output. A row passes when the predicate is exactly
 * TRUE (three-valued logic); for each surviving row every projection is evaluated and its value —
 * boxed {@code Long}/{@code Double} or Java {@code null} for SQL NULL — is written to the output.
 *
 * <p>Call arguments: {@code [TRow row, Object[][] out]}, where {@code out} has one {@code Object[]}
 * per projection (capacity ≥ row count). Returns the survivor count {@code k}; {@code out[c][0..k)}
 * holds projection {@code c}'s values in row order. Boxed output is the simplest correct null-aware
 * carrier for this layer; writing into typed Arrow vectors with validity is a later optimization.
 */
public final class TypedFilterProjectRoot extends RootNode {

    @Child private TExpr filter; // may be null (no predicate)
    @Children private final TExpr[] projections;

    public TypedFilterProjectRoot(TruffleLanguage<?> language, TExpr filter, TExpr[] projections) {
        super(language);
        this.filter = filter;
        this.projections = projections;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        TRow trow = (TRow) frame.getArguments()[0];
        Object[][] out = (Object[][]) frame.getArguments()[1];

        int n = trow.cols.rowCount;
        int k = 0;
        for (int i = 0; i < n; i++) {
            trow.row = i;
            if (filter == null || Boolean.TRUE.equals(filter.executeGeneric(frame))) {
                writeRow(frame, out, k);
                k++;
            }
        }
        return k;
    }

    private void writeRow(VirtualFrame frame, Object[][] out, int k) {
        for (int c = 0; c < projections.length; c++) {
            Object v = projections[c].executeGeneric(frame);
            out[c][k] = SqlNull.isNull(v) ? null : v;
        }
    }
}
