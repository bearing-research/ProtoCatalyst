package protocatalyst.truffle;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;

/**
 * Minimal Truffle DSL node — the Phase-0 toolchain probe (see
 * {@code docs/compiler/TRUFFLE_EXPLORATION.md}).
 *
 * <p>This abstract class carries a single {@code @Specialization}. The Truffle DSL annotation
 * processor ({@code truffle-dsl-processor}) reads it at {@code javac} time and generates a concrete
 * {@code AddNodeGen} subclass that implements {@link #execute} via the uninitialized → specialized →
 * generic state machine. If {@code AddNodeGen.create()} resolves and this module compiles, the DSL
 * processor is wired correctly into our sbt build — the one piece of the Truffle toolchain that is
 * specific to this repo (§2 of the plan: the DSL is a Java annotation processor and cannot run from
 * Scala, hence this Java module).
 */
public abstract class AddNode extends Node {

    /** Add two longs. The DSL generates the dispatch in {@code AddNodeGen}. */
    public abstract long execute(long left, long right);

    @Specialization
    protected long addLongs(long left, long right) {
        return left + right;
    }
}
