package protocatalyst.truffle.exec;

/** The six SQL comparison operators, for the general backend's {@code GNodes.Cmp} predicate. */
public enum CmpOp {
    EQ,
    NE,
    LT,
    LE,
    GT,
    GE;

    public boolean apply(double a, double b) {
        switch (this) {
            case EQ:
                return a == b;
            case NE:
                return a != b;
            case LT:
                return a < b;
            case LE:
                return a <= b;
            case GT:
                return a > b;
            case GE:
                return a >= b;
            default:
                throw new IllegalStateException("unreachable");
        }
    }
}
