package protocatalyst.dsl

import scala.language.implicitConversions

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.{NullOrdering, SortDirection}
import protocatalyst.types._

/** Spark-compatible syntax extensions.
  *
  * Import this object to enable Spark DataFrame-style syntax:
  * {{{
  * import protocatalyst.dsl.SparkSyntax.given
  *
  * // Now you can write:
  * users.filter($"age" > 18)
  * users.filter(_.age > 18)  // Without Expr.lit()
  * users.select($"name", $"age")
  * }}}
  */
object SparkSyntax:

  /** String interpolator for column references. Enables Spark's $"columnName" syntax.
    *
    * Example:
    * {{{
    * val ageCol = $"age"  // Returns UntypedColumn
    * users.filter($"age" > 18)
    * }}}
    */
  extension (sc: StringContext)
    def $(args: Any*): UntypedColumn =
      val name = sc.parts.mkString
      UntypedColumn(name)

  /** Create an untyped column reference by name. Similar to Spark's col("name") function.
    */
  def col(name: String): UntypedColumn = UntypedColumn(name)

  // === Implicit conversions from literals to Expr ===

  given intToExpr: Conversion[Int, Expr[Int]] = value =>
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_Int)

  given longToExpr: Conversion[Long, Expr[Long]] = value =>
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_Long)

  given doubleToExpr: Conversion[Double, Expr[Double]] = value =>
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_Double)

  given stringToExpr: Conversion[String, Expr[String]] = value =>
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_String)

  given booleanToExpr: Conversion[Boolean, Expr[Boolean]] = value =>
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_Boolean)

/** Untyped column for Spark-compatible syntax. Type checking deferred to Spark runtime.
  */
class UntypedColumn(val name: String, private val protoExprOverride: Option[ProtoExpr] = None)
    extends Expr[Any]:
  def toProtoExpr: ProtoExpr = protoExprOverride.getOrElse(
    ProtoExpr.ColumnRef(name, None, ProtoType.UnresolvedType("untyped"), nullable = true)
  )

  def encoder: ProtoEncoder[Any] =
    // Create a placeholder encoder for untyped columns
    new ProtoEncoder[Any]:
      def catalystType: ProtoType = ProtoType.UnresolvedType("untyped")
      def schema: protocatalyst.schema.ProtoSchema = protocatalyst.schema.ProtoSchema(Vector.empty)
      def nullable: Boolean = true
      def clsTag: scala.reflect.ClassTag[Any] = scala.reflect.ClassTag.Any
      override def fields: Vector[protocatalyst.encoder.FieldEncoder[?]] = Vector.empty

  private def cmp(op: (ProtoExpr, ProtoExpr) => ProtoExpr, value: Int | Long | Double | String): Expr[Boolean] =
    BinaryExpr(op(this.toProtoExpr, toProtoLiteral(value)), ProtoEncoder.given_ProtoEncoder_Boolean)

  // Comparison operators that accept raw literals
  def >(value: Int | Long | Double): Expr[Boolean] = cmp(ProtoExpr.Gt.apply, value)
  def >=(value: Int | Long | Double): Expr[Boolean] = cmp(ProtoExpr.GtEq.apply, value)
  def <(value: Int | Long | Double): Expr[Boolean] = cmp(ProtoExpr.Lt.apply, value)
  def <=(value: Int | Long | Double): Expr[Boolean] = cmp(ProtoExpr.LtEq.apply, value)
  def ===(value: Int | Long | Double | String): Expr[Boolean] = cmp(ProtoExpr.Eq.apply, value)
  def =!=(value: Int | Long | Double | String): Expr[Boolean] = cmp(ProtoExpr.NotEq.apply, value)

  // Comparison with other expressions
  def ===(other: Expr[?]): Expr[Boolean] =
    BinaryExpr(ProtoExpr.Eq(this.toProtoExpr, other.toProtoExpr), ProtoEncoder.given_ProtoEncoder_Boolean)

  def =!=(other: Expr[?]): Expr[Boolean] =
    BinaryExpr(ProtoExpr.NotEq(this.toProtoExpr, other.toProtoExpr), ProtoEncoder.given_ProtoEncoder_Boolean)

  // Sorting
  def asc: SortExpr =
    new SortExpr(this.toProtoExpr, SortDirection.Ascending, NullOrdering.NullsLast)
  def desc: SortExpr =
    new SortExpr(this.toProtoExpr, SortDirection.Descending, NullOrdering.NullsFirst)

  // Alias
  override def as(alias: String): UntypedColumn =
    new UntypedColumn(alias, Some(ProtoExpr.Alias(this.toProtoExpr, alias)))
