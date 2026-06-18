package protocatalyst.truffle.typed;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

/**
 * Typed binary arithmetic via the Truffle DSL — the canonical {@code @Specialization} idiom Phase 0
 * set up, now doing real work. Each operator specializes on {@code (long,long)} and
 * {@code (double,double)} (the {@code long→double} implicit cast in {@link SqlTypes} covers mixed
 * operands); the {@code @Fallback} fires when either operand is {@link SqlNull} (a child threw
 * {@code UnexpectedResultException} on its typed path), propagating NULL. The DSL generates the
 * uninitialized→specialized→generic state machine and the {@code create(left, right)} factories.
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

        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }
}
