package protocatalyst.truffle.typed;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Typed literal leaves (never NULL). */
public final class TLit {

    private TLit() {}

    public static final class Long extends TExpr {
        private final long value;

        public Long(long value) {
            this.value = value;
        }

        @Override
        public long executeLong(VirtualFrame frame) {
            return value;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return value;
        }
    }

    public static final class Double extends TExpr {
        private final double value;

        public Double(double value) {
            this.value = value;
        }

        @Override
        public double executeDouble(VirtualFrame frame) {
            return value;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return value;
        }
    }

    public static final class Str extends TExpr {
        private final String value;

        public Str(String value) {
            this.value = value;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return value;
        }
    }

    public static final class Dec extends TExpr {
        private final java.math.BigDecimal value;

        public Dec(java.math.BigDecimal value) {
            this.value = value;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return value;
        }
    }
}
