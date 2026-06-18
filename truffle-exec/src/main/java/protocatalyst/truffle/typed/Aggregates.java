package protocatalyst.truffle.typed;

import java.math.BigDecimal;

/** Shared aggregate accumulation helpers for the global and grouped aggregate roots. */
final class Aggregates {

    private Aggregates() {}

    /**
     * Add a non-null value into a SUM/AVG running total, preserving <b>exact</b> arbitrary-precision
     * {@link BigDecimal} when the input is decimal, and using {@code double} otherwise. The accumulator
     * is {@code null} until the first value is seen.
     */
    static Object addSum(Object acc, Object v) {
        if (v instanceof BigDecimal) {
            return (acc == null) ? v : ((BigDecimal) acc).add((BigDecimal) v);
        }
        double d = ((Number) v).doubleValue();
        return (acc == null) ? Double.valueOf(d) : Double.valueOf(((Number) acc).doubleValue() + d);
    }
}
