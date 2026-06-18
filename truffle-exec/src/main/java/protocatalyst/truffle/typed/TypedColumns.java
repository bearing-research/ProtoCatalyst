package protocatalyst.truffle.typed;

/**
 * A typed, nullable columnar batch — the Layer-1 input model that replaces the double-only
 * {@code ColumnBatch}. Each column is a primitive array ({@code long[]} or {@code double[]}) plus an
 * optional null mask. The Scala backend decodes Arrow vectors (including their validity bitmaps) into
 * this; later layers add string/decimal/temporal column kinds.
 */
public final class TypedColumns {

    /** One primitive array per column: {@code long[]} or {@code double[]}. */
    public final Object[] columns;

    /** Per-column null mask; {@code nulls[c]} may be {@code null} meaning "no nulls in column c". */
    public final boolean[][] nulls;

    public final int rowCount;

    public TypedColumns(Object[] columns, boolean[][] nulls, int rowCount) {
        this.columns = columns;
        this.nulls = nulls;
        this.rowCount = rowCount;
    }

    public boolean isNull(int col, int row) {
        boolean[] mask = nulls[col];
        return mask != null && mask[row];
    }

    public long getLong(int col, int row) {
        return ((long[]) columns[col])[row];
    }

    public double getDouble(int col, int row) {
        return ((double[]) columns[col])[row];
    }

    public String getString(int col, int row) {
        return ((String[]) columns[col])[row];
    }
}
