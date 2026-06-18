package protocatalyst.truffle.backend

import protocatalyst.expr.ProtoExpr
import protocatalyst.types.LiteralValue

/** A small expression interpreter over an already-materialized **boxed row** (`Vector[AnyRef]` with
  * `Long`/`Double`/`String`/`BigDecimal`/`null`), addressed by output column names. It backs the
  * composition layer where values are rows rather than Arrow columns — notably a join's residual
  * `condition`, which spans both sides of the combined row. SQL three-valued logic throughout: a NULL
  * operand yields NULL, and a predicate "holds" only when it evaluates to exactly TRUE.
  *
  * This mirrors the typed Truffle nodes' semantics for the predicate/arithmetic subset; it is the
  * Scala, row-at-a-time counterpart used above joins (the Truffle-PE fast path stays on single-table
  * leaves).
  */
object RowEval:

  /** True iff `predicate` evaluates to exactly TRUE on `row` (NULL/FALSE do not hold). */
  def holds(predicate: ProtoExpr, row: Vector[AnyRef], names: Vector[String]): Boolean =
    eval(predicate, row, names) == java.lang.Boolean.TRUE

  /** The unqualified suffix of a (possibly `qualifier.col`) name. */
  private def suffix(fieldName: String): String =
    val i = fieldName.lastIndexOf('.')
    if i >= 0 then fieldName.substring(i + 1) else fieldName

  /** Resolve a column reference to its index in `names`, mirroring the executor's
    * `ExprEvaluator.resolveColumn`: with a qualifier, prefer an exact `qualifier.name` match (this is
    * what disambiguates same-named columns across a join, since leaves qualify their fields by alias),
    * else fall back to a bare/suffix match; without a qualifier, prefer an exact bare match, else the
    * unqualified suffix. Returns -1 when nothing matches. */
  def resolveIndex(name: String, qualifier: Option[String], names: Vector[String]): Int =
    qualifier match
      case Some(q) =>
        val exact = names.indexWhere(_.equalsIgnoreCase(s"$q.$name"))
        if exact >= 0 then exact
        else names.indexWhere(n => n.equalsIgnoreCase(name) || suffix(n).equalsIgnoreCase(name))
      case None =>
        val exact = names.indexWhere(_.equalsIgnoreCase(name))
        if exact >= 0 then exact
        else names.indexWhere(n => suffix(n).equalsIgnoreCase(name))

  def eval(e: ProtoExpr, row: Vector[AnyRef], names: Vector[String]): AnyRef =
    e match
      case ProtoExpr.ColumnRef(n, q, _, _) =>
        val i = resolveIndex(n, q, names)
        if i < 0 then throw IllegalArgumentException(s"column not in row: ${q.map(_ + ".").getOrElse("")}$n")
        else row(i)
      case ProtoExpr.Literal(v)      => litBoxed(v)
      case ProtoExpr.Alias(c, _)     => eval(c, row, names)
      case ProtoExpr.Cast(c, _)      => eval(c, row, names) // numeric cast ≈ pass-through here
      case ProtoExpr.And(cs)         => and3(cs.map(c => eval(c, row, names)))
      case ProtoExpr.Or(cs)          => or3(cs.map(c => eval(c, row, names)))
      case ProtoExpr.Not(c)          => not3(eval(c, row, names))
      case ProtoExpr.IsNull(c)       => java.lang.Boolean.valueOf(eval(c, row, names) == null)
      case ProtoExpr.IsNotNull(c)    => java.lang.Boolean.valueOf(eval(c, row, names) != null)
      case ProtoExpr.Eq(l, r)        => cmp(l, r, row, names, _ == 0)
      case ProtoExpr.NotEq(l, r)     => cmp(l, r, row, names, _ != 0)
      case ProtoExpr.Lt(l, r)        => cmp(l, r, row, names, _ < 0)
      case ProtoExpr.LtEq(l, r)      => cmp(l, r, row, names, _ <= 0)
      case ProtoExpr.Gt(l, r)        => cmp(l, r, row, names, _ > 0)
      case ProtoExpr.GtEq(l, r)      => cmp(l, r, row, names, _ >= 0)
      case ProtoExpr.Add(l, r)       => arith(l, r, row, names, 'a')
      case ProtoExpr.Subtract(l, r)  => arith(l, r, row, names, 's')
      case ProtoExpr.Multiply(l, r)  => arith(l, r, row, names, 'm')
      case ProtoExpr.Divide(l, r)    => arith(l, r, row, names, 'd')
      case other =>
        throw IllegalArgumentException(s"unsupported row expression: ${other.getClass.getSimpleName}")

  private def litBoxed(v: LiteralValue): AnyRef =
    v match
      case LiteralValue.IntValue(x)     => java.lang.Long.valueOf(x.toLong)
      case LiteralValue.LongValue(x)    => java.lang.Long.valueOf(x)
      case LiteralValue.ShortValue(x)   => java.lang.Long.valueOf(x.toLong)
      case LiteralValue.ByteValue(x)    => java.lang.Long.valueOf(x.toLong)
      case LiteralValue.DoubleValue(x)  => java.lang.Double.valueOf(x)
      case LiteralValue.FloatValue(x)   => java.lang.Double.valueOf(x.toDouble)
      case LiteralValue.DecimalValue(x) => x.bigDecimal
      case LiteralValue.StringValue(x)  => x
      case LiteralValue.BooleanValue(x) => java.lang.Boolean.valueOf(x)
      // Dates/timestamps box as their epoch long (matching the decoder), so a `DATE '…'` predicate
      // over a join (e.g. TPC-H Q5/Q10 `o.orderdate >= DATE '1994-01-01'`) compares as long.
      case LiteralValue.DateValue(d)      => java.lang.Long.valueOf(d.toLong)
      case LiteralValue.TimestampValue(t) => java.lang.Long.valueOf(t)
      case LiteralValue.NullValue(_)    => null
      case other => throw IllegalArgumentException(s"unsupported literal: ${other.getClass.getSimpleName}")

  private def cmp(
      l: ProtoExpr,
      r: ProtoExpr,
      row: Vector[AnyRef],
      names: Vector[String],
      test: Int => Boolean
  ): AnyRef =
    val a = eval(l, row, names)
    val b = eval(r, row, names)
    if a == null || b == null then null else java.lang.Boolean.valueOf(test(compareVals(a, b)))

  private def compareVals(x: AnyRef, y: AnyRef): Int =
    (x, y) match
      case (p: java.math.BigDecimal, q: java.math.BigDecimal) => p.compareTo(q)
      case (p: java.lang.Number, q: java.lang.Number)         => java.lang.Double.compare(p.doubleValue, q.doubleValue)
      case (p: String, q: String)                             => p.compareTo(q)
      case _                                                  => x.toString.compareTo(y.toString)

  private def arith(
      l: ProtoExpr,
      r: ProtoExpr,
      row: Vector[AnyRef],
      names: Vector[String],
      op: Char
  ): AnyRef =
    val a = eval(l, row, names)
    val b = eval(r, row, names)
    if a == null || b == null then null
    else
      (a, b) match
        case (p: java.math.BigDecimal, q: java.math.BigDecimal) =>
          op match
            case 'a' => p.add(q)
            case 's' => p.subtract(q)
            case 'm' => p.multiply(q)
            case 'd' => if q.signum == 0 then null else java.lang.Double.valueOf(p.doubleValue / q.doubleValue)
        case (p: java.lang.Number, q: java.lang.Number) =>
          val x = p.doubleValue
          val y = q.doubleValue
          op match
            case 'a' => java.lang.Double.valueOf(x + y)
            case 's' => java.lang.Double.valueOf(x - y)
            case 'm' => java.lang.Double.valueOf(x * y)
            case 'd' => if y == 0.0 then null else java.lang.Double.valueOf(x / y)
        case _ => null

  private def and3(vs: Vector[AnyRef]): AnyRef =
    if vs.contains(java.lang.Boolean.FALSE) then java.lang.Boolean.FALSE
    else if vs.contains(null) then null
    else java.lang.Boolean.TRUE

  private def or3(vs: Vector[AnyRef]): AnyRef =
    if vs.contains(java.lang.Boolean.TRUE) then java.lang.Boolean.TRUE
    else if vs.contains(null) then null
    else java.lang.Boolean.FALSE

  private def not3(v: AnyRef): AnyRef =
    if v == null then null
    else java.lang.Boolean.valueOf(v != java.lang.Boolean.TRUE)
