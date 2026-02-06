package protocatalyst.macros

import scala.quoted._

import protocatalyst.optimizer.Optimizer
import protocatalyst.plan.ProtoLogicalPlan

/** Compile-time optimizer that runs ProtoCatalyst optimizer rules during macro expansion.
  *
  * This enables optimization at Scala compile time rather than query execution time.
  *
  * The main entry point is `optimizeAndLift`, which takes a plan value (already available at
  * compile time in a macro context) and returns an Expr containing the optimized plan.
  */
object CompileTimeOptimizer:
  import ProtoLiftables.given

  /** Optimize a plan and lift it to an Expr.
    *
    * This is the main API for use within macros. The plan value is already available at compile
    * time (e.g., from parsing SQL), so we optimize it and then lift the result to an Expr.
    *
    * @param plan
    *   The logical plan value to optimize
    * @return
    *   An Expr containing the optimized plan
    */
  def optimizeAndLift(plan: ProtoLogicalPlan)(using Quotes): Expr[ProtoLogicalPlan] =
    // Run optimizer at compile time - all 48 rules execute during scalac
    val optimized = Optimizer.optimize(plan)
    // Lift the optimized plan to an Expr (embeds as constant in bytecode)
    Expr(optimized)

  /** Optimize a plan without lifting.
    *
    * Useful when you need the optimized plan value for further processing before lifting.
    */
  def optimize(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    Optimizer.optimize(plan)
