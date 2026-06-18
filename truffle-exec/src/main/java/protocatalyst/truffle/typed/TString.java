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
}
