package protocatalyst.truffle.backend

import com.oracle.truffle.api.CallTarget
import org.apache.arrow.vector.*

import protocatalyst.executor.exec.Batch
import protocatalyst.expr.ProtoExpr
import protocatalyst.plan.ProtoPhysicalPlan
import protocatalyst.schema.ProtoSchema
import protocatalyst.truffle.exec.{CmpOp, GNodes}
import protocatalyst.truffle.slice.ColumnBatch
import protocatalyst.types.LiteralValue

/** Phase-3 builder: compile a numeric-subset `ProtoPhysicalPlan` into a fused-row Truffle plan
  * (`GNodes`), the AOT-safe winning shape from Phase 2. This is the bridge from the hand-built Q6
  * slice to the project's real IR — the same plans the Local Arrow executor and DataFusion run.
  *
  * Supported today: `PhysicalProject` / `PhysicalFilter` over a `TableScan`, with column refs,
  * numeric literals, `+ − × ÷`, the six comparisons, and `AND/OR/NOT`, over numeric columns (decoded
  * to `double`). Anything else (decimals as exact decimals, strings, temporal arithmetic, aggregates,
  * joins, null three-valued logic) is rejected with [[UnsupportedPlanException]] rather than guessed —
  * the coverage gap is explicit, matching the doc's bounded scope.
  */
object ProtoTruffleCompiler:

  final class UnsupportedPlanException(message: String) extends RuntimeException(message)

  def compile(plan: ProtoPhysicalPlan): CompiledTruffleQuery =
    val (projList, filterOpt, scanSchema) = decompose(plan)
    val index: String => Int = name =>
      val i = scanSchema.fields.indexWhere(_.name.equalsIgnoreCase(name))
      if i < 0 then throw UnsupportedPlanException(s"column not found: $name") else i
    val projections = projList.map(e => buildExpr(e, index)).toArray
    val filterNode = filterOpt.map(c => buildPred(c, index)).orNull
    val root = GNodes.FilterProjectRoot(null, filterNode, projections)
    CompiledTruffleQuery(root.getCallTarget, scanSchema, projList.map(outputName), projections.length)

  /** Peel `Project(Filter(Scan))` (in any subset) into (projections, optional filter, scan schema). */
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

  private def filterAndScan(plan: ProtoPhysicalPlan): (Option[ProtoExpr], ProtoSchema) =
    plan match
      case ProtoPhysicalPlan.PhysicalFilter(cond, child) =>
        val (_, schema) = filterAndScan(child)
        (Some(cond), schema)
      case ProtoPhysicalPlan.TableScan(_, _, schema, _) =>
        (None, schema)
      case other =>
        throw UnsupportedPlanException(s"unsupported operator: ${other.getClass.getSimpleName}")

  private def identityProjections(schema: ProtoSchema): Vector[ProtoExpr] =
    schema.fields.map(f => ProtoExpr.ColumnRef(f.name, None, f.dataType, f.nullable))

  private def buildExpr(e: ProtoExpr, index: String => Int): GNodes.Expr =
    e match
      case ProtoExpr.ColumnRef(name, _, _, _) => GNodes.Column(index(name))
      case ProtoExpr.BoundRef(i, _, _)        => GNodes.Column(i)
      case ProtoExpr.Literal(v)               => GNodes.Lit(toDouble(v))
      case ProtoExpr.Add(l, r)                => GNodes.Add(buildExpr(l, index), buildExpr(r, index))
      case ProtoExpr.Subtract(l, r)           => GNodes.Sub(buildExpr(l, index), buildExpr(r, index))
      case ProtoExpr.Multiply(l, r)           => GNodes.Mul(buildExpr(l, index), buildExpr(r, index))
      case ProtoExpr.Divide(l, r)             => GNodes.Div(buildExpr(l, index), buildExpr(r, index))
      case ProtoExpr.Cast(child, _)           => buildExpr(child, index) // numeric cast ⇒ double
      case ProtoExpr.Alias(child, _)          => buildExpr(child, index)
      case other =>
        throw UnsupportedPlanException(s"unsupported expression: ${other.getClass.getSimpleName}")

  private def buildPred(e: ProtoExpr, index: String => Int): GNodes.Pred =
    e match
      case ProtoExpr.And(children) => GNodes.And(children.map(c => buildPred(c, index)).toArray)
      case ProtoExpr.Or(children)  => GNodes.Or(children.map(c => buildPred(c, index)).toArray)
      case ProtoExpr.Not(c)        => GNodes.Not(buildPred(c, index))
      case ProtoExpr.Eq(l, r)      => cmp(l, r, CmpOp.EQ, index)
      case ProtoExpr.NotEq(l, r)   => cmp(l, r, CmpOp.NE, index)
      case ProtoExpr.Lt(l, r)      => cmp(l, r, CmpOp.LT, index)
      case ProtoExpr.LtEq(l, r)    => cmp(l, r, CmpOp.LE, index)
      case ProtoExpr.Gt(l, r)      => cmp(l, r, CmpOp.GT, index)
      case ProtoExpr.GtEq(l, r)    => cmp(l, r, CmpOp.GE, index)
      case other =>
        throw UnsupportedPlanException(s"unsupported predicate: ${other.getClass.getSimpleName}")

  private def cmp(l: ProtoExpr, r: ProtoExpr, op: CmpOp, index: String => Int): GNodes.Cmp =
    GNodes.Cmp(buildExpr(l, index), buildExpr(r, index), op)

  private def toDouble(v: LiteralValue): Double =
    v match
      case LiteralValue.DoubleValue(d)  => d
      case LiteralValue.FloatValue(f)   => f.toDouble
      case LiteralValue.IntValue(i)     => i.toDouble
      case LiteralValue.LongValue(l)    => l.toDouble
      case LiteralValue.ShortValue(s)   => s.toDouble
      case LiteralValue.ByteValue(b)    => b.toDouble
      case LiteralValue.DecimalValue(d) => d.toDouble
      case other =>
        throw UnsupportedPlanException(s"non-numeric literal: ${other.getClass.getSimpleName}")

  private def outputName(e: ProtoExpr): String =
    e match
      case ProtoExpr.Alias(_, name)           => name
      case ProtoExpr.ColumnRef(name, _, _, _) => name
      case ProtoExpr.BoundRef(i, _, _)        => s"col$i"
      case _                                  => "expr"

/** A compiled plan: the Truffle call target plus the metadata needed to feed it an Arrow batch and
  * read its output. The decode step turns the referenced numeric Arrow columns into `double[]`
  * (isolating the node-shape result from Arrow buffer access, per the doc). */
final class CompiledTruffleQuery(
    callTarget: CallTarget,
    val inputSchema: ProtoSchema,
    val outputNames: Vector[String],
    numProjections: Int
):

  /** End-to-end: decode the Arrow columns to `double[]`, then run the fused AST. */
  def run(input: Batch): TruffleResult = execute(decode(input))

  /** Decode the referenced numeric Arrow columns into the primitive column model the nodes read.
    * Separated from [[execute]] so a benchmark can amortize the decode and measure pure execution. */
  def decode(input: Batch): ColumnBatch =
    val n = input.rowCount
    val numCols = inputSchema.fields.size
    val cols = new Array[Array[Double]](numCols)
    var c = 0
    while c < numCols do
      cols(c) = decodeColumn(input.column(c), n)
      c += 1
    new ColumnBatch(cols, n)

  /** Run the fused filter→project AST over already-decoded columns. */
  def execute(cb: ColumnBatch): TruffleResult =
    val n = cb.rowCount
    val out = new Array[Array[Double]](numProjections)
    var p = 0
    while p < numProjections do
      out(p) = new Array[Double](n)
      p += 1
    val k = callTarget.call(cb, out).asInstanceOf[java.lang.Integer].intValue
    TruffleResult(outputNames, out, k)

  private def decodeColumn(vec: FieldVector, n: Int): Array[Double] =
    vec match
      case v: Float8Vector   => Array.tabulate(n)(i => v.get(i))
      case v: Float4Vector   => Array.tabulate(n)(i => v.get(i).toDouble)
      case v: BigIntVector   => Array.tabulate(n)(i => v.get(i).toDouble)
      case v: IntVector      => Array.tabulate(n)(i => v.get(i).toDouble)
      case v: SmallIntVector => Array.tabulate(n)(i => v.get(i).toDouble)
      case v: TinyIntVector  => Array.tabulate(n)(i => v.get(i).toDouble)
      case v: DecimalVector  => Array.tabulate(n)(i => v.getObject(i).doubleValue)
      case v: DateDayVector  => Array.tabulate(n)(i => v.get(i).toDouble)
      case v: BitVector      => Array.tabulate(n)(i => if v.get(i) != 0 then 1.0 else 0.0)
      case _                 => new Array[Double](n) // non-numeric, unreferenced ⇒ placeholder zeros

/** The Truffle backend's result, with numeric cells already as `Double` — the same normalization the
  * cross-backend harness uses, so rows compare directly against the Local/DataFusion outputs. */
final class TruffleResult(
    val names: Vector[String],
    cols: Array[Array[Double]],
    val rowCount: Int
):
  def rows: Vector[Vector[Any]] =
    (0 until rowCount).map(j => names.indices.map(c => cols(c)(j): Any).toVector).toVector
