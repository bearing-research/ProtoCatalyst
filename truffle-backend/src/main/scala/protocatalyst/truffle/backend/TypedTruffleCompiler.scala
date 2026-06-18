package protocatalyst.truffle.backend

import com.oracle.truffle.api.CallTarget

import protocatalyst.executor.exec.Batch
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.ProtoPhysicalPlan
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
  def compile(plan: ProtoPhysicalPlan): CompiledTypedQuery =
    plan match
      case ProtoPhysicalPlan.HashAggregate(grouping, aggs, child) if grouping.isEmpty =>
        compileGlobalAggregate(aggs, child)
      case ProtoPhysicalPlan.SortAggregate(grouping, aggs, child) if grouping.isEmpty =>
        compileGlobalAggregate(aggs, child)
      case _ => compileFilterProject(plan)

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
          ProtoType.DateType =>
        ColKind.LongCol
      case ProtoType.DoubleType | ProtoType.FloatType =>
        ColKind.DoubleCol
      case _: ProtoType.DecimalType =>
        ColKind.DecimalCol
      case ProtoType.StringType =>
        ColKind.StringCol
      case other =>
        throw UnsupportedPlanException(s"unsupported column type: ${other.getClass.getSimpleName}")

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
      case ProtoExpr.Literal(LiteralValue.DecimalValue(v)) => TLit.Dec(v.bigDecimal)
      case ProtoExpr.Add(l, r)      => TArithFactory.AddNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Subtract(l, r) => TArithFactory.SubNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Multiply(l, r) => TArithFactory.MulNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Cast(child, _) => buildExpr(child, schema)
      case ProtoExpr.Alias(child, _) => buildExpr(child, schema)
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
      case other =>
        throw UnsupportedPlanException(s"unsupported predicate: ${other.getClass.getSimpleName}")

/** A compiled typed filter: counts rows passing the predicate under three-valued logic. */
final class TypedFilterCount(callTarget: CallTarget, schema: ProtoSchema):

  def count(input: Batch): Long =
    val cols = TypedColumnsDecoder.decode(input, schema)
    callTarget.call(new TRow(cols)).asInstanceOf[java.lang.Long].longValue

/** A compiled typed filter→project: produces null-preserving rows. */
final class CompiledTypedQuery(
    callTarget: CallTarget,
    schema: ProtoSchema,
    val outputNames: Vector[String]
):

  def run(input: Batch): TypedResult =
    val cols = TypedColumnsDecoder.decode(input, schema)
    val numProjections = outputNames.size
    val out = Array.ofDim[AnyRef](numProjections, math.max(1, cols.rowCount))
    val k = callTarget.call(new TRow(cols), out).asInstanceOf[java.lang.Integer].intValue
    TypedResult(outputNames, out, k)

/** Typed result rows, normalized for cross-engine comparison: numeric cells become `Double` and SQL
  * NULL becomes `null` — the same yardstick the cross-backend harness and the interpreter use. */
final class TypedResult(val names: Vector[String], cols: Array[Array[AnyRef]], val rowCount: Int):

  def rows: Vector[Vector[Any]] =
    (0 until rowCount).map { j =>
      names.indices.map { c =>
        cols(c)(j) match
          case null                => null
          case x: java.lang.Number => x.doubleValue: Any
          case other               => other.toString: Any
      }.toVector
    }.toVector
