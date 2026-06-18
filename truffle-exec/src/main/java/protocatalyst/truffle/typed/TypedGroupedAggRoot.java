package protocatalyst.truffle.typed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

import protocatalyst.truffle.exec.AggKind;

/**
 * Typed, null-aware grouped aggregate (GROUP BY) — Layer 3. A single pass over surviving rows
 * (filter exactly TRUE) buckets each row by its group key into a hash table, accumulating
 * SUM/COUNT/MIN/MAX/AVG per group (SUM/AVG/MIN/MAX skip NULL inputs; COUNT(*) counts rows). NULL group
 * keys form their own group; decimal keys are normalized ({@code stripTrailingZeros}) so equal values
 * group together.
 *
 * <p>Output rows are grouping keys followed by aggregate results. Call args: {@code [TRow, Object[][]
 * out]} (capacity ≥ row count, an upper bound on group count); returns the group count. Unlike the
 * other roots this uses a {@code HashMap}, so it is not fully partial-evaluable — a specialized hash
 * table is a later performance refinement; here correctness comes first.
 */
public final class TypedGroupedAggRoot extends RootNode {

    @Child private TExpr filter; // may be null
    @Children private final TExpr[] keys;
    @Children private final TExpr[] inputs;
    @CompilationFinal(dimensions = 1) private final AggKind[] kinds;

    public TypedGroupedAggRoot(
            TruffleLanguage<?> language, TExpr filter, TExpr[] keys, TExpr[] inputs, AggKind[] kinds) {
        super(language);
        this.filter = filter;
        this.keys = keys;
        this.inputs = inputs;
        this.kinds = kinds;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        TRow trow = (TRow) frame.getArguments()[0];
        Object[][] out = (Object[][]) frame.getArguments()[1];

        int nk = keys.length;
        int na = kinds.length;
        int n = trow.cols.rowCount;
        HashMap<List<Object>, GroupState> map = new HashMap<>();
        ArrayList<GroupState> order = new ArrayList<>();

        for (int i = 0; i < n; i++) {
            trow.row = i;
            if (filter != null && !Boolean.TRUE.equals(filter.executeGeneric(frame))) {
                continue;
            }
            Object[] keyVals = new Object[nk];
            Object[] norm = new Object[nk];
            for (int k = 0; k < nk; k++) {
                Object kv = keys[k].executeGeneric(frame);
                kv = SqlNull.isNull(kv) ? null : kv;
                keyVals[k] = kv;
                norm[k] = (kv instanceof BigDecimal) ? ((BigDecimal) kv).stripTrailingZeros() : kv;
            }
            List<Object> key = Arrays.asList(norm);
            GroupState gs = map.get(key);
            if (gs == null) {
                gs = new GroupState(na, keyVals);
                map.put(key, gs);
                order.add(gs);
            }
            accumulate(frame, gs);
        }

        int g = 0;
        for (GroupState gs : order) {
            for (int k = 0; k < nk; k++) {
                out[k][g] = gs.keyVals[k];
            }
            for (int a = 0; a < na; a++) {
                out[nk + a][g] = result(gs, a);
            }
            g++;
        }
        return order.size();
    }

    private void accumulate(VirtualFrame frame, GroupState gs) {
        for (int a = 0; a < kinds.length; a++) {
            if (kinds[a] == AggKind.COUNT) {
                gs.count[a]++;
                continue;
            }
            Object v = inputs[a].executeGeneric(frame);
            if (SqlNull.isNull(v)) {
                continue;
            }
            double d = ((Number) v).doubleValue();
            gs.seen[a] = true;
            switch (kinds[a]) {
                case SUM:
                    gs.sum[a] += d;
                    break;
                case AVG:
                    gs.sum[a] += d;
                    gs.count[a]++;
                    break;
                case MIN:
                    if (d < gs.min[a]) {
                        gs.min[a] = d;
                    }
                    break;
                case MAX:
                    if (d > gs.max[a]) {
                        gs.max[a] = d;
                    }
                    break;
                default:
                    break;
            }
        }
    }

    private Object result(GroupState gs, int a) {
        switch (kinds[a]) {
            case COUNT:
                return Long.valueOf(gs.count[a]);
            case SUM:
                return gs.seen[a] ? Double.valueOf(gs.sum[a]) : null;
            case AVG:
                return (gs.seen[a] && gs.count[a] > 0) ? Double.valueOf(gs.sum[a] / gs.count[a]) : null;
            case MIN:
                return gs.seen[a] ? Double.valueOf(gs.min[a]) : null;
            case MAX:
                return gs.seen[a] ? Double.valueOf(gs.max[a]) : null;
            default:
                return null;
        }
    }

    private static final class GroupState {
        final double[] sum;
        final long[] count;
        final double[] min;
        final double[] max;
        final boolean[] seen;
        final Object[] keyVals;

        GroupState(int na, Object[] keyVals) {
            this.sum = new double[na];
            this.count = new long[na];
            this.min = new double[na];
            this.max = new double[na];
            this.seen = new boolean[na];
            this.keyVals = keyVals;
            for (int a = 0; a < na; a++) {
                min[a] = Double.POSITIVE_INFINITY;
                max[a] = Double.NEGATIVE_INFINITY;
            }
        }
    }
}
