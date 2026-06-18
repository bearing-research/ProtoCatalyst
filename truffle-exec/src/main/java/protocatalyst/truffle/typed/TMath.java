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

    public static final class Exp extends TExpr {
        @Child private TExpr child;

        public Exp(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v) ? SqlNull.INSTANCE : Double.valueOf(Math.exp(((Number) v).doubleValue()));
        }
    }

    public static final class Cbrt extends TExpr {
        @Child private TExpr child;

        public Cbrt(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v) ? SqlNull.INSTANCE : Double.valueOf(Math.cbrt(((Number) v).doubleValue()));
        }
    }

    /** SIGN(x): -1.0 / 0.0 / 1.0 as a double. */
    public static final class Sign extends TExpr {
        @Child private TExpr child;

        public Sign(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v) ? SqlNull.INSTANCE : Double.valueOf(Math.signum(((Number) v).doubleValue()));
        }
    }

    public static final class Pow extends TExpr {
        @Child private TExpr base;
        @Child private TExpr exponent;

        public Pow(TExpr base, TExpr exponent) {
            this.base = base;
            this.exponent = exponent;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object b = base.executeGeneric(frame);
            if (SqlNull.isNull(b)) {
                return SqlNull.INSTANCE;
            }
            Object e = exponent.executeGeneric(frame);
            if (SqlNull.isNull(e)) {
                return SqlNull.INSTANCE;
            }
            return Double.valueOf(Math.pow(((Number) b).doubleValue(), ((Number) e).doubleValue()));
        }
    }

    /** LOG: natural log when no base, else log base b of x = ln(x)/ln(b). */
    public static final class Log extends TExpr {
        @Child private TExpr child;
        @Child private TExpr base; // may be null → natural log

        public Log(TExpr child, TExpr base) {
            this.child = child;
            this.base = base;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                return SqlNull.INSTANCE;
            }
            double x = Math.log(((Number) v).doubleValue());
            if (base == null) {
                return Double.valueOf(x);
            }
            Object b = base.executeGeneric(frame);
            if (SqlNull.isNull(b)) {
                return SqlNull.INSTANCE;
            }
            return Double.valueOf(x / Math.log(((Number) b).doubleValue()));
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
