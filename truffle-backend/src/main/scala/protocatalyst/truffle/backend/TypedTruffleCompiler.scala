package protocatalyst.truffle.backend

import com.oracle.truffle.api.CallTarget

import protocatalyst.executor.exec.Batch
import protocatalyst.expr.{ProtoExpr, TrimType}
import protocatalyst.plan.{JoinType, NullOrdering, ProtoPhysicalPlan, SortDirection, SortOrder}
import protocatalyst.schema.ProtoSchema
import protocatalyst.truffle.exec.AggKind
import protocatalyst.truffle.typed.*
import protocatalyst.types.{LiteralValue, ProtoType}

/** Layer-1 integration: compile a `ProtoPhysicalPlan`'s filter into the **typed, null-aware** node
  * library (`truffle-exec/typed`), decoding Arrow columns *with their validity bitmaps* into
  * [[TypedColumns]]. Unlike [[ProtoTruffleCompiler]] (double-only, reads NULL as 0.0), this honours
  * SQL three-valued logic: a row passes only when the predicate is exactly TRUE.
  *
  * This first integration covers a `Filter` (over a `TableScan`) and counts surviving rows — enough
  * to show the typed backend matching the Scala interpreter on nullable data. Typed multi-column
  * projection output and more column kinds (string/decimal/temporal) are the next steps.
  */
object TypedTruffleCompiler:

  final class UnsupportedPlanException(message: String) extends RuntimeException(message)

  private enum ColKind:
    case LongCol, DoubleCol, StringCol, DecimalCol

  def compileFilterCount(plan: ProtoPhysicalPlan): TypedFilterCount =
    val (filterOpt, schema) = filterAndScan(plan)
    val predicate = filterOpt
      .map(c => buildPred(c, schema))
      .getOrElse(throw UnsupportedPlanException("expected a Filter over a TableScan"))
    val root = TFilterCountRoot(null, predicate)
    TypedFilterCount(root.getCallTarget, schema)

  /** Compile `Project`/`Filter` over a `TableScan` into a typed, null-preserving filter→project. */
  def compileFilterProject(plan: ProtoPhysicalPlan): CompiledTypedQuery =
    val (projList, filterOpt, schema) = decompose(plan)
    val projections = projList.map(e => buildExpr(e, schema)).toArray
    val filterNode = filterOpt.map(c => buildPred(c, schema)).orNull
    val root = TypedFilterProjectRoot(null, filterNode, projections)
    CompiledTypedQuery(root.getCallTarget, schema, projList.map(outputName))

  private def decompose(
      plan: ProtoPhysicalPlan
  ): (Vector[ProtoExpr], Option[ProtoExpr], ProtoSchema) =
    plan match
      case ProtoPhysicalPlan.PhysicalProject(projList, child) =>
        val (filt, schema) = filterAndScan(child)
        (projList, filt, schema)
      case other =>
        val (filt, schema) = filterAndScan(other)
        (identityProjections(schema), filt, schema)

  private def identityProjections(schema: ProtoSchema): Vector[ProtoExpr] =
    schema.fields.map(f => ProtoExpr.ColumnRef(f.name, None, f.dataType, f.nullable))

  private def outputName(e: ProtoExpr): String =
    e match
      case ProtoExpr.Alias(_, name)           => name
      case ProtoExpr.ColumnRef(name, _, _, _) => name
      case ProtoExpr.BoundRef(i, _, _)        => s"col$i"
      case _                                  => "expr"

  /** Dispatch: a global aggregate (no GROUP BY) or a filter→project. */
  def compile(plan: ProtoPhysicalPlan): TypedQuery =
    plan match
      case ProtoPhysicalPlan.PhysicalLimit(limit, child) => LimitQuery(compile(child), limit)
      case ProtoPhysicalPlan.PhysicalDistinct(child)     => DistinctQuery(compile(child))
      case ProtoPhysicalPlan.PhysicalSort(order, child) =>
        val c = compile(child)
        SortQuery(c, order.map(so => sortKey(so, c.outputNames)))
      case ProtoPhysicalPlan.PhysicalWindow(windowExprs, partitionSpec, orderSpec, child) =>
        compileWindow(windowExprs, partitionSpec, orderSpec, child)
      case ProtoPhysicalPlan.HashAggregate(grouping, aggs, child) if grouping.isEmpty =>
        compileGlobalAggregate(aggs, child)
      case ProtoPhysicalPlan.SortAggregate(grouping, aggs, child) if grouping.isEmpty =>
        compileGlobalAggregate(aggs, child)
      case ProtoPhysicalPlan.HashAggregate(grouping, aggs, child) =>
        compileGroupedAggregate(grouping, aggs, child)
      case ProtoPhysicalPlan.SortAggregate(grouping, aggs, child) =>
        compileGroupedAggregate(grouping, aggs, child)
      case _ => compileFilterProject(plan)

  private def compileGroupedAggregate(
      grouping: Vector[ProtoExpr],
      aggs: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  ): CompiledTypedQuery =
    val (filterOpt, schema) = filterAndScan(child)
    val keyNodes = grouping.map(g => buildExpr(g, schema)).toArray
    val parsed = aggs.map(a => buildAgg(a, schema))
    val kinds = parsed.map(_._1).toArray
    val inputs = parsed.map(_._2).toArray
    val filterNode = filterOpt.map(c => buildPred(c, schema)).orNull
    val root = TypedGroupedAggRoot(null, filterNode, keyNodes, inputs, kinds)
    // Output columns: grouping keys first, then aggregates (the interpreter's order).
    val names = grouping.map(outputName) ++ parsed.map(_._3)
    CompiledTypedQuery(root.getCallTarget, schema, names)

  private def compileGlobalAggregate(
      aggs: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  ): CompiledTypedQuery =
    val (filterOpt, schema) = filterAndScan(child)
    val parsed = aggs.map(a => buildAgg(a, schema))
    val kinds = parsed.map(_._1).toArray
    val inputs = parsed.map(_._2).toArray
    val filterNode = filterOpt.map(c => buildPred(c, schema)).orNull
    val root = TypedGlobalAggRoot(null, filterNode, inputs, kinds)
    CompiledTypedQuery(root.getCallTarget, schema, parsed.map(_._3))

  /** Map a (possibly aliased) aggregate to (kind, inner-expr, name). COUNT uses a placeholder input
    * (its value is ignored — COUNT(*) counts surviving rows), so the `@Children` array has no nulls. */
  private def buildAgg(e: ProtoExpr, schema: ProtoSchema): (AggKind, TExpr, String) =
    e match
      case ProtoExpr.Alias(child, name) =>
        val (k, inp, _) = buildAgg(child, schema)
        (k, inp, name)
      case ProtoExpr.Sum(c)      => (AggKind.SUM, buildExpr(c, schema), "sum")
      case ProtoExpr.Count(_, _) => (AggKind.COUNT, TLit.Long(1), "count")
      case ProtoExpr.Min(c)      => (AggKind.MIN, buildExpr(c, schema), "min")
      case ProtoExpr.Max(c)      => (AggKind.MAX, buildExpr(c, schema), "max")
      case ProtoExpr.Avg(c)      => (AggKind.AVG, buildExpr(c, schema), "avg")
      case other =>
        throw UnsupportedPlanException(s"unsupported aggregate: ${other.getClass.getSimpleName}")

  private def filterAndScan(plan: ProtoPhysicalPlan): (Option[ProtoExpr], ProtoSchema) =
    plan match
      case ProtoPhysicalPlan.PhysicalFilter(cond, child) =>
        val (_, schema) = filterAndScan(child)
        (Some(cond), schema)
      case ProtoPhysicalPlan.TableScan(_, _, schema, _) =>
        (None, schema)
      case other =>
        throw UnsupportedPlanException(s"unsupported operator: ${other.getClass.getSimpleName}")

  private def kindOf(t: ProtoType): ColKind =
    t match
      case ProtoType.LongType | ProtoType.IntegerType | ProtoType.ShortType | ProtoType.ByteType |
          ProtoType.DateType | ProtoType.TimestampType =>
        ColKind.LongCol
      case ProtoType.DoubleType | ProtoType.FloatType =>
        ColKind.DoubleCol
      case _: ProtoType.DecimalType =>
        ColKind.DecimalCol
      case ProtoType.StringType =>
        ColKind.StringCol
      case other =>
        throw UnsupportedPlanException(s"unsupported column type: ${other.getClass.getSimpleName}")

  private def castTarget(t: ProtoType): TCast.Target =
    t match
      case ProtoType.LongType | ProtoType.IntegerType | ProtoType.ShortType | ProtoType.ByteType |
          ProtoType.DateType | ProtoType.TimestampType =>
        TCast.Target.LONG
      case ProtoType.DoubleType | ProtoType.FloatType => TCast.Target.DOUBLE
      case _: ProtoType.DecimalType                   => TCast.Target.DECIMAL
      case ProtoType.StringType                       => TCast.Target.STRING
      case other =>
        throw UnsupportedPlanException(s"unsupported cast target: ${other.getClass.getSimpleName}")

  private def trimMode(t: TrimType): TString.TrimMode =
    t match
      case TrimType.Both     => TString.TrimMode.BOTH
      case TrimType.Leading  => TString.TrimMode.LEADING
      case TrimType.Trailing => TString.TrimMode.TRAILING

  private def constInt(e: ProtoExpr): Int =
    e match
      case ProtoExpr.Literal(LiteralValue.IntValue(v))  => v
      case ProtoExpr.Literal(LiteralValue.LongValue(v)) => v.toInt
      case other =>
        throw UnsupportedPlanException(s"expected an integer literal, got ${other.getClass.getSimpleName}")

  /** A constant literal as a boxed value (for window LAG/LEAD defaults), normalized like the engine's
    * output: numbers as Long/Double, decimals as BigDecimal, strings as String. */
  private def constBoxed(e: ProtoExpr): AnyRef =
    e match
      case ProtoExpr.Literal(LiteralValue.IntValue(v))     => java.lang.Long.valueOf(v.toLong)
      case ProtoExpr.Literal(LiteralValue.LongValue(v))    => java.lang.Long.valueOf(v)
      case ProtoExpr.Literal(LiteralValue.ShortValue(v))   => java.lang.Long.valueOf(v.toLong)
      case ProtoExpr.Literal(LiteralValue.ByteValue(v))    => java.lang.Long.valueOf(v.toLong)
      case ProtoExpr.Literal(LiteralValue.DoubleValue(v))  => java.lang.Double.valueOf(v)
      case ProtoExpr.Literal(LiteralValue.FloatValue(v))   => java.lang.Double.valueOf(v.toDouble)
      case ProtoExpr.Literal(LiteralValue.DecimalValue(v)) => v.bigDecimal
      case ProtoExpr.Literal(LiteralValue.StringValue(v))  => v
      case other =>
        throw UnsupportedPlanException(s"window default must be a literal, got ${other.getClass.getSimpleName}")

  /** Resolve an ORDER BY key to (output column, direction, null ordering). The key must reference an
    * output column by name (the common case after projection). */
  private def sortKey(so: SortOrder, names: Vector[String]): SortKey =
    val column = so.child match
      case ProtoExpr.ColumnRef(name, _, _, _) => outputIndex(name, names)
      case ProtoExpr.Alias(_, name)           => outputIndex(name, names)
      case other =>
        throw UnsupportedPlanException(s"sort key must be a column reference, got ${other.getClass.getSimpleName}")
    SortKey(
      column,
      so.direction == SortDirection.Ascending,
      so.nullOrdering == NullOrdering.NullsFirst
    )

  private def outputIndex(name: String, names: Vector[String]): Int =
    val i = names.indexWhere(_.equalsIgnoreCase(name))
    if i < 0 then throw UnsupportedPlanException(s"sort key not in output: $name") else i

  /** Compile an equi-join into an inner hash join over its two table inputs. Outer joins, join
    * conditions beyond the equi-keys, and filters/projects *under* the join are not yet handled. */
  def compileJoin(plan: ProtoPhysicalPlan): JoinQuery =
    val (left, right, joinType, leftKeys, rightKeys) = plan match
      case ProtoPhysicalPlan.HashJoin(l, r, jt, lk, rk, _, _)          => (l, r, jt, lk, rk)
      case ProtoPhysicalPlan.BroadcastHashJoin(l, r, jt, lk, rk, _, _) => (l, r, jt, lk, rk)
      case ProtoPhysicalPlan.SortMergeJoin(l, r, jt, lk, rk, _)        => (l, r, jt, lk, rk)
      case other =>
        throw UnsupportedPlanException(s"not a supported equi-join: ${other.getClass.getSimpleName}")
    val leftSchema = scanSchema(left)
    val rightSchema = scanSchema(right)
    val leftKeyTarget = TypedKeysRoot(null, leftKeys.map(k => buildExpr(k, leftSchema)).toArray).getCallTarget
    val rightKeyTarget = TypedKeysRoot(null, rightKeys.map(k => buildExpr(k, rightSchema)).toArray).getCallTarget
    val outputNames = leftSchema.fields.map(_.name) ++ rightSchema.fields.map(_.name)
    JoinQuery(leftSchema, rightSchema, leftKeyTarget, rightKeyTarget, leftKeys.size, joinType, outputNames)

  private def compileWindow(
      windowExprs: Vector[ProtoExpr],
      partitionSpec: Vector[ProtoExpr],
      orderSpec: Vector[SortOrder],
      childPlan: ProtoPhysicalPlan
  ): WindowQuery =
    val child = compile(childPlan)
    val names = child.outputNames
    val partitionCols = partitionSpec.map {
      case ProtoExpr.ColumnRef(name, _, _, _) => outputIndex(name, names)
      case other =>
        throw UnsupportedPlanException(s"window PARTITION BY must be a column ref, got ${other.getClass.getSimpleName}")
    }
    val orderKeys = orderSpec.map(so => sortKey(so, names))
    val fns = windowExprs.map(e => parseWindowFn(e, names))
    WindowQuery(child, partitionCols, orderKeys, fns, names ++ fns.map(_.name))

  private def parseWindowFn(e: ProtoExpr, names: Vector[String]): WindowFn =
    e match
      case ProtoExpr.Alias(inner, name) => buildWindowFn(inner, name, names)
      case other                        => buildWindowFn(other, "window", names)

  private def buildWindowFn(e: ProtoExpr, name: String, names: Vector[String]): WindowFn =
    e match
      case ProtoExpr.WindowExpr(fn, _, _, _) => buildWindowFn(fn, name, names)
      case ProtoExpr.RowNumber()             => WindowFn(WindowFnKind.RowNumber, -1, name)
      case ProtoExpr.Rank()                  => WindowFn(WindowFnKind.Rank, -1, name)
      case ProtoExpr.DenseRank()             => WindowFn(WindowFnKind.DenseRank, -1, name)
      case ProtoExpr.Sum(c)                  => WindowFn(WindowFnKind.Sum, aggCol(c, names), name)
      case ProtoExpr.Count(c, _)             => WindowFn(WindowFnKind.Count, aggColOrNeg(c, names), name)
      case ProtoExpr.Min(c)                  => WindowFn(WindowFnKind.Min, aggCol(c, names), name)
      case ProtoExpr.Max(c)                  => WindowFn(WindowFnKind.Max, aggCol(c, names), name)
      case ProtoExpr.Avg(c)                  => WindowFn(WindowFnKind.Avg, aggCol(c, names), name)
      case ProtoExpr.Lead(c, off, dflt) =>
        WindowFn(WindowFnKind.Lead, aggCol(c, names), name, constInt(off), dflt.map(constBoxed).orNull)
      case ProtoExpr.Lag(c, off, dflt) =>
        WindowFn(WindowFnKind.Lag, aggCol(c, names), name, constInt(off), dflt.map(constBoxed).orNull)
      case ProtoExpr.Ntile(nExpr) =>
        WindowFn(WindowFnKind.Ntile, -1, name, constInt(nExpr))
      case ProtoExpr.FirstValue(c, ignoreNulls) =>
        WindowFn(WindowFnKind.FirstValue, aggCol(c, names), name, ignoreNulls = ignoreNulls)
      case ProtoExpr.LastValue(c, ignoreNulls) =>
        WindowFn(WindowFnKind.LastValue, aggCol(c, names), name, ignoreNulls = ignoreNulls)
      case ProtoExpr.NthValue(c, nExpr) =>
        WindowFn(WindowFnKind.NthValue, aggCol(c, names), name, constInt(nExpr))
      case other =>
        throw UnsupportedPlanException(s"unsupported window function: ${other.getClass.getSimpleName}")

  private def aggCol(c: ProtoExpr, names: Vector[String]): Int =
    c match
      case ProtoExpr.ColumnRef(n, _, _, _) => outputIndex(n, names)
      case other =>
        throw UnsupportedPlanException(s"window aggregate input must be a column ref, got ${other.getClass.getSimpleName}")

  private def aggColOrNeg(c: ProtoExpr, names: Vector[String]): Int =
    c match
      case ProtoExpr.ColumnRef(n, _, _, _) => outputIndex(n, names)
      case _                               => -1 // COUNT(literal) / COUNT(*) → count all rows

  private def scanSchema(p: ProtoPhysicalPlan): ProtoSchema =
    p match
      case ProtoPhysicalPlan.TableScan(_, _, schema, _) => schema
      case ProtoPhysicalPlan.PhysicalFilter(_, child)   => scanSchema(child)
      case ProtoPhysicalPlan.PhysicalProject(_, child)  => scanSchema(child)
      case other =>
        throw UnsupportedPlanException(s"join child must be a scan, got ${other.getClass.getSimpleName}")

  private def buildExpr(e: ProtoExpr, schema: ProtoSchema): TExpr =
    e match
      case ProtoExpr.ColumnRef(name, _, _, _) =>
        val i = schema.fields.indexWhere(_.name.equalsIgnoreCase(name))
        if i < 0 then throw UnsupportedPlanException(s"column not found: $name")
        kindOf(schema.fields(i).dataType) match
          case ColKind.LongCol    => TLongColumn(i)
          case ColKind.DoubleCol  => TDoubleColumn(i)
          case ColKind.StringCol  => TStringColumn(i)
          case ColKind.DecimalCol => TDecimalColumn(i)
      case ProtoExpr.Literal(LiteralValue.StringValue(v)) => TLit.Str(v)
      case ProtoExpr.Literal(LiteralValue.LongValue(v))    => TLit.Long(v)
      case ProtoExpr.Literal(LiteralValue.IntValue(v))     => TLit.Long(v.toLong)
      case ProtoExpr.Literal(LiteralValue.ShortValue(v))   => TLit.Long(v.toLong)
      case ProtoExpr.Literal(LiteralValue.ByteValue(v))    => TLit.Long(v.toLong)
      case ProtoExpr.Literal(LiteralValue.DoubleValue(v))  => TLit.Double(v)
      case ProtoExpr.Literal(LiteralValue.FloatValue(v))   => TLit.Double(v.toDouble)
      case ProtoExpr.Literal(LiteralValue.DecimalValue(v))   => TLit.Dec(v.bigDecimal)
      case ProtoExpr.Literal(LiteralValue.DateValue(d))      => TLit.Long(d.toLong)
      case ProtoExpr.Literal(LiteralValue.TimestampValue(t)) => TLit.Long(t)
      case ProtoExpr.Add(l, r)      => TArithFactory.AddNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Subtract(l, r) => TArithFactory.SubNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Multiply(l, r) => TArithFactory.MulNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Divide(l, r)   => TControl.Divide(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Coalesce(children) =>
        TControl.Coalesce(children.map(c => buildExpr(c, schema)).toArray)
      case ProtoExpr.NullIf(l, r) => TControl.NullIf(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.If(p, t, f) =>
        TControl.If(buildPred(p, schema), buildExpr(t, schema), buildExpr(f, schema))
      case ProtoExpr.CaseWhen(branches, elseValue) =>
        val conditions = branches.map(b => buildPred(b._1, schema)).toArray
        val values = branches.map(b => buildExpr(b._2, schema)).toArray
        TControl.CaseWhen(conditions, values, elseValue.map(e => buildExpr(e, schema)).orNull)
      case ProtoExpr.Cast(child, targetType) => TCast(buildExpr(child, schema), castTarget(targetType))
      case ProtoExpr.Upper(c)                => TString.Upper(buildExpr(c, schema))
      case ProtoExpr.Lower(c)                => TString.Lower(buildExpr(c, schema))
      case ProtoExpr.Length(c)               => TString.Length(buildExpr(c, schema))
      case ProtoExpr.Abs(c)                  => TMath.Abs(buildExpr(c, schema))
      case ProtoExpr.Sqrt(c)                 => TMath.Sqrt(buildExpr(c, schema))
      case ProtoExpr.Floor(c)                => TMath.Floor(buildExpr(c, schema))
      case ProtoExpr.Ceil(c)                 => TMath.Ceil(buildExpr(c, schema))
      case ProtoExpr.Round(c, scale)         => TMath.Round(buildExpr(c, schema), constInt(scale))
      case ProtoExpr.Exp(c)                  => TMath.Exp(buildExpr(c, schema))
      case ProtoExpr.Cbrt(c)                 => TMath.Cbrt(buildExpr(c, schema))
      case ProtoExpr.Sign(c)                 => TMath.Sign(buildExpr(c, schema))
      case ProtoExpr.Pow(l, r)               => TMath.Pow(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Log(c, base)            => TMath.Log(buildExpr(c, schema), base.map(b => buildExpr(b, schema)).orNull)
      case ProtoExpr.Concat(children)        => TString.Concat(children.map(c => buildExpr(c, schema)).toArray)
      case ProtoExpr.Reverse(c)              => TString.Reverse(buildExpr(c, schema))
      case ProtoExpr.Substring(str, pos, len) =>
        TString.Substring(buildExpr(str, schema), buildExpr(pos, schema), buildExpr(len, schema))
      case ProtoExpr.Trim(c, _, trimType) => TString.Trim(buildExpr(c, schema), trimMode(trimType))
      case ProtoExpr.Alias(child, _)      => buildExpr(child, schema)
      case other =>
        throw UnsupportedPlanException(s"unsupported expression: ${other.getClass.getSimpleName}")

  private def buildPred(e: ProtoExpr, schema: ProtoSchema): TExpr =
    e match
      case ProtoExpr.And(children) =>
        children.map(c => buildPred(c, schema)).reduce((l, r) => TLogic.And(l, r))
      case ProtoExpr.Or(children) =>
        children.map(c => buildPred(c, schema)).reduce((l, r) => TLogic.Or(l, r))
      case ProtoExpr.Lt(l, r)    => TCompareFactory.LtNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.LtEq(l, r)  => TCompareFactory.LeNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Gt(l, r)    => TCompareFactory.GtNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.GtEq(l, r)  => TCompareFactory.GeNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Eq(l, r)    => TCompareFactory.EqNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.NotEq(l, r) => TCompareFactory.NeNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Not(c)        => TLogic.Not(buildPred(c, schema))
      case ProtoExpr.IsNull(c)     => TControl.IsNull(buildExpr(c, schema))
      case ProtoExpr.IsNotNull(c)  => TControl.IsNotNull(buildExpr(c, schema))
      case ProtoExpr.In(value, list) =>
        TControl.In(buildExpr(value, schema), list.map(e => buildExpr(e, schema)).toArray)
      case ProtoExpr.Like(value, pattern, escape) =>
        TControl.Like(buildExpr(value, schema), compileLike(pattern, escape))
      case other =>
        throw UnsupportedPlanException(s"unsupported predicate: ${other.getClass.getSimpleName}")

  private def compileLike(pattern: ProtoExpr, escape: Option[ProtoExpr]): java.util.regex.Pattern =
    val pat = pattern match
      case ProtoExpr.Literal(LiteralValue.StringValue(s)) => s
      case other =>
        throw UnsupportedPlanException(s"LIKE pattern must be a string literal, got ${other.getClass.getSimpleName}")
    val esc = escape match
      case None                                                       => '\\'
      case Some(ProtoExpr.Literal(LiteralValue.StringValue(s))) if s.nonEmpty => s.charAt(0)
      case other =>
        throw UnsupportedPlanException(s"LIKE escape must be a non-empty string literal")
    java.util.regex.Pattern.compile(likeToRegex(pat, esc), java.util.regex.Pattern.DOTALL)

  /** Translate a SQL LIKE pattern to a Java regex: `%` → `.*`, `_` → `.`, the escape char makes the
    * next character literal, and every other character is quoted. */
  private def likeToRegex(pattern: String, escape: Char): String =
    val sb = new StringBuilder
    var i = 0
    while i < pattern.length do
      val c = pattern.charAt(i)
      if c == escape && i + 1 < pattern.length then
        sb.append(java.util.regex.Pattern.quote(pattern.charAt(i + 1).toString))
        i += 2
      else
        c match
          case '%'   => sb.append(".*")
          case '_'   => sb.append(".")
          case other => sb.append(java.util.regex.Pattern.quote(other.toString))
        i += 1
    sb.toString

/** A compiled typed filter: counts rows passing the predicate under three-valued logic. */
final class TypedFilterCount(callTarget: CallTarget, schema: ProtoSchema):

  def count(input: Batch): Long =
    val cols = TypedColumnsDecoder.decode(input, schema)
    callTarget.call(new TRow(cols)).asInstanceOf[java.lang.Long].longValue

/** A compiled typed query: produces null-preserving result rows. */
sealed trait TypedQuery:
  def outputNames: Vector[String]
  def run(input: Batch): TypedResult

/** Leaf query: a Truffle call target (filter→project, or a global/grouped aggregate). */
final class CompiledTypedQuery(
    callTarget: CallTarget,
    schema: ProtoSchema,
    val outputNames: Vector[String]
) extends TypedQuery:

  def run(input: Batch): TypedResult =
    val cols = TypedColumnsDecoder.decode(input, schema)
    val numCols = outputNames.size
    val out = Array.ofDim[AnyRef](numCols, math.max(1, cols.rowCount))
    val k = callTarget.call(new TRow(cols), out).asInstanceOf[java.lang.Integer].intValue
    val boxed = (0 until k).map(j => (0 until numCols).map(c => out(c)(j)).toVector).toVector
    new TypedResult(outputNames, boxed)

/** LIMIT n — the result-level operators are plain row transforms over a child query. */
final class LimitQuery(child: TypedQuery, limit: Int) extends TypedQuery:
  def outputNames: Vector[String] = child.outputNames
  def run(input: Batch): TypedResult = child.run(input).take(limit)

/** DISTINCT. */
final class DistinctQuery(child: TypedQuery) extends TypedQuery:
  def outputNames: Vector[String] = child.outputNames
  def run(input: Batch): TypedResult = child.run(input).distinct

/** ORDER BY, honoring each key's direction and NULLS FIRST/LAST. */
final class SortQuery(child: TypedQuery, keys: Vector[SortKey]) extends TypedQuery:
  def outputNames: Vector[String] = child.outputNames
  def run(input: Batch): TypedResult = child.run(input).sortedBy(keys)

final case class SortKey(column: Int, ascending: Boolean, nullsFirst: Boolean)

enum WindowFnKind:
  case RowNumber, Rank, DenseRank, Sum, Count, Min, Max, Avg, Lead, Lag, Ntile, FirstValue, LastValue,
    NthValue

/** A window function: its kind, the input column index (-1 for ranking / COUNT(*) / NTILE), output
  * name, a constant `offset` (LAG/LEAD offset, NTILE bucket count, or NTH index), a LAG/LEAD `default`,
  * and an `ignoreNulls` flag (FIRST_VALUE/LAST_VALUE). */
final case class WindowFn(
    kind: WindowFnKind,
    inputCol: Int,
    name: String,
    offset: Int = 0,
    default: AnyRef = null,
    ignoreNulls: Boolean = false
)

/** Window functions over PARTITION BY + ORDER BY. Ranking (ROW_NUMBER/RANK/DENSE_RANK) is per-row by
  * partition order; aggregate windows (SUM/COUNT/MIN/MAX/AVG OVER) are whole-partition (every row gets
  * the partition aggregate — the interpreter's frame). Offset functions (LAG/LEAD), NTILE, and
  * explicit frames are not yet handled. */
final class WindowQuery(
    child: TypedQuery,
    partitionCols: Vector[Int],
    orderKeys: Vector[SortKey],
    windowFns: Vector[WindowFn],
    val outputNames: Vector[String]
) extends TypedQuery:

  def run(input: Batch): TypedResult =
    val rows = child.run(input).boxedRows
    val result = new Array[Vector[AnyRef]](rows.size)
    val partitions = rows.indices.groupBy(i => partitionKey(rows(i)))

    partitions.valuesIterator.foreach { idxs =>
      val ordered = idxs.sortWith((x, y) => TypedResult.lessThan(rows(x), rows(y), orderKeys)).toVector
      val m = ordered.size
      val rank = new Array[Long](m)
      val dense = new Array[Long](m)
      var p = 0
      while p < m do
        if p == 0 then { rank(0) = 1L; dense(0) = 1L }
        else if sameOrder(rows(ordered(p)), rows(ordered(p - 1))) then
          rank(p) = rank(p - 1); dense(p) = dense(p - 1)
        else { rank(p) = (p + 1).toLong; dense(p) = dense(p - 1) + 1 }
        p += 1

      // Whole-partition values (computed once; same for every row in the partition).
      val constVals: Vector[AnyRef] = windowFns.map { fn =>
        fn.kind match
          case WindowFnKind.Sum | WindowFnKind.Count | WindowFnKind.Min | WindowFnKind.Max |
              WindowFnKind.Avg =>
            partitionAggregate(fn, idxs, rows)
          case WindowFnKind.FirstValue => firstLastValue(ordered, fn, rows, first = true)
          case WindowFnKind.LastValue  => firstLastValue(ordered, fn, rows, first = false)
          case WindowFnKind.NthValue =>
            if fn.offset >= 1 && fn.offset <= m then rows(ordered(fn.offset - 1))(fn.inputCol) else null
          case _ => null // ranking / offset — per-row
      }

      var pos = 0
      while pos < m do
        val orig = ordered(pos)
        val wvals = windowFns.zipWithIndex.map { (fn, fi) =>
          (fn.kind match
            case WindowFnKind.RowNumber => java.lang.Long.valueOf((pos + 1).toLong)
            case WindowFnKind.Rank      => java.lang.Long.valueOf(rank(pos))
            case WindowFnKind.DenseRank => java.lang.Long.valueOf(dense(pos))
            case WindowFnKind.Lead      => offsetValue(ordered, pos + fn.offset, fn, rows)
            case WindowFnKind.Lag       => offsetValue(ordered, pos - fn.offset, fn, rows)
            case WindowFnKind.Ntile     => java.lang.Long.valueOf(ntileBucket(pos, m, fn.offset).toLong)
            case _                      => constVals(fi)
          ): AnyRef
        }
        result(orig) = rows(orig) ++ wvals
        pos += 1
    }
    new TypedResult(outputNames, result.toVector)

  /** LAG/LEAD: the input column of the partition row at `target` (ordered position), or the default. */
  private def offsetValue(ordered: Vector[Int], target: Int, fn: WindowFn, rows: Vector[Vector[AnyRef]]): AnyRef =
    if target >= 0 && target < ordered.size then rows(ordered(target))(fn.inputCol) else fn.default

  /** FIRST_VALUE / LAST_VALUE over the ordered partition, optionally ignoring NULLs. */
  private def firstLastValue(ordered: Vector[Int], fn: WindowFn, rows: Vector[Vector[AnyRef]], first: Boolean): AnyRef =
    if ordered.isEmpty then null
    else
      val seq = if first then ordered else ordered.reverse
      if fn.ignoreNulls then seq.iterator.map(i => rows(i)(fn.inputCol)).find(_ != null).orNull
      else rows(seq.head)(fn.inputCol)

  /** NTILE bucket (1-based) for position `pos` in a partition of `m` rows split into `n` buckets:
    * the first `m % n` buckets get one extra row. */
  private def ntileBucket(pos: Int, m: Int, n: Int): Int =
    if n <= 0 then 1
    else
      val base = m / n
      val rem = m % n
      val bigCount = rem * (base + 1)
      if pos < bigCount then pos / (base + 1) + 1
      else rem + (pos - bigCount) / math.max(1, base) + 1

  private def partitionAggregate(fn: WindowFn, idxs: IndexedSeq[Int], rows: Vector[Vector[AnyRef]]): AnyRef =
    fn.kind match
      case WindowFnKind.Count =>
        val c = if fn.inputCol < 0 then idxs.size else idxs.count(i => rows(i)(fn.inputCol) != null)
        java.lang.Long.valueOf(c.toLong)
      case WindowFnKind.Sum =>
        sumBoxed(idxs.iterator.map(i => rows(i)(fn.inputCol)))
      case WindowFnKind.Avg =>
        val vals = idxs.iterator.map(i => rows(i)(fn.inputCol)).filter(_ != null).toVector
        if vals.isEmpty then null
        else java.lang.Double.valueOf(vals.map(_.asInstanceOf[java.lang.Number].doubleValue).sum / vals.size)
      case WindowFnKind.Min => extremeBoxed(idxs, fn.inputCol, rows, min = true)
      case WindowFnKind.Max => extremeBoxed(idxs, fn.inputCol, rows, min = false)
      case _                => null // ranking — handled per-row

  private def sumBoxed(vs: Iterator[AnyRef]): AnyRef =
    var acc: AnyRef = null
    vs.foreach { v =>
      if v != null then
        acc = (acc, v) match
          case (null, d: java.math.BigDecimal)                    => d
          case (null, n: java.lang.Number)                        => java.lang.Double.valueOf(n.doubleValue)
          case (a: java.math.BigDecimal, d: java.math.BigDecimal) => a.add(d)
          case (a: java.lang.Number, n: java.lang.Number)         => java.lang.Double.valueOf(a.doubleValue + n.doubleValue)
          case _                                                  => acc
    }
    acc

  private def extremeBoxed(idxs: IndexedSeq[Int], col: Int, rows: Vector[Vector[AnyRef]], min: Boolean): AnyRef =
    var best: AnyRef = null
    idxs.foreach { i =>
      val v = rows(i)(col)
      if v != null then
        if best == null then best = v
        else
          val c = compareVals(v, best)
          if (min && c < 0) || (!min && c > 0) then best = v
    }
    best

  private def compareVals(x: AnyRef, y: AnyRef): Int =
    (x, y) match
      case (p: java.math.BigDecimal, q: java.math.BigDecimal) => p.compareTo(q)
      case (p: java.lang.Number, q: java.lang.Number)         => java.lang.Double.compare(p.doubleValue, q.doubleValue)
      case (p: String, q: String)                             => p.compareTo(q)
      case _                                                  => x.toString.compareTo(y.toString)

  private def partitionKey(row: Vector[AnyRef]): List[AnyRef] =
    partitionCols.map { c =>
      row(c) match
        case d: java.math.BigDecimal => d.stripTrailingZeros
        case other                   => other
    }.toList

  private def sameOrder(a: Vector[AnyRef], b: Vector[AnyRef]): Boolean =
    orderKeys.forall(k => TypedResult.compareKey(a, b, k) == 0)

/** Equi hash join (inner + outer): build a hash table on the right keys, probe with the left, emit
  * `left ++ right` for each match. NULL keys never match (SQL semantics); decimal keys are
  * value-normalized. For LEFT/FULL OUTER, unmatched left rows are emitted with a null-padded right;
  * for RIGHT/FULL OUTER, unmatched right rows with a null-padded left. Two explicit inputs (joins are
  * inherently multi-input). */
final class JoinQuery(
    leftSchema: ProtoSchema,
    rightSchema: ProtoSchema,
    leftKeyTarget: CallTarget,
    rightKeyTarget: CallTarget,
    numKeys: Int,
    joinType: JoinType,
    val outputNames: Vector[String]
):

  private val emitUnmatchedLeft = joinType == JoinType.LeftOuter || joinType == JoinType.FullOuter
  private val emitUnmatchedRight = joinType == JoinType.RightOuter || joinType == JoinType.FullOuter

  def run(leftBatch: Batch, rightBatch: Batch): TypedResult =
    val left = TypedColumnsDecoder.decode(leftBatch, leftSchema)
    val right = TypedColumnsDecoder.decode(rightBatch, rightSchema)
    val leftKeys = evalKeys(leftKeyTarget, left)
    val rightKeys = evalKeys(rightKeyTarget, right)
    val numLeftCols = leftSchema.fields.size
    val numRightCols = rightSchema.fields.size
    val nullLeft = Vector.fill[AnyRef](numLeftCols)(null)
    val nullRight = Vector.fill[AnyRef](numRightCols)(null)

    val table =
      scala.collection.mutable.HashMap[List[AnyRef], scala.collection.mutable.ArrayBuffer[Int]]()
    var ri = 0
    while ri < right.rowCount do
      keyAt(rightKeys, ri).foreach { key =>
        table.getOrElseUpdate(key, scala.collection.mutable.ArrayBuffer[Int]()) += ri
      }
      ri += 1
    val matchedRight = Array.fill(right.rowCount)(false)

    val rows = scala.collection.mutable.ArrayBuffer[Vector[AnyRef]]()
    var li = 0
    while li < left.rowCount do
      val leftRow = rowBoxed(left, li, numLeftCols)
      keyAt(leftKeys, li).flatMap(table.get) match
        case Some(matches) =>
          matches.foreach { rj =>
            rows += (leftRow ++ rowBoxed(right, rj, numRightCols))
            matchedRight(rj) = true
          }
        case None =>
          if emitUnmatchedLeft then rows += (leftRow ++ nullRight)
      li += 1

    if emitUnmatchedRight then
      var rj = 0
      while rj < right.rowCount do
        if !matchedRight(rj) then rows += (nullLeft ++ rowBoxed(right, rj, numRightCols))
        rj += 1

    new TypedResult(outputNames, rows.toVector)

  private def rowBoxed(cols: TypedColumns, row: Int, numCols: Int): Vector[AnyRef] =
    val v = new Array[AnyRef](numCols)
    var c = 0
    while c < numCols do
      v(c) = cols.getBoxed(c, row)
      c += 1
    v.toVector

  private def evalKeys(target: CallTarget, cols: TypedColumns): Array[Array[AnyRef]] =
    val out = Array.ofDim[AnyRef](numKeys, math.max(1, cols.rowCount))
    target.call(new TRow(cols), out)
    out

  /** The key tuple for a row, or None if any component is NULL (NULL never joins). */
  private def keyAt(keys: Array[Array[AnyRef]], row: Int): Option[List[AnyRef]] =
    val acc = scala.collection.mutable.ListBuffer[AnyRef]()
    var k = 0
    while k < numKeys do
      val v = keys(k)(row)
      if v == null then return None
      acc += (v match
        case d: java.math.BigDecimal => d.stripTrailingZeros
        case other                   => other)
      k += 1
    Some(acc.toList)

/** Typed result rows (boxed Long/Double/String/BigDecimal/null), with the result-level transforms and
  * a normalized `rows` view for comparison (numbers → Double, NULL → null) — the cross-backend harness
  * yardstick. */
final class TypedResult(val names: Vector[String], val boxedRows: Vector[Vector[AnyRef]]):

  def rowCount: Int = boxedRows.size

  def rows: Vector[Vector[Any]] = boxedRows.map(_.map(TypedResult.normalize))

  def take(k: Int): TypedResult = new TypedResult(names, boxedRows.take(math.max(0, k)))

  def distinct: TypedResult = new TypedResult(names, boxedRows.distinct)

  def sortedBy(keys: Vector[SortKey]): TypedResult =
    new TypedResult(names, boxedRows.sortWith((a, b) => TypedResult.lessThan(a, b, keys)))

object TypedResult:

  private def normalize(v: AnyRef): Any =
    v match
      case null                => null
      case x: java.lang.Number => x.doubleValue
      case other               => other.toString

  private[backend] def lessThan(a: Vector[AnyRef], b: Vector[AnyRef], keys: Vector[SortKey]): Boolean =
    var i = 0
    while i < keys.size do
      val c = compareKey(a, b, keys(i))
      if c != 0 then return c < 0
      i += 1
    false

  private[backend] def compareKey(a: Vector[AnyRef], b: Vector[AnyRef], key: SortKey): Int =
    (a(key.column), b(key.column)) match
      case (null, null) => 0
      case (null, _)    => if key.nullsFirst then -1 else 1
      case (_, null)    => if key.nullsFirst then 1 else -1
      case (x, y) =>
        val c = compareNonNull(x, y)
        if key.ascending then c else -c

  private def compareNonNull(x: AnyRef, y: AnyRef): Int =
    (x, y) match
      case (p: java.math.BigDecimal, q: java.math.BigDecimal) => p.compareTo(q)
      case (p: java.lang.Number, q: java.lang.Number) => java.lang.Double.compare(p.doubleValue, q.doubleValue)
      case (p: String, q: String)                     => p.compareTo(q)
      case _                                          => x.toString.compareTo(y.toString)
