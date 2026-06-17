package protocatalyst.truffle.slice;

/**
 * The comparison operators the Q6 slice needs. Q6's WHERE clause is all column-vs-constant
 * comparisons ({@code discount >= .05 AND discount <= .07 AND quantity < 24}), so both node shapes
 * compare a numeric subtree against a constant using one of these.
 */
public enum Cmp {
    LT,
    LE,
    GE,
    GT;

    /** Apply this comparison: {@code value <op> constant}. */
    public boolean apply(double value, double constant) {
        switch (this) {
            case LT:
                return value < constant;
            case LE:
                return value <= constant;
            case GE:
                return value >= constant;
            case GT:
                return value > constant;
            default:
                throw new IllegalStateException("unreachable");
        }
    }
}
