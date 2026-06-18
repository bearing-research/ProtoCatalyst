package protocatalyst.truffle.typed;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Layer-2 math functions (NULL-aware): ABS (type-preserving), SQRT, FLOOR, CEIL, ROUND. */
public final class TMath {

    private TMath() {}

    public static final class Abs extends TExpr {
        @Child private TExpr child;

        public Abs(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                return SqlNull.INSTANCE;
            }
            if (v instanceof Long) {
                return Long.valueOf(Math.abs((Long) v));
            }
            if (v instanceof BigDecimal) {
                return ((BigDecimal) v).abs();
            }
            return Double.valueOf(Math.abs(((Number) v).doubleValue()));
        }
    }

    public static final class Sqrt extends TExpr {
        @Child private TExpr child;

        public Sqrt(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v)
                    ? SqlNull.INSTANCE
                    : Double.valueOf(Math.sqrt(((Number) v).doubleValue()));
        }
    }

    public static final class Floor extends TExpr {
        @Child private TExpr child;

        public Floor(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v)
                    ? SqlNull.INSTANCE
                    : Long.valueOf((long) Math.floor(((Number) v).doubleValue()));
        }
    }

    public static final class Ceil extends TExpr {
        @Child private TExpr child;

        public Ceil(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v)
                    ? SqlNull.INSTANCE
                    : Long.valueOf((long) Math.ceil(((Number) v).doubleValue()));
        }
    }

    /** ROUND(x, scale) with HALF_UP, matching Spark; returns a double. */
    public static final class Round extends TExpr {
        @Child private TExpr child;
        private final int scale;

        public Round(TExpr child, int scale) {
            this.child = child;
            this.scale = scale;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                return SqlNull.INSTANCE;
            }
            double d = ((Number) v).doubleValue();
            return Double.valueOf(
                    new BigDecimal(Double.toString(d)).setScale(scale, RoundingMode.HALF_UP).doubleValue());
        }
    }
}
