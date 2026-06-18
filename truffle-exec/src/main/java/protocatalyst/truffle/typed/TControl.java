package protocatalyst.truffle.typed;

import java.util.regex.Pattern;

import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;

/**
 * Layer-2 null-handling and control-flow expressions (hand-written, since these are not type
 * dispatch). All are NULL-aware per SQL: IS [NOT] NULL test for NULL; COALESCE returns the first
 * non-null; NULLIF returns NULL when the operands are equal; IF/CASE pick a branch on a predicate
 * being exactly TRUE; division by zero yields NULL (non-ANSI semantics).
 */
public final class TControl {

    private TControl() {}

    public static final class IsNull extends TExpr {
        @Child private TExpr child;

        public IsNull(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return SqlNull.isNull(child.executeGeneric(frame)) ? Boolean.TRUE : Boolean.FALSE;
        }
    }

    public static final class IsNotNull extends TExpr {
        @Child private TExpr child;

        public IsNotNull(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            return SqlNull.isNull(child.executeGeneric(frame)) ? Boolean.FALSE : Boolean.TRUE;
        }
    }

    public static final class Coalesce extends TExpr {
        @Children private final TExpr[] children;

        public Coalesce(TExpr[] children) {
            this.children = children;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            for (TExpr child : children) {
                Object v = child.executeGeneric(frame);
                if (!SqlNull.isNull(v)) {
                    return v;
                }
            }
            return SqlNull.INSTANCE;
        }
    }

    public static final class NullIf extends TExpr {
        @Child private TExpr left;
        @Child private TExpr right;

        public NullIf(TExpr left, TExpr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object l = left.executeGeneric(frame);
            if (SqlNull.isNull(l)) {
                return SqlNull.INSTANCE;
            }
            Object r = right.executeGeneric(frame);
            if (SqlNull.isNull(r)) {
                return l; // NULLIF(a, NULL) = a
            }
            return valuesEqual(l, r) ? SqlNull.INSTANCE : l;
        }
    }

    public static final class If extends TExpr {
        @Child private TExpr predicate;
        @Child private TExpr trueValue;
        @Child private TExpr falseValue;

        public If(TExpr predicate, TExpr trueValue, TExpr falseValue) {
            this.predicate = predicate;
            this.trueValue = trueValue;
            this.falseValue = falseValue;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            // FALSE and NULL both take the else branch (SQL IF semantics).
            return Boolean.TRUE.equals(predicate.executeGeneric(frame))
                    ? trueValue.executeGeneric(frame)
                    : falseValue.executeGeneric(frame);
        }
    }

    public static final class CaseWhen extends TExpr {
        @Children private final TExpr[] conditions;
        @Children private final TExpr[] values;
        @Child private TExpr elseValue; // may be null (no ELSE → NULL)

        public CaseWhen(TExpr[] conditions, TExpr[] values, TExpr elseValue) {
            this.conditions = conditions;
            this.values = values;
            this.elseValue = elseValue;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            for (int i = 0; i < conditions.length; i++) {
                if (Boolean.TRUE.equals(conditions[i].executeGeneric(frame))) {
                    return values[i].executeGeneric(frame);
                }
            }
            return elseValue == null ? SqlNull.INSTANCE : elseValue.executeGeneric(frame);
        }
    }

    public static final class Divide extends TExpr {
        @Child private TExpr left;
        @Child private TExpr right;

        public Divide(TExpr left, TExpr right) {
            this.left = left;
            this.right = right;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object l = left.executeGeneric(frame);
            if (SqlNull.isNull(l)) {
                return SqlNull.INSTANCE;
            }
            Object r = right.executeGeneric(frame);
            if (SqlNull.isNull(r)) {
                return SqlNull.INSTANCE;
            }
            double divisor = ((Number) r).doubleValue();
            if (divisor == 0.0) {
                return SqlNull.INSTANCE; // x / 0 = NULL (non-ANSI)
            }
            return Double.valueOf(((Number) l).doubleValue() / divisor);
        }
    }

    /** {@code value IN (list)} with SQL 3VL: TRUE on a match; NULL if no match but value or any list
     * element is NULL; else FALSE. */
    public static final class In extends TExpr {
        @Child private TExpr value;
        @Children private final TExpr[] list;

        public In(TExpr value, TExpr[] list) {
            this.value = value;
            this.list = list;
        }

        @Override
        @ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            Object v = value.executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                return SqlNull.INSTANCE;
            }
            boolean anyNull = false;
            for (TExpr element : list) {
                Object e = element.executeGeneric(frame);
                if (SqlNull.isNull(e)) {
                    anyNull = true;
                } else if (valuesEqual(v, e)) {
                    return Boolean.TRUE;
                }
            }
            return anyNull ? SqlNull.INSTANCE : Boolean.FALSE;
        }
    }

    /** {@code value LIKE pattern} — the SQL pattern is precompiled to a regex; NULL value → NULL. */
    public static final class Like extends TExpr {
        @Child private TExpr value;
        private final Pattern pattern;

        public Like(TExpr value, Pattern pattern) {
            this.value = value;
            this.pattern = pattern;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = value.executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                return SqlNull.INSTANCE;
            }
            return pattern.matcher(v.toString()).matches() ? Boolean.TRUE : Boolean.FALSE;
        }
    }

    private static boolean valuesEqual(Object l, Object r) {
        if (l instanceof Number && r instanceof Number) {
            return ((Number) l).doubleValue() == ((Number) r).doubleValue();
        }
        return l.equals(r);
    }
}
