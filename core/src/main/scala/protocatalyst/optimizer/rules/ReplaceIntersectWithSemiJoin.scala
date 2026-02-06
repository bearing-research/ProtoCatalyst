package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan._
import protocatalyst.types._

/** Replaces INTERSECT operations with left-semi join patterns.
  *
  * INTERSECT returns rows that exist in both sides. This can be implemented as a left-semi join,
  * which is often more efficient.
  *
  * Transformation:
  * {{{
  * -- Before:
  * SELECT a, b FROM t1 INTERSECT SELECT a, b FROM t2
  *
  * -- After:
  * SELECT DISTINCT a, b FROM t1
  * WHERE EXISTS (SELECT 1 FROM t2 WHERE t1.a = t2.a AND t1.b = t2.b)
  *
  * -- Or equivalently as a plan:
  * Distinct(
  *   LeftSemiJoin(t1, t2, t1.a = t2.a AND t1.b = t2.b)
  * )
  * }}}
  *
  * Note: This only applies to INTERSECT (not INTERSECT ALL), which removes duplicates.
  *
  * Based on Spark Catalyst's ReplaceIntersectWithSemiJoin rule (Optimizer.scala).
  */
object ReplaceIntersectWithSemiJoin extends Rule:
  override val ruleName: String = "ReplaceIntersectWithSemiJoin"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // INTERSECT (not ALL) can be converted to left-semi join + distinct
      // Only when both sides are Projects (so we know the columns)
      case ProtoLogicalPlan.Intersect(
            left @ ProtoLogicalPlan.Project(leftList, _),
            right @ ProtoLogicalPlan.Project(rightList, _),
            isAll
          ) if !isAll && leftList.size == rightList.size =>
        val joinCondition = buildJoinCondition(leftList, rightList)
        joinCondition match
          case Some(cond) =>
            ProtoLogicalPlan.Distinct(
              ProtoLogicalPlan.Join(left, right, JoinType.LeftSemi, Some(cond))
            )
          case None =>
            ProtoLogicalPlan.Intersect(left, right, isAll)
    }

  /** Build join condition from two project lists.
    */
  private def buildJoinCondition(
      leftList: Vector[ProtoExpr],
      rightList: Vector[ProtoExpr]
  ): Option[ProtoExpr] =
    val leftCols = leftList.map(getColumnInfo)
    val rightCols = rightList.map(getColumnInfo)

    if leftCols.exists(_.isEmpty) || rightCols.exists(_.isEmpty) then None
    else
      val conditions =
        leftCols.zip(rightCols).collect { case (Some((ln, lt, lq)), Some((rn, rt, rq))) =>
          val leftCol = ProtoExpr.ColumnRef(ln, lq, lt, nullable = true)
          val rightCol = ProtoExpr.ColumnRef(rn, rq, rt, nullable = true)
          ProtoExpr.Eq(leftCol, rightCol)
        }

      conditions match
        case Vector(single) => Some(single)
        case multiple       => Some(ProtoExpr.And(multiple))

  private def getColumnInfo(expr: ProtoExpr): Option[(String, ProtoType, Option[String])] =
    expr match
      case ProtoExpr.ColumnRef(name, qual, dt, _)                  => Some((name, dt, qual))
      case ProtoExpr.Alias(ProtoExpr.ColumnRef(_, _, dt, _), name) => Some((name, dt, None))
      case ProtoExpr.Alias(_, name) => Some((name, ProtoType.StringType, None))
      case _                        => None
