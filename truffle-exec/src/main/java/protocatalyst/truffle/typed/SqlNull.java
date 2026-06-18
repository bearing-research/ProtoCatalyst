package protocatalyst.truffle.typed;

/**
 * The SQL NULL marker for the typed, null-aware node library (Layer 1, see
 * {@code docs/compiler/TRUFFLE_EXPLORATION.md}). A single sentinel flows through `executeGeneric` as
 * the "value is NULL" case; the typed execute paths ({@code executeLong}/{@code executeDouble}/…)
 * instead signal null by throwing {@link com.oracle.truffle.api.nodes.UnexpectedResultException},
 * which the Truffle DSL turns into a fallback to the generic/null specialization.
 */
public final class SqlNull {

    public static final SqlNull INSTANCE = new SqlNull();

    private SqlNull() {}

    public static boolean isNull(Object value) {
        return value == INSTANCE;
    }

    @Override
    public String toString() {
        return "NULL";
    }
}
