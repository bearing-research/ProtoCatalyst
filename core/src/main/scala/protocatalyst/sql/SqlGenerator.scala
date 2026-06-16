package protocatalyst.sql

import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{JoinType, ProtoLogicalPlan}

/** Converts [[ProtoLogicalPlan]] to SQL query strings (ANSI SQL / DataFusion compatible).
  *
  * Produces executable SQL queries from ProtoCatalyst's IR.
  */
object SqlGenerator:

  private var subqueryCounter = 0

  /** Generate SQL query from ProtoLogicalPlan.
    *
    * Examples:
    *   - `RelationRef("users", ...)` → `"users"`
    *   - `Project(..., Filter(..., RelationRef(...)))` → `"SELECT ... FROM ... WHERE ..."`
    */
  def generate(plan: ProtoLogicalPlan): String =
    subqueryCounter = 0
    generateInternal(plan)

  private def generateInternal(plan: ProtoLogicalPlan): String = plan match
    // ========== Leaf nodes ==========
    case ProtoLogicalPlan.RelationRef(name, aliasOpt, _) =>
      aliasOpt match
        case Some(alias) if alias != name => s"$name AS $alias"
        case _                            => name

    case ProtoLogicalPlan.Values(rows, schema) =>
      if rows.isEmpty then s"VALUES (${schema.fields.map(_ => "NULL").mkString(", ")})"
      else
        val rowStrings = rows.map { row =>
          s"(${row.map(ExprSqlGenerator.generate).mkString(", ")})"
        }
        s"VALUES ${rowStrings.mkString(", ")}"

    // ========== Unary operators ==========
    case ProtoLogicalPlan.Project(projectList, child) =>
      val selectList = projectList.map(ExprSqlGenerator.generate).mkString(", ")
      // A projection over the unit relation (one empty row, no columns) is a literal SELECT with
      // no FROM — `SELECT 42`. `... FROM (VALUES ())` is both invalid and zero-row in DataFusion.
      if isUnitRelation(child) then s"SELECT $selectList"
      else s"SELECT $selectList FROM ${wrapAsSubquery(child)}"

    case ProtoLogicalPlan.Filter(condition, child) =>
      val whereSql = ExprSqlGenerator.generate(condition)
      val childSql = wrapAsSubquery(child)
      s"SELECT * FROM $childSql WHERE $whereSql"

    case ProtoLogicalPlan.Aggregate(groupingExprs, aggregateExprs, child) =>
      val selectExprs = (groupingExprs ++ aggregateExprs).map(ExprSqlGenerator.generate)
      val selectList = selectExprs.mkString(", ")
      val childSql = wrapAsSubquery(child)
      val groupByList = groupingExprs.map(ExprSqlGenerator.generate).mkString(", ")
      s"SELECT $selectList FROM $childSql GROUP BY $groupByList"

    case ProtoLogicalPlan.Sort(order, child) =>
      val orderByList = order.map(ExprSqlGenerator.generateSortOrder).mkString(", ")
      val childSql = wrapAsSubquery(child)
      s"SELECT * FROM $childSql ORDER BY $orderByList"

    case ProtoLogicalPlan.Limit(limit, child) =>
      val childSql = wrapAsSubquery(child)
      s"SELECT * FROM $childSql LIMIT $limit"

    case ProtoLogicalPlan.Distinct(child) =>
      val childSql = wrapAsSubquery(child)
      s"SELECT DISTINCT * FROM $childSql"

    case ProtoLogicalPlan.SubqueryAlias(alias, child) =>
      s"(${generateInternal(child)}) AS $alias"

    // ========== Binary operators ==========
    case ProtoLogicalPlan.Join(left, right, joinType, condition) =>
      val leftSql = wrapAsSubquery(left)
      val rightSql = wrapAsSubquery(right)
      val joinTypeStr = joinType match
        case JoinType.Inner      => "INNER JOIN"
        case JoinType.LeftOuter  => "LEFT OUTER JOIN"
        case JoinType.RightOuter => "RIGHT OUTER JOIN"
        case JoinType.FullOuter  => "FULL OUTER JOIN"
        case JoinType.LeftSemi   => "LEFT SEMI JOIN"
        case JoinType.LeftAnti   => "LEFT ANTI JOIN"
        case JoinType.Cross      => "CROSS JOIN"

      condition match
        case Some(cond) if joinType != JoinType.Cross =>
          val condSql = ExprSqlGenerator.generate(cond)
          s"SELECT * FROM $leftSql $joinTypeStr $rightSql ON $condSql"
        case None if joinType == JoinType.Cross =>
          s"SELECT * FROM $leftSql $joinTypeStr $rightSql"
        case _ =>
          // Cross join with condition, or join without condition (unusual but handle it)
          s"SELECT * FROM $leftSql $joinTypeStr $rightSql"

    // ========== Set operations ==========
    case ProtoLogicalPlan.Union(children, byName, allowMissingColumns) =>
      if children.isEmpty then "SELECT NULL WHERE FALSE" // Empty union
      else
        val childQueries = children.map(generateInternal)
        childQueries.mkString(" UNION ALL ")

    case ProtoLogicalPlan.Intersect(left, right, isAll) =>
      val leftSql = generateInternal(left)
      val rightSql = generateInternal(right)
      val allStr = if isAll then " ALL" else ""
      s"($leftSql) INTERSECT$allStr ($rightSql)"

    case ProtoLogicalPlan.Except(left, right, isAll) =>
      val leftSql = generateInternal(left)
      val rightSql = generateInternal(right)
      val allStr = if isAll then " ALL" else ""
      s"($leftSql) EXCEPT$allStr ($rightSql)"

    // ========== Window ==========
    case ProtoLogicalPlan.Window(windowExprs, partitionSpec, orderSpec, child) =>
      // Window functions are embedded in SELECT expressions
      val selectList = windowExprs.map(ExprSqlGenerator.generate).mkString(", ")
      val childSql = wrapAsSubquery(child)
      s"SELECT $selectList FROM $childSql"

    // ========== CTE (WITH) ==========
    case ProtoLogicalPlan.With(cteRelations, recursive, child) =>
      val recursiveStr = if recursive then "RECURSIVE " else ""
      val cteStrings = cteRelations.map { case (name, ctePlan) =>
        s"$name AS (${generateInternal(ctePlan)})"
      }
      val cteList = cteStrings.mkString(", ")
      val childSql = generateInternal(child)
      s"WITH $recursiveStr$cteList $childSql"

    // ========== Unsupported nodes ==========
    case ProtoLogicalPlan.Pivot(_, _, _, _, _) =>
      throw UnsupportedSqlFeatureException("PIVOT not supported in standard SQL generation")

    case ProtoLogicalPlan.Unpivot(_, _, _, _, _) =>
      throw UnsupportedSqlFeatureException("UNPIVOT not supported in standard SQL generation")

    case ProtoLogicalPlan.LateralJoin(_, _, _) =>
      throw UnsupportedSqlFeatureException("LATERAL JOIN not supported in standard SQL generation")

    case ProtoLogicalPlan.Generate(_, _, _, _) =>
      throw UnsupportedSqlFeatureException(
        "LATERAL VIEW / GENERATE not supported in standard SQL generation"
      )

    case ProtoLogicalPlan.ResolvedHint(hints, child) =>
      // Hints are engine-specific, skip them and process child
      generateInternal(child)

    case ProtoLogicalPlan.Predict(_, _, _) =>
      throw UnsupportedSqlFeatureException("ML Predict node has no SQL representation")

    case ProtoLogicalPlan.Fit(_, _, _, _, _) =>
      throw UnsupportedSqlFeatureException("ML Fit node has no SQL representation")

  end generateInternal

  // ========== Helper methods ==========

  /** Wrap a plan as a subquery if it's not a simple relation reference.
    *
    * Examples:
    *   - `RelationRef("users")` → `"users"`
    *   - `Project(...)` → `"(__subquery_0)"` with generated alias
    */
  private def wrapAsSubquery(plan: ProtoLogicalPlan): String = plan match
    case ProtoLogicalPlan.RelationRef(name, Some(alias), _) if alias != name =>
      s"$name AS $alias"
    case ProtoLogicalPlan.RelationRef(name, _, _) =>
      name
    case ProtoLogicalPlan.SubqueryAlias(alias, _) =>
      generateInternal(plan) // Already has alias
    case ProtoLogicalPlan.Values(_, schema) if schema.fields.nonEmpty =>
      // `(VALUES ...)` columns are named column1, column2, … by DataFusion; alias them to the
      // schema's names so downstream references (e.g. `WHERE id > 1`) resolve.
      val alias = s"__subquery_$subqueryCounter"
      subqueryCounter += 1
      val cols = schema.fields.map(_.name).mkString(", ")
      s"(${generateInternal(plan)}) AS $alias($cols)"
    case _ =>
      val alias = s"__subquery_$subqueryCounter"
      subqueryCounter += 1
      s"(${generateInternal(plan)}) AS $alias"

  /** The unit relation: a single empty row with no columns (`Values` of one empty tuple, or no
    * rows, with an empty schema). A projection over it is a `SELECT` with no `FROM`. */
  private def isUnitRelation(plan: ProtoLogicalPlan): Boolean = plan match
    case ProtoLogicalPlan.Values(rows, schema) =>
      schema.fields.isEmpty && (rows.isEmpty || rows.forall(_.isEmpty))
    case _ => false

end SqlGenerator
