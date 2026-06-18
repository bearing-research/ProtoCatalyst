package protocatalyst.truffle.typed;

import java.time.LocalDate;

import com.oracle.truffle.api.frame.VirtualFrame;

/**
 * Layer-2 date functions over date columns (epoch-day {@code long}s). YEAR/MONTH/DAY extract integer
 * fields; DATE_ADD/DATE_SUB shift by a day count (result is epoch days). All NULL-aware.
 */
public final class TDate {

    private TDate() {}

    public static final class Year extends TExpr {
        @Child private TExpr child;

        public Year(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v)
                    ? SqlNull.INSTANCE
                    : Long.valueOf(LocalDate.ofEpochDay(((Number) v).longValue()).getYear());
        }
    }

    public static final class Month extends TExpr {
        @Child private TExpr child;

        public Month(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v)
                    ? SqlNull.INSTANCE
                    : Long.valueOf(LocalDate.ofEpochDay(((Number) v).longValue()).getMonthValue());
        }
    }

    public static final class DayOfMonth extends TExpr {
        @Child private TExpr child;

        public DayOfMonth(TExpr child) {
            this.child = child;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object v = child.executeGeneric(frame);
            return SqlNull.isNull(v)
                    ? SqlNull.INSTANCE
                    : Long.valueOf(LocalDate.ofEpochDay(((Number) v).longValue()).getDayOfMonth());
        }
    }

    /** DATE_ADD(start, days) / DATE_SUB(start, days): epoch-day arithmetic. */
    public static final class Shift extends TExpr {
        @Child private TExpr start;
        @Child private TExpr days;
        private final boolean add;

        public Shift(TExpr start, TExpr days, boolean add) {
            this.start = start;
            this.days = days;
            this.add = add;
        }

        @Override
        public Object executeGeneric(VirtualFrame frame) {
            Object s = start.executeGeneric(frame);
            if (SqlNull.isNull(s)) {
                return SqlNull.INSTANCE;
            }
            Object d = days.executeGeneric(frame);
            if (SqlNull.isNull(d)) {
                return SqlNull.INSTANCE;
            }
            long base = ((Number) s).longValue();
            long delta = ((Number) d).longValue();
            return Long.valueOf(add ? base + delta : base - delta);
        }
    }
}
