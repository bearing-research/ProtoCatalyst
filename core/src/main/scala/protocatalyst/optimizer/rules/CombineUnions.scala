package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan._

/** Flattens nested Union operators into a single Union.
  *
  * This rule combines adjacent Union operators to reduce plan complexity and enable more efficient
  * execution. Only unions with the same settings (byName, allowMissingColumns) can be combined.
  *
  * Examples:
  * {{{
  * Before:
  *   Union
  *     Union
  *       Scan A
  *       Scan B
  *     Scan C
  *
  * After:
  *   Union
  *     Scan A
  *     Scan B
  *     Scan C
  * }}}
  *
  * {{{
  * Before:
  *   Union
  *     Scan A
  *     Union
  *       Scan B
  *       Union
  *         Scan C
  *         Scan D
  *
  * After:
  *   Union
  *     Scan A
  *     Scan B
  *     Scan C
  *     Scan D
  * }}}
  *
  * Based on Spark Catalyst's CombineUnions rule (Optimizer.scala:600-650).
  */
object CombineUnions extends Rule:
  override val ruleName: String = "CombineUnions"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      case union @ ProtoLogicalPlan.Union(children, byName, allowMissing) =>
        val flattened = children.flatMap {
          // Only flatten unions with matching settings
          case ProtoLogicalPlan.Union(innerChildren, innerByName, innerAllowMissing)
              if innerByName == byName && innerAllowMissing == allowMissing =>
            innerChildren
          case other =>
            Vector(other)
        }
        // Single-child Union is just the child
        if flattened.size == 1 then flattened.head
        // Only create new Union if we actually flattened something
        else if flattened.length != children.length then
          ProtoLogicalPlan.Union(flattened, byName, allowMissing)
        else union
    }
