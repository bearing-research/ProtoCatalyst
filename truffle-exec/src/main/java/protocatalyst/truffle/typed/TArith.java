package protocatalyst.truffle.typed;

import java.math.BigDecimal;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

/**
 * Typed binary arithmetic via the Truffle DSL — the canonical {@code @Specialization} idiom Phase 0
 * set up, now doing real work. Each operator specializes on {@code (long,long)}, {@code (double,double)}
 * (the {@code long→double} implicit cast in {@link SqlTypes} covers mixed numeric operands), and
 * {@code (BigDecimal,BigDecimal)} — exact arbitrary-precision decimal arithmetic, matching the
 * interpreter (Spark's capped decimal(38,s) precision/scale rules are a further Layer-4 refinement).
 * The {@code @Fallback} fires when either operand is {@link SqlNull}, propagating NULL. The DSL
 * generates the uninitialized→specialized→generic state machine and the {@code create(left, right)}
 * factories.
 */
public final class TArith {

    private TArith() {}

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Add extends TExpr {
        @Specialization
        protected long longs(long l, long r) {
            return l + r;
        }

        @Specialization
        protected double doubles(double l, double r) {
            return l + r;
        }


        @Specialization
        protected java.math.BigDecimal decimals(BigDecimal l, BigDecimal r) {
            return l.add(r);
        }
        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Sub extends TExpr {
        @Specialization
        protected long longs(long l, long r) {
            return l - r;
        }

        @Specialization
        protected double doubles(double l, double r) {
            return l - r;
        }


        @Specialization
        protected java.math.BigDecimal decimals(BigDecimal l, BigDecimal r) {
            return l.subtract(r);
        }
        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Mul extends TExpr {
        @Specialization
        protected long longs(long l, long r) {
            return l * r;
        }

        @Specialization
        protected double doubles(double l, double r) {
            return l * r;
        }


        @Specialization
        protected java.math.BigDecimal decimals(BigDecimal l, BigDecimal r) {
            return l.multiply(r);
        }
        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }
}
