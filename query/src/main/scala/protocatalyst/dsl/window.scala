package protocatalyst.dsl

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.expr._
import protocatalyst.plan._
import functions.FunctionExpr

/** Window function constructors and specification builders for the DSL. */
object window:

  // === Window Ranking Functions ===

  /** ROW_NUMBER() — sequential row number within partition */
  def rowNumber: WindowFn[Long] =
    WindowFn(ProtoExpr.RowNumber(), ProtoEncoder.given_ProtoEncoder_Long)

  /** RANK() — rank with gaps for ties */
  def rank: WindowFn[Long] =
    WindowFn(ProtoExpr.Rank(), ProtoEncoder.given_ProtoEncoder_Long)

  /** DENSE_RANK() — rank without gaps */
  def denseRank: WindowFn[Long] =
    WindowFn(ProtoExpr.DenseRank(), ProtoEncoder.given_ProtoEncoder_Long)

  /** NTILE(n) — distribute rows into n buckets */
  def ntile(n: Expr[Int]): WindowFn[Long] =
    WindowFn(ProtoExpr.Ntile(n.toProtoExpr), ProtoEncoder.given_ProtoEncoder_Long)

  // === Window Value Functions ===

  /** LEAD — access row ahead of current row */
  def lead[A](expr: Expr[A], offset: Int = 1)(using enc: ProtoEncoder[A]): WindowFn[A] =
    WindowFn(
      ProtoExpr.Lead(expr.toProtoExpr, ProtoExpr.lit(offset), None),
      enc
    )

  /** LAG — access row behind current row */
  def lag[A](expr: Expr[A], offset: Int = 1)(using enc: ProtoEncoder[A]): WindowFn[A] =
    WindowFn(
      ProtoExpr.Lag(expr.toProtoExpr, ProtoExpr.lit(offset), None),
      enc
    )

  /** FIRST_VALUE — first value in window frame */
  def firstValue[A](expr: Expr[A])(using enc: ProtoEncoder[A]): WindowFn[A] =
    WindowFn(ProtoExpr.FirstValue(expr.toProtoExpr, ignoreNulls = false), enc)

  /** LAST_VALUE — last value in window frame */
  def lastValue[A](expr: Expr[A])(using enc: ProtoEncoder[A]): WindowFn[A] =
    WindowFn(ProtoExpr.LastValue(expr.toProtoExpr, ignoreNulls = false), enc)

  // === Window Specification Builder ===

  /** Static factory for building window specifications. */
  object Window:
    /** Create window spec with PARTITION BY. */
    def partitionBy(exprs: Expr[?]*): WindowSpec =
      WindowSpec(partitionExprs = exprs.map(_.toProtoExpr).toVector)

    /** Create window spec with ORDER BY (no partition). */
    def orderBy(sorts: SortExpr*): WindowSpec =
      WindowSpec(orderExprs = sorts.map(_.toSortOrder).toVector)

  /** Immutable window specification with partition, order, and frame. */
  case class WindowSpec private[dsl] (
      partitionExprs: Vector[ProtoExpr] = Vector.empty,
      orderExprs: Vector[SortOrder] = Vector.empty,
      frame: Option[WindowFrame] = None
  ):
    /** Add ORDER BY to this window spec. */
    def orderBy(sorts: SortExpr*): WindowSpec =
      copy(orderExprs = sorts.map(_.toSortOrder).toVector)

    /** Add ROWS BETWEEN frame. */
    def rowsBetween(start: FrameBound, end: FrameBound): WindowSpec =
      copy(frame = Some(WindowFrame(FrameType.Rows, start, end)))

    /** Add RANGE BETWEEN frame. */
    def rangeBetween(start: FrameBound, end: FrameBound): WindowSpec =
      copy(frame = Some(WindowFrame(FrameType.Range, start, end)))

  // === WindowFn Type ===

  /** Pending window function that requires .over(spec) to produce an Expr. */
  case class WindowFn[A] private[dsl] (proto: ProtoExpr, encoder: ProtoEncoder[A]):
    /** Apply a window specification to produce a windowed expression. */
    def over(spec: WindowSpec): Expr[A] =
      FunctionExpr(
        ProtoExpr.WindowExpr(proto, spec.partitionExprs, spec.orderExprs, spec.frame),
        encoder
      )

  // === .over() Extension for Aggregate-as-Window ===

  /** Allows any expression (typically aggregates) to be used as a window function. */
  extension [A](expr: Expr[A])
    def over(spec: WindowSpec): Expr[A] =
      FunctionExpr(
        ProtoExpr.WindowExpr(expr.toProtoExpr, spec.partitionExprs, spec.orderExprs, spec.frame),
        expr.encoder
      )
