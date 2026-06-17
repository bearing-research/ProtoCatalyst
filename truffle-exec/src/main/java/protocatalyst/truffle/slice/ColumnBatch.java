package protocatalyst.truffle.slice;

/**
 * The columnar input both node shapes consume — a minimal stand-in for an Arrow batch.
 *
 * <p>For the Phase-1 slice (see {@code docs/compiler/TRUFFLE_EXPLORATION.md} §4 Phase 1) columns are
 * on-heap {@code double[]}s. This deliberately isolates the variable under study — node granularity
 * (row-at-a-time vs batch-at-a-time) — from Arrow buffer-access overhead. Phase 3 swaps this for a
 * view over a real Arrow {@code VectorSchemaRoot} when the backend joins the cross-backend harness.
 */
public final class ColumnBatch {

    /** One {@code double[]} per column, each of length {@link #rowCount}. */
    public final double[][] cols;

    public final int rowCount;

    public ColumnBatch(double[][] cols, int rowCount) {
        this.cols = cols;
        this.rowCount = rowCount;
    }
}
