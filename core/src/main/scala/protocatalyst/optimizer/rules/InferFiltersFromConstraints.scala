package protocatalyst.optimizer.rules

import protocatalyst.expr._
import protocatalyst.optimizer._
import protocatalyst.plan._

/** Infers additional filter conditions from join constraints.
  *
  * When two tables are joined with an equality condition, constraints from one side can be inferred
  * and applied to the other side.
  *
  * Examples:
  * {{{
  * -- Before:
  * SELECT * FROM a JOIN b ON a.id = b.id WHERE a.id > 5
  *
  * -- After (inferred filter):
  * SELECT * FROM a JOIN b ON a.id = b.id WHERE a.id > 5 AND b.id > 5
  * }}}
  *
  * This can enable predicate pushdown to both sides of the join.
  *
  * Note: This is a simplified version that handles basic equality joins. The full Spark
  * implementation handles more complex constraint propagation.
  *
  * Based on Spark Catalyst's InferFiltersFromConstraints rule (Optimizer.scala).
  */
object InferFiltersFromConstraints extends Rule:
  override val ruleName: String = "InferFiltersFromConstraints"

  override def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    TreeTransform.transformPlanUp(plan) {
      // Filter over Join - try to infer constraints
      case ProtoLogicalPlan.Filter(
            condition,
            join @ ProtoLogicalPlan.Join(left, right, joinType, Some(joinCond))
          ) if joinType == JoinType.Inner =>
        // Extract equality constraints from join condition
        val equalities = extractEqualities(joinCond)

        if equalities.isEmpty then ProtoLogicalPlan.Filter(condition, join)
        else
          // Extract constraints from filter condition
          val filterConstraints = extractConstraints(condition)

          // For each equality (a.x = b.y), if we have a constraint on a.x,
          // infer the same constraint for b.y
          val inferred = inferConstraints(filterConstraints, equalities)

          if inferred.isEmpty then ProtoLogicalPlan.Filter(condition, join)
          else
            // Add inferred constraints to the filter
            val newCondition = combineWithAnd(condition +: inferred)
            ProtoLogicalPlan.Filter(newCondition, join)
    }

  /** Extract equality pairs from join condition.
    */
  private def extractEqualities(expr: ProtoExpr): Vector[(ProtoExpr, ProtoExpr)] =
    expr match
      case ProtoExpr.Eq(left, right) =>
        Vector((left, right))
      case ProtoExpr.And(children) =>
        children.flatMap(extractEqualities)
      case _ =>
        Vector.empty

  /** Extract constraints from a filter condition.
    *
    * Returns a map from column reference key to the constraint expression.
    */
  private def extractConstraints(expr: ProtoExpr): Vector[(ProtoExpr, ProtoExpr)] =
    def extract(e: ProtoExpr): Vector[(ProtoExpr, ProtoExpr)] = e match
      case ProtoExpr.And(children) =>
        children.flatMap(extract)
      // Column comparison to literal
      case cmp @ ProtoExpr.Gt(col @ ProtoExpr.ColumnRef(_, _, _, _), _) =>
        Vector((col, cmp))
      case cmp @ ProtoExpr.GtEq(col @ ProtoExpr.ColumnRef(_, _, _, _), _) =>
        Vector((col, cmp))
      case cmp @ ProtoExpr.Lt(col @ ProtoExpr.ColumnRef(_, _, _, _), _) =>
        Vector((col, cmp))
      case cmp @ ProtoExpr.LtEq(col @ ProtoExpr.ColumnRef(_, _, _, _), _) =>
        Vector((col, cmp))
      case cmp @ ProtoExpr.Eq(col @ ProtoExpr.ColumnRef(_, _, _, _), lit @ ProtoExpr.Literal(_)) =>
        Vector((col, cmp))
      case cmp @ ProtoExpr.NotEq(col @ ProtoExpr.ColumnRef(_, _, _, _), _) =>
        Vector((col, cmp))
      case cmp @ ProtoExpr.IsNotNull(col @ ProtoExpr.ColumnRef(_, _, _, _)) =>
        Vector((col, cmp))
      case _ =>
        Vector.empty

    extract(expr)

  /** Infer new constraints based on equalities.
    */
  private def inferConstraints(
      constraints: Vector[(ProtoExpr, ProtoExpr)],
      equalities: Vector[(ProtoExpr, ProtoExpr)]
  ): Vector[ProtoExpr] =
    val inferred = for
      (col, constraint) <- constraints
      (eqLeft, eqRight) <- equalities
      newConstraint <- inferFromEquality(col, constraint, eqLeft, eqRight)
    yield newConstraint

    // Remove any constraints that already exist
    val existingExprs = constraints.map(_._2).toSet
    inferred.filterNot(existingExprs.contains)

  /** Infer a constraint for the other side of an equality.
    */
  private def inferFromEquality(
      constraintCol: ProtoExpr,
      constraint: ProtoExpr,
      eqLeft: ProtoExpr,
      eqRight: ProtoExpr
  ): Option[ProtoExpr] =
    if columnsMatch(constraintCol, eqLeft) then
      // Constraint is on left side of equality, infer for right side
      Some(substituteColumn(constraint, constraintCol, eqRight))
    else if columnsMatch(constraintCol, eqRight) then
      // Constraint is on right side of equality, infer for left side
      Some(substituteColumn(constraint, constraintCol, eqLeft))
    else None

  /** Check if two column expressions refer to the same column.
    */
  private def columnsMatch(c1: ProtoExpr, c2: ProtoExpr): Boolean =
    (c1, c2) match
      case (ProtoExpr.ColumnRef(n1, q1, _, _), ProtoExpr.ColumnRef(n2, q2, _, _)) =>
        n1 == n2 && q1 == q2
      case _ => false

  /** Substitute one column for another in a constraint expression.
    */
  private def substituteColumn(
      expr: ProtoExpr,
      oldCol: ProtoExpr,
      newCol: ProtoExpr
  ): ProtoExpr =
    TreeTransform.transformExprUp(expr) {
      case col @ ProtoExpr.ColumnRef(_, _, _, _) if columnsMatch(col, oldCol) =>
        newCol
    }

  private def combineWithAnd(exprs: Vector[ProtoExpr]): ProtoExpr =
    exprs match
      case Vector(single) => single
      case multiple       => ProtoExpr.And(multiple)
