package protocatalyst.truffle.typed;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * Counts rows whose predicate evaluates to exactly TRUE — SQL WHERE semantics under three-valued
 * logic (NULL and FALSE both fail the filter). Used to exercise the typed/null-aware node library
 * (Layer 1). Call argument: {@code [TRow row]}; returns the matching row count as a {@code long}.
 */
public final class TFilterCountRoot extends RootNode {

    @Child private TExpr predicate;

    public TFilterCountRoot(TruffleLanguage<?> language, TExpr predicate) {
        super(language);
        this.predicate = predicate;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        TRow trow = (TRow) frame.getArguments()[0];
        int n = trow.cols.rowCount;
        long count = 0;
        for (int i = 0; i < n; i++) {
            trow.row = i;
            if (Boolean.TRUE.equals(predicate.executeGeneric(frame))) {
                count++;
            }
        }
        return count;
    }
}
