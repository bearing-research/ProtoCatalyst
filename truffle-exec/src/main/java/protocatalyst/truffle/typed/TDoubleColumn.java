package protocatalyst.truffle.typed;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/** A nullable {@code double} column read (the {@code double} analogue of {@link TLongColumn}). */
public final class TDoubleColumn extends TExpr {

    private final int idx;

    public TDoubleColumn(int idx) {
        this.idx = idx;
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        TRow r = row(frame);
        if (r.cols.isNull(idx, r.row)) {
            throw new UnexpectedResultException(SqlNull.INSTANCE);
        }
        return r.cols.getDouble(idx, r.row);
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        TRow r = row(frame);
        if (r.cols.isNull(idx, r.row)) {
            return SqlNull.INSTANCE;
        }
        return r.cols.getDouble(idx, r.row);
    }
}
