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
 *
 * <p>{@code BigDecimal → double} is likewise an {@link ImplicitCast}, so a comparison or arithmetic
 * that mixes a decimal column with a {@code long}/{@code double} literal (e.g. TPC-H's
 * {@code discount BETWEEN 0.05 AND 0.07} or {@code quantity < 24}, where the parser leaves the
 * literals as {@code DoubleValue}/{@code IntValue} and inserts no cast) widens to the {@code double}
 * path — matching the interpreter's {@code (Number, Number) → doubleValue} rule. A decimal-vs-decimal
 * operation still hits the exact {@code BigDecimal} specialization (an exact-type match always beats
 * an implicitly-cast one), so exact-decimal correctness is preserved where both sides are decimal.
 */
@TypeSystem({long.class, double.class, boolean.class, String.class, BigDecimal.class, SqlNull.class})
public abstract class SqlTypes {

    @ImplicitCast
    public static double promoteToDouble(long value) {
        return value;
    }

    @ImplicitCast
    public static double bigDecimalToDouble(BigDecimal value) {
        return value.doubleValue();
    }
}
