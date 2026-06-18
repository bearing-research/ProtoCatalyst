package protocatalyst.truffle.typed;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * A nullable decimal column read. Decimals flow as {@link java.math.BigDecimal} through
 * {@link #executeGeneric}; comparisons use {@code compareTo}, which is exact (and scale-insensitive)
 * — the correctness the {@code double} path can't give at boundaries like {@code 0.05}/{@code 0.07}.
 */
public final class TDecimalColumn extends TExpr {

    private final int idx;

    public TDecimalColumn(int idx) {
        this.idx = idx;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        TRow r = row(frame);
        if (r.cols.isNull(idx, r.row)) {
            return SqlNull.INSTANCE;
        }
        return r.cols.getDecimal(idx, r.row);
    }
}
