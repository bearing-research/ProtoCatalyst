package protocatalyst.truffle.typed;

import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;

/**
 * Typed comparisons via the DSL. Each produces a {@code boolean} when both operands are present and
 * propagates {@link SqlNull} (SQL "unknown") when either is NULL — the {@code @Fallback} fires after a
 * child throws {@code UnexpectedResultException} on its typed path. The {@code long→double} implicit
 * cast covers mixed-type comparisons.
 */
public final class TCompare {

    private TCompare() {}

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Lt extends TExpr {
        @Specialization
        protected boolean longs(long l, long r) {
            return l < r;
        }

        @Specialization
        protected boolean doubles(double l, double r) {
            return l < r;
        }

        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Le extends TExpr {
        @Specialization
        protected boolean longs(long l, long r) {
            return l <= r;
        }

        @Specialization
        protected boolean doubles(double l, double r) {
            return l <= r;
        }

        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Gt extends TExpr {
        @Specialization
        protected boolean longs(long l, long r) {
            return l > r;
        }

        @Specialization
        protected boolean doubles(double l, double r) {
            return l > r;
        }

        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Ge extends TExpr {
        @Specialization
        protected boolean longs(long l, long r) {
            return l >= r;
        }

        @Specialization
        protected boolean doubles(double l, double r) {
            return l >= r;
        }

        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Eq extends TExpr {
        @Specialization
        protected boolean longs(long l, long r) {
            return l == r;
        }

        @Specialization
        protected boolean doubles(double l, double r) {
            return l == r;
        }

        @Specialization
        protected boolean strings(String l, String r) {
            return l.equals(r);
        }

        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }

    @NodeChild(value = "left", type = TExpr.class)
    @NodeChild(value = "right", type = TExpr.class)
    public abstract static class Ne extends TExpr {
        @Specialization
        protected boolean longs(long l, long r) {
            return l != r;
        }

        @Specialization
        protected boolean doubles(double l, double r) {
            return l != r;
        }

        @Specialization
        protected boolean strings(String l, String r) {
            return !l.equals(r);
        }

        @Fallback
        protected Object nullResult(Object l, Object r) {
            return SqlNull.INSTANCE;
        }
    }
}
