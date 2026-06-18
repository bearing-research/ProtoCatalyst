package protocatalyst.truffle.typed;

import com.oracle.truffle.api.dsl.TypeSystemReference;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;

/**
 * Base of the typed, null-aware expression nodes. Nodes execute against a {@link VirtualFrame} (the
 * standard Truffle execution token, required by the DSL's {@code @NodeChild} wiring); the per-row
 * cursor {@link TRow} is passed as {@code frame.getArguments()[0]} and read via {@link #row}.
 *
 * <p>{@link #executeGeneric} always works and returns a boxed value or {@link SqlNull#INSTANCE}; the
 * typed paths return primitives on the fast path and throw {@link UnexpectedResultException} when the
 * value isn't of that type (including NULL), which the DSL uses to drive specialization fallback. The
 * {@code expect*} helpers come from the DSL-generated {@code SqlTypesGen}.
 */
@TypeSystemReference(SqlTypes.class)
public abstract class TExpr extends Node {

    public abstract Object executeGeneric(VirtualFrame frame);

    public long executeLong(VirtualFrame frame) throws UnexpectedResultException {
        return SqlTypesGen.expectLong(executeGeneric(frame));
    }

    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return SqlTypesGen.expectDouble(executeGeneric(frame));
    }

    public boolean executeBoolean(VirtualFrame frame) throws UnexpectedResultException {
        return SqlTypesGen.expectBoolean(executeGeneric(frame));
    }

    protected static TRow row(VirtualFrame frame) {
        return (TRow) frame.getArguments()[0];
    }
}
