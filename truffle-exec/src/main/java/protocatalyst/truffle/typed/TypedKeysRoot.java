package protocatalyst.truffle.typed;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Evaluates a list of key expressions for every row of one input — used by the join driver to extract
 * build/probe keys via the typed expression nodes. Call args: {@code [TRow, Object[][] out]}, where
 * {@code out[k][row]} receives key {@code k}'s value (boxed, or Java {@code null} for SQL NULL).
 * Returns the row count.
 */
public final class TypedKeysRoot extends RootNode {

    @Children private final TExpr[] keys;

    public TypedKeysRoot(TruffleLanguage<?> language, TExpr[] keys) {
        super(language);
        this.keys = keys;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        TRow trow = (TRow) frame.getArguments()[0];
        Object[][] out = (Object[][]) frame.getArguments()[1];
        int n = trow.cols.rowCount;
        for (int row = 0; row < n; row++) {
            trow.row = row;
            for (int k = 0; k < keys.length; k++) {
                Object v = keys[k].executeGeneric(frame);
                out[k][row] = SqlNull.isNull(v) ? null : v;
            }
        }
        return Integer.valueOf(n);
    }
}
