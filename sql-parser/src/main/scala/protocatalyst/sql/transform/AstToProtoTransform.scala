package protocatalyst.sql.transform

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.sql.ast._
import protocatalyst.types._

/** Transforms SQL AST to ProtoLogicalPlan and ProtoExpr. */
object AstToProtoTransform:

  /** Transform any SQL statement to a ProtoLogicalPlan. */
  /** Transform a statement against a multi-table catalog (table name → schema). The single-table
    * `transformStmt(stmt, schema, tableName)` overload below delegates here with `Map(tableName ->
    * schema)`. */
  def transformStmt(
      stmt: SqlStatement,
      catalog: Map[String, ProtoSchema]
  ): Either[TransformError, ProtoLogicalPlan] =
    val defaultSchema = catalog.values.headOption.getOrElse(ProtoSchema(Vector.empty))
    transformStmt(stmt, defaultSchema, catalog.keys.headOption.getOrElse(""), catalog)

  def transformStmt(
      stmt: SqlStatement,
      schema: ProtoSchema,
      tableName: String,
      catalog: Map[String, ProtoSchema] = Map.empty
  ): Either[TransformError, ProtoLogicalPlan] =
    stmt match
      case s: SqlStatement.SelectStatement =>
        transform(s, schema, tableName, catalog)
      case SqlStatement.CompoundStatement(left, op, right) =>
        for
          leftPlan <- transformStmt(left, schema, tableName, catalog)
          rightPlan <- transformStmt(right, schema, tableName, catalog)
        yield op match
          case SetOperation.Union(all) =>
            ProtoLogicalPlan.Union(
              Vector(leftPlan, rightPlan),
              byName = false,
              allowMissingColumns = false
            )
          case SetOperation.Intersect(all) =>
            ProtoLogicalPlan.Intersect(leftPlan, rightPlan, isAll = all)
          case SetOperation.Except(all) =>
            ProtoLogicalPlan.Except(leftPlan, rightPlan, isAll = all)
      case SqlStatement.WithStatement(ctes, recursive, query) =>
        transformWithStatement(ctes, recursive, query, schema, tableName, catalog)

  /** Transform a WITH statement (CTEs) to a ProtoLogicalPlan. */
  private def transformWithStatement(
      ctes: Vector[CteDefinition],
      recursive: Boolean,
      query: SqlStatement,
      schema: ProtoSchema,
      tableName: String,
      catalog: Map[String, ProtoSchema] = Map.empty
  ): Either[TransformError, ProtoLogicalPlan] =
    // Transform each CTE definition into a plan
    val cteResults = ctes
      .foldLeft[Either[TransformError, Vector[(String, ProtoLogicalPlan)]]](Right(Vector.empty)) {
        case (Right(acc), CteDefinition(cteName, columnAliases, cteQuery)) =>
          // Transform the CTE query
          transformStmt(cteQuery, schema, tableName, catalog).map { ctePlan =>
            // Wrap with SubqueryAlias if needed and apply column aliases
            val aliasedPlan = columnAliases match
              case Some(_) =>
                // Create a Project with renamed columns if column aliases are specified
                // For now, just use SubqueryAlias (proper column renaming would need type analysis)
                ProtoLogicalPlan.SubqueryAlias(cteName, ctePlan)
              case None =>
                ProtoLogicalPlan.SubqueryAlias(cteName, ctePlan)
            acc :+ (cteName, aliasedPlan)
          }
        case (Left(err), _) => Left(err)
      }

    // Transform the main query and wrap with With
    cteResults.flatMap { cteRelations =>
      transformStmt(query, schema, tableName, catalog).map { mainPlan =>
        ProtoLogicalPlan.With(cteRelations, recursive, mainPlan)
      }
    }

  /** Transform a SELECT statement to a ProtoLogicalPlan. */
  def transform(
      stmt: SqlStatement.SelectStatement,
      schema: ProtoSchema,
      tableName: String,
      catalog: Map[String, ProtoSchema] = Map.empty
  ): Either[TransformError, ProtoLogicalPlan] =
    // Build context with each table's own schema (from the catalog; empty = single-table fallback).
    val schemas = collectTableSchemas(stmt.from, schema, catalog)
    val ctx = TransformContext(schemas, schema)

    for
      // Start with the base relation (handling joins)
      basePlan <- transformFromClause(stmt.from, schema, tableName, ctx)

      // Apply WHERE clause
      filtered <- stmt.where match
        case Some(whereExpr) =>
          transformExpr(whereExpr, ctx).map(cond => ProtoLogicalPlan.Filter(cond, basePlan))
        case None =>
          Right(basePlan)

      // Apply GROUP BY if present
      aggregated <- stmt.groupBy match
        case Some(groupByClause) =>
          transformAggregate(stmt.projections, groupByClause, filtered, ctx)
        case None if projectionsHaveAggregate(stmt.projections, ctx) =>
          // A global aggregate (e.g. `SELECT SUM(x) FROM t`): no GROUP BY, but the projections
          // contain aggregate functions. Build an Aggregate with empty grouping.
          transformGlobalAggregate(stmt.projections, filtered, ctx)
        case None =>
          Right(filtered)

      // Apply HAVING (filter after aggregation)
      havingFiltered <- stmt.having match
        case Some(havingExpr) =>
          transformExpr(havingExpr, ctx).map(cond => ProtoLogicalPlan.Filter(cond, aggregated))
        case None =>
          Right(aggregated)

      // Apply DISTINCT if needed (before projection for SQL semantics)
      distinctPlan =
        if stmt.distinct then ProtoLogicalPlan.Distinct(havingFiltered) else havingFiltered

      // Apply projection (skip if we already have an aggregate, as aggregates handle projection)
      projected <- stmt.groupBy match
        case Some(_) =>
          Right(distinctPlan) // Aggregate already handles the expressions
        case None if projectionsHaveAggregate(stmt.projections, ctx) =>
          Right(distinctPlan) // the global Aggregate already handles the expressions
        case None =>
          transformProjections(stmt.projections, ctx, distinctPlan)

      // Apply ORDER BY
      sorted <- stmt.orderBy match
        case orders if orders.nonEmpty =>
          transformOrderBy(orders, ctx).map { sortOrders =>
            ProtoLogicalPlan.Sort(sortOrders, projected)
          }
        case _ =>
          Right(projected)

      // Apply LIMIT
      limited = stmt.limit match
        case Some(n) => ProtoLogicalPlan.Limit(n.toInt, sorted)
        case None    => sorted

      // Apply hints
      hinted =
        if stmt.hints.nonEmpty then
          val planHints = stmt.hints.map(transformHint)
          ProtoLogicalPlan.ResolvedHint(planHints, limited)
        else limited
    yield hinted

  /** Collect all table schemas from the FROM clause. */
  private def collectTableSchemas(
      from: FromClause,
      defaultSchema: ProtoSchema,
      catalog: Map[String, ProtoSchema]
  ): Map[String, ProtoSchema] =
    from match
      case FromClause.Table(ref) =>
        // Each table gets its own schema from the catalog (by table name), keyed by alias-or-name.
        // Falls back to `defaultSchema` for the single-table case (empty catalog).
        val key = ref.alias.getOrElse(ref.name)
        Map(key -> catalog.getOrElse(ref.name, defaultSchema))
      case FromClause.Join(left, right, _, _) =>
        collectTableSchemas(left, defaultSchema, catalog) ++
          collectTableSchemas(right, defaultSchema, catalog)
      case FromClause.Subquery(stmt, alias) =>
        // For subqueries, derive schema from the subquery's projections
        // For now, use the default schema (proper schema inference would require type analysis)
        Map(alias -> defaultSchema)
      case FromClause.Pivot(source, _, alias) =>
        // For PIVOT, collect schemas from the source and optionally add alias
        val sourceSchemas = collectTableSchemas(source, defaultSchema, catalog)
        alias.map(a => sourceSchemas + (a -> defaultSchema)).getOrElse(sourceSchemas)
      case FromClause.Unpivot(source, _, alias) =>
        // For UNPIVOT, collect schemas from the source and optionally add alias
        val sourceSchemas = collectTableSchemas(source, defaultSchema, catalog)
        alias.map(a => sourceSchemas + (a -> defaultSchema)).getOrElse(sourceSchemas)
      case FromClause.Lateral(_, alias) =>
        // For LATERAL subqueries, use the alias as the schema key
        Map(alias -> defaultSchema)
      case FromClause.LateralView(source, _) =>
        // For LATERAL VIEW, only collect schemas from source
        // The table alias refers to the generated columns, not the source columns
        collectTableSchemas(source, defaultSchema, catalog)
      case FromClause.Values(_, alias, _) =>
        // For VALUES, use the alias as the schema key
        Map(alias -> defaultSchema)

  /** Transform the FROM clause to a plan. */
  private def transformFromClause(
      from: FromClause,
      schema: ProtoSchema,
      tableName: String,
      ctx: TransformContext
  ): Either[TransformError, ProtoLogicalPlan] =
    from match
      case FromClause.Table(ref) =>
        // Use the table's own schema (per the catalog, via ctx) — not the single default schema.
        val key = ref.alias.getOrElse(ref.name)
        Right(createRelationRef(ref.name, ref.alias, ctx.tableSchemas.getOrElse(key, schema)))
      case FromClause.Join(left, right, joinType, condition) =>
        for
          leftPlan <- transformFromClause(left, schema, tableName, ctx)
          rightPlan <- transformFromClause(right, schema, tableName, ctx)
          condExpr <- condition.map(transformExpr(_, ctx)).getOrElse(Right(None)).map {
            case e: ProtoExpr => Some(e)
            case _            => None
          }
        yield ProtoLogicalPlan.Join(leftPlan, rightPlan, toProtoJoinType(joinType), condExpr)
      case FromClause.Subquery(stmt, alias) =>
        // Transform the subquery and wrap in SubqueryAlias
        transformSubquery(stmt, ctx).map { subPlan =>
          ProtoLogicalPlan.SubqueryAlias(alias, subPlan)
        }
      case FromClause.Pivot(source, spec, alias) =>
        for
          sourcePlan <- transformFromClause(source, schema, tableName, ctx)
          pivotCol <- transformExpr(spec.pivotColumn, ctx)
          pivotVals <- transformExprList(spec.pivotValues.map(_.value), ctx)
          aggregates <- transformExprList(spec.aggregates.map(_.aggregate), ctx)
          // For grouping expressions, we'd need to derive them from the source schema minus pivot and aggregate columns
          // For now, use empty grouping (the pivot will be applied to all non-aggregated columns)
          groupingExprs = Vector.empty[ProtoExpr]
          pivotPlan = ProtoLogicalPlan.Pivot(
            groupingExprs,
            pivotCol,
            pivotVals,
            aggregates,
            sourcePlan
          )
        yield alias.map(a => ProtoLogicalPlan.SubqueryAlias(a, pivotPlan)).getOrElse(pivotPlan)
      case FromClause.Unpivot(source, spec, alias) =>
        for
          sourcePlan <- transformFromClause(source, schema, tableName, ctx)
          columns <- transformExprList(spec.columns.map(_.column), ctx)
          columnAliases = spec.columns.map(_.alias)
          unpivotPlan = ProtoLogicalPlan.Unpivot(
            spec.valueColumn,
            spec.nameColumn,
            columns.zip(columnAliases),
            spec.includeNulls,
            sourcePlan
          )
        yield alias.map(a => ProtoLogicalPlan.SubqueryAlias(a, unpivotPlan)).getOrElse(unpivotPlan)
      case FromClause.Lateral(stmt, alias) =>
        // Transform the lateral subquery and wrap in SubqueryAlias
        // Note: LATERAL alone creates a subquery alias; the actual LateralJoin is formed
        // when LATERAL appears as part of a join (e.g., FROM t1, LATERAL (...) t2)
        transformSubquery(stmt, ctx).map { subPlan =>
          ProtoLogicalPlan.SubqueryAlias(alias, subPlan)
        }
      case FromClause.LateralView(source, spec) =>
        for
          sourcePlan <- transformFromClause(source, schema, tableName, ctx)
          generator <- transformExpr(spec.generator, ctx)
          generatorExpr <- transformToGenerator(generator)
        yield ProtoLogicalPlan.Generate(generatorExpr, spec.columnAliases, spec.outer, sourcePlan)
      case FromClause.Values(rows, alias, columnAliases) =>
        // Transform each row of VALUES
        transformValuesRows(rows, ctx).map { protoRows =>
          // Build schema from column aliases or generate default names
          val columnNames = columnAliases.getOrElse(
            (1 to protoRows.headOption.map(_.size).getOrElse(0)).map(i => s"col$i").toVector
          )
          // Infer types from first row (simplified - would need proper type analysis)
          val valueSchema = ProtoSchema(
            columnNames.zipWithIndex.map { (name, idx) =>
              val dataType = protoRows.headOption.flatMap(_.lift(idx)) match
                case Some(ProtoExpr.Literal(LiteralValue.IntValue(_)))     => ProtoType.IntegerType
                case Some(ProtoExpr.Literal(LiteralValue.LongValue(_)))    => ProtoType.LongType
                case Some(ProtoExpr.Literal(LiteralValue.DoubleValue(_)))  => ProtoType.DoubleType
                case Some(ProtoExpr.Literal(LiteralValue.StringValue(_)))  => ProtoType.StringType
                case Some(ProtoExpr.Literal(LiteralValue.BooleanValue(_))) => ProtoType.BooleanType
                case _                                                     => ProtoType.StringType
              ProtoStructField(name, dataType, nullable = true)
            }
          )
          val valuesPlan = ProtoLogicalPlan.Values(protoRows, valueSchema)
          ProtoLogicalPlan.SubqueryAlias(alias, valuesPlan)
        }

  /** Transform VALUES rows to ProtoExpr vectors. */
  private def transformValuesRows(
      rows: Vector[Vector[SqlExpr]],
      ctx: TransformContext
  ): Either[TransformError, Vector[Vector[ProtoExpr]]] =
    rows.foldLeft[Either[TransformError, Vector[Vector[ProtoExpr]]]](Right(Vector.empty)) {
      case (Right(acc), row) =>
        transformExprList(row, ctx).map(acc :+ _)
      case (Left(err), _) => Left(err)
    }

  /** Convert a general expression to a generator expression. */
  private def transformToGenerator(expr: ProtoExpr): Either[TransformError, ProtoExpr] =
    import ProtoExpr.*
    expr match
      // Already a generator expression
      case _: Explode | _: PosExplode | _: Inline | _: Stack => Right(expr)
      // OpaqueCall to known generator functions
      case OpaqueCall(name, args, _, _) =>
        name.toUpperCase match
          case "EXPLODE" if args.size == 1    => Right(Explode(args.head))
          case "POSEXPLODE" if args.size == 1 => Right(PosExplode(args.head))
          case "INLINE" if args.size == 1     => Right(Inline(args.head))
          case "STACK" if args.size >= 2      => Right(Stack(args.head, args.tail))
          case _ => Left(TransformError.InvalidExpression(s"Unknown generator function: $name"))
      case other =>
        Left(
          TransformError.InvalidExpression(
            s"Expected generator function in LATERAL VIEW, got: $other"
          )
        )

  /** Convert SQL JoinType to ProtoLogicalPlan JoinType. */
  private def toProtoJoinType(jt: protocatalyst.sql.ast.JoinType): protocatalyst.plan.JoinType =
    import protocatalyst.sql.ast.{JoinType => SqlJoinType}
    import protocatalyst.plan.{JoinType => PlanJoinType}
    jt match
      case SqlJoinType.Inner      => PlanJoinType.Inner
      case SqlJoinType.LeftOuter  => PlanJoinType.LeftOuter
      case SqlJoinType.RightOuter => PlanJoinType.RightOuter
      case SqlJoinType.FullOuter  => PlanJoinType.FullOuter
      case SqlJoinType.Cross      => PlanJoinType.Cross

  private def createRelationRef(
      tableName: String,
      alias: Option[String],
      schema: ProtoSchema
  ): ProtoLogicalPlan =
    val contract = SchemaContract(
      relationName = tableName,
      requiredFields = schema.fields.map { f =>
        FieldContract(f.name, f.dataType, f.nullable)
      },
      fingerprint = schema.fingerprint
    )
    ProtoLogicalPlan.RelationRef(tableName, alias, contract)

  private def transformProjections(
      projections: Vector[Projection],
      ctx: TransformContext,
      child: ProtoLogicalPlan
  ): Either[TransformError, ProtoLogicalPlan] =
    // Check for SELECT *
    val hasStar = projections.exists {
      case Projection(SqlExpr.Star(_), _) => true
      case _                              => false
    }

    if hasStar && projections.size == 1 then
      // SELECT * - no explicit projection needed
      Right(child)
    else
      // Transform each projection, collecting errors
      val results: Vector[Either[TransformError, Vector[ProtoExpr]]] = projections.map { proj =>
        proj.expr match
          case SqlExpr.Star(qualifier) =>
            // Expand star to all columns
            Right(ctx.schema.fields.map { f =>
              ProtoExpr.ColumnRef(f.name, qualifier, f.dataType, f.nullable)
            })
          case expr =>
            transformExpr(expr, ctx).map { e =>
              proj.alias match
                case Some(name) => Vector(ProtoExpr.Alias(e, name))
                case None       => Vector(e)
            }
      }

      // Sequence the results - fail on first error
      results
        .foldLeft[Either[TransformError, Vector[ProtoExpr]]](Right(Vector.empty)) {
          case (Right(acc), Right(exprs)) => Right(acc ++ exprs)
          case (Left(err), _)             => Left(err)
          case (_, Left(err))             => Left(err)
        }
        .map(exprs => ProtoLogicalPlan.Project(exprs, child))

  private def transformOrderBy(
      orders: Vector[OrderSpec],
      ctx: TransformContext
  ): Either[TransformError, Vector[SortOrder]] =
    val transformed = orders.map { spec =>
      transformExpr(spec.expr, ctx).map { expr =>
        val direction = if spec.ascending then SortDirection.Ascending else SortDirection.Descending
        val nullOrdering =
          if spec.ascending then NullOrdering.NullsLast else NullOrdering.NullsFirst
        SortOrder(expr, direction, nullOrdering)
      }
    }

    // Sequence the results
    transformed.foldLeft[Either[TransformError, Vector[SortOrder]]](Right(Vector.empty)) {
      case (Right(acc), Right(order)) => Right(acc :+ order)
      case (Left(err), _)             => Left(err)
      case (_, Left(err))             => Left(err)
    }

  /** Transform GROUP BY to an Aggregate plan. */
  private def transformAggregate(
      projections: Vector[Projection],
      groupBy: GroupByClause,
      child: ProtoLogicalPlan,
      ctx: TransformContext
  ): Either[TransformError, ProtoLogicalPlan] =
    for
      // Transform GROUP BY expressions based on clause type
      groupingExprs <- groupBy match
        case GroupByClause.Simple(exprs) =>
          transformExprList(exprs, ctx)
        case GroupByClause.GroupingSets(sets) =>
          // For GROUPING SETS, flatten all expressions for the aggregate
          // The actual grouping sets logic would need a more complex plan
          val allExprs = sets.flatten.distinct
          transformExprList(allExprs, ctx)
        case GroupByClause.Cube(exprs) =>
          // CUBE generates all combinations - for now, just use the base expressions
          transformExprList(exprs, ctx)
        case GroupByClause.Rollup(exprs) =>
          // ROLLUP generates hierarchical combinations - for now, just use the base expressions
          transformExprList(exprs, ctx)

      // Extract aggregate expressions from projections
      aggregateExprs <- extractAggregateExprs(projections, ctx)
    yield ProtoLogicalPlan.Aggregate(groupingExprs, aggregateExprs, child)

  /** A global aggregate (aggregates in the SELECT, no GROUP BY): `Aggregate` with empty grouping. */
  private def transformGlobalAggregate(
      projections: Vector[Projection],
      child: ProtoLogicalPlan,
      ctx: TransformContext
  ): Either[TransformError, ProtoLogicalPlan] =
    extractAggregateExprs(projections, ctx).map { aggExprs =>
      ProtoLogicalPlan.Aggregate(Vector.empty[ProtoExpr], aggExprs, child)
    }

  /** True if any projection contains an aggregate function (and isn't `*`). */
  private def projectionsHaveAggregate(
      projections: Vector[Projection],
      ctx: TransformContext
  ): Boolean =
    projections.exists { proj =>
      proj.expr match
        case SqlExpr.Star(_) => false
        case expr            => extractAggregates(expr, ctx).map(_.nonEmpty).getOrElse(false)
    }

  /** Extract aggregate expressions from projections. */
  private def extractAggregateExprs(
      projections: Vector[Projection],
      ctx: TransformContext
  ): Either[TransformError, Vector[ProtoExpr]] =
    val results = projections.map { proj =>
      proj.expr match
        case SqlExpr.Star(_) =>
          // Star expands to columns, not aggregates
          Right(Vector.empty[ProtoExpr])
        case expr =>
          // `Aggregate.aggregateExprs` holds aggregate functions ONLY: both consumers add the
          // grouping keys separately (`SqlGenerator` prepends `groupingExprs`; the executor's
          // `AggregateOp` emits a group column per `groupingExpr`). A non-aggregate projection in a
          // grouped query is therefore a grouping key already present in `groupingExprs` — drop it
          // here so it isn't double-emitted (transpiler) or mistaken for an aggregate (executor).
          extractAggregates(expr, ctx).map(aggs => if aggs.nonEmpty then aggs else Vector.empty)
    }

    // Sequence the results
    results.foldLeft[Either[TransformError, Vector[ProtoExpr]]](Right(Vector.empty)) {
      case (Right(acc), Right(exprs)) => Right(acc ++ exprs)
      case (Left(err), _)             => Left(err)
      case (_, Left(err))             => Left(err)
    }

  /** Extract aggregate function calls from an expression. */
  private def extractAggregates(
      expr: SqlExpr,
      ctx: TransformContext
  ): Either[TransformError, Vector[ProtoExpr]] =
    expr match
      case SqlExpr.FunctionCall(name, args, distinct) if isAggregateFunction(name) =>
        // For aggregate functions, transform them directly without recursing into args
        transformFunctionCall(name, args, distinct, ctx).map(Vector(_))
      case SqlExpr.FunctionCall(name, args, distinct) =>
        // For non-aggregate functions, look for aggregates in args
        args.foldLeft[Either[TransformError, Vector[ProtoExpr]]](Right(Vector.empty)) {
          case (Right(acc), arg) => extractAggregates(arg, ctx).map(acc ++ _)
          case (Left(err), _)    => Left(err)
        }
      case SqlExpr.And(left, right) =>
        for
          l <- extractAggregates(left, ctx)
          r <- extractAggregates(right, ctx)
        yield l ++ r
      case SqlExpr.Or(left, right) =>
        for
          l <- extractAggregates(left, ctx)
          r <- extractAggregates(right, ctx)
        yield l ++ r
      case SqlExpr.Arithmetic(left, _, right) =>
        for
          l <- extractAggregates(left, ctx)
          r <- extractAggregates(right, ctx)
        yield l ++ r
      case SqlExpr.Compare(left, _, right) =>
        for
          l <- extractAggregates(left, ctx)
          r <- extractAggregates(right, ctx)
        yield l ++ r
      case SqlExpr.Paren(child) =>
        extractAggregates(child, ctx)
      case SqlExpr.CaseWhen(branches, elseValue) =>
        val branchAggs =
          branches.foldLeft[Either[TransformError, Vector[ProtoExpr]]](Right(Vector.empty)) {
            case (Right(acc), (cond, result)) =>
              for
                condAggs <- extractAggregates(cond, ctx)
                resultAggs <- extractAggregates(result, ctx)
              yield acc ++ condAggs ++ resultAggs
            case (Left(err), _) => Left(err)
          }
        elseValue match
          case Some(e) =>
            for
              ba <- branchAggs
              ea <- extractAggregates(e, ctx)
            yield ba ++ ea
          case None => branchAggs
      case SqlExpr.Cast(expr, _) =>
        extractAggregates(expr, ctx)
      case g: SqlExpr.Grouping =>
        // GROUPING(...) is evaluated per group (it reports whether a column was rolled up), so it
        // belongs in `aggregateExprs` alongside the aggregate functions — not treated as a plain
        // grouping/passthrough column (which would now be dropped).
        transformExpr(g, ctx).map(Vector(_))
      case _ =>
        Right(Vector.empty)

  /** Check if a function name is an aggregate function. */
  private def isAggregateFunction(name: String): Boolean =
    name match
      case "COUNT" | "SUM" | "AVG" | "MIN" | "MAX" => true
      case _                                       => false

  /** Transform a SQL expression to a ProtoExpr. */
  def transformExpr(expr: SqlExpr, ctx: TransformContext): Either[TransformError, ProtoExpr] =
    expr match
      case SqlExpr.IntLit(value) =>
        // Use IntValue if the value fits in Int range, otherwise LongValue
        if value >= Int.MinValue && value <= Int.MaxValue then Right(ProtoExpr.lit(value.toInt))
        else Right(ProtoExpr.lit(value))

      case SqlExpr.DoubleLit(value) =>
        Right(ProtoExpr.lit(value))

      case SqlExpr.StringLit(value) =>
        Right(ProtoExpr.lit(value))

      case SqlExpr.DateLit(value) =>
        scala.util
          .Try(java.time.LocalDate.parse(value).toEpochDay.toInt)
          .toEither
          .left
          .map(e => TransformError.InvalidExpression(s"invalid DATE literal '$value': ${e.getMessage}"))
          .map(days => ProtoExpr.Literal(LiteralValue.DateValue(days)))

      case SqlExpr.BoolLit(value) =>
        Right(ProtoExpr.lit(value))

      case SqlExpr.NullLit =>
        // Without type context, use a generic null
        Right(ProtoExpr.litNull(ProtoType.StringType))

      case SqlExpr.ColumnRef(name, qualifier) =>
        resolveColumn(name, qualifier, ctx)

      case SqlExpr.Star(qualifier) =>
        Left(TransformError.UnsupportedFeature("Star expression in this context"))

      case SqlExpr.Compare(left, op, right) =>
        for
          l <- transformExpr(left, ctx)
          r <- transformExpr(right, ctx)
        yield op match
          case CompareOp.Eq    => ProtoExpr.Eq(l, r)
          case CompareOp.NotEq => ProtoExpr.NotEq(l, r)
          case CompareOp.Lt    => ProtoExpr.Lt(l, r)
          case CompareOp.LtEq  => ProtoExpr.LtEq(l, r)
          case CompareOp.Gt    => ProtoExpr.Gt(l, r)
          case CompareOp.GtEq  => ProtoExpr.GtEq(l, r)

      case SqlExpr.Arithmetic(left, op, right) =>
        for
          l <- transformExpr(left, ctx)
          r <- transformExpr(right, ctx)
        yield op match
          case ArithOp.Add      => ProtoExpr.Add(l, r)
          case ArithOp.Subtract => ProtoExpr.Subtract(l, r)
          case ArithOp.Multiply => ProtoExpr.Multiply(l, r)
          case ArithOp.Divide   => ProtoExpr.Divide(l, r)

      case SqlExpr.And(left, right) =>
        for
          l <- transformExpr(left, ctx)
          r <- transformExpr(right, ctx)
        yield ProtoExpr.And(Vector(l, r))

      case SqlExpr.Or(left, right) =>
        for
          l <- transformExpr(left, ctx)
          r <- transformExpr(right, ctx)
        yield ProtoExpr.Or(Vector(l, r))

      case SqlExpr.Not(child) =>
        transformExpr(child, ctx).map(ProtoExpr.Not(_))

      case SqlExpr.IsNull(child) =>
        transformExpr(child, ctx).map(ProtoExpr.IsNull(_))

      case SqlExpr.IsNotNull(child) =>
        transformExpr(child, ctx).map(ProtoExpr.IsNotNull(_))

      case SqlExpr.Paren(child) =>
        transformExpr(child, ctx)

      // BETWEEN low AND high -> (value >= low) AND (value <= high)
      case SqlExpr.Between(value, low, high) =>
        for
          v <- transformExpr(value, ctx)
          l <- transformExpr(low, ctx)
          h <- transformExpr(high, ctx)
        yield ProtoExpr.And(Vector(ProtoExpr.GtEq(v, l), ProtoExpr.LtEq(v, h)))

      // NOT BETWEEN -> NOT ((value >= low) AND (value <= high))
      case SqlExpr.NotBetween(value, low, high) =>
        for
          v <- transformExpr(value, ctx)
          l <- transformExpr(low, ctx)
          h <- transformExpr(high, ctx)
        yield ProtoExpr.Not(ProtoExpr.And(Vector(ProtoExpr.GtEq(v, l), ProtoExpr.LtEq(v, h))))

      case SqlExpr.Like(value, pattern, escape) =>
        for
          v <- transformExpr(value, ctx)
          p <- transformExpr(pattern, ctx)
          e <- escape.map(transformExpr(_, ctx)).getOrElse(Right(None)).map {
            case e: ProtoExpr => Some(e)
            case _            => None
          }
        yield ProtoExpr.Like(v, p, e)

      case SqlExpr.NotLike(value, pattern, escape) =>
        for
          v <- transformExpr(value, ctx)
          p <- transformExpr(pattern, ctx)
          e <- escape.map(transformExpr(_, ctx)).getOrElse(Right(None)).map {
            case e: ProtoExpr => Some(e)
            case _            => None
          }
        yield ProtoExpr.Not(ProtoExpr.Like(v, p, e))

      case SqlExpr.In(value, list) =>
        for
          v <- transformExpr(value, ctx)
          l <- transformExprList(list, ctx)
        yield ProtoExpr.In(v, l)

      case SqlExpr.NotIn(value, list) =>
        for
          v <- transformExpr(value, ctx)
          l <- transformExprList(list, ctx)
        yield ProtoExpr.Not(ProtoExpr.In(v, l))

      case SqlExpr.FunctionCall(name, args, distinct) =>
        transformFunctionCall(name, args, distinct, ctx)

      case SqlExpr.CaseWhen(branches, elseValue) =>
        for
          transformedBranches <- transformCaseWhenBranches(branches, ctx)
          transformedElse <- elseValue match
            case Some(e) => transformExpr(e, ctx).map(Some(_))
            case None    => Right(None)
        yield ProtoExpr.CaseWhen(transformedBranches, transformedElse)

      case SqlExpr.Cast(expr, targetType) =>
        transformExpr(expr, ctx).map { transformedExpr =>
          ProtoExpr.Cast(transformedExpr, sqlTypeToProtoType(targetType))
        }

      case SqlExpr.ScalarSubquery(stmt) =>
        // For scalar subqueries, we need to transform the inner statement
        // Using the same schema as context (subqueries are correlated)
        transformSubquery(stmt, ctx).map(ProtoExpr.ScalarSubquery(_))

      case SqlExpr.Exists(stmt) =>
        transformSubquery(stmt, ctx).map(ProtoExpr.Exists(_))

      case SqlExpr.NotExists(stmt) =>
        transformSubquery(stmt, ctx).map(plan => ProtoExpr.Not(ProtoExpr.Exists(plan)))

      case SqlExpr.InSubquery(value, stmt) =>
        for
          v <- transformExpr(value, ctx)
          plan <- transformSubquery(stmt, ctx)
        yield ProtoExpr.InSubquery(v, plan)

      case SqlExpr.NotInSubquery(value, stmt) =>
        for
          v <- transformExpr(value, ctx)
          plan <- transformSubquery(stmt, ctx)
        yield ProtoExpr.Not(ProtoExpr.InSubquery(v, plan))

      case SqlExpr.WindowFunction(function, windowSpec) =>
        for
          func <- transformExpr(function, ctx)
          windowFunc <- transformWindowFunction(func)
          partitionBy <- transformExprList(windowSpec.partitionBy, ctx)
          orderBy <- transformOrderSpecs(windowSpec.orderBy, ctx)
          frame = windowSpec.frame.map(transformFrame)
        yield ProtoExpr.WindowExpr(windowFunc, partitionBy, orderBy, frame)

      case SqlExpr.Grouping(columns) =>
        transformExprList(columns, ctx).map(ProtoExpr.Grouping(_))

  /** Transform a regular expression to a window function if applicable. */
  private def transformWindowFunction(expr: ProtoExpr): Either[TransformError, ProtoExpr] =
    import ProtoExpr.*
    expr match
      // Known window functions
      case OpaqueCall("ROW_NUMBER", Vector(), _, _)        => Right(RowNumber())
      case OpaqueCall("RANK", Vector(), _, _)              => Right(Rank())
      case OpaqueCall("DENSE_RANK", Vector(), _, _)        => Right(DenseRank())
      case OpaqueCall("NTILE", Vector(n), _, _)            => Right(Ntile(n))
      case OpaqueCall("LEAD", args, _, _) if args.nonEmpty =>
        val input = args.head
        val offset = args.lift(1).getOrElse(lit(1))
        val default = args.lift(2)
        Right(Lead(input, offset, default))
      case OpaqueCall("LAG", args, _, _) if args.nonEmpty =>
        val input = args.head
        val offset = args.lift(1).getOrElse(lit(1))
        val default = args.lift(2)
        Right(Lag(input, offset, default))
      case OpaqueCall("FIRST_VALUE", Vector(input), _, _) =>
        Right(FirstValue(input, ignoreNulls = false))
      case OpaqueCall("LAST_VALUE", Vector(input), _, _) =>
        Right(LastValue(input, ignoreNulls = false))
      case OpaqueCall("NTH_VALUE", Vector(input, n), _, _) =>
        Right(NthValue(input, n))
      // Aggregates can also be used as window functions
      case _: Sum | _: Avg | _: Count | _: Min | _: Max => Right(expr)
      // Keep other expressions as-is (for aggregate-over-window)
      case _ => Right(expr)

  /** Transform order specs for window functions. */
  private def transformOrderSpecs(
      orderSpecs: Vector[OrderSpec],
      ctx: TransformContext
  ): Either[TransformError, Vector[SortOrder]] =
    orderSpecs.foldLeft[Either[TransformError, Vector[SortOrder]]](Right(Vector.empty)) {
      case (Right(acc), OrderSpec(expr, ascending)) =>
        transformExpr(expr, ctx).map { transformedExpr =>
          val direction = if ascending then SortDirection.Ascending else SortDirection.Descending
          acc :+ SortOrder(transformedExpr, direction, NullOrdering.NullsLast)
        }
      case (Left(err), _) => Left(err)
    }

  /** Transform window frame specification. */
  private def transformFrame(
      frame: protocatalyst.sql.ast.WindowFrame
  ): protocatalyst.expr.WindowFrame =
    import protocatalyst.sql.ast.{FrameType as SFT}
    import protocatalyst.expr.{FrameType as PFT}
    val frameType = frame.frameType match
      case SFT.Rows  => PFT.Rows
      case SFT.Range => PFT.Range
    val lower = transformFrameBound(frame.start)
    val upper = transformFrameBound(frame.end)
    protocatalyst.expr.WindowFrame(frameType, lower, upper)

  private def transformFrameBound(
      bound: protocatalyst.sql.ast.FrameBound
  ): protocatalyst.expr.FrameBound =
    import protocatalyst.sql.ast.{FrameBound as SFB}
    import protocatalyst.expr.{FrameBound as PFB}
    bound match
      case SFB.UnboundedPreceding => PFB.UnboundedPreceding
      case SFB.UnboundedFollowing => PFB.UnboundedFollowing
      case SFB.CurrentRow         => PFB.CurrentRow
      case SFB.Preceding(n)       => PFB.Preceding(n)
      case SFB.Following(n)       => PFB.Following(n)

  /** Transform a subquery statement to a plan. */
  private def transformSubquery(
      stmt: SqlStatement.SelectStatement,
      ctx: TransformContext
  ): Either[TransformError, ProtoLogicalPlan] =
    // For subqueries, we use the same context (for correlated subqueries)
    // The subquery's own tables will be added when transforming its FROM clause
    val tableName = extractTableName(stmt.from)
    transform(stmt, ctx.outputSchema, tableName)

  private def extractTableName(from: FromClause): String =
    from match
      case FromClause.Table(ref)                => ref.alias.getOrElse(ref.name)
      case FromClause.Join(left, _, _, _)       => extractTableName(left)
      case FromClause.Subquery(_, alias)        => alias
      case FromClause.Lateral(_, alias)         => alias
      case FromClause.Pivot(source, _, alias)   => alias.getOrElse(extractTableName(source))
      case FromClause.Unpivot(source, _, alias) => alias.getOrElse(extractTableName(source))
      case FromClause.LateralView(source, spec) => spec.tableAlias
      case FromClause.Values(_, alias, _)       => alias

  private def transformCaseWhenBranches(
      branches: Vector[(SqlExpr, SqlExpr)],
      ctx: TransformContext
  ): Either[TransformError, Vector[(ProtoExpr, ProtoExpr)]] =
    branches.foldLeft[Either[TransformError, Vector[(ProtoExpr, ProtoExpr)]]](Right(Vector.empty)) {
      case (Right(acc), (condition, result)) =>
        for
          c <- transformExpr(condition, ctx)
          r <- transformExpr(result, ctx)
        yield acc :+ (c, r)
      case (Left(err), _) => Left(err)
    }

  private def sqlTypeToProtoType(sqlType: SqlType): ProtoType =
    import protocatalyst.sql.ast.SqlType as ST
    sqlType match
      case ST.IntegerType   => ProtoType.IntegerType
      case ST.LongType      => ProtoType.LongType
      case ST.DoubleType    => ProtoType.DoubleType
      case ST.StringType    => ProtoType.StringType
      case ST.BooleanType   => ProtoType.BooleanType
      case ST.DateType      => ProtoType.DateType
      case ST.TimestampType => ProtoType.TimestampType

  private def transformExprList(
      exprs: Vector[SqlExpr],
      ctx: TransformContext
  ): Either[TransformError, Vector[ProtoExpr]] =
    exprs.foldLeft[Either[TransformError, Vector[ProtoExpr]]](Right(Vector.empty)) {
      case (Right(acc), expr) =>
        transformExpr(expr, ctx).map(e => acc :+ e)
      case (Left(err), _) => Left(err)
    }

  private def transformFunctionCall(
      name: String,
      args: Vector[SqlExpr],
      distinct: Boolean,
      ctx: TransformContext
  ): Either[TransformError, ProtoExpr] =
    // Handle COUNT(*) specially - Star is valid here
    name match
      case "COUNT" =>
        val hasStar = args.exists {
          case SqlExpr.Star(_) => true
          case _               => false
        }
        if hasStar || args.isEmpty then
          // COUNT(*) or COUNT() - count all rows
          Right(ProtoExpr.Count(ProtoExpr.lit(1), distinct))
        else
          transformExprList(args, ctx).map { transformedArgs =>
            ProtoExpr.Count(transformedArgs.head, distinct)
          }
      case _ =>
        // Transform arguments normally
        transformExprList(args, ctx).flatMap { transformedArgs =>
          // Map known SQL functions to ProtoExpr
          name match
            // String functions
            case "UPPER" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Upper(transformedArgs.head))
            case "LOWER" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Lower(transformedArgs.head))
            case "CONCAT" =>
              Right(ProtoExpr.Concat(transformedArgs))
            case "SUBSTRING" | "SUBSTR" if transformedArgs.size == 3 =>
              Right(ProtoExpr.Substring(transformedArgs(0), transformedArgs(1), transformedArgs(2)))
            case "COALESCE" =>
              Right(ProtoExpr.Coalesce(transformedArgs))

            // Additional string functions
            case "TRIM" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Trim(transformedArgs.head, None, TrimType.Both))
            case "LTRIM" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Trim(transformedArgs.head, None, TrimType.Leading))
            case "RTRIM" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Trim(transformedArgs.head, None, TrimType.Trailing))
            case "LENGTH" | "CHAR_LENGTH" | "CHARACTER_LENGTH" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Length(transformedArgs.head))
            case "REPLACE" if transformedArgs.size == 3 =>
              Right(ProtoExpr.Replace(transformedArgs(0), transformedArgs(1), transformedArgs(2)))
            case "POSITION" | "LOCATE" | "INSTR" if transformedArgs.size == 2 =>
              Right(ProtoExpr.StringLocate(transformedArgs(0), transformedArgs(1), None))
            case "LPAD" if transformedArgs.size == 3 =>
              Right(ProtoExpr.Lpad(transformedArgs(0), transformedArgs(1), transformedArgs(2)))
            case "RPAD" if transformedArgs.size == 3 =>
              Right(ProtoExpr.Rpad(transformedArgs(0), transformedArgs(1), transformedArgs(2)))
            case "SPLIT" if transformedArgs.size >= 2 =>
              Right(
                ProtoExpr.StringSplit(
                  transformedArgs(0),
                  transformedArgs(1),
                  transformedArgs.lift(2)
                )
              )
            case "REVERSE" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Reverse(transformedArgs.head))
            case "REPEAT" if transformedArgs.size == 2 =>
              Right(ProtoExpr.StringRepeat(transformedArgs(0), transformedArgs(1)))
            case "CONCAT_WS" if transformedArgs.nonEmpty =>
              // CONCAT_WS(sep, s1, s2, ...) - just use Concat for now (simplified)
              Right(ProtoExpr.Concat(transformedArgs.tail))

            // Math functions
            case "ABS" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Abs(transformedArgs.head))
            case "CEIL" | "CEILING" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Ceil(transformedArgs.head))
            case "FLOOR" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Floor(transformedArgs.head))
            case "ROUND" if transformedArgs.size >= 1 =>
              val scale = transformedArgs.lift(1).getOrElse(ProtoExpr.lit(0))
              Right(ProtoExpr.Round(transformedArgs(0), scale))
            case "TRUNCATE" | "TRUNC" if transformedArgs.size == 2 =>
              Right(ProtoExpr.Truncate(transformedArgs(0), transformedArgs(1)))
            case "SQRT" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Sqrt(transformedArgs.head))
            case "CBRT" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Cbrt(transformedArgs.head))
            case "POW" | "POWER" if transformedArgs.size == 2 =>
              Right(ProtoExpr.Pow(transformedArgs(0), transformedArgs(1)))
            case "MOD" | "PMOD" if transformedArgs.size == 2 =>
              Right(ProtoExpr.Pmod(transformedArgs(0), transformedArgs(1)))
            case "SIGN" | "SIGNUM" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Sign(transformedArgs.head))
            case "LOG" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Log(transformedArgs.head, None))
            case "LOG" if transformedArgs.size == 2 =>
              Right(ProtoExpr.Log(transformedArgs(1), Some(transformedArgs(0))))
            case "LN" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Log(transformedArgs.head, None))
            case "LOG10" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Log(transformedArgs.head, Some(ProtoExpr.lit(10))))
            case "LOG2" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Log(transformedArgs.head, Some(ProtoExpr.lit(2))))
            case "EXP" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Exp(transformedArgs.head))

            // Null/conditional functions
            case "NULLIF" if transformedArgs.size == 2 =>
              Right(ProtoExpr.NullIf(transformedArgs(0), transformedArgs(1)))
            case "NVL" | "IFNULL" if transformedArgs.size == 2 =>
              Right(ProtoExpr.Coalesce(transformedArgs))
            case "IF" | "IIF" if transformedArgs.size == 3 =>
              Right(ProtoExpr.If(transformedArgs(0), transformedArgs(1), transformedArgs(2)))

            // Date/Time functions
            case "CURRENT_DATE" if transformedArgs.isEmpty =>
              Right(ProtoExpr.CurrentDate())
            case "CURRENT_TIMESTAMP" | "NOW" if transformedArgs.isEmpty =>
              Right(ProtoExpr.CurrentTimestamp())
            case "DATE_ADD" | "DATEADD" if transformedArgs.size == 2 =>
              Right(ProtoExpr.DateAdd(transformedArgs(0), transformedArgs(1)))
            case "DATE_SUB" | "DATESUB" if transformedArgs.size == 2 =>
              Right(ProtoExpr.DateSub(transformedArgs(0), transformedArgs(1)))
            case "DATE_DIFF" | "DATEDIFF" if transformedArgs.size == 2 =>
              Right(ProtoExpr.DateDiff(transformedArgs(0), transformedArgs(1)))
            case "DATE_TRUNC" | "DATETRUNC" if transformedArgs.size == 2 =>
              // DATE_TRUNC(field, timestamp) - field should be a string literal
              transformedArgs(0) match
                case ProtoExpr.Literal(LiteralValue.StringValue(field)) =>
                  parseDateTimeField(field) match
                    case Some(dtf) => Right(ProtoExpr.DateTrunc(dtf, transformedArgs(1)))
                    case None      =>
                      Left(
                        TransformError.InvalidExpression(s"Unknown date/time field: $field")
                      )
                case _ =>
                  Left(
                    TransformError.InvalidExpression(
                      "DATE_TRUNC first argument must be a string literal"
                    )
                  )
            case "TO_DATE" if transformedArgs.size >= 1 =>
              Right(ProtoExpr.ToDate(transformedArgs(0), transformedArgs.lift(1)))
            case "TO_TIMESTAMP" if transformedArgs.size >= 1 =>
              Right(ProtoExpr.ToTimestamp(transformedArgs(0), transformedArgs.lift(1)))
            case "YEAR" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Year(transformedArgs.head))
            case "MONTH" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Month(transformedArgs.head))
            case "DAY" | "DAYOFMONTH" if transformedArgs.size == 1 =>
              Right(ProtoExpr.DayOfMonth(transformedArgs.head))
            case "HOUR" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Hour(transformedArgs.head))
            case "MINUTE" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Minute(transformedArgs.head))
            case "SECOND" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Second(transformedArgs.head))
            case "EXTRACT" if transformedArgs.size == 2 =>
              // EXTRACT(field, source) - field is a string literal
              transformedArgs(0) match
                case ProtoExpr.Literal(LiteralValue.StringValue(field)) =>
                  parseDateTimeField(field) match
                    case Some(dtf) => Right(ProtoExpr.Extract(dtf, transformedArgs(1)))
                    case None      =>
                      Left(
                        TransformError.InvalidExpression(s"Unknown EXTRACT field: $field")
                      )
                case _ =>
                  Left(
                    TransformError.InvalidExpression(
                      "EXTRACT first argument must be a field name"
                    )
                  )

            // Aggregate functions
            case "SUM" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Sum(transformedArgs.head))
            case "AVG" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Avg(transformedArgs.head))
            case "MIN" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Min(transformedArgs.head))
            case "MAX" if transformedArgs.size == 1 =>
              Right(ProtoExpr.Max(transformedArgs.head))

            // Unknown function - use OpaqueCall
            case _ =>
              Right(ProtoExpr.OpaqueCall(name, transformedArgs, None, deterministic = true))
        }

  private def resolveColumn(
      name: String,
      qualifier: Option[String],
      ctx: TransformContext
  ): Either[TransformError, ProtoExpr] =
    qualifier match
      case Some(q) =>
        // Look up in specific table
        ctx.tableSchemas.get(q) match
          case Some(schema) =>
            schema.fields.find(_.name == name) match
              case Some(field) =>
                Right(ProtoExpr.ColumnRef(name, qualifier, field.dataType, field.nullable))
              case None =>
                Left(TransformError.UnknownColumn(name, qualifier))
          case None =>
            Left(TransformError.UnknownColumn(name, qualifier))
      case None =>
        // Look in all tables, fail if ambiguous
        val matches = ctx.tableSchemas.flatMap { case (tableName, schema) =>
          schema.fields.find(_.name == name).map((tableName, _))
        }.toVector
        matches match
          case Vector() =>
            Left(TransformError.UnknownColumn(name, None))
          case Vector((_, field)) =>
            Right(ProtoExpr.ColumnRef(name, None, field.dataType, field.nullable))
          case multiple =>
            Left(TransformError.AmbiguousColumn(name, multiple.map(_._1)))

  /** Parse a date/time field name to DateTimeField. */
  private def parseDateTimeField(field: String): Option[DateTimeField] =
    field.toUpperCase match
      case "YEAR"              => Some(DateTimeField.Year)
      case "MONTH"             => Some(DateTimeField.Month)
      case "DAY"               => Some(DateTimeField.Day)
      case "HOUR"              => Some(DateTimeField.Hour)
      case "MINUTE"            => Some(DateTimeField.Minute)
      case "SECOND"            => Some(DateTimeField.Second)
      case "QUARTER"           => Some(DateTimeField.Quarter)
      case "WEEK"              => Some(DateTimeField.Week)
      case "DAYOFWEEK" | "DOW" => Some(DateTimeField.DayOfWeek)
      case "DAYOFYEAR" | "DOY" => Some(DateTimeField.DayOfYear)
      case "MICROSECOND"       => Some(DateTimeField.Microsecond)
      case "MILLISECOND"       => Some(DateTimeField.Millisecond)
      case _                   => None

  /** Transform a SQL QueryHint to an opaque PlanHint. */
  private def transformHint(hint: QueryHint): PlanHint =
    hint match
      case QueryHint.Broadcast(tables) =>
        PlanHint("BROADCAST", tables.map(HintParam.StringVal(_)))
      case QueryHint.Merge(tables) =>
        PlanHint("MERGE", tables.map(HintParam.StringVal(_)))
      case QueryHint.ShuffleHash(tables) =>
        PlanHint("SHUFFLE_HASH", tables.map(HintParam.StringVal(_)))
      case QueryHint.ShuffleReplicateNL(tables) =>
        PlanHint("SHUFFLE_REPLICATE_NL", tables.map(HintParam.StringVal(_)))
      case QueryHint.Coalesce(partitions) =>
        PlanHint("COALESCE", Vector(HintParam.IntVal(partitions)))
      case QueryHint.Repartition(partitions, columns) =>
        PlanHint("REPARTITION", HintParam.IntVal(partitions) +: columns.map(HintParam.StringVal(_)))
      case QueryHint.RepartitionByRange(partitions, columns) =>
        PlanHint(
          "REPARTITION_BY_RANGE",
          HintParam.IntVal(partitions) +: columns.map(HintParam.StringVal(_))
        )

/** Context for transformation. */
case class TransformContext(
    tableSchemas: Map[String, ProtoSchema],
    outputSchema: ProtoSchema
):
  /** Get schema for expansion of SELECT * */
  def schema: ProtoSchema = outputSchema
