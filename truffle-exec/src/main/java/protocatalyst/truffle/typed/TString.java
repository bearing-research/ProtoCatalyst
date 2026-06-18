package protocatalyst.truffle.typed;

import java.util.Locale;

import com.oracle.truffle.api.frame.VirtualFrame;

/** Layer-2 string functions (NULL-aware): UPPER, LOWER, LENGTH. */
public final class TString {

    private TString() {}

    public static final class Upper extends TExpr {
        @Child private TExpr child;

        public Upper(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v) ? SqlNull.INSTANCE : v.toString().toUpperCase(Locale.ROOT);
        }
    }

    public static final class Lower extends TExpr {
        @Child private TExpr child;

        public Lower(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v) ? SqlNull.INSTANCE : v.toString().toLowerCase(Locale.ROOT);
        }
    }

    public static final class Length extends TExpr {
        @Child private TExpr child;

        public Length(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v) ? SqlNull.INSTANCE : Long.valueOf(v.toString().length());
        }
    }

    public static final class Reverse extends TExpr {
        @Child private TExpr child;

        public Reverse(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v) ? SqlNull.INSTANCE : new StringBuilder(v.toString()).reverse().toString();
        }
    }

    /** CONCAT(a, b, ...) — NULL arguments contribute the empty string (this engine's interpreter
     * semantics), so the result is never NULL. */
    public static final class Concat extends TExpr {
        @Children private final TExpr[] parts;

        public Concat(TExpr[] parts) {
            this.parts = parts;
        }

        @Override
        @com.oracle.truffle.api.nodes.ExplodeLoop
        public Object executeGeneric(VirtualFrame frame) {
            StringBuilder sb = new StringBuilder();
            for (TExpr part : parts) {
                Object v = part.executeGeneric(frame);
                if (!SqlNull.isNull(v)) {
                    sb.append(v.toString());
                }
            }
            return sb.toString();
        }
    }

    public enum TrimMode {
        BOTH,
        LEADING,
        TRAILING
    }

    /** TRIM/LTRIM/RTRIM of ASCII spaces (matching Spark's default whitespace = space). */
    public static final class Trim extends TExpr {
        @Child private TExpr child;
        private final TrimMode mode;

        public Trim(TExpr child, TrimMode mode) {
            this.child = child;
            this.mode = mode;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                return SqlNull.INSTANCE;
            }
            String s = v.toString();
            int start = 0;
            int end = s.length();
            if (mode != TrimMode.TRAILING) {
                while (start < end && s.charAt(start) == ' ') {
                    start++;
                }
            }
            if (mode != TrimMode.LEADING) {
                while (end > start && s.charAt(end - 1) == ' ') {
                    end--;
                }
            }
            return s.substring(start, end);
        }
    }

    /** SUBSTRING(str, pos, len): 1-based pos; pos &lt; 0 counts from the end; len characters. */
    public static final class Substring extends TExpr {
        @Child private TExpr str;
        @Child private TExpr pos;
        @Child private TExpr len;

        public Substring(TExpr str, TExpr pos, TExpr len) {
            this.str = str;
            this.pos = pos;
            this.len = len;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object sv = str.executeGeneric(frame);
            if (SqlNull.isNull(sv)) {
                return SqlNull.INSTANCE;
            }
            Object pv = pos.executeGeneric(frame);
            Object lv = len.executeGeneric(frame);
            if (SqlNull.isNull(pv) || SqlNull.isNull(lv)) {
                return SqlNull.INSTANCE;
            }
            String s = sv.toString();
            int p = (int) ((Number) pv).longValue();
            int l = (int) ((Number) lv).longValue();
            int strLen = s.length();
            int start = (p > 0) ? p - 1 : (p == 0) ? 0 : strLen + p;
            if (start < 0) {
                start = 0;
            }
            long endL = (long) start + Math.max(0, l);
            int end = (endL > strLen) ? strLen : (int) endL;
            return (start >= end) ? "" : s.substring(start, end);
        }
    }
}
