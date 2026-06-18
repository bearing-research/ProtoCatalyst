package protocatalyst.truffle.backend

import com.oracle.truffle.api.CallTarget

import protocatalyst.executor.exec.Batch
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.ProtoPhysicalPlan
import protocatalyst.schema.ProtoSchema
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
    case LongCol, DoubleCol

  def compileFilterCount(plan: ProtoPhysicalPlan): TypedFilterCount =
    val (filterOpt, schema) = filterAndScan(plan)
    val predicate = filterOpt
      .map(c => buildPred(c, schema))
      .getOrElse(throw UnsupportedPlanException("expected a Filter over a TableScan"))
    val root = TFilterCountRoot(null, predicate)
    TypedFilterCount(root.getCallTarget, schema)

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
      case ProtoType.DoubleType | ProtoType.FloatType | _: ProtoType.DecimalType =>
        ColKind.DoubleCol
      case other =>
        throw UnsupportedPlanException(s"unsupported column type: ${other.getClass.getSimpleName}")

  private def buildExpr(e: ProtoExpr, schema: ProtoSchema): TExpr =
    e match
      case ProtoExpr.ColumnRef(name, _, _, _) =>
        val i = schema.fields.indexWhere(_.name.equalsIgnoreCase(name))
        if i < 0 then throw UnsupportedPlanException(s"column not found: $name")
        kindOf(schema.fields(i).dataType) match
          case ColKind.LongCol   => TLongColumn(i)
          case ColKind.DoubleCol => TDoubleColumn(i)
      case ProtoExpr.Literal(LiteralValue.LongValue(v))    => TLit.Long(v)
      case ProtoExpr.Literal(LiteralValue.IntValue(v))     => TLit.Long(v.toLong)
      case ProtoExpr.Literal(LiteralValue.ShortValue(v))   => TLit.Long(v.toLong)
      case ProtoExpr.Literal(LiteralValue.ByteValue(v))    => TLit.Long(v.toLong)
      case ProtoExpr.Literal(LiteralValue.DoubleValue(v))  => TLit.Double(v)
      case ProtoExpr.Literal(LiteralValue.FloatValue(v))   => TLit.Double(v.toDouble)
      case ProtoExpr.Literal(LiteralValue.DecimalValue(v)) => TLit.Double(v.toDouble)
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
      case ProtoExpr.Lt(l, r)   => TCompareFactory.LtNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.LtEq(l, r) => TCompareFactory.LeNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.Gt(l, r)   => TCompareFactory.GtNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case ProtoExpr.GtEq(l, r) => TCompareFactory.GeNodeGen.create(buildExpr(l, schema), buildExpr(r, schema))
      case other =>
        throw UnsupportedPlanException(s"unsupported predicate: ${other.getClass.getSimpleName}")

/** A compiled typed filter: counts rows passing the predicate under three-valued logic. */
final class TypedFilterCount(callTarget: CallTarget, schema: ProtoSchema):

  def count(input: Batch): Long =
    val cols = TypedColumnsDecoder.decode(input, schema)
    callTarget.call(new TRow(cols)).asInstanceOf[java.lang.Long].longValue
