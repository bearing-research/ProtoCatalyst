package protocatalyst.truffle.typed;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Three-valued AND/OR — hand-written rather than DSL-specialized, because SQL's 3VL is not a simple
 * type dispatch:
 * <ul>
 *   <li>{@code AND}: FALSE if any operand is FALSE (even with NULLs); else NULL if any NULL; else TRUE.</li>
 *   <li>{@code OR}:  TRUE if any operand is TRUE (even with NULLs); else NULL if any NULL; else FALSE.</li>
 * </ul>
 * Operands flow as boxed {@code Boolean} or {@link SqlNull}; this is the null-correct logic Catalyst's
 * WHERE relies on (a row passes only when the predicate is exactly TRUE).
 */
public final class TLogic {

    private TLogic() {}

    public static final class And extends TExpr {
        @Child private TExpr left;
        @Child private TExpr right;

        public And(TExpr left, TExpr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object l = left.executeGeneric(frame);
            if (Boolean.FALSE.equals(l)) {
                return Boolean.FALSE;
            }
            Object r = right.executeGeneric(frame);
            if (Boolean.FALSE.equals(r)) {
                return Boolean.FALSE;
            }
            boolean anyNull = SqlNull.isNull(l) || SqlNull.isNull(r);
            return anyNull ? SqlNull.INSTANCE : Boolean.TRUE;
        }
    }

    public static final class Or extends TExpr {
        @Child private TExpr left;
        @Child private TExpr right;

        public Or(TExpr left, TExpr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object l = left.executeGeneric(frame);
            if (Boolean.TRUE.equals(l)) {
                return Boolean.TRUE;
            }
            Object r = right.executeGeneric(frame);
            if (Boolean.TRUE.equals(r)) {
                return Boolean.TRUE;
            }
            boolean anyNull = SqlNull.isNull(l) || SqlNull.isNull(r);
            return anyNull ? SqlNull.INSTANCE : Boolean.FALSE;
        }
    }

    /** Three-valued NOT: NOT NULL = NULL, NOT TRUE = FALSE, NOT FALSE = TRUE. */
    public static final class Not extends TExpr {
        @Child private TExpr child;

        public Not(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                return SqlNull.INSTANCE;
            }
            return Boolean.TRUE.equals(v) ? Boolean.FALSE : Boolean.TRUE;
        }
    }
}
