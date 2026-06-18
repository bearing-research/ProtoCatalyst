package protocatalyst.truffle.typed;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * A nullable {@code long} column read. On the fast path {@link #executeLong} returns the primitive;
 * when the cell is NULL it throws {@link UnexpectedResultException}, so a parent specialization falls
 * back to its generic/null path — that is how three-valued logic is threaded through the DSL.
 */
public final class TLongColumn extends TExpr {

    private final int idx;

    public TLongColumn(int idx) {
        this.idx = idx;
    }

    @Override
    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        TRow r = row(frame);
        if (r.cols.isNull(idx, r.row)) {
            throw new UnexpectedResultException(SqlNull.INSTANCE);
        }
        return r.cols.getLong(idx, r.row);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        TRow r = row(frame);
        if (r.cols.isNull(idx, r.row)) {
            return SqlNull.INSTANCE;
        }
        return r.cols.getLong(idx, r.row);
    }
}
