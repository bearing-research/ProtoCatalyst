package protocatalyst.truffle;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;

import protocatalyst.truffle.slice.ColumnBatch;
import protocatalyst.truffle.slice.Q6SliceAst;

/**
 * Phase-4 native-image driver (see {@code docs/compiler/TRUFFLE_EXPLORATION.md} §4 Phase 4).
 *
 * <p>Builds the fused-row Q6 plan <b>at runtime</b> (a Truffle AST composed of pre-existing node
 * types — the analogue of planning {@code spark.sql(userInput)} at runtime) and executes it. The
 * point of compiling this with {@code native-image} is the existence proof the AOT roadmap says has
 * no prior art: a query plan, built and run at runtime, executes inside a closed-world native binary
 * with <b>no runtime bytecode generation</b> — the thing Janino/WSCG cannot do. Compile-time codegen
 * (Option A) can't run dynamic plans at all; here the plan is dynamic and still AOT-legal because the
 * nodes are pre-compiled and only composed at runtime.
 *
 * <p>Prints the active Truffle runtime, the result, and the wall-clock from process logic start to
 * first result (the cold-start metric that matters for short-lived clients).
 */
public final class Q6Native {

    public static void main(String[] args) {
        int rows = args.length > 0 ? Integer.parseInt(args[0]) : 65_536;

        long t0 = System.nanoTime();
        ColumnBatch batch = Q6SliceAst.synthetic(rows);
        CallTarget plan = Q6SliceAst.rowAst().getCallTarget(); // plan built at runtime
        double[] out = new double[rows];
        int k = (int) plan.call(batch, out);
        double sum = 0;
        for (int i = 0; i < k; i++) {
            sum += out[i];
        }
        long t1 = System.nanoTime();

        System.out.println("runtime          : " + Truffle.getRuntime().getName());
        System.out.println("rows in          : " + rows);
        System.out.println("matches          : " + k);
        System.out.println("sum(ep*disc)     : " + sum);
        System.out.printf("plan+exec wall   : %.3f ms%n", (t1 - t0) / 1e6);
    }
}
