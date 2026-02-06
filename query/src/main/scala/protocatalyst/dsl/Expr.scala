package protocatalyst.dsl

import scala.annotation.targetName

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.expr.ProtoExpr

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

/** Convert a Scala literal value to a ProtoExpr literal. */
private[dsl] def toProtoLiteral(value: Any): ProtoExpr = value match
  case v: Int     => ProtoExpr.lit(v)
  case v: Long    => ProtoExpr.lit(v)
  case v: Double  => ProtoExpr.lit(v)
  case v: Float   => ProtoExpr.lit(v.toDouble)
  case v: String  => ProtoExpr.lit(v)
  case v: Boolean => ProtoExpr.lit(v)
  case _ => throw new IllegalArgumentException(s"Unsupported literal type: ${value.getClass}")

// ============================================================================
// Typed comparison, equality, and arithmetic for comparable/numeric types
// No context bounds - keeps the macro AST simple (no extra Apply layers)
// ============================================================================

/** Comparison operations for ordered types (Expr vs Expr and Expr vs literal) */
extension [A <: Int | Long | Double | Float | String](expr: Expr[A])
  def >(other: Expr[A]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Gt(expr.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  @targetName("gtLiteral")
  def >(value: A): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Gt(expr.toProtoExpr, toProtoLiteral(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def >=(other: Expr[A]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.GtEq(expr.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  @targetName("gtEqLiteral")
  def >=(value: A): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.GtEq(expr.toProtoExpr, toProtoLiteral(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <(other: Expr[A]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Lt(expr.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  @targetName("ltLiteral")
  def <(value: A): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Lt(expr.toProtoExpr, toProtoLiteral(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  def <=(other: Expr[A]): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.LtEq(expr.toProtoExpr, other.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  @targetName("ltEqLiteral")
  def <=(value: A): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.LtEq(expr.toProtoExpr, toProtoLiteral(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  @targetName("eqLiteral")
  def ===(value: A): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.Eq(expr.toProtoExpr, toProtoLiteral(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  @targetName("notEqLiteral")
  def =!=(value: A): Expr[Boolean] =
    BinaryExpr(
      ProtoExpr.NotEq(expr.toProtoExpr, toProtoLiteral(value)),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

/** Arithmetic operations for numeric types (Expr vs Expr and Expr vs literal). Uses expr.encoder
  * instead of context bounds to avoid extra AST layers.
  */
extension [A <: Int | Long | Double | Float](expr: Expr[A])
  def +(other: Expr[A]): Expr[A] =
    BinaryExpr(ProtoExpr.Add(expr.toProtoExpr, other.toProtoExpr), expr.encoder)

  @targetName("addLiteral")
  def +(value: A): Expr[A] =
    BinaryExpr(ProtoExpr.Add(expr.toProtoExpr, toProtoLiteral(value)), expr.encoder)

  def -(other: Expr[A]): Expr[A] =
    BinaryExpr(ProtoExpr.Subtract(expr.toProtoExpr, other.toProtoExpr), expr.encoder)

  @targetName("subtractLiteral")
  def -(value: A): Expr[A] =
    BinaryExpr(ProtoExpr.Subtract(expr.toProtoExpr, toProtoLiteral(value)), expr.encoder)

  def *(other: Expr[A]): Expr[A] =
    BinaryExpr(ProtoExpr.Multiply(expr.toProtoExpr, other.toProtoExpr), expr.encoder)

  @targetName("multiplyLiteral")
  def *(value: A): Expr[A] =
    BinaryExpr(ProtoExpr.Multiply(expr.toProtoExpr, toProtoLiteral(value)), expr.encoder)

  def /(other: Expr[A]): Expr[A] =
    BinaryExpr(ProtoExpr.Divide(expr.toProtoExpr, other.toProtoExpr), expr.encoder)

  @targetName("divideLiteral")
  def /(value: A): Expr[A] =
    BinaryExpr(ProtoExpr.Divide(expr.toProtoExpr, toProtoLiteral(value)), expr.encoder)

// ============================================================================
// Boolean, String, Null operations
// ============================================================================

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

/** Subquery predicate operations */
extension [A](expr: Expr[A])
  /** IN subquery: expr IN (SELECT ...) */
  infix def in(subquery: Query[A]): Expr[Boolean] =
    UnaryExpr(
      ProtoExpr.InSubquery(expr.toProtoExpr, subquery.plan),
      ProtoEncoder.given_ProtoEncoder_Boolean
    )

  /** NOT IN subquery: expr NOT IN (SELECT ...) */
  infix def notIn(subquery: Query[A]): Expr[Boolean] =
    UnaryExpr(
      ProtoExpr.Not(ProtoExpr.InSubquery(expr.toProtoExpr, subquery.plan)),
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
