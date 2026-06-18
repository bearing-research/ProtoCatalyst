package protocatalyst.truffle.typed;

/** The mutable per-row cursor for the typed node library (the typed analogue of {@code GNodes.Ctx}). */
public final class TRow {

    public final TypedColumns cols;
    public int row;

    public TRow(TypedColumns cols) {
        this.cols = cols;
    }
}
