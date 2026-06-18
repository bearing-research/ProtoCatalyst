package protocatalyst.truffle.typed;

import java.math.BigDecimal;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Layer-2 type coercion: convert a value to a target SQL type. NULL casts to NULL. Numeric→integer
 * truncates toward zero; numeric→double widens; →string uses the value's text form; →decimal builds a
 * {@code BigDecimal}. An unparseable string cast yields NULL (non-ANSI semantics) rather than failing.
 *
 * <p>This replaces the earlier Cast pass-through (which ignored the target type) — a real correctness
 * fix. Exact ANSI overflow/rounding rules are Layer-4 refinements.
 */
public final class TCast extends TExpr {

    public enum Target {
        LONG,
        DOUBLE,
        STRING,
        DECIMAL
    }

    @Child private TExpr child;
    private final Target target;

    public TCast(TExpr child, Target target) {
        this.child = child;
        this.target = target;
    }

    @Override
    public Object executeGeneric(VirtualFrame frame) {
        Object v = child.executeGeneric(frame);
        if (SqlNull.isNull(v)) {
            return SqlNull.INSTANCE;
        }
        switch (target) {
            case LONG:
                return toLong(v);
            case DOUBLE:
                return toDouble(v);
            case STRING:
                return v.toString();
            case DECIMAL:
                return toDecimal(v);
            default:
                return SqlNull.INSTANCE;
        }
    }

    private static Object toLong(Object v) {
        if (v instanceof Number) {
            return Long.valueOf(((Number) v).longValue()); // truncates toward zero
        }
        if (v instanceof String) {
            try {
                return Long.valueOf(Long.parseLong(((String) v).trim()));
            } catch (NumberFormatException e) {
                return SqlNull.INSTANCE;
            }
        }
        return SqlNull.INSTANCE;
    }

    private static Object toDouble(Object v) {
        if (v instanceof Number) {
            return Double.valueOf(((Number) v).doubleValue());
        }
        if (v instanceof String) {
            try {
                return Double.valueOf(Double.parseDouble(((String) v).trim()));
            } catch (NumberFormatException e) {
                return SqlNull.INSTANCE;
            }
        }
        return SqlNull.INSTANCE;
    }

    private static Object toDecimal(Object v) {
        if (v instanceof BigDecimal) {
            return v;
        }
        if (v instanceof Long || v instanceof Integer) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof Number) {
            return BigDecimal.valueOf(((Number) v).doubleValue());
        }
        if (v instanceof String) {
            try {
                return new BigDecimal(((String) v).trim());
            } catch (NumberFormatException e) {
                return SqlNull.INSTANCE;
            }
        }
        return SqlNull.INSTANCE;
    }
}
