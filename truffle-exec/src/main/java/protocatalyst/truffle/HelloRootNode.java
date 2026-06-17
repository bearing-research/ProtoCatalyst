package protocatalyst.truffle;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;

/**
 * A trivial {@link RootNode} that adds its two {@code long} call arguments via an {@link AddNode}
 * child. Phase-0 skeleton only: a real backend will build root nodes from {@code ProtoPhysicalPlan}.
 *
 * <p>{@code language} may be {@code null} here — sufficient to obtain a {@code CallTarget} and run
 * the node. Whether such a target is picked up by the optimizing runtime is exactly what the
 * {@link HelloTruffle} probe checks.
 */
public final class HelloRootNode extends RootNode {

    @Child private AddNode add = AddNodeGen.create();

    public HelloRootNode(TruffleLanguage<?> language) {
        super(language);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object[] args = frame.getArguments();
        long left = (long) args[0];
        long right = (long) args[1];
        return add.execute(left, right);
    }
}
