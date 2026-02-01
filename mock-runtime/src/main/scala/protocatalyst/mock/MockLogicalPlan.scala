package protocatalyst.mock

import protocatalyst.mock.MockExpression._

/** Mock Spark LogicalPlan hierarchy.
  *
  * Mirrors org.apache.spark.sql.catalyst.plans.logical.LogicalPlan for testing the conversion layer
  * before real Spark Scala 3 is available.
  */
sealed trait MockLogicalPlan:
  def output: Seq[AttributeReference]
  def children: Seq[MockLogicalPlan]

object MockLogicalPlan:

  // === Leaf Plans ===

  /** Unresolved table reference - needs catalog lookup. */
  case class UnresolvedRelation(tableName: Seq[String]) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = Seq.empty // Unknown until resolved
    def children: Seq[MockLogicalPlan] = Seq.empty

  /** Resolved table/view with known schema. */
  case class LogicalRelation(
      tableName: String,
      output: Seq[AttributeReference]
  ) extends MockLogicalPlan:
    def children: Seq[MockLogicalPlan] = Seq.empty

  /** Inline data (VALUES clause). */
  case class LocalRelation(
      output: Seq[AttributeReference],
      data: Seq[Seq[Any]] = Seq.empty
  ) extends MockLogicalPlan:
    def children: Seq[MockLogicalPlan] = Seq.empty

  // === Unary Plans ===

  /** SELECT column list. */
  case class Project(
      projectList: Seq[MockExpression],
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = projectList.map {
      case a: AttributeReference => a
      case Alias(_, name)        => AttributeReference(name, MockDataType.StringType, true)
      case e                     => AttributeReference("col", e.dataType, e.nullable)
    }
    def children: Seq[MockLogicalPlan] = Seq(child)

  /** WHERE clause. */
  case class Filter(
      condition: MockExpression,
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = child.output
    def children: Seq[MockLogicalPlan] = Seq(child)

  /** GROUP BY with aggregations. */
  case class Aggregate(
      groupingExpressions: Seq[MockExpression],
      aggregateExpressions: Seq[MockExpression],
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = aggregateExpressions.map {
      case a: AttributeReference => a
      case Alias(_, name)        => AttributeReference(name, MockDataType.StringType, true)
      case e                     => AttributeReference("col", e.dataType, e.nullable)
    }
    def children: Seq[MockLogicalPlan] = Seq(child)

  /** ORDER BY clause. */
  case class Sort(
      order: Seq[SortOrder],
      global: Boolean,
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = child.output
    def children: Seq[MockLogicalPlan] = Seq(child)

  /** LIMIT clause. */
  case class GlobalLimit(
      limitExpr: MockExpression,
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = child.output
    def children: Seq[MockLogicalPlan] = Seq(child)

  case class LocalLimit(
      limitExpr: MockExpression,
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = child.output
    def children: Seq[MockLogicalPlan] = Seq(child)

  /** SELECT DISTINCT. */
  case class Distinct(child: MockLogicalPlan) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = child.output
    def children: Seq[MockLogicalPlan] = Seq(child)

  /** Table/subquery alias. */
  case class SubqueryAlias(
      alias: String,
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = child.output.map(a => a.copy(qualifier = Seq(alias)))
    def children: Seq[MockLogicalPlan] = Seq(child)

  /** Window functions. */
  case class Window(
      windowExpressions: Seq[MockExpression],
      partitionSpec: Seq[MockExpression],
      orderSpec: Seq[SortOrder],
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = child.output ++ windowExpressions.map {
      case Alias(_, name) => AttributeReference(name, MockDataType.IntegerType, true)
      case e              => AttributeReference("window", e.dataType, e.nullable)
    }
    def children: Seq[MockLogicalPlan] = Seq(child)

  // === Binary Plans ===

  /** JOIN. */
  case class Join(
      left: MockLogicalPlan,
      right: MockLogicalPlan,
      joinType: JoinType,
      condition: Option[MockExpression]
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = joinType match
      case JoinType.LeftSemi | JoinType.LeftAnti => left.output
      case _                                     => left.output ++ right.output
    def children: Seq[MockLogicalPlan] = Seq(left, right)

  enum JoinType:
    case Inner, LeftOuter, RightOuter, FullOuter, LeftSemi, LeftAnti, Cross

  // === Set Operations ===

  /** UNION. */
  case class Union(
      children: Seq[MockLogicalPlan],
      byName: Boolean = false,
      allowMissingColumns: Boolean = false
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = children.headOption.map(_.output).getOrElse(Seq.empty)

  /** INTERSECT. */
  case class Intersect(
      left: MockLogicalPlan,
      right: MockLogicalPlan,
      isAll: Boolean
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = left.output
    def children: Seq[MockLogicalPlan] = Seq(left, right)

  /** EXCEPT. */
  case class Except(
      left: MockLogicalPlan,
      right: MockLogicalPlan,
      isAll: Boolean
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = left.output
    def children: Seq[MockLogicalPlan] = Seq(left, right)

  // === CTE ===

  /** WITH clause (Common Table Expressions). */
  case class WithCTE(
      cteRelations: Seq[(String, MockLogicalPlan)],
      child: MockLogicalPlan
  ) extends MockLogicalPlan:
    def output: Seq[AttributeReference] = child.output
    def children: Seq[MockLogicalPlan] = cteRelations.map(_._2) :+ child

  /** Reference to a CTE. */
  case class CTERelationRef(
      cteId: Long,
      name: String,
      output: Seq[AttributeReference]
  ) extends MockLogicalPlan:
    def children: Seq[MockLogicalPlan] = Seq.empty
