package protocatalyst.dsl

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.expr.ProtoExpr
import protocatalyst.types.*
import scala.util.NotGiven

/** Type-safe expression wrapper. A is the Scala type this expression evaluates to. */
trait Expr[A]:
  def toProtoExpr: ProtoExpr
  def encoder: ProtoEncoder[A]

  // Comparison operators - return Expr[Boolean]
  def ===[B](other: Expr[B])(using ev: A =:= B): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Eq(this.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def =!=[B](other: Expr[B])(using ev: A =:= B): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.NotEq(this.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  // Alias
  def as(name: String): Expr[A] =
    AliasExpr(this, name)

object Expr:
  // Literal constructors
  def lit(value: Boolean): Expr[Boolean] =
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_Boolean)

  def lit(value: Int): Expr[Int] =
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_Int)

  def lit(value: Long): Expr[Long] =
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_Long)

  def lit(value: Double): Expr[Double] =
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_Double)

  def lit(value: String): Expr[String] =
    LiteralExpr(ProtoExpr.lit(value), ProtoEncoder.given_ProtoEncoder_String)

/** Numeric expression operations */
extension [A](expr: Expr[A])(using num: Numeric[A], enc: ProtoEncoder[A])
  def +(other: Expr[A]): Expr[A] =
    BinaryExpr(ProtoExpr.Add(expr.toProtoExpr, other.toProtoExpr), enc)

  def -(other: Expr[A]): Expr[A] =
    BinaryExpr(ProtoExpr.Subtract(expr.toProtoExpr, other.toProtoExpr), enc)

  def *(other: Expr[A]): Expr[A] =
    BinaryExpr(ProtoExpr.Multiply(expr.toProtoExpr, other.toProtoExpr), enc)

  def /(other: Expr[A]): Expr[A] =
    BinaryExpr(ProtoExpr.Divide(expr.toProtoExpr, other.toProtoExpr), enc)

/** Ordered expression operations (comparisons) for typed expressions. These work with typed columns
  * like users.col[Int]("age").
  */
extension [A <: Int | Long | Double | Float | String](expr: Expr[A])(using ord: Ordering[A])
  def <(other: Expr[A]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Lt(expr.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <=(other: Expr[A]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.LtEq(expr.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def >(other: Expr[A]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Gt(expr.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def >=(other: Expr[A]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.GtEq(expr.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

/** Boolean expression operations */
extension (expr: Expr[Boolean])
  def &&(other: Expr[Boolean]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.And(Vector(expr.toProtoExpr, other.toProtoExpr)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def ||(other: Expr[Boolean]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Or(Vector(expr.toProtoExpr, other.toProtoExpr)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def unary_! : Expr[Boolean] =
    UnaryExpr(ProtoExpr.Not(expr.toProtoExpr), ProtoEncoder.given_ProtoEncoder_Boolean)

/** String expression operations */
extension (expr: Expr[String])
  def ++(other: Expr[String]): Expr[String] =
    BinaryExpr(
      ProtoExpr.Concat(Vector(expr.toProtoExpr, other.toProtoExpr)),
      ProtoEncoder.given_ProtoEncoder_String
    )

  def upper: Expr[String] =
    UnaryExpr(ProtoExpr.Upper(expr.toProtoExpr), ProtoEncoder.given_ProtoEncoder_String)

  def lower: Expr[String] =
    UnaryExpr(ProtoExpr.Lower(expr.toProtoExpr), ProtoEncoder.given_ProtoEncoder_String)

/** Null handling operations */
extension [A](expr: Expr[A])
  def isNull: Expr[Boolean] =
    UnaryExpr(ProtoExpr.IsNull(expr.toProtoExpr), ProtoEncoder.given_ProtoEncoder_Boolean)

  def isNotNull: Expr[Boolean] =
    UnaryExpr(ProtoExpr.IsNotNull(expr.toProtoExpr), ProtoEncoder.given_ProtoEncoder_Boolean)

/** Unchecked comparison operators for dynamic field access. When using lambda-style field selectors
  * (_.fieldName), the type is erased to Any. These operators enable comparisons without
  * compile-time type checking - runtime Spark/Catalyst will validate types instead.
  */
extension (expr: Expr[Any])
  // Direct comparisons with primitives (Spark-compatible syntax)
  def >(value: Int): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Gt(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def >(value: Long): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Gt(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def >(value: Double): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Gt(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def >=(value: Int): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.GtEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def >=(value: Long): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.GtEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def >=(value: Double): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.GtEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <(value: Int): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Lt(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <(value: Long): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Lt(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <(value: Double): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Lt(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <=(value: Int): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.LtEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <=(value: Long): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.LtEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <=(value: Double): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.LtEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def ===(value: Int): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Eq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def ===(value: Long): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Eq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def ===(value: Double): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Eq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def ===(value: String): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Eq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def =!=(value: Int): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.NotEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def =!=(value: Long): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.NotEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def =!=(value: Double): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.NotEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def =!=(value: String): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.NotEq(expr.toProtoExpr, ProtoExpr.lit(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

// Internal expression implementations

private[dsl] case class LiteralExpr[A](proto: ProtoExpr, encoder: ProtoEncoder[A]) extends Expr[A]:
  def toProtoExpr: ProtoExpr = proto

private[dsl] case class BinaryExpr[A](proto: ProtoExpr, encoder: ProtoEncoder[A]) extends Expr[A]:
  def toProtoExpr: ProtoExpr = proto

private[dsl] case class UnaryExpr[A](proto: ProtoExpr, encoder: ProtoEncoder[A]) extends Expr[A]:
  def toProtoExpr: ProtoExpr = proto

private[dsl] case class AliasExpr[A](child: Expr[A], name: String) extends Expr[A]:
  def toProtoExpr: ProtoExpr = ProtoExpr.Alias(child.toProtoExpr, name)
  def encoder: ProtoEncoder[A] = child.encoder
