package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan._

/** Propagates empty relations through the plan.
  *
  * When a child relation is known to be empty (e.g., Limit 0), we can simplify or eliminate parent
  * operators. Note: We use `Limit(0, ...)` as a proxy for EmptyRelation since we don't have schema
  * information to create a true empty relation.
  *
  * Examples:
  *   - `Project(Limit(0, x))` → `Limit(0, Project(..., x))` (keep the structure but mark as empty)
  *   - `Filter(Limit(0, x))` → `Limit(0, x)` (filter on empty is empty)
  *   - `Join(Limit(0, x), y, Inner)` → `Limit(0, x)` (inner join with empty is empty)
  *   - `Union(Limit(0, x), y)` → `y` (empty union member can be removed)
  *
  * Based on Spark Catalyst's PropagateEmptyRelation rule (Optimizer.scala).
  */
object PropagateEmptyRelation extends Rule:
  override val ruleName: String = "PropagateEmptyRelation"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Filter on empty → empty
      case ProtoLogicalPlan.Filter(_, child) if isEmpty(child) =>
        child

      // Sort on empty → empty
      case ProtoLogicalPlan.Sort(_, child) if isEmpty(child) =>
        child

      // Distinct on empty → empty
      case ProtoLogicalPlan.Distinct(child) if isEmpty(child) =>
        child

      // Aggregate on empty with no grouping keys → single row with null/zero aggregates
      // (We can't fully optimize this without more info, so leave it)

      // Aggregate on empty with grouping keys → empty
      case agg @ ProtoLogicalPlan.Aggregate(groupingExprs, _, child)
          if isEmpty(child) && groupingExprs.nonEmpty =>
        ProtoLogicalPlan.Limit(0, agg)

      // Inner join with empty → empty
      case ProtoLogicalPlan.Join(left, _, JoinType.Inner, _) if isEmpty(left) =>
        left

      case ProtoLogicalPlan.Join(_, right, JoinType.Inner, _) if isEmpty(right) =>
        right

      // Cross join with empty → empty
      case ProtoLogicalPlan.Join(left, _, JoinType.Cross, _) if isEmpty(left) =>
        left

      case ProtoLogicalPlan.Join(_, right, JoinType.Cross, _) if isEmpty(right) =>
        right

      // Left semi join with empty left → empty
      case ProtoLogicalPlan.Join(left, _, JoinType.LeftSemi, _) if isEmpty(left) =>
        left

      // Left semi join with empty right → empty
      case ProtoLogicalPlan.Join(left, _, JoinType.LeftSemi, _) if isEmpty(left) =>
        left

      // Left anti join with empty left → empty
      case ProtoLogicalPlan.Join(left, _, JoinType.LeftAnti, _) if isEmpty(left) =>
        left

      // Left anti join with empty right → left (no matches to exclude)
      case ProtoLogicalPlan.Join(left, right, JoinType.LeftAnti, _) if isEmpty(right) =>
        left

      // Union with empty members - remove the empty ones
      case ProtoLogicalPlan.Union(children, byName, allowMissing) =>
        val nonEmpty = children.filterNot(isEmpty)
        nonEmpty match
          case Vector() =>
            children.headOption.getOrElse(ProtoLogicalPlan.Union(children, byName, allowMissing))
          case Vector(single) => single
          case _              => ProtoLogicalPlan.Union(nonEmpty, byName, allowMissing)

      // Except with empty left → empty
      case ProtoLogicalPlan.Except(left, _, _) if isEmpty(left) =>
        left

      // Except with empty right → left (nothing to exclude)
      case ProtoLogicalPlan.Except(left, right, _) if isEmpty(right) =>
        left

      // Intersect with empty → empty
      case ProtoLogicalPlan.Intersect(left, _, _) if isEmpty(left) =>
        left

      case ProtoLogicalPlan.Intersect(_, right, _) if isEmpty(right) =>
        right
    }

  /** Check if a plan is known to be empty.
    */
  private def isEmpty(plan: ProtoLogicalPlan): Boolean = plan match
    case ProtoLogicalPlan.Limit(0, _) => true
    case _                            => false
