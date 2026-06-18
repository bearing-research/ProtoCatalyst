package protocatalyst.truffle.typed;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.RootNode;

import protocatalyst.truffle.exec.AggKind;

/**
 * Typed, null-aware fused filter→global-aggregate (no GROUP BY). The key SQL semantics the double-only
 * path can't express: {@code SUM/AVG/MIN/MAX} <b>skip NULL inputs</b>, and yield NULL when no non-null
 * value was seen; {@code COUNT(*)} counts surviving rows. A row is considered only when the filter is
 * exactly TRUE (three-valued logic).
 *
 * <p>Call arguments: {@code [TRow row, Object[][] out]} (one length-≥1 array per aggregate); returns 1.
 * {@code out[a][0]} is the boxed result or Java {@code null} for SQL NULL. Each {@code inputs[a]} is the
 * aggregate's inner expression (a non-null placeholder for {@code COUNT(*)}, whose value is ignored).
 */
public final class TypedGlobalAggRoot extends RootNode {

    @Child private TExpr filter; // may be null
    @Children private final TExpr[] inputs;
    @CompilationFinal(dimensions = 1) private final AggKind[] kinds;

    public TypedGlobalAggRoot(
            TruffleLanguage<?> language, TExpr filter, TExpr[] inputs, AggKind[] kinds) {
        super(language);
        this.filter = filter;
        this.inputs = inputs;
        this.kinds = kinds;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        TRow trow = (TRow) frame.getArguments()[0];
        Object[][] out = (Object[][]) frame.getArguments()[1];

        int na = kinds.length;
        Object[] sumAcc = new Object[na]; // SUM/AVG running total — Double or (exact) BigDecimal
        long[] count = new long[na];
        double[] min = new double[na];
        double[] max = new double[na];
        boolean[] seen = new boolean[na];
        for (int a = 0; a < na; a++) {
            min[a] = Double.POSITIVE_INFINITY;
            max[a] = Double.NEGATIVE_INFINITY;
        }

        int n = trow.cols.rowCount;
        for (int i = 0; i < n; i++) {
            trow.row = i;
            if (filter == null || Boolean.TRUE.equals(filter.executeGeneric(frame))) {
                accumulate(frame, sumAcc, count, min, max, seen);
            }
        }
        finish(out, sumAcc, count, min, max, seen);
        return 1;
    }

    @ExplodeLoop
    private void accumulate(
            VirtualFrame frame,
            Object[] sumAcc,
            long[] count,
            double[] min,
            double[] max,
            boolean[] seen) {
        for (int a = 0; a < kinds.length; a++) {
            if (kinds[a] == AggKind.COUNT) {
                count[a]++; // COUNT(*): every surviving row
                continue;
            }
            Object v = inputs[a].executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                continue; // SUM/AVG/MIN/MAX ignore NULL inputs
            }
            seen[a] = true;
            switch (kinds[a]) {
                case SUM:
                    sumAcc[a] = Aggregates.addSum(sumAcc[a], v);
                    break;
                case AVG:
                    sumAcc[a] = Aggregates.addSum(sumAcc[a], v);
                    count[a]++;
                    break;
                case MIN: {
                    double d = ((Number) v).doubleValue();
                    if (d < min[a]) {
                        min[a] = d;
                    }
                    break;
                }
                case MAX: {
                    double d = ((Number) v).doubleValue();
                    if (d > max[a]) {
                        max[a] = d;
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }

    @ExplodeLoop
    private void finish(
            Object[][] out, Object[] sumAcc, long[] count, double[] min, double[] max, boolean[] seen) {
        for (int a = 0; a < kinds.length; a++) {
            Object r;
            switch (kinds[a]) {
                case COUNT:
                    r = Long.valueOf(count[a]);
                    break;
                case SUM:
                    r = seen[a] ? sumAcc[a] : null;
                    break;
                case AVG:
                    r =
                            (seen[a] && count[a] > 0)
                                    ? Double.valueOf(((Number) sumAcc[a]).doubleValue() / count[a])
                                    : null;
                    break;
                case MIN:
                    r = seen[a] ? Double.valueOf(min[a]) : null;
                    break;
                case MAX:
                    r = seen[a] ? Double.valueOf(max[a]) : null;
                    break;
                default:
                    r = null;
                    break;
            }
            out[a][0] = r;
        }
    }
}
