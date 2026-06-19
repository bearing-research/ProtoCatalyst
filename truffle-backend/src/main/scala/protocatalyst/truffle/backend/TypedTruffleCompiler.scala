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
    val (filterOpt, schema, tableName, _) = filterAndScan(plan)
    val cond = filterOpt.getOrElse(throw UnsupportedPlanException("expected a Filter over a TableScan"))
    val dsCols = decimalDoubleSafe(schema, Seq(cond))
    // A thunk so each parallel slice gets its own AST (Truffle nodes aren't thread-safe to share).
    val buildTarget = () =>
      given DoubleSafe = DoubleSafe(dsCols)
      TFilterCountRoot(null, buildPred(cond, schema)).getCallTarget
    TypedFilterCount(buildTarget, schema, tableName, neededColumns(schema, Seq(cond)), dsCols)

  /** Compile `Project`/`Filter` over a `TableScan` into a typed, null-preserving filter→project. */
  def compileFilterProject(plan: ProtoPhysicalPlan): CompiledTypedQuery =
    val (projList, filterOpt, schema, tableName, alias) = decompose(plan)
    val dsCols = decimalDoubleSafe(schema, filterOpt.toSeq ++ projList)
    val buildTarget = () =>
      given DoubleSafe = DoubleSafe(dsCols)
      val projections = projList.map(e => buildExpr(e, schema)).toArray
      val filterNode = filterOpt.map(c => buildPred(c, schema)).orNull
      TypedFilterProjectRoot(null, filterNode, projections).getCallTarget
    CompiledTypedQuery(
      buildTarget, schema, tableName,
      qualify(projList.map(outputName), alias),
      neededColumns(schema, filterOpt.toSeq ++ projList),
      LeafMerge.Concat,
      dsCols
    )

  private def decompose(
      plan: ProtoPhysicalPlan
  ): (Vector[ProtoExpr], Option[ProtoExpr], ProtoSchema, String, Option[String]) =
    plan match
      case ProtoPhysicalPlan.PhysicalProject(projList, child) =>
        val (filt, schema, name, alias) = filterAndScan(child)
        (projList, filt, schema, name, alias)
      case other =>
        val (filt, schema, name, alias) = filterAndScan(other)
        (identityProjections(schema), filt, schema, name, alias)

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
      case ProtoPhysicalPlan.HashAggregate(grouping, aggs, child) =>
        compileAggregate(grouping, aggs, child)
      case ProtoPhysicalPlan.SortAggregate(grouping, aggs, child) =>
        compileAggregate(grouping, aggs, child)
      case ProtoPhysicalPlan.HashJoin(l, r, jt, lk, rk, cond, _) => compileJoin(l, r, jt, lk, rk, cond)
      case ProtoPhysicalPlan.BroadcastHashJoin(l, r, jt, lk, rk, cond, _) => compileJoin(l, r, jt, lk, rk, cond)
      case ProtoPhysicalPlan.SortMergeJoin(l, r, jt, lk, rk, cond) => compileJoin(l, r, jt, lk, rk, cond)
      // Nested-loop / cross join: no equi-keys — a cross product filtered by the condition, which the
      // hash join expresses as empty key lists (every left row matches the single empty-key bucket).
      case ProtoPhysicalPlan.NestedLoopJoin(l, r, jt, cond) =>
        compileJoin(l, r, jt, Vector.empty, Vector.empty, cond)
      // Filter/Project over a non-single-table child (a join, an aggregate → HAVING, …): row-based,
      // so WHERE/HAVING/SELECT compose over joins. Single-table Filter/Project use the Truffle leaf.
      case ProtoPhysicalPlan.PhysicalFilter(cond, child) if !isSingleTable(child) =>
        RowFilterQuery(compile(child), cond)
      case ProtoPhysicalPlan.PhysicalProject(projList, child) if !isSingleTable(child) =>
        RowProjectQuery(compile(child), projList, projList.map(outputName))
      case _ => compileFilterProject(plan)

  /** Route an aggregate: a single-table (Filter/Scan) child uses the Truffle-accelerated leaf path;
    * anything else (a join, a projected child, …) uses the row-based aggregate over the child query. */
  private def compileAggregate(
      grouping: Vector[ProtoExpr],
      aggs: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  ): TypedQuery =
    if isSingleTableLeaf(child) then
      if grouping.isEmpty then compileGlobalAggregate(aggs, child)
      else compileGroupedAggregate(grouping, aggs, child)
    else compileRowAggregate(grouping, aggs, child)

  private def isSingleTableLeaf(p: ProtoPhysicalPlan): Boolean =
    p match
      case ProtoPhysicalPlan.TableScan(_, _, _, _) => true
      case ProtoPhysicalPlan.PhysicalFilter(_, c)  => isSingleTableLeaf(c)
      case _                                       => false

  /** A Filter/Project/Scan chain over a single table (no joins/aggregates) — the Truffle leaf path
    * (`compileFilterProject`) handles it; anything else routes to the row-based operators. */
  private def isSingleTable(p: ProtoPhysicalPlan): Boolean =
    p match
      case ProtoPhysicalPlan.TableScan(_, _, _, _)   => true
      case ProtoPhysicalPlan.PhysicalFilter(_, c)    => isSingleTable(c)
      case ProtoPhysicalPlan.PhysicalProject(_, c)   => isSingleTable(c)
      case _                                         => false

  /** Row-based aggregate over an arbitrary child query (e.g. a join) — groups the child's output rows
    * and aggregates them, so GROUP BY composes on top of any operator. */
  private def compileRowAggregate(
      grouping: Vector[ProtoExpr],
      aggs: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  ): RowAggregateQuery =
    val childQuery = compile(child)
    val names = childQuery.outputNames
    val groupCols = grouping.map(g => keyColumn(g, names))
    val parsed = aggs.map(a => parseRowAgg(a))
    val outputNames = grouping.map(outputName) ++ parsed.map(_._3)
    RowAggregateQuery(childQuery, names, groupCols, parsed.map(_._1).toVector, parsed.map(_._2).toVector, outputNames)

  /** Map a (possibly aliased) aggregate to (kind, input expression, name) — the row-based analogue of
    * `buildAgg`. Unlike the Truffle leaf, the input may be an arbitrary expression (e.g.
    * `SUM(extendedprice * (1 - discount))` in TPC-H Q3/Q5/Q10), evaluated per row by `RowEval` against
    * the child's output. `None` means "count all rows" (COUNT(*) / COUNT(literal)). */
  private def parseRowAgg(e: ProtoExpr): (AggKind, Option[ProtoExpr], String) =
    e match
      case ProtoExpr.Alias(inner, name) =>
        val (k, c, _) = parseRowAgg(inner)
        (k, c, name)
      case ProtoExpr.Sum(c)                              => (AggKind.SUM, Some(c), "sum")
      case ProtoExpr.Count(ProtoExpr.Literal(_), _)      => (AggKind.COUNT, None, "count")
      case ProtoExpr.Count(c, _)                         => (AggKind.COUNT, Some(c), "count")
      case ProtoExpr.Min(c)                              => (AggKind.MIN, Some(c), "min")
      case ProtoExpr.Max(c)                              => (AggKind.MAX, Some(c), "max")
      case ProtoExpr.Avg(c)                              => (AggKind.AVG, Some(c), "avg")
      case other =>
        throw UnsupportedPlanException(s"unsupported aggregate: ${other.getClass.getSimpleName}")

  private def compileGroupedAggregate(
      grouping: Vector[ProtoExpr],
      aggs: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  ): CompiledTypedQuery =
    val (filterOpt, schema, tableName, _) = filterAndScan(child)
    val parsed = aggs.map(a => buildAgg(a, schema)) // built once for kinds/names (nodes discarded)
    val aggKinds = parsed.map(_._1)
    // Output columns: grouping keys first, then aggregates (the interpreter's order).
    val names = grouping.map(outputName) ++ parsed.map(_._3)
    val dsCols = decimalDoubleSafe(schema, filterOpt.toSeq ++ grouping ++ aggs)
    val buildTarget = () =>
      given DoubleSafe = DoubleSafe(dsCols)
      val keyNodes = grouping.map(g => buildExpr(g, schema)).toArray
      val p = aggs.map(a => buildAgg(a, schema))
      val filterNode = filterOpt.map(c => buildPred(c, schema)).orNull
      TypedGroupedAggRoot(null, filterNode, keyNodes, p.map(_._2).toArray, p.map(_._1).toArray).getCallTarget
    // SUM/COUNT/MIN/MAX finals re-aggregate across slices; AVG can't, so it stays serial.
    val merge =
      if aggKinds.contains(AggKind.AVG) then LeafMerge.Serial
      else LeafMerge.Agg(grouping.size, aggKinds)
    CompiledTypedQuery(
      buildTarget, schema, tableName, names,
      neededColumns(schema, filterOpt.toSeq ++ grouping ++ aggs),
      merge,
      dsCols
    )

  private def compileGlobalAggregate(
      aggs: Vector[ProtoExpr],
      child: ProtoPhysicalPlan
  ): CompiledTypedQuery =
    val (filterOpt, schema, tableName, _) = filterAndScan(child)
    val parsed = aggs.map(a => buildAgg(a, schema)) // built once for kinds/names (nodes discarded)
    val aggKinds = parsed.map(_._1)
    val dsCols = decimalDoubleSafe(schema, filterOpt.toSeq ++ aggs)
    val buildTarget = () =>
      given DoubleSafe = DoubleSafe(dsCols)
      val p = aggs.map(a => buildAgg(a, schema))
      val filterNode = filterOpt.map(c => buildPred(c, schema)).orNull
      TypedGlobalAggRoot(null, filterNode, p.map(_._2).toArray, p.map(_._1).toArray).getCallTarget
    val merge =
      if aggKinds.contains(AggKind.AVG) then LeafMerge.Serial
      else LeafMerge.Agg(0, aggKinds)
    CompiledTypedQuery(
      buildTarget, schema, tableName, parsed.map(_._3),
      neededColumns(schema, filterOpt.toSeq ++ aggs),
      merge,
      dsCols
    )

  /** Map a (possibly aliased) aggregate to (kind, inner-expr, name). COUNT uses a placeholder input
    * (its value is ignored — COUNT(*) counts surviving rows), so the `@Children` array has no nulls. */
  private def buildAgg(e: ProtoExpr, schema: ProtoSchema)(using DoubleSafe): (AggKind, TExpr, String) =
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

  private def filterAndScan(
      plan: ProtoPhysicalPlan
  ): (Option[ProtoExpr], ProtoSchema, String, Option[String]) =
    plan match
      case ProtoPhysicalPlan.PhysicalFilter(cond, child) =>
        val (_, schema, name, alias) = filterAndScan(child)
        (Some(cond), schema, name, alias)
      case ProtoPhysicalPlan.TableScan(name, alias, schema, _) =>
        (None, schema, name, alias)
      case other =>
        throw UnsupportedPlanException(s"unsupported operator: ${other.getClass.getSimpleName}")

  /** Prefix each output name with `alias.` (unless it already carries a qualifier), mirroring the
    * executor's `qualifyFields`. This lets a join's combined output disambiguate same-named columns
    * from the two sides (e.g. `n.regionkey` vs `r.regionkey`); unaliased scans keep bare names. */
  private def qualify(names: Vector[String], alias: Option[String]): Vector[String] =
    alias match
      case Some(a) => names.map(n => if n.contains('.') then n else s"$a.$n")
      case None    => names

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
      case ProtoExpr.ColumnRef(name, q, _, _) => outputIndex(name, q, names)
      case ProtoExpr.Alias(_, name)           => outputIndex(name, None, names)
      case other =>
        throw UnsupportedPlanException(s"sort key must be a column reference, got ${other.getClass.getSimpleName}")
    SortKey(
      column,
      so.direction == SortDirection.Ascending,
      so.nullOrdering == NullOrdering.NullsFirst
    )

  /** Resolve a column reference (name + optional qualifier) to its index in the child's output names,
    * using the same qualifier-aware matching as the executor (see `RowEval.resolveIndex`). */
  private def outputIndex(name: String, qualifier: Option[String], names: Vector[String]): Int =
    val i = RowEval.resolveIndex(name, qualifier, names)
    if i < 0 then throw UnsupportedPlanException(s"column not in output: ${qualifier.map(_ + ".").getOrElse("")}$name")
    else i

  /** Compile an equi-join. Each side is compiled as a full child query (so filters/projects/aggregates
    * *under* a join run first and compose); the join then matches their row outputs on the key columns. */
  private def compileJoin(
      left: ProtoPhysicalPlan,
      right: ProtoPhysicalPlan,
      joinType: JoinType,
      leftKeys: Vector[ProtoExpr],
      rightKeys: Vector[ProtoExpr],
      condition: Option[ProtoExpr]
  ): JoinQuery =
    val leftChild = compile(left)
    val rightChild = compile(right)
    val leftKeyCols = leftKeys.map(k => keyColumn(k, leftChild.outputNames))
    val rightKeyCols = rightKeys.map(k => keyColumn(k, rightChild.outputNames))
    JoinQuery(
      leftChild,
      rightChild,
      leftKeyCols,
      rightKeyCols,
      joinType,
      condition,
      leftChild.outputNames ++ rightChild.outputNames
    )

  private def keyColumn(k: ProtoExpr, names: Vector[String]): Int =
    k match
      case ProtoExpr.ColumnRef(n, q, _, _) => outputIndex(n, q, names)
      case other =>
        throw UnsupportedPlanException(s"join key must be a column ref, got ${other.getClass.getSimpleName}")

  private def compileWindow(
      windowExprs: Vector[ProtoExpr],
      partitionSpec: Vector[ProtoExpr],
      orderSpec: Vector[SortOrder],
      childPlan: ProtoPhysicalPlan
  ): WindowQuery =
    val child = compile(childPlan)
    val names = child.outputNames
    val partitionCols = partitionSpec.map {
      case ProtoExpr.ColumnRef(name, q, _, _) => outputIndex(name, q, names)
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
      case ProtoExpr.ColumnRef(n, q, _, _) => outputIndex(n, q, names)
      case other =>
        throw UnsupportedPlanException(s"window aggregate input must be a column ref, got ${other.getClass.getSimpleName}")

  private def aggColOrNeg(c: ProtoExpr, names: Vector[String]): Int =
    c match
      case ProtoExpr.ColumnRef(n, q, _, _) => outputIndex(n, q, names)
      case _                               => -1 // COUNT(literal) / COUNT(*) → count all rows

  /** The schema-column indices `exprs` actually reference — so a leaf decodes only those columns
    * (`TypedColumnsDecoder.decode(.., needed)`) instead of the whole row. Walks each ProtoExpr
    * generically via `productIterator`, collecting `ColumnRef`s and mapping their (bare) name to the
    * schema field index (the same resolution `buildExpr` uses). */
  private def neededColumns(schema: ProtoSchema, exprs: Seq[ProtoExpr]): Set[Int] =
    def refs(any: Any): Seq[String] =
      any match
        case ProtoExpr.ColumnRef(name, _, _, _) => Seq(name)
        case p: Product                         => p.productIterator.flatMap(refs).toSeq
        case it: Iterable[?]                     => it.flatMap(refs).toSeq
        case _                                  => Seq.empty
    exprs
      .flatMap(refs)
      .map(name => schema.fields.indexWhere(_.name.equalsIgnoreCase(name)))
      .filter(_ >= 0)
      .toSet

  /** Schema indices of decimal columns referenced **only** as `Cast(col AS DOUBLE)` (never as an exact
    * decimal — a bare ref, a decimal comparison/SUM). Such a column decodes straight into a `double[]`
    * (skipping the per-cell `BigDecimal` the `DecimalVector` decode allocates) and its column node
    * becomes a `TDoubleColumn`. Any other use excludes it, preserving exact-decimal semantics. The
    * money columns in TPC-H Q1/Q3/Q5/Q6/Q10 are all `CAST(... AS DOUBLE)`, so this removes millions of
    * BigDecimal allocations per query — the GC bound the parallel scan otherwise hit. */
  private def decimalDoubleSafe(schema: ProtoSchema, exprs: Seq[ProtoExpr]): Set[Int] =
    def isDecimal(name: String): Boolean =
      val i = schema.fields.indexWhere(_.name.equalsIgnoreCase(name))
      i >= 0 && schema.fields(i).dataType.isInstanceOf[ProtoType.DecimalType]
    // A literal that is *not* an exact decimal — comparing a decimal column to it widens both to
    // double (exactly what the interpreter's `(Number, Number) → doubleValue` rule does), so reading
    // the column as double matches. A decimal literal or a non-literal would not be safe.
    def isNonDecimalLiteral(e: ProtoExpr): Boolean = e match
      case ProtoExpr.Literal(_: LiteralValue.DecimalValue) => false
      case ProtoExpr.Literal(_)                            => true
      case _                                               => false
    def comparisonOperands(e: ProtoExpr): Option[(ProtoExpr, ProtoExpr)] = e match
      case ProtoExpr.Eq(l, r)    => Some((l, r))
      case ProtoExpr.NotEq(l, r) => Some((l, r))
      case ProtoExpr.Lt(l, r)    => Some((l, r))
      case ProtoExpr.LtEq(l, r)  => Some((l, r))
      case ProtoExpr.Gt(l, r)    => Some((l, r))
      case ProtoExpr.GtEq(l, r)  => Some((l, r))
      case _                     => None

    val safe = scala.collection.mutable.Set[String]()
    val unsafe = scala.collection.mutable.Set[String]()
    def walk(any: Any): Unit =
      any match
        case ProtoExpr.Cast(ProtoExpr.ColumnRef(name, _, _, _), ProtoType.DoubleType) if isDecimal(name) =>
          safe += name.toLowerCase // double-safe occurrence; don't descend into the column
        case e: ProtoExpr if comparisonOperands(e).isDefined =>
          val (l, r) = comparisonOperands(e).get
          (l, r) match
            // `decimal col <cmp> non-decimal literal` (either order) is a double-semantic comparison.
            case (ProtoExpr.ColumnRef(name, _, _, _), lit) if isDecimal(name) && isNonDecimalLiteral(lit) =>
              safe += name.toLowerCase
            case (lit, ProtoExpr.ColumnRef(name, _, _, _)) if isDecimal(name) && isNonDecimalLiteral(lit) =>
              safe += name.toLowerCase
            case _ => walk(l); walk(r)
        case ProtoExpr.ColumnRef(name, _, _, _) if isDecimal(name) =>
          unsafe += name.toLowerCase
        case p: Product      => p.productIterator.foreach(walk)
        case it: Iterable[?] => it.foreach(walk)
        case _               => ()
    exprs.foreach(walk)
    safe.iterator
      .filterNot(unsafe.contains)
      .map(name => schema.fields.indexWhere(_.name.equalsIgnoreCase(name)))
      .filter(_ >= 0)
      .toSet

  /** Which referenced decimal columns to decode as `double[]` (see [[decimalDoubleSafe]]). Carried as a
    * `using` value so it threads through `buildExpr`'s ~50 recursive cases without per-call plumbing;
    * the default is empty (no decimal is treated as double unless a leaf opts it in). */
  private case class DoubleSafe(cols: Set[Int])
  private given DoubleSafe = DoubleSafe(Set.empty)

  private def buildExpr(e: ProtoExpr, schema: ProtoSchema)(using ds: DoubleSafe): TExpr =
    e match
      case ProtoExpr.ColumnRef(name, _, _, _) =>
        val i = schema.fields.indexWhere(_.name.equalsIgnoreCase(name))
        if i < 0 then throw UnsupportedPlanException(s"column not found: $name")
        kindOf(schema.fields(i).dataType) match
          case ColKind.LongCol                          => TLongColumn(i)
          case ColKind.DoubleCol                        => TDoubleColumn(i)
          case ColKind.StringCol                        => TStringColumn(i)
          case ColKind.DecimalCol if ds.cols.contains(i) => TDoubleColumn(i) // decoded as double[]
          case ColKind.DecimalCol                       => TDecimalColumn(i)
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
      case ProtoExpr.Replace(s, search, r) =>
        TString.Replace(buildExpr(s, schema), buildExpr(search, schema), buildExpr(r, schema))
      case ProtoExpr.Lpad(s, l, p) => TString.Pad(buildExpr(s, schema), buildExpr(l, schema), buildExpr(p, schema), true)
      case ProtoExpr.Rpad(s, l, p) => TString.Pad(buildExpr(s, schema), buildExpr(l, schema), buildExpr(p, schema), false)
      case ProtoExpr.StringRepeat(s, t) => TString.Repeat(buildExpr(s, schema), buildExpr(t, schema))
      case ProtoExpr.Year(c)              => TDate.Year(buildExpr(c, schema))
      case ProtoExpr.Month(c)             => TDate.Month(buildExpr(c, schema))
      case ProtoExpr.DayOfMonth(c)        => TDate.DayOfMonth(buildExpr(c, schema))
      case ProtoExpr.DateAdd(s, d)        => TDate.Shift(buildExpr(s, schema), buildExpr(d, schema), true)
      case ProtoExpr.DateSub(s, d)        => TDate.Shift(buildExpr(s, schema), buildExpr(d, schema), false)
      case ProtoExpr.Alias(child, _)      => buildExpr(child, schema)
      case other =>
        throw UnsupportedPlanException(s"unsupported expression: ${other.getClass.getSimpleName}")

  private def buildPred(e: ProtoExpr, schema: ProtoSchema)(using DoubleSafe): TExpr =
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
final class TypedFilterCount(
    buildTarget: () => CallTarget,
    schema: ProtoSchema,
    tableName: String,
    neededCols: Set[Int],
    decimalAsDouble: Set[Int]
):

  def count(tables: Map[String, Batch]): Long =
    val batch = tables.getOrElse(tableName, throw IllegalArgumentException(s"input table not found: $tableName"))
    // Count parallelizes cleanly — the per-slice counts simply sum.
    if ParallelScan.shouldParallelize(batch.rowCount) then
      val slices = ParallelScan.slices(batch, ParallelScan.parallelism)
      val total = ParallelScan.parMap(slices)(sb => java.lang.Long.valueOf(countSlice(sb, buildTarget()))).map(_.longValue).sum
      slices.foreach(_.close())
      total
    else countSlice(batch, buildTarget())

  private def countSlice(batch: Batch, target: CallTarget): Long =
    val cols = TypedColumnsDecoder.decode(batch, schema, neededCols, decimalAsDouble)
    target.call(new TRow(cols)).asInstanceOf[java.lang.Long].longValue

  def count(input: Batch): Long = count(Map(tableName -> input))

/** A compiled typed query: produces null-preserving result rows. Executes against a catalog of input
  * tables (so joins compose — each leaf scan resolves its own table by name). */
sealed trait TypedQuery:
  def outputNames: Vector[String]

  /** The tables this query reads (by name). */
  def tableNames: Set[String]

  /** Run against a catalog of input tables. */
  def run(tables: Map[String, Batch]): TypedResult

  /** Convenience for single-table queries. */
  final def run(input: Batch): TypedResult =
    tableNames.toList match
      case sole :: Nil => run(Map(sole -> input))
      case _ =>
        throw IllegalArgumentException(s"run(Batch) requires exactly one input table, found $tableNames")

/** How a leaf's per-row-range partial results combine, so the scan can run in parallel over row
  * slices. `Concat` (filter/project) just appends rows in slice order; `Agg` re-groups partials by the
  * first `numKeys` columns and combines each aggregate column by its kind (SUM/COUNT add, MIN/MAX
  * extreme) — valid because those finals are themselves mergeable. `Serial` opts a leaf out of
  * parallelism (e.g. AVG, whose per-slice finals can't be re-averaged without per-slice counts). */
enum LeafMerge:
  case Concat
  case Agg(numKeys: Int, kinds: Vector[AggKind])
  case Serial

/** Parallel-scan helpers: zero-copy row slicing + a parallel map. The Arrow root is sliced serially
  * (slicing reads the source vectors — not safe to do concurrently), then each slice is decoded and
  * executed on its own thread. Each task builds a *fresh* Truffle AST (`buildTarget()`); Truffle nodes
  * mutate specialization state on first calls, so sharing one AST across threads would race. */
object ParallelScan:
  import scala.jdk.CollectionConverters.*

  val parallelism: Int = math.max(1, Runtime.getRuntime.availableProcessors - 1)
  /** Below this row count the fork/join + slice overhead isn't worth it — run serially. A `var` only so
    * tests can force the parallel path over small fixtures; production never mutates it. */
  @volatile var threshold: Int = 250_000

  def shouldParallelize(rowCount: Int): Boolean = parallelism > 1 && rowCount >= threshold

  /** -Dtruffle.profile prints per-operator timings to stderr (composition-layer profiling). */
  val profile: Boolean = sys.props.contains("truffle.profile")
  inline def prof(msg: => String): Unit = if profile then System.err.println(msg)
  inline def ms(t0: Long, t1: Long): Long = (t1 - t0) / 1000000L

  /** Contiguous, zero-copy row-range slices of `batch` (caller closes them). */
  def slices(batch: Batch, p: Int): Vector[Batch] =
    val n = batch.rowCount
    val chunk = (n + p - 1) / p
    (0 until p).iterator
      .map(i => (i * chunk, math.min(chunk, n - i * chunk)))
      .takeWhile(_._2 > 0)
      .map((start, len) => Batch.fromRoot(batch.root.slice(start, len), batch.schema))
      .toVector

  /** Map over slices on the common fork/join pool, preserving slice order. */
  def parMap[A](items: Vector[Batch])(f: Batch => A): Vector[A] =
    items.asJava.parallelStream().map(b => f(b)).collect(java.util.stream.Collectors.toList).asScala.toVector

/** Combine two per-slice aggregate finals — the same arithmetic the row-aggregate path uses, applied
  * across slices. SUM/COUNT add; MIN/MAX take the extreme; all null-aware (a SUM/MIN/MAX over a slice
  * with no surviving rows is null). AVG is never combined here — its leaf is marked `Serial`. */
object AggMerge:
  def normKey(v: AnyRef): AnyRef = v match
    case d: java.math.BigDecimal => d.stripTrailingZeros
    case other                   => other

  def combine(kind: AggKind, a: AnyRef, b: AnyRef): AnyRef =
    kind match
      case AggKind.COUNT => java.lang.Long.valueOf(asLong(a) + asLong(b))
      case AggKind.SUM   => add(a, b)
      case AggKind.MIN   => extreme(a, b, min = true)
      case AggKind.MAX   => extreme(a, b, min = false)
      case AggKind.AVG   => add(a, b) // unreachable (AVG ⇒ Serial); defined for totality

  private def asLong(v: AnyRef): Long = if v == null then 0L else v.asInstanceOf[java.lang.Number].longValue

  private def add(a: AnyRef, b: AnyRef): AnyRef =
    (a, b) match
      case (null, y) => y
      case (x, null) => x
      case (x: java.math.BigDecimal, y: java.math.BigDecimal) => x.add(y)
      case (x: java.lang.Number, y: java.lang.Number)         => java.lang.Double.valueOf(x.doubleValue + y.doubleValue)
      case _                                                  => a

  private def extreme(a: AnyRef, b: AnyRef, min: Boolean): AnyRef =
    (a, b) match
      case (null, y) => y
      case (x, null) => x
      case (x, y) =>
        val c = (x, y) match
          case (p: java.math.BigDecimal, q: java.math.BigDecimal) => p.compareTo(q)
          case (p: java.lang.Number, q: java.lang.Number)         => java.lang.Double.compare(p.doubleValue, q.doubleValue)
          case (p: String, q: String)                             => p.compareTo(q)
          case _                                                  => x.toString.compareTo(y.toString)
        if (min && c <= 0) || (!min && c >= 0) then x else y

/** Leaf query: a Truffle call target (filter→project, or a global/grouped aggregate) over one table.
  * Holds `buildTarget`, a thunk that builds a fresh AST per call, so a large scan can be split into row
  * slices decoded + executed in parallel (`merge` says how the partials recombine). */
final class CompiledTypedQuery(
    buildTarget: () => CallTarget,
    schema: ProtoSchema,
    tableName: String,
    val outputNames: Vector[String],
    neededCols: Set[Int],
    merge: LeafMerge,
    decimalAsDouble: Set[Int]
) extends TypedQuery:

  def tableNames: Set[String] = Set(tableName)

  def run(tables: Map[String, Batch]): TypedResult =
    val batch = tables.getOrElse(tableName, throw IllegalArgumentException(s"input table not found: $tableName"))
    merge match
      case LeafMerge.Serial => runSlice(batch, buildTarget())
      case _ if !ParallelScan.shouldParallelize(batch.rowCount) => runSlice(batch, buildTarget())
      case _ =>
        val slices = ParallelScan.slices(batch, ParallelScan.parallelism)
        val partials = ParallelScan.parMap(slices)(sb => runSlice(sb, buildTarget()))
        slices.foreach(_.close())
        merge match
          case LeafMerge.Concat            => new TypedResult(outputNames, partials.flatMap(_.boxedRows))
          case LeafMerge.Agg(numKeys, kinds) => mergeAgg(partials, numKeys, kinds)
          case LeafMerge.Serial            => partials.head // unreachable

  /** Decode + execute one (possibly sliced) batch through a fresh call target. */
  private def runSlice(batch: Batch, target: CallTarget): TypedResult =
    val cols = TypedColumnsDecoder.decode(batch, schema, neededCols, decimalAsDouble)
    val numCols = outputNames.size
    val out = Array.ofDim[AnyRef](numCols, math.max(1, cols.rowCount))
    val k = target.call(new TRow(cols), out).asInstanceOf[java.lang.Integer].intValue
    val boxed = (0 until k).map(j => (0 until numCols).map(c => out(c)(j)).toArray).toVector
    new TypedResult(outputNames, boxed)

  /** Re-aggregate per-slice partials: group by the first `numKeys` columns, combine each aggregate
    * column by its kind. Global aggregate is `numKeys == 0` (one combined group). */
  private def mergeAgg(partials: Vector[TypedResult], numKeys: Int, kinds: Vector[AggKind]): TypedResult =
    val groups = scala.collection.mutable.LinkedHashMap[List[AnyRef], Array[AnyRef]]()
    for p <- partials; row <- p.boxedRows do
      val key = (0 until numKeys).map(c => AggMerge.normKey(row(c))).toList
      groups.get(key) match
        case None => groups(key) = row.toArray
        case Some(acc) =>
          var a = 0
          while a < kinds.size do
            val col = numKeys + a
            acc(col) = AggMerge.combine(kinds(a), acc(col), row(col))
            a += 1
    new TypedResult(outputNames, groups.valuesIterator.toVector)

/** LIMIT n — the result-level operators are plain row transforms over a child query. */
final class LimitQuery(child: TypedQuery, limit: Int) extends TypedQuery:
  def outputNames: Vector[String] = child.outputNames
  def tableNames: Set[String] = child.tableNames
  def run(tables: Map[String, Batch]): TypedResult = child.run(tables).take(limit)

/** DISTINCT. */
final class DistinctQuery(child: TypedQuery) extends TypedQuery:
  def outputNames: Vector[String] = child.outputNames
  def tableNames: Set[String] = child.tableNames
  def run(tables: Map[String, Batch]): TypedResult = child.run(tables).distinct

/** ORDER BY, honoring each key's direction and NULLS FIRST/LAST. */
final class SortQuery(child: TypedQuery, keys: Vector[SortKey]) extends TypedQuery:
  def outputNames: Vector[String] = child.outputNames
  def tableNames: Set[String] = child.tableNames
  def run(tables: Map[String, Batch]): TypedResult = child.run(tables).sortedBy(keys)

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

  def tableNames: Set[String] = child.tableNames

  def run(tables: Map[String, Batch]): TypedResult =
    val rows = child.run(tables).boxedRows
    val result = new Array[Array[AnyRef]](rows.size)
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
      val constVals: Array[AnyRef] = windowFns.map { fn =>
        fn.kind match
          case WindowFnKind.Sum | WindowFnKind.Count | WindowFnKind.Min | WindowFnKind.Max |
              WindowFnKind.Avg =>
            partitionAggregate(fn, idxs, rows)
          case WindowFnKind.FirstValue => firstLastValue(ordered, fn, rows, first = true)
          case WindowFnKind.LastValue  => firstLastValue(ordered, fn, rows, first = false)
          case WindowFnKind.NthValue =>
            if fn.offset >= 1 && fn.offset <= m then rows(ordered(fn.offset - 1))(fn.inputCol) else null
          case _ => null // ranking / offset — per-row
      }.toArray

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
  private def offsetValue(ordered: Vector[Int], target: Int, fn: WindowFn, rows: Vector[Array[AnyRef]]): AnyRef =
    if target >= 0 && target < ordered.size then rows(ordered(target))(fn.inputCol) else fn.default

  /** FIRST_VALUE / LAST_VALUE over the ordered partition, optionally ignoring NULLs. */
  private def firstLastValue(ordered: Vector[Int], fn: WindowFn, rows: Vector[Array[AnyRef]], first: Boolean): AnyRef =
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

  private def partitionAggregate(fn: WindowFn, idxs: IndexedSeq[Int], rows: Vector[Array[AnyRef]]): AnyRef =
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

  private def extremeBoxed(idxs: IndexedSeq[Int], col: Int, rows: Vector[Array[AnyRef]], min: Boolean): AnyRef =
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

  private def partitionKey(row: Array[AnyRef]): List[AnyRef] =
    partitionCols.map { c =>
      row(c) match
        case d: java.math.BigDecimal => d.stripTrailingZeros
        case other                   => other
    }.toList

  private def sameOrder(a: Array[AnyRef], b: Array[AnyRef]): Boolean =
    orderKeys.forall(k => TypedResult.compareKey(a, b, k) == 0)

/** Equi hash join (inner + outer): build a hash table on the right keys, probe with the left, emit
  * `left ++ right` for each match. NULL keys never match (SQL semantics); decimal keys are
  * value-normalized. For LEFT/FULL OUTER, unmatched left rows are emitted with a null-padded right;
  * for RIGHT/FULL OUTER, unmatched right rows with a null-padded left. Two explicit inputs (joins are
  * inherently multi-input). */
/** Equi hash join (inner + outer) — a composable `TypedQuery`. Runs both child queries against the
  * catalog (so filters/projects/aggregates under the join execute first), builds a hash table on the
  * right child's key columns, probes with the left, and emits `left-row ++ right-row` per match. NULL
  * keys never match; decimal keys are value-normalized. LEFT/FULL emit unmatched left rows with a
  * null-padded right; RIGHT/FULL emit unmatched right rows with a null-padded left. */
final class JoinQuery(
    leftChild: TypedQuery,
    rightChild: TypedQuery,
    leftKeyCols: Vector[Int],
    rightKeyCols: Vector[Int],
    joinType: JoinType,
    condition: Option[ProtoExpr],
    val outputNames: Vector[String]
) extends TypedQuery:

  def tableNames: Set[String] = leftChild.tableNames ++ rightChild.tableNames

  private val emitUnmatchedLeft = joinType == JoinType.LeftOuter || joinType == JoinType.FullOuter
  private val emitUnmatchedRight = joinType == JoinType.RightOuter || joinType == JoinType.FullOuter

  def run(tables: Map[String, Batch]): TypedResult =
    val tStart = System.nanoTime()
    val left = leftChild.run(tables).boxedRows
    val tLeft = System.nanoTime()
    val right = rightChild.run(tables).boxedRows
    val tRight = System.nanoTime()
    val nullLeft = Array.fill[AnyRef](leftChild.outputNames.size)(null)
    val nullRight = Array.fill[AnyRef](rightChild.outputNames.size)(null)
    val empty = scala.collection.mutable.ArrayBuffer.empty[Int]

    val table =
      scala.collection.mutable.HashMap[List[AnyRef], scala.collection.mutable.ArrayBuffer[Int]]()
    var ri = 0
    while ri < right.size do
      keyOf(right(ri), rightKeyCols).foreach { key =>
        table.getOrElseUpdate(key, scala.collection.mutable.ArrayBuffer[Int]()) += ri
      }
      ri += 1
    val matchedRight = Array.fill(right.size)(false)

    val rows = scala.collection.mutable.ArrayBuffer[Array[AnyRef]]()
    var li = 0
    while li < left.size do
      val candidates = keyOf(left(li), leftKeyCols).flatMap(table.get).getOrElse(empty)
      var anyMatch = false
      candidates.foreach { rj =>
        val combined = left(li) ++ right(rj)
        // The residual condition (if any) is part of the match test — important for outer joins,
        // where a left row whose only key-matches fail the condition still gets null-padded.
        if condition.forall(c => RowEval.holds(c, combined, outputNames)) then
          rows += combined
          matchedRight(rj) = true
          anyMatch = true
      }
      if !anyMatch && emitUnmatchedLeft then rows += (left(li) ++ nullRight)
      li += 1

    if emitUnmatchedRight then
      var rj = 0
      while rj < right.size do
        if !matchedRight(rj) then rows += (nullLeft ++ right(rj))
        rj += 1

    val tEnd = System.nanoTime()
    ParallelScan.prof(
      s"[JOIN ${outputNames.size}c] left=${ParallelScan.ms(tStart, tLeft)}ms(${left.size}) " +
        s"right=${ParallelScan.ms(tLeft, tRight)}ms(${right.size}) probe=${ParallelScan.ms(tRight, tEnd)}ms out=${rows.size}")
    new TypedResult(outputNames, rows.toVector)

  /** The key tuple for a row, or None if any component is NULL (NULL never joins). */
  private def keyOf(row: Array[AnyRef], keyCols: Vector[Int]): Option[List[AnyRef]] =
    val acc = scala.collection.mutable.ListBuffer[AnyRef]()
    var k = 0
    while k < keyCols.size do
      val v = row(keyCols(k))
      if v == null then return None
      acc += (v match
        case d: java.math.BigDecimal => d.stripTrailingZeros
        case other                   => other)
      k += 1
    Some(acc.toList)

/** Row-based filter over a child query (WHERE/HAVING above a join or aggregate). Keeps child rows for
  * which the predicate is exactly TRUE, evaluated over the boxed row by [[RowEval]]. */
final class RowFilterQuery(child: TypedQuery, predicate: ProtoExpr) extends TypedQuery:
  def outputNames: Vector[String] = child.outputNames
  def tableNames: Set[String] = child.tableNames
  def run(tables: Map[String, Batch]): TypedResult =
    val r = child.run(tables)
    new TypedResult(r.names, r.boxedRows.filter(row => RowEval.holds(predicate, row, r.names)))

/** Row-based projection over a child query (SELECT above a join). Evaluates each projection expression
  * over the boxed row via [[RowEval]]. */
final class RowProjectQuery(child: TypedQuery, exprs: Vector[ProtoExpr], val outputNames: Vector[String])
    extends TypedQuery:
  def tableNames: Set[String] = child.tableNames
  def run(tables: Map[String, Batch]): TypedResult =
    val r = child.run(tables)
    new TypedResult(outputNames, r.boxedRows.map(row => exprs.map(e => RowEval.eval(e, row, r.names)).toArray))

/** Row-based aggregate over a child query's output rows — used when GROUP BY sits above a join (or any
  * non-single-table child). Groups boxed rows by the grouping columns and computes
  * SUM/COUNT/MIN/MAX/AVG (SUM exact for decimals). Output is grouping keys ++ aggregate results; a
  * global aggregate (no grouping) always emits one row. */
final class RowAggregateQuery(
    child: TypedQuery,
    childNames: Vector[String],
    groupCols: Vector[Int],
    aggKinds: Vector[AggKind],
    aggInputs: Vector[Option[ProtoExpr]],
    val outputNames: Vector[String]
) extends TypedQuery:

  def tableNames: Set[String] = child.tableNames

  def run(tables: Map[String, Batch]): TypedResult =
    val t0 = System.nanoTime()
    val rows = child.run(tables).boxedRows
    val t1 = System.nanoTime()
    if groupCols.isEmpty then
      val r = new TypedResult(outputNames, Vector(aggregateGroup(rows.indices, rows)))
      ParallelScan.prof(s"[ROWAGG global] child=${ParallelScan.ms(t0, t1)}ms(${rows.size}) agg=${ParallelScan.ms(t1, System.nanoTime())}ms")
      r
    else
      val groups =
        scala.collection.mutable.LinkedHashMap[List[AnyRef], scala.collection.mutable.ArrayBuffer[Int]]()
      var i = 0
      while i < rows.size do
        val key = groupCols.map(c => normalizeKey(rows(i)(c))).toList
        groups.getOrElseUpdate(key, scala.collection.mutable.ArrayBuffer[Int]()) += i
        i += 1
      val out = groups.valuesIterator.map { idxs =>
        groupCols.map(c => rows(idxs.head)(c)).toArray ++ aggregateGroup(idxs, rows)
      }.toVector
      ParallelScan.prof(s"[ROWAGG ${groupCols.size}k] child=${ParallelScan.ms(t0, t1)}ms(${rows.size}) group+agg=${ParallelScan.ms(t1, System.nanoTime())}ms groups=${out.size}")
      new TypedResult(outputNames, out)

  /** The aggregate input value for row `i`: the input expression evaluated against the child's output
    * (so `SUM(extendedprice * (1 - discount))` works), or `null` for COUNT(*). */
  private def inputAt(a: Int, i: Int, rows: Vector[Array[AnyRef]]): AnyRef =
    aggInputs(a) match
      case Some(e) => RowEval.eval(e, rows(i), childNames)
      case None    => null

  private def aggregateGroup(idxs: scala.collection.IndexedSeq[Int], rows: Vector[Array[AnyRef]]): Array[AnyRef] =
    aggKinds.indices.map { a =>
      (aggKinds(a) match
        case AggKind.COUNT =>
          val c = if aggInputs(a).isEmpty then idxs.size else idxs.count(i => inputAt(a, i, rows) != null)
          java.lang.Long.valueOf(c.toLong)
        case AggKind.SUM => sumBoxed(idxs.iterator.map(i => inputAt(a, i, rows)))
        case AggKind.AVG =>
          val vals = idxs.iterator.map(i => inputAt(a, i, rows)).filter(_ != null).toVector
          if vals.isEmpty then null
          else
            java.lang.Double.valueOf(vals.map(_.asInstanceOf[java.lang.Number].doubleValue).sum / vals.size)
        case AggKind.MIN => extreme(idxs, a, rows, min = true)
        case AggKind.MAX => extreme(idxs, a, rows, min = false)
      ): AnyRef
    }.toArray

  private def normalizeKey(v: AnyRef): AnyRef =
    v match
      case d: java.math.BigDecimal => d.stripTrailingZeros
      case other                   => other

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

  private def extreme(idxs: scala.collection.IndexedSeq[Int], a: Int, rows: Vector[Array[AnyRef]], min: Boolean): AnyRef =
    var best: AnyRef = null
    idxs.foreach { i =>
      val v = inputAt(a, i, rows)
      if v != null then
        if best == null then best = v
        else
          val c = (v, best) match
            case (p: java.math.BigDecimal, q: java.math.BigDecimal) => p.compareTo(q)
            case (p: java.lang.Number, q: java.lang.Number)         => java.lang.Double.compare(p.doubleValue, q.doubleValue)
            case (p: String, q: String)                             => p.compareTo(q)
            case _                                                  => v.toString.compareTo(best.toString)
          if (min && c < 0) || (!min && c > 0) then best = v
    }
    best

/** Typed result rows (boxed Long/Double/String/BigDecimal/null), with the result-level transforms and
  * a normalized `rows` view for comparison (numbers → Double, NULL → null) — the cross-backend harness
  * yardstick. */
final class TypedResult(val names: Vector[String], val boxedRows: Vector[Array[AnyRef]]):

  def rowCount: Int = boxedRows.size

  def rows: Vector[Vector[Any]] = boxedRows.map(_.map(TypedResult.normalize).toVector)

  def take(k: Int): TypedResult = new TypedResult(names, boxedRows.take(math.max(0, k)))

  // Rows are Array[AnyRef] (reference equality), so dedup by a value-equality key (the row wrapped as
  // an immutable ArraySeq), preserving first-seen order.
  def distinct: TypedResult =
    val seen = scala.collection.mutable.HashSet[scala.collection.immutable.ArraySeq[AnyRef]]()
    val out = scala.collection.mutable.ArrayBuffer[Array[AnyRef]]()
    boxedRows.foreach { row =>
      if seen.add(scala.collection.immutable.ArraySeq.unsafeWrapArray(row)) then out += row
    }
    new TypedResult(names, out.toVector)

  def sortedBy(keys: Vector[SortKey]): TypedResult =
    new TypedResult(names, boxedRows.sortWith((a, b) => TypedResult.lessThan(a, b, keys)))

object TypedResult:

  private def normalize(v: AnyRef): Any =
    v match
      case null                => null
      case x: java.lang.Number => x.doubleValue
      case other               => other.toString

  private[backend] def lessThan(a: Array[AnyRef], b: Array[AnyRef], keys: Vector[SortKey]): Boolean =
    var i = 0
    while i < keys.size do
      val c = compareKey(a, b, keys(i))
      if c != 0 then return c < 0
      i += 1
    false

  private[backend] def compareKey(a: Array[AnyRef], b: Array[AnyRef], key: SortKey): Int =
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
