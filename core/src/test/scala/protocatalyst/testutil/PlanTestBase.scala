package protocatalyst.testutil

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

/** Base trait for optimizer plan tests, inspired by Spark Catalyst's PlanTest.
  *
  * Provides utilities for comparing plans and building test fixtures.
  */
trait PlanTestBase:

  // ========================================================
  // Plan Comparison Utilities
  // ========================================================

  /** Compares two plans for structural equality.
    *
    * Unlike simple equality, this normalizes plans before comparison and provides informative error
    * messages on failure.
    */
  def comparePlans(actual: ProtoLogicalPlan, expected: ProtoLogicalPlan): Unit =
    val actualNorm = normalizePlan(actual)
    val expectedNorm = normalizePlan(expected)
    if actualNorm != expectedNorm then
      val diff = sideBySide(planToTree(actualNorm), planToTree(expectedNorm))
      throw new AssertionError(
        s"""Plans do not match!
           |
           |=== Actual ===
           |${planToTree(actualNorm)}
           |
           |=== Expected ===
           |${planToTree(expectedNorm)}
           |
           |=== Side by Side ===
           |$diff
           |""".stripMargin
      )

  /** Compares two expressions for structural equality. */
  def compareExpressions(actual: ProtoExpr, expected: ProtoExpr): Unit =
    if actual != expected then
      throw new AssertionError(
        s"""Expressions do not match!
           |Actual:   $actual
           |Expected: $expected
           |""".stripMargin
      )

  /** Normalize a plan for comparison (e.g., sort children, normalize IDs). */
  protected def normalizePlan(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    // For now, just return as-is. Can be extended to normalize:
    // - Expression IDs
    // - Attribute ordering in certain contexts
    // - Metadata
    plan

  /** Convert plan to tree string for display. */
  protected def planToTree(plan: ProtoLogicalPlan, indent: Int = 0): String =
    val prefix = "  " * indent
    val children = getChildren(plan)
    val childStrings = children.map(c => planToTree(c, indent + 1))
    val nodeStr = planNodeString(plan)
    if childStrings.isEmpty then s"$prefix$nodeStr"
    else s"$prefix$nodeStr\n${childStrings.mkString("\n")}"

  private def planNodeString(plan: ProtoLogicalPlan): String = plan match
    case ProtoLogicalPlan.RelationRef(name, alias, _) =>
      alias.fold(s"RelationRef($name)")(a => s"RelationRef($name AS $a)")
    case ProtoLogicalPlan.Project(list, _) =>
      s"Project(${list.map(exprString).mkString(", ")})"
    case ProtoLogicalPlan.Filter(cond, _) =>
      s"Filter(${exprString(cond)})"
    case ProtoLogicalPlan.Aggregate(grouping, aggs, _) =>
      s"Aggregate(groupBy=[${grouping.map(exprString).mkString(", ")}], aggs=[${aggs.map(exprString).mkString(", ")}])"
    case ProtoLogicalPlan.Join(_, _, jt, cond) =>
      cond.fold(s"Join($jt)")(c => s"Join($jt, ${exprString(c)})")
    case ProtoLogicalPlan.Sort(order, _) =>
      s"Sort(${order.map(o => s"${exprString(o.child)} ${o.direction}").mkString(", ")})"
    case ProtoLogicalPlan.Limit(n, _) =>
      s"Limit($n)"
    case ProtoLogicalPlan.Distinct(_) =>
      "Distinct"
    case ProtoLogicalPlan.Union(_, byName, allowMissing) =>
      s"Union(byName=$byName, allowMissing=$allowMissing)"
    case ProtoLogicalPlan.Intersect(_, _, isAll) =>
      s"Intersect(all=$isAll)"
    case ProtoLogicalPlan.Except(_, _, isAll) =>
      s"Except(all=$isAll)"
    case other =>
      other.toString.take(50)

  private def exprString(expr: ProtoExpr): String = expr match
    case ProtoExpr.ColumnRef(name, qual, _, _) =>
      qual.fold(name)(q => s"$q.$name")
    case ProtoExpr.Literal(v) =>
      literalString(v)
    case ProtoExpr.Alias(child, name) =>
      s"${exprString(child)} AS $name"
    case ProtoExpr.Eq(l, r) =>
      s"${exprString(l)} = ${exprString(r)}"
    case ProtoExpr.And(children) =>
      children.map(exprString).mkString("(", " AND ", ")")
    case ProtoExpr.Or(children) =>
      children.map(exprString).mkString("(", " OR ", ")")
    case ProtoExpr.Add(l, r) =>
      s"(${exprString(l)} + ${exprString(r)})"
    case ProtoExpr.IsNull(child) =>
      s"IsNull(${exprString(child)})"
    case ProtoExpr.IsNotNull(child) =>
      s"IsNotNull(${exprString(child)})"
    case other =>
      other.toString.take(30)

  private def literalString(v: LiteralValue): String = v match
    case LiteralValue.IntValue(i)     => i.toString
    case LiteralValue.LongValue(l)    => s"${l}L"
    case LiteralValue.StringValue(s)  => s"'$s'"
    case LiteralValue.BooleanValue(b) => b.toString
    case LiteralValue.NullValue(_)    => "NULL"
    case other                        => other.toString

  private def getChildren(plan: ProtoLogicalPlan): Vector[ProtoLogicalPlan] = plan match
    case ProtoLogicalPlan.RelationRef(_, _, _)       => Vector.empty
    case ProtoLogicalPlan.Project(_, child)          => Vector(child)
    case ProtoLogicalPlan.Filter(_, child)           => Vector(child)
    case ProtoLogicalPlan.Aggregate(_, _, child)     => Vector(child)
    case ProtoLogicalPlan.Join(l, r, _, _)           => Vector(l, r)
    case ProtoLogicalPlan.Sort(_, child)             => Vector(child)
    case ProtoLogicalPlan.Limit(_, child)            => Vector(child)
    case ProtoLogicalPlan.Distinct(child)            => Vector(child)
    case ProtoLogicalPlan.SubqueryAlias(_, child)    => Vector(child)
    case ProtoLogicalPlan.Union(children, _, _)      => children
    case ProtoLogicalPlan.Intersect(l, r, _)         => Vector(l, r)
    case ProtoLogicalPlan.Except(l, r, _)            => Vector(l, r)
    case ProtoLogicalPlan.Window(_, _, _, child)     => Vector(child)
    case ProtoLogicalPlan.Values(_, _)               => Vector.empty
    case ProtoLogicalPlan.With(_, _, child)          => Vector(child)
    case ProtoLogicalPlan.ResolvedHint(_, child)     => Vector(child)
    case ProtoLogicalPlan.Pivot(_, _, _, _, child)   => Vector(child)
    case ProtoLogicalPlan.Unpivot(_, _, _, _, child) => Vector(child)
    case ProtoLogicalPlan.LateralJoin(l, r, _)       => Vector(l, r)
    case ProtoLogicalPlan.Generate(_, _, _, child)   => Vector(child)
    case ProtoLogicalPlan.Predict(_, _, child)       => Vector(child)
    case ProtoLogicalPlan.Fit(_, _, _, _, child)     => Vector(child)

  private def sideBySide(left: String, right: String): String =
    val leftLines = left.split("\n")
    val rightLines = right.split("\n")
    val maxLen = leftLines.map(_.length).maxOption.getOrElse(0)
    val maxLines = math.max(leftLines.length, rightLines.length)

    (0 until maxLines)
      .map { i =>
        val l = leftLines.lift(i).getOrElse("")
        val r = rightLines.lift(i).getOrElse("")
        val marker = if l == r then " " else "!"
        val paddedL = l.padTo(maxLen, ' ')
        s"$marker $paddedL | $r"
      }
      .mkString("\n")

  // ========================================================
  // Test Fixture Helpers
  // ========================================================

  /** Create a test relation with given name. */
  def testRelation(name: String): ProtoLogicalPlan =
    ProtoLogicalPlan.RelationRef(name, None, emptyContract(name))

  /** Create an empty schema contract. */
  def emptyContract(name: String): SchemaContract =
    SchemaContract(name, Vector.empty, SchemaFingerprint.fromLong(0L))

  /** Create a column reference with IntegerType. */
  def col(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.IntegerType, nullable = true)

  /** Create a column reference with qualifier. */
  def col(qualifier: String, name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, Some(qualifier), ProtoType.IntegerType, nullable = true)

  /** Create a column reference with specific type. */
  def col(name: String, dataType: ProtoType): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, dataType, nullable = true)

  /** Create an integer literal. */
  def lit(value: Int): ProtoExpr =
    ProtoExpr.Literal(LiteralValue.IntValue(value))

  /** Create a long literal. */
  def lit(value: Long): ProtoExpr =
    ProtoExpr.Literal(LiteralValue.LongValue(value))

  /** Create a string literal. */
  def lit(value: String): ProtoExpr =
    ProtoExpr.Literal(LiteralValue.StringValue(value))

  /** Create a boolean literal. */
  def lit(value: Boolean): ProtoExpr =
    ProtoExpr.Literal(LiteralValue.BooleanValue(value))

  /** Create a null literal with given type. */
  def litNull(dataType: ProtoType): ProtoExpr =
    ProtoExpr.Literal(LiteralValue.NullValue(dataType))
