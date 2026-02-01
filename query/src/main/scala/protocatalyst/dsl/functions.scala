package protocatalyst.dsl

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.expr.ProtoExpr
import protocatalyst.types.*

/** Aggregate and scalar functions for the DSL. */
object functions:

  // === Aggregate Functions ===

  /** Count all rows */
  def count: Expr[Long] =
    AggExpr(
      ProtoExpr.Count(ProtoExpr.Literal(LiteralValue.IntValue(1)), distinct = false),
      ProtoEncoder.given_ProtoEncoder_Long
    )

  /** Count non-null values */
  def count[A](expr: Expr[A]): Expr[Long] =
    AggExpr(
      ProtoExpr.Count(expr.toProtoExpr, distinct = false),
      ProtoEncoder.given_ProtoEncoder_Long
    )

  /** Count distinct values */
  def countDistinct[A](expr: Expr[A]): Expr[Long] =
    AggExpr(
      ProtoExpr.Count(expr.toProtoExpr, distinct = true),
      ProtoEncoder.given_ProtoEncoder_Long
    )

  /** Sum of numeric values */
  def sum[A: Numeric](expr: Expr[A])(using enc: ProtoEncoder[A]): Expr[A] =
    AggExpr(ProtoExpr.Sum(expr.toProtoExpr), enc)

  /** Sum returning Double */
  def sumDouble[A: Numeric](expr: Expr[A]): Expr[Double] =
    AggExpr(ProtoExpr.Sum(expr.toProtoExpr), ProtoEncoder.given_ProtoEncoder_Double)

  /** Average of numeric values */
  def avg[A: Numeric](expr: Expr[A]): Expr[Double] =
    AggExpr(ProtoExpr.Avg(expr.toProtoExpr), ProtoEncoder.given_ProtoEncoder_Double)

  /** Minimum value */
  def min[A: Ordering](expr: Expr[A])(using enc: ProtoEncoder[A]): Expr[A] =
    AggExpr(ProtoExpr.Min(expr.toProtoExpr), enc)

  /** Maximum value */
  def max[A: Ordering](expr: Expr[A])(using enc: ProtoEncoder[A]): Expr[A] =
    AggExpr(ProtoExpr.Max(expr.toProtoExpr), enc)

  // === Conditional Functions ===

  /** Coalesce - return first non-null value */
  def coalesce[A](exprs: Expr[A]*)(using enc: ProtoEncoder[A]): Expr[A] =
    FunctionExpr(
      ProtoExpr.Coalesce(exprs.map(_.toProtoExpr).toVector),
      enc
    )

  /** If-then-else expression */
  def when[A](condition: Expr[Boolean], thenValue: Expr[A]): WhenBuilder[A] =
    new WhenBuilder(Vector((condition, thenValue)))

  /** Case-when builder */
  final class WhenBuilder[A] private[functions] (
      private val branches: Vector[(Expr[Boolean], Expr[A])]
  ):
    /** Add another when clause */
    def when(condition: Expr[Boolean], thenValue: Expr[A]): WhenBuilder[A] =
      new WhenBuilder(branches :+ (condition, thenValue))

    /** Add else clause and complete */
    def otherwise(elseValue: Expr[A])(using enc: ProtoEncoder[A]): Expr[A] =
      FunctionExpr(
        ProtoExpr.CaseWhen(
          branches.map((c, v) => (c.toProtoExpr, v.toProtoExpr)),
          Some(elseValue.toProtoExpr)
        ),
        enc
      )

    /** Complete without else (returns null if no match) */
    def end(using enc: ProtoEncoder[A]): Expr[A] =
      FunctionExpr(
        ProtoExpr.CaseWhen(
          branches.map((c, v) => (c.toProtoExpr, v.toProtoExpr)),
          None
        ),
        enc
      )

  // === String Functions ===

  /** Concatenate strings */
  def concat(exprs: Expr[String]*): Expr[String] =
    FunctionExpr(
      ProtoExpr.Concat(exprs.map(_.toProtoExpr).toVector),
      ProtoEncoder.given_ProtoEncoder_String
    )

  /** Substring */
  def substring(str: Expr[String], pos: Expr[Int], len: Expr[Int]): Expr[String] =
    FunctionExpr(
      ProtoExpr.Substring(str.toProtoExpr, pos.toProtoExpr, len.toProtoExpr),
      ProtoEncoder.given_ProtoEncoder_String
    )

  /** Upper case */
  def upper(str: Expr[String]): Expr[String] =
    FunctionExpr(ProtoExpr.Upper(str.toProtoExpr), ProtoEncoder.given_ProtoEncoder_String)

  /** Lower case */
  def lower(str: Expr[String]): Expr[String] =
    FunctionExpr(ProtoExpr.Lower(str.toProtoExpr), ProtoEncoder.given_ProtoEncoder_String)

  // === Cast Functions ===

  /** Cast expression to target type */
  def cast[T](expr: Expr[?])(using enc: ProtoEncoder[T]): Expr[T] =
    FunctionExpr(ProtoExpr.Cast(expr.toProtoExpr, enc.catalystType), enc)

  // === Internal expression types ===

  private[dsl] case class AggExpr[A](proto: ProtoExpr, encoder: ProtoEncoder[A]) extends Expr[A]:
    def toProtoExpr: ProtoExpr = proto

  private[dsl] case class FunctionExpr[A](proto: ProtoExpr, encoder: ProtoEncoder[A])
      extends Expr[A]:
    def toProtoExpr: ProtoExpr = proto

/** Implicit conversions for literal values in expressions. */
object implicits:
  import scala.language.implicitConversions

  given intToExpr: Conversion[Int, Expr[Int]] = v => Expr.lit(v)
  given longToExpr: Conversion[Long, Expr[Long]] = v => Expr.lit(v)
  given doubleToExpr: Conversion[Double, Expr[Double]] = v => Expr.lit(v)
  given stringToExpr: Conversion[String, Expr[String]] = v => Expr.lit(v)
  given booleanToExpr: Conversion[Boolean, Expr[Boolean]] = v => Expr.lit(v)
