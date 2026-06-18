package protocatalyst.truffle.typed;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * A nullable string column read. Strings are reference values, so they flow through
 * {@link #executeGeneric} (a {@code String} or {@link SqlNull#INSTANCE}); the DSL dispatches string
 * specializations via an {@code instanceof} check rather than a primitive typed path.
 */
public final class TStringColumn extends TExpr {

    private final int idx;

    public TStringColumn(int idx) {
        this.idx = idx;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        TRow r = row(frame);
        if (r.cols.isNull(idx, r.row)) {
            return SqlNull.INSTANCE;
        }
        return r.cols.getString(idx, r.row);
    }
}
