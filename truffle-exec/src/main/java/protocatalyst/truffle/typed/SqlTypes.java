package protocatalyst.truffle.typed;

import java.math.BigDecimal;

import com.oracle.truffle.api.dsl.ImplicitCast;
import com.oracle.truffle.api.dsl.TypeSystem;

/**
 * The Truffle DSL type system for the typed node library: the value types that flow through
 * expression nodes, plus SQL's numeric promotion as an implicit cast. The DSL generates
 * {@code SqlTypesGen} (expect/cast helpers + specialization dispatch) from this.
 *
 * <p>{@code long → double} is an {@link ImplicitCast} so mixed-type arithmetic (e.g. an integer
 * column times a double column) specializes to the {@code double} path — SQL's numeric coercion,
 * handled by the DSL rather than per-node casts.
 */
@TypeSystem({long.class, double.class, boolean.class, String.class, BigDecimal.class, SqlNull.class})
public abstract class SqlTypes {

    @ImplicitCast
    public static double promoteToDouble(long value) {
        return value;
    }
}
