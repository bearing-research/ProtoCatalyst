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

    public java.math.BigDecimal getDecimal(int col, int row) {
        return ((java.math.BigDecimal[]) columns[col])[row];
    }

    /** The cell's value as a boxed object (Long/Double/String/BigDecimal), or {@code null} for SQL
     * NULL — used to materialize join output rows regardless of column kind. */
    public Object getBoxed(int col, int row) {
        if (isNull(col, row)) {
            return null;
        }
        Object c = columns[col];
        if (c instanceof long[]) {
            return Long.valueOf(((long[]) c)[row]);
        }
        if (c instanceof double[]) {
            return Double.valueOf(((double[]) c)[row]);
        }
        return ((Object[]) c)[row]; // String[] or BigDecimal[]
    }
}

