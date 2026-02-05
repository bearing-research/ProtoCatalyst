package protocatalyst.testutil

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

/** DSL extensions for building test plans and expressions fluently.
  *
  * Inspired by Spark Catalyst's DSL package.
  *
  * Usage:
  * {{{
  * import protocatalyst.testutil.PlanDsl._
  *
  * // Traditional style
  * val expr = col("a") + col("b") * lit(2)
  * val filter = col("a").isNull && (col("b") === lit(1) || col("c") > lit(5))
  *
  * // Spark-style $ interpolator
  * val expr2 = $"a" + $"b" * lit(2)
  * val filter2 = $"a".isNull && ($"b" === lit(1) || $"c" > lit(5))
  *
  * val plan = relation("t")
  *   .where($"a" > lit(10))
  *   .select($"a", $"b")
  * }}}
  */
object PlanDsl:

  // ========================================================
  // Expression DSL
  // ========================================================

  /** Extension methods for expressions. */
  extension (expr: ProtoExpr)
    // Arithmetic operators
    def +(other: ProtoExpr): ProtoExpr = ProtoExpr.Add(expr, other)
    def -(other: ProtoExpr): ProtoExpr = ProtoExpr.Subtract(expr, other)
    def *(other: ProtoExpr): ProtoExpr = ProtoExpr.Multiply(expr, other)
    def /(other: ProtoExpr): ProtoExpr = ProtoExpr.Divide(expr, other)
    def %(other: ProtoExpr): ProtoExpr = ProtoExpr.Pmod(expr, other)

    // Comparison operators
    def ===(other: ProtoExpr): ProtoExpr = ProtoExpr.Eq(expr, other)
    def =!=(other: ProtoExpr): ProtoExpr = ProtoExpr.NotEq(expr, other)
    def <(other: ProtoExpr): ProtoExpr = ProtoExpr.Lt(expr, other)
    def <=(other: ProtoExpr): ProtoExpr = ProtoExpr.LtEq(expr, other)
    def >(other: ProtoExpr): ProtoExpr = ProtoExpr.Gt(expr, other)
    def >=(other: ProtoExpr): ProtoExpr = ProtoExpr.GtEq(expr, other)

    // Logical operators
    def &&(other: ProtoExpr): ProtoExpr = ProtoExpr.And(Vector(expr, other))
    def ||(other: ProtoExpr): ProtoExpr = ProtoExpr.Or(Vector(expr, other))
    def unary_! : ProtoExpr = ProtoExpr.Not(expr)

    // Null checks
    def isNull: ProtoExpr = ProtoExpr.IsNull(expr)
    def isNotNull: ProtoExpr = ProtoExpr.IsNotNull(expr)

    // Alias
    def as(name: String): ProtoExpr = ProtoExpr.Alias(expr, name)

    // Cast
    def cast(dataType: ProtoType): ProtoExpr = ProtoExpr.Cast(expr, dataType)

    // IN operator
    def in(values: ProtoExpr*): ProtoExpr = ProtoExpr.In(expr, values.toVector)

    // LIKE operator
    def like(pattern: String): ProtoExpr =
      ProtoExpr.Like(expr, ProtoExpr.Literal(LiteralValue.StringValue(pattern)), None)

    // Sort order
    def asc: SortOrder = SortOrder(expr, SortDirection.Ascending, NullOrdering.NullsFirst)
    def desc: SortOrder = SortOrder(expr, SortDirection.Descending, NullOrdering.NullsLast)
    def ascNullsLast: SortOrder = SortOrder(expr, SortDirection.Ascending, NullOrdering.NullsLast)
    def descNullsFirst: SortOrder =
      SortOrder(expr, SortDirection.Descending, NullOrdering.NullsFirst)

  // ========================================================
  // Plan DSL
  // ========================================================

  /** Extension methods for logical plans. */
  extension (plan: ProtoLogicalPlan)
    /** Add a filter (WHERE clause). */
    def where(condition: ProtoExpr): ProtoLogicalPlan =
      ProtoLogicalPlan.Filter(condition, plan)

    /** Add a projection (SELECT clause). */
    def select(exprs: ProtoExpr*): ProtoLogicalPlan =
      ProtoLogicalPlan.Project(exprs.toVector, plan)

    /** Add grouping and aggregation (GROUP BY clause). */
    def groupBy(grouping: ProtoExpr*)(aggregates: ProtoExpr*): ProtoLogicalPlan =
      ProtoLogicalPlan.Aggregate(grouping.toVector, aggregates.toVector, plan)

    /** Add an aggregate without explicit grouping (whole-table aggregation). */
    def aggregate(aggregates: ProtoExpr*): ProtoLogicalPlan =
      ProtoLogicalPlan.Aggregate(Vector.empty, aggregates.toVector, plan)

    /** Add a sort (ORDER BY clause). */
    def orderBy(orders: SortOrder*): ProtoLogicalPlan =
      ProtoLogicalPlan.Sort(orders.toVector, global = true, plan)

    /** Add a sort with global flag. */
    def sort(orders: SortOrder*)(global: Boolean = true): ProtoLogicalPlan =
      ProtoLogicalPlan.Sort(orders.toVector, global, plan)

    /** Add a limit. */
    def limit(n: Int): ProtoLogicalPlan =
      ProtoLogicalPlan.Limit(n, plan)

    /** Add distinct. */
    def distinct: ProtoLogicalPlan =
      ProtoLogicalPlan.Distinct(plan)

    /** Add a subquery alias. */
    def subquery(alias: String): ProtoLogicalPlan =
      ProtoLogicalPlan.SubqueryAlias(alias, plan)

    /** Join with another plan. */
    def join(
        other: ProtoLogicalPlan,
        joinType: JoinType = JoinType.Inner,
        condition: Option[ProtoExpr] = None
    ): ProtoLogicalPlan =
      ProtoLogicalPlan.Join(plan, other, joinType, condition)

    /** Inner join with condition. */
    def innerJoin(other: ProtoLogicalPlan, condition: ProtoExpr): ProtoLogicalPlan =
      ProtoLogicalPlan.Join(plan, other, JoinType.Inner, Some(condition))

    /** Left outer join with condition. */
    def leftJoin(other: ProtoLogicalPlan, condition: ProtoExpr): ProtoLogicalPlan =
      ProtoLogicalPlan.Join(plan, other, JoinType.LeftOuter, Some(condition))

    /** Right outer join with condition. */
    def rightJoin(other: ProtoLogicalPlan, condition: ProtoExpr): ProtoLogicalPlan =
      ProtoLogicalPlan.Join(plan, other, JoinType.RightOuter, Some(condition))

    /** Full outer join with condition. */
    def fullJoin(other: ProtoLogicalPlan, condition: ProtoExpr): ProtoLogicalPlan =
      ProtoLogicalPlan.Join(plan, other, JoinType.FullOuter, Some(condition))

    /** Left semi join with condition. */
    def semiJoin(other: ProtoLogicalPlan, condition: ProtoExpr): ProtoLogicalPlan =
      ProtoLogicalPlan.Join(plan, other, JoinType.LeftSemi, Some(condition))

    /** Left anti join with condition. */
    def antiJoin(other: ProtoLogicalPlan, condition: ProtoExpr): ProtoLogicalPlan =
      ProtoLogicalPlan.Join(plan, other, JoinType.LeftAnti, Some(condition))

    /** Cross join. */
    def crossJoin(other: ProtoLogicalPlan): ProtoLogicalPlan =
      ProtoLogicalPlan.Join(plan, other, JoinType.Cross, None)

    /** Union with another plan. */
    def union(other: ProtoLogicalPlan): ProtoLogicalPlan =
      ProtoLogicalPlan.Union(Vector(plan, other), byName = false, allowMissingColumns = false)

    /** Intersect with another plan. */
    def intersect(other: ProtoLogicalPlan, isAll: Boolean = false): ProtoLogicalPlan =
      ProtoLogicalPlan.Intersect(plan, other, isAll)

    /** Except with another plan. */
    def except(other: ProtoLogicalPlan, isAll: Boolean = false): ProtoLogicalPlan =
      ProtoLogicalPlan.Except(plan, other, isAll)

  // ========================================================
  // Factory Methods
  // ========================================================

  /** String interpolator for column references. Enables Spark's $"columnName" syntax.
    *
    * Example:
    * {{{
    * val ageCol = $"age"
    * relation("users").where($"age" > lit(18))
    * }}}
    */
  extension (sc: StringContext)
    def $(args: Any*): ProtoExpr =
      ProtoExpr.ColumnRef(sc.parts.mkString, None, ProtoType.IntegerType, nullable = true)

  /** Create a relation reference (table scan). */
  def relation(name: String): ProtoLogicalPlan =
    ProtoLogicalPlan.RelationRef(
      name,
      None,
      SchemaContract(name, Vector.empty, SchemaFingerprint.fromLong(0L))
    )

  /** Create a relation reference with alias. */
  def relation(name: String, alias: String): ProtoLogicalPlan =
    ProtoLogicalPlan.RelationRef(
      name,
      Some(alias),
      SchemaContract(name, Vector.empty, SchemaFingerprint.fromLong(0L))
    )

  /** Create a column reference. */
  def col(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.IntegerType, nullable = true)

  /** Create a column reference with qualifier. */
  def col(qualifier: String, name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, Some(qualifier), ProtoType.IntegerType, nullable = true)

  /** Create a column reference with specific type. */
  def colOf(name: String, dataType: ProtoType): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, dataType, nullable = true)

  // Typed column helpers
  /** Create an integer column reference. */
  def intCol(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.IntegerType, nullable = true)

  /** Create a string column reference. */
  def stringCol(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.StringType, nullable = true)

  /** Create a boolean column reference. */
  def boolCol(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.BooleanType, nullable = true)

  /** Create a long column reference. */
  def longCol(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.LongType, nullable = true)

  /** Create a double column reference. */
  def doubleCol(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.DoubleType, nullable = true)

  /** Create a non-nullable column reference. */
  def nonNullCol(name: String, dataType: ProtoType = ProtoType.IntegerType): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, dataType, nullable = false)

  // Literal factory methods
  def lit(value: Int): ProtoExpr = ProtoExpr.Literal(LiteralValue.IntValue(value))
  def lit(value: Long): ProtoExpr = ProtoExpr.Literal(LiteralValue.LongValue(value))
  def lit(value: Double): ProtoExpr = ProtoExpr.Literal(LiteralValue.DoubleValue(value))
  def lit(value: Float): ProtoExpr = ProtoExpr.Literal(LiteralValue.FloatValue(value))
  def lit(value: String): ProtoExpr = ProtoExpr.Literal(LiteralValue.StringValue(value))
  def lit(value: Boolean): ProtoExpr = ProtoExpr.Literal(LiteralValue.BooleanValue(value))
  def litNull(dataType: ProtoType): ProtoExpr = ProtoExpr.Literal(LiteralValue.NullValue(dataType))

  // Aggregate functions
  def sum(expr: ProtoExpr): ProtoExpr = ProtoExpr.Sum(expr)
  def count(expr: ProtoExpr, distinct: Boolean = false): ProtoExpr = ProtoExpr.Count(expr, distinct)
  def countDistinct(expr: ProtoExpr): ProtoExpr = ProtoExpr.Count(expr, distinct = true)
  def avg(expr: ProtoExpr): ProtoExpr = ProtoExpr.Avg(expr)
  def min(expr: ProtoExpr): ProtoExpr = ProtoExpr.Min(expr)
  def max(expr: ProtoExpr): ProtoExpr = ProtoExpr.Max(expr)

  // Conditional expressions
  def when(condition: ProtoExpr, value: ProtoExpr): CaseWhenBuilder =
    CaseWhenBuilder(Vector((condition, value)), None)

  def ifExpr(condition: ProtoExpr, trueValue: ProtoExpr, falseValue: ProtoExpr): ProtoExpr =
    ProtoExpr.If(condition, trueValue, falseValue)

  def coalesce(exprs: ProtoExpr*): ProtoExpr =
    ProtoExpr.Coalesce(exprs.toVector)

  // ========================================================
  // Helper Classes
  // ========================================================

  /** Builder for CASE WHEN expressions. */
  case class CaseWhenBuilder(
      branches: Vector[(ProtoExpr, ProtoExpr)],
      elseValue: Option[ProtoExpr]
  ):
    def when(condition: ProtoExpr, value: ProtoExpr): CaseWhenBuilder =
      copy(branches = branches :+ (condition, value))

    def otherwise(value: ProtoExpr): ProtoExpr =
      ProtoExpr.CaseWhen(branches, Some(value))

    def end: ProtoExpr =
      ProtoExpr.CaseWhen(branches, elseValue)
