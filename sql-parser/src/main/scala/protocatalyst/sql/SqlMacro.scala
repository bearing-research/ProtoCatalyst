package protocatalyst.sql

import scala.quoted._

import protocatalyst.artifact._
import protocatalyst.encoder._
import protocatalyst.schema._
import protocatalyst.sql.ast._
import protocatalyst.sql.parser.SqlParser
import protocatalyst.sql.transform.AstToProtoTransform

/** Compile-time SQL parsing macro. */
object SqlMacro:

  /** Compile a SQL string at compile time. Returns Either for error handling. */
  inline def compileSQL[A](inline sql: String)(using
      enc: ProtoEncoder[A]
  ): Either[String, CompiledArtifact] =
    ${ compileSQLImpl[A]('sql, 'enc) }

  def compileSQLImpl[A: Type](
      sqlExpr: Expr[String],
      encExpr: Expr[ProtoEncoder[A]]
  )(using Quotes): Expr[Either[String, CompiledArtifact]] =
    import quotes.reflect.*

    // Extract the SQL string literal at compile time
    val sql = sqlExpr.valueOrAbort

    // Parse SQL at compile time
    SqlParser.parse(sql) match
      case Left(parseError) =>
        report.errorAndAbort(s"SQL parse error: $parseError")

      case Right(stmt) =>
        // Extract table name from statement
        val tableName = extractTableNameFromStmt(stmt)

        // Generate code that will run at runtime to:
        // 1. Get schema from encoder
        // 2. Validate columns against schema
        // 3. Transform AST to Proto plan
        // 4. Build CompiledArtifact

        '{
          val encoder = $encExpr
          val schema = encoder.schema
          val table = ${ Expr(tableName) }

          // Validate columns
          val errors = SqlMacro.validateColumnsStmt(${ sqlStmtToExpr(stmt) }, schema)
          if errors.nonEmpty then Left(s"Schema validation failed: ${errors.mkString("; ")}")
          else
            // Transform to Proto
            AstToProtoTransform.transformStmt(${ sqlStmtToExpr(stmt) }, schema, table) match
              case Right(plan) =>
                val outputSchema = SqlMacro.deriveOutputSchemaStmt(${ sqlStmtToExpr(stmt) }, schema)
                val contract = SchemaContract(
                  table,
                  schema.fields.zipWithIndex.map { (f, i) =>
                    FieldContract(f.name, f.dataType, f.nullable, i)
                  },
                  schema.fingerprint
                )
                val artifact = CompiledArtifact(
                  formatVersion = ArtifactVersion.current,
                  protocatalystVersion = "0.1.0-SNAPSHOT",
                  compiledAt = System.currentTimeMillis(),
                  contentHash = plan.hashCode().toLong,
                  schemaContracts = Vector(contract),
                  plan = plan,
                  outputSchema = outputSchema,
                  sourceInfo = Some(SourceInfo("sql", 0, Some(${ Expr(sql) })))
                )
                Right(artifact)
              case Left(err) =>
                Left(s"Transform failed: ${err.message}")
        }

  /** Extract the primary table name from a statement. */
  private def extractTableNameFromStmt(stmt: SqlStatement): String =
    stmt match
      case SqlStatement.SelectStatement(_, _, _, from, _, _, _, _, _) =>
        extractTableName(from)
      case SqlStatement.CompoundStatement(left, _, _) =>
        extractTableNameFromStmt(left)
      case SqlStatement.WithStatement(_, _, query) =>
        extractTableNameFromStmt(query)

  /** Extract the primary table name from a FROM clause. */
  private def extractTableName(from: FromClause): String =
    from match
      case FromClause.Table(ref)                => ref.name
      case FromClause.Join(left, _, _, _)       => extractTableName(left)
      case FromClause.Subquery(_, alias)        => alias
      case FromClause.Lateral(_, alias)         => alias
      case FromClause.Pivot(source, _, alias)   => alias.getOrElse(extractTableName(source))
      case FromClause.Unpivot(source, _, alias) => alias.getOrElse(extractTableName(source))
      case FromClause.LateralView(source, spec) => spec.tableAlias
      case FromClause.Values(_, alias, _)       => alias

  /** Convert SqlStatement to Expr for runtime use. */
  private def sqlStmtToExpr(stmt: SqlStatement)(using Quotes): Expr[SqlStatement] =
    stmt match
      case s: SqlStatement.SelectStatement                 => stmtToExpr(s)
      case SqlStatement.CompoundStatement(left, op, right) =>
        val leftExpr = sqlStmtToExpr(left)
        val rightExpr = sqlStmtToExpr(right)
        val opExpr = op match
          case SetOperation.Union(all)     => '{ SetOperation.Union(${ Expr(all) }) }
          case SetOperation.Intersect(all) => '{ SetOperation.Intersect(${ Expr(all) }) }
          case SetOperation.Except(all)    => '{ SetOperation.Except(${ Expr(all) }) }
        '{ SqlStatement.CompoundStatement($leftExpr, $opExpr, $rightExpr) }
      case SqlStatement.WithStatement(ctes, recursive, query) =>
        val ctesExpr = Expr.ofSeq(ctes.map(cteDefinitionToExpr))
        val queryExpr = sqlStmtToExpr(query)
        '{ SqlStatement.WithStatement($ctesExpr.toVector, ${ Expr(recursive) }, $queryExpr) }

  /** Convert CteDefinition to Expr. */
  private def cteDefinitionToExpr(cte: CteDefinition)(using Quotes): Expr[CteDefinition] =
    val columnAliasesExpr = cte.columnAliases match
      case Some(aliases) =>
        val aliasesSeq = Expr.ofSeq(aliases.map(Expr(_)))
        '{ Some($aliasesSeq.toVector) }
      case None => '{ None }
    val queryExpr = sqlStmtToExpr(cte.query)
    '{ CteDefinition(${ Expr(cte.name) }, $columnAliasesExpr, $queryExpr) }

  /** Convert statement to Expr for runtime use. */
  private def stmtToExpr(stmt: SqlStatement.SelectStatement)(using
      Quotes
  ): Expr[SqlStatement.SelectStatement] =
    val hintsExpr = Expr.ofSeq(stmt.hints.map(queryHintToExpr))
    val projectionsExpr = Expr.ofSeq(stmt.projections.map(projectionToExpr))
    val fromExpr = fromClauseToExpr(stmt.from)
    val whereExpr = stmt.where match
      case Some(w) => '{ Some(${ sqlExprToExpr(w) }) }
      case None    => '{ None }
    val groupByExpr = stmt.groupBy match
      case Some(gb) => '{ Some(${ groupByClauseToExpr(gb) }) }
      case None     => '{ None }
    val havingExpr = stmt.having match
      case Some(h) => '{ Some(${ sqlExprToExpr(h) }) }
      case None    => '{ None }
    val orderByExpr = Expr.ofSeq(stmt.orderBy.map(orderSpecToExpr))

    '{
      SqlStatement.SelectStatement(
        $hintsExpr.toVector,
        ${ Expr(stmt.distinct) },
        $projectionsExpr.toVector,
        $fromExpr,
        $whereExpr,
        $groupByExpr,
        $havingExpr,
        $orderByExpr.toVector,
        ${ Expr(stmt.limit) }
      )
    }

  /** Convert FromClause to Expr. */
  private def fromClauseToExpr(from: FromClause)(using Quotes): Expr[FromClause] =
    from match
      case FromClause.Table(ref) =>
        '{ FromClause.Table(TableRef(${ Expr(ref.name) }, ${ Expr(ref.alias) })) }
      case FromClause.Join(left, right, joinType, condition) =>
        val leftExpr = fromClauseToExpr(left)
        val rightExpr = fromClauseToExpr(right)
        val joinTypeExpr = joinType match
          case ast.JoinType.Inner      => '{ ast.JoinType.Inner }
          case ast.JoinType.LeftOuter  => '{ ast.JoinType.LeftOuter }
          case ast.JoinType.RightOuter => '{ ast.JoinType.RightOuter }
          case ast.JoinType.FullOuter  => '{ ast.JoinType.FullOuter }
          case ast.JoinType.Cross      => '{ ast.JoinType.Cross }
        val condExpr = condition match
          case Some(cond) => '{ Some(${ sqlExprToExpr(cond) }) }
          case None       => '{ None }
        '{ FromClause.Join($leftExpr, $rightExpr, $joinTypeExpr, $condExpr) }
      case FromClause.Subquery(stmt, alias) =>
        '{ FromClause.Subquery(${ stmtToExpr(stmt) }, ${ Expr(alias) }) }
      case FromClause.Lateral(stmt, alias) =>
        '{ FromClause.Lateral(${ stmtToExpr(stmt) }, ${ Expr(alias) }) }
      case FromClause.Pivot(source, spec, alias) =>
        val sourceExpr = fromClauseToExpr(source)
        val specExpr = pivotSpecToExpr(spec)
        val aliasExpr = alias match
          case Some(a) => '{ Some(${ Expr(a) }) }
          case None    => '{ None }
        '{ FromClause.Pivot($sourceExpr, $specExpr, $aliasExpr) }
      case FromClause.Unpivot(source, spec, alias) =>
        val sourceExpr = fromClauseToExpr(source)
        val specExpr = unpivotSpecToExpr(spec)
        val aliasExpr = alias match
          case Some(a) => '{ Some(${ Expr(a) }) }
          case None    => '{ None }
        '{ FromClause.Unpivot($sourceExpr, $specExpr, $aliasExpr) }
      case FromClause.LateralView(source, spec) =>
        val sourceExpr = fromClauseToExpr(source)
        val specExpr = lateralViewSpecToExpr(spec)
        '{ FromClause.LateralView($sourceExpr, $specExpr) }
      case FromClause.Values(rows, alias, columnAliases) =>
        val rowsExpr =
          Expr.ofSeq(rows.map(row => Expr.ofSeq(row.map(sqlExprToExpr)).asExprOf[Seq[SqlExpr]]))
        val aliasExpr = Expr(alias)
        val columnAliasesExpr = columnAliases match
          case Some(cols) => '{ Some(${ Expr.ofSeq(cols.map(Expr(_))) }.toVector) }
          case None       => '{ None }
        '{ FromClause.Values($rowsExpr.map(_.toVector).toVector, $aliasExpr, $columnAliasesExpr) }

  private def lateralViewSpecToExpr(spec: LateralViewSpec)(using Quotes): Expr[LateralViewSpec] =
    val generatorExpr = sqlExprToExpr(spec.generator)
    val columnAliasesExpr = Expr.ofSeq(spec.columnAliases.map(Expr(_)))
    '{
      LateralViewSpec(
        ${ Expr(spec.outer) },
        $generatorExpr,
        ${ Expr(spec.tableAlias) },
        $columnAliasesExpr.toVector
      )
    }

  private def pivotSpecToExpr(spec: PivotSpec)(using Quotes): Expr[PivotSpec] =
    val aggregatesExpr = Expr.ofSeq(spec.aggregates.map(pivotAggregateToExpr))
    val pivotColumnExpr = sqlExprToExpr(spec.pivotColumn)
    val valuesExpr = Expr.ofSeq(spec.pivotValues.map(pivotValueToExpr))
    '{ PivotSpec($aggregatesExpr.toVector, $pivotColumnExpr, $valuesExpr.toVector) }

  private def pivotAggregateToExpr(agg: PivotAggregate)(using Quotes): Expr[PivotAggregate] =
    val exprExpr = sqlExprToExpr(agg.aggregate)
    val aliasExpr = agg.alias match
      case Some(a) => '{ Some(${ Expr(a) }) }
      case None    => '{ None }
    '{ PivotAggregate($exprExpr, $aliasExpr) }

  private def pivotValueToExpr(v: PivotValue)(using Quotes): Expr[PivotValue] =
    val exprExpr = sqlExprToExpr(v.value)
    val aliasExpr = v.alias match
      case Some(a) => '{ Some(${ Expr(a) }) }
      case None    => '{ None }
    '{ PivotValue($exprExpr, $aliasExpr) }

  private def unpivotSpecToExpr(spec: UnpivotSpec)(using Quotes): Expr[UnpivotSpec] =
    val columnsExpr = Expr.ofSeq(spec.columns.map(unpivotColumnToExpr))
    '{
      UnpivotSpec(
        ${ Expr(spec.valueColumn) },
        ${ Expr(spec.nameColumn) },
        $columnsExpr.toVector,
        ${ Expr(spec.includeNulls) }
      )
    }

  private def unpivotColumnToExpr(col: UnpivotColumn)(using Quotes): Expr[UnpivotColumn] =
    val exprExpr = sqlExprToExpr(col.column)
    val aliasExpr = col.alias match
      case Some(a) => '{ Some(${ Expr(a) }) }
      case None    => '{ None }
    '{ UnpivotColumn($exprExpr, $aliasExpr) }

  private def projectionToExpr(proj: Projection)(using Quotes): Expr[Projection] =
    '{ Projection(${ sqlExprToExpr(proj.expr) }, ${ Expr(proj.alias) }) }

  private def orderSpecToExpr(spec: OrderSpec)(using Quotes): Expr[OrderSpec] =
    '{ OrderSpec(${ sqlExprToExpr(spec.expr) }, ${ Expr(spec.ascending) }) }

  private def groupByClauseToExpr(gb: GroupByClause)(using Quotes): Expr[GroupByClause] =
    gb match
      case GroupByClause.Simple(exprs) =>
        val exprsExpr = Expr.ofSeq(exprs.map(sqlExprToExpr))
        '{ GroupByClause.Simple($exprsExpr.toVector) }
      case GroupByClause.GroupingSets(sets) =>
        val setsExpr = Expr.ofSeq(sets.map { s =>
          val inner = Expr.ofSeq(s.map(sqlExprToExpr))
          '{ $inner.toVector }
        })
        '{ GroupByClause.GroupingSets($setsExpr.toVector) }
      case GroupByClause.Cube(exprs) =>
        val exprsExpr = Expr.ofSeq(exprs.map(sqlExprToExpr))
        '{ GroupByClause.Cube($exprsExpr.toVector) }
      case GroupByClause.Rollup(exprs) =>
        val exprsExpr = Expr.ofSeq(exprs.map(sqlExprToExpr))
        '{ GroupByClause.Rollup($exprsExpr.toVector) }

  private def queryHintToExpr(hint: QueryHint)(using Quotes): Expr[QueryHint] =
    hint match
      case QueryHint.Broadcast(tables) =>
        val tablesExpr = Expr.ofSeq(tables.map(Expr(_)))
        '{ QueryHint.Broadcast($tablesExpr.toVector) }
      case QueryHint.Merge(tables) =>
        val tablesExpr = Expr.ofSeq(tables.map(Expr(_)))
        '{ QueryHint.Merge($tablesExpr.toVector) }
      case QueryHint.ShuffleHash(tables) =>
        val tablesExpr = Expr.ofSeq(tables.map(Expr(_)))
        '{ QueryHint.ShuffleHash($tablesExpr.toVector) }
      case QueryHint.ShuffleReplicateNL(tables) =>
        val tablesExpr = Expr.ofSeq(tables.map(Expr(_)))
        '{ QueryHint.ShuffleReplicateNL($tablesExpr.toVector) }
      case QueryHint.Coalesce(partitions) =>
        '{ QueryHint.Coalesce(${ Expr(partitions) }) }
      case QueryHint.Repartition(partitions, columns) =>
        val columnsExpr = Expr.ofSeq(columns.map(Expr(_)))
        '{ QueryHint.Repartition(${ Expr(partitions) }, $columnsExpr.toVector) }
      case QueryHint.RepartitionByRange(partitions, columns) =>
        val columnsExpr = Expr.ofSeq(columns.map(Expr(_)))
        '{ QueryHint.RepartitionByRange(${ Expr(partitions) }, $columnsExpr.toVector) }

  private def sqlExprToExpr(expr: SqlExpr)(using Quotes): Expr[SqlExpr] =
    expr match
      case SqlExpr.IntLit(v)         => '{ SqlExpr.IntLit(${ Expr(v) }) }
      case SqlExpr.DoubleLit(v)      => '{ SqlExpr.DoubleLit(${ Expr(v) }) }
      case SqlExpr.StringLit(v)      => '{ SqlExpr.StringLit(${ Expr(v) }) }
      case SqlExpr.BoolLit(v)        => '{ SqlExpr.BoolLit(${ Expr(v) }) }
      case SqlExpr.NullLit           => '{ SqlExpr.NullLit }
      case SqlExpr.ColumnRef(n, q)   => '{ SqlExpr.ColumnRef(${ Expr(n) }, ${ Expr(q) }) }
      case SqlExpr.Star(q)           => '{ SqlExpr.Star(${ Expr(q) }) }
      case SqlExpr.Compare(l, op, r) =>
        val opExpr = op match
          case CompareOp.Eq    => '{ CompareOp.Eq }
          case CompareOp.NotEq => '{ CompareOp.NotEq }
          case CompareOp.Lt    => '{ CompareOp.Lt }
          case CompareOp.LtEq  => '{ CompareOp.LtEq }
          case CompareOp.Gt    => '{ CompareOp.Gt }
          case CompareOp.GtEq  => '{ CompareOp.GtEq }
        '{ SqlExpr.Compare(${ sqlExprToExpr(l) }, $opExpr, ${ sqlExprToExpr(r) }) }
      case SqlExpr.Arithmetic(l, op, r) =>
        val opExpr = op match
          case ArithOp.Add      => '{ ArithOp.Add }
          case ArithOp.Subtract => '{ ArithOp.Subtract }
          case ArithOp.Multiply => '{ ArithOp.Multiply }
          case ArithOp.Divide   => '{ ArithOp.Divide }
        '{ SqlExpr.Arithmetic(${ sqlExprToExpr(l) }, $opExpr, ${ sqlExprToExpr(r) }) }
      case SqlExpr.And(l, r)    => '{ SqlExpr.And(${ sqlExprToExpr(l) }, ${ sqlExprToExpr(r) }) }
      case SqlExpr.Or(l, r)     => '{ SqlExpr.Or(${ sqlExprToExpr(l) }, ${ sqlExprToExpr(r) }) }
      case SqlExpr.Not(c)       => '{ SqlExpr.Not(${ sqlExprToExpr(c) }) }
      case SqlExpr.IsNull(c)    => '{ SqlExpr.IsNull(${ sqlExprToExpr(c) }) }
      case SqlExpr.IsNotNull(c) => '{ SqlExpr.IsNotNull(${ sqlExprToExpr(c) }) }
      case SqlExpr.Paren(c)     => '{ SqlExpr.Paren(${ sqlExprToExpr(c) }) }
      case SqlExpr.Between(v, l, h) =>
        '{ SqlExpr.Between(${ sqlExprToExpr(v) }, ${ sqlExprToExpr(l) }, ${ sqlExprToExpr(h) }) }
      case SqlExpr.NotBetween(v, l, h) =>
        '{ SqlExpr.NotBetween(${ sqlExprToExpr(v) }, ${ sqlExprToExpr(l) }, ${ sqlExprToExpr(h) }) }
      case SqlExpr.Like(v, p, e) =>
        val escapeExpr = e match
          case Some(esc) => '{ Some(${ sqlExprToExpr(esc) }) }
          case None      => '{ None }
        '{ SqlExpr.Like(${ sqlExprToExpr(v) }, ${ sqlExprToExpr(p) }, $escapeExpr) }
      case SqlExpr.NotLike(v, p, e) =>
        val escapeExpr = e match
          case Some(esc) => '{ Some(${ sqlExprToExpr(esc) }) }
          case None      => '{ None }
        '{ SqlExpr.NotLike(${ sqlExprToExpr(v) }, ${ sqlExprToExpr(p) }, $escapeExpr) }
      case SqlExpr.In(v, list) =>
        val listExpr = Expr.ofSeq(list.map(sqlExprToExpr))
        '{ SqlExpr.In(${ sqlExprToExpr(v) }, $listExpr.toVector) }
      case SqlExpr.NotIn(v, list) =>
        val listExpr = Expr.ofSeq(list.map(sqlExprToExpr))
        '{ SqlExpr.NotIn(${ sqlExprToExpr(v) }, $listExpr.toVector) }
      case SqlExpr.FunctionCall(name, args, distinct) =>
        val argsExpr = Expr.ofSeq(args.map(sqlExprToExpr))
        '{ SqlExpr.FunctionCall(${ Expr(name) }, $argsExpr.toVector, ${ Expr(distinct) }) }
      case SqlExpr.CaseWhen(branches, elseValue) =>
        val branchesExpr = Expr.ofSeq(branches.map { (cond, result) =>
          '{ (${ sqlExprToExpr(cond) }, ${ sqlExprToExpr(result) }) }
        })
        elseValue match
          case Some(e) => '{ SqlExpr.CaseWhen($branchesExpr.toVector, Some(${ sqlExprToExpr(e) })) }
          case None    => '{ SqlExpr.CaseWhen($branchesExpr.toVector, None) }
      case SqlExpr.Cast(expr, targetType) =>
        '{ SqlExpr.Cast(${ sqlExprToExpr(expr) }, ${ sqlTypeToExpr(targetType) }) }
      case SqlExpr.ScalarSubquery(stmt) =>
        '{ SqlExpr.ScalarSubquery(${ stmtToExpr(stmt) }) }
      case SqlExpr.Exists(stmt) =>
        '{ SqlExpr.Exists(${ stmtToExpr(stmt) }) }
      case SqlExpr.NotExists(stmt) =>
        '{ SqlExpr.NotExists(${ stmtToExpr(stmt) }) }
      case SqlExpr.InSubquery(value, stmt) =>
        '{ SqlExpr.InSubquery(${ sqlExprToExpr(value) }, ${ stmtToExpr(stmt) }) }
      case SqlExpr.NotInSubquery(value, stmt) =>
        '{ SqlExpr.NotInSubquery(${ sqlExprToExpr(value) }, ${ stmtToExpr(stmt) }) }
      case SqlExpr.WindowFunction(function, windowSpec) =>
        val partitionByExpr = Expr.ofSeq(windowSpec.partitionBy.map(sqlExprToExpr))
        val orderByExpr = Expr.ofSeq(windowSpec.orderBy.map(orderSpecToExpr))
        val frameExpr = windowSpec.frame match
          case Some(f) => '{ Some(${ windowFrameToExpr(f) }) }
          case None    => '{ None }
        '{
          SqlExpr.WindowFunction(
            ${ sqlExprToExpr(function) },
            WindowSpec($partitionByExpr.toVector, $orderByExpr.toVector, $frameExpr)
          )
        }
      case SqlExpr.Grouping(columns) =>
        val columnsExpr = Expr.ofSeq(columns.map(sqlExprToExpr))
        '{ SqlExpr.Grouping($columnsExpr.toVector) }

  private def windowFrameToExpr(frame: WindowFrame)(using Quotes): Expr[WindowFrame] =
    val frameTypeExpr = frame.frameType match
      case FrameType.Rows  => '{ FrameType.Rows }
      case FrameType.Range => '{ FrameType.Range }
    val startExpr = frameBoundToExpr(frame.start)
    val endExpr = frameBoundToExpr(frame.end)
    '{ WindowFrame($frameTypeExpr, $startExpr, $endExpr) }

  private def frameBoundToExpr(bound: FrameBound)(using Quotes): Expr[FrameBound] =
    bound match
      case FrameBound.UnboundedPreceding => '{ FrameBound.UnboundedPreceding }
      case FrameBound.UnboundedFollowing => '{ FrameBound.UnboundedFollowing }
      case FrameBound.CurrentRow         => '{ FrameBound.CurrentRow }
      case FrameBound.Preceding(n)       => '{ FrameBound.Preceding(${ Expr(n) }) }
      case FrameBound.Following(n)       => '{ FrameBound.Following(${ Expr(n) }) }

  private def sqlTypeToExpr(sqlType: SqlType)(using Quotes): Expr[SqlType] =
    sqlType match
      case SqlType.IntegerType   => '{ SqlType.IntegerType }
      case SqlType.LongType      => '{ SqlType.LongType }
      case SqlType.DoubleType    => '{ SqlType.DoubleType }
      case SqlType.StringType    => '{ SqlType.StringType }
      case SqlType.BooleanType   => '{ SqlType.BooleanType }
      case SqlType.DateType      => '{ SqlType.DateType }
      case SqlType.TimestampType => '{ SqlType.TimestampType }

  // Runtime helper methods

  /** Validate columns exist in schema for any statement type. */
  def validateColumnsStmt(stmt: SqlStatement, schema: ProtoSchema): Vector[String] =
    stmt match
      case s: SqlStatement.SelectStatement                => validateColumns(s, schema)
      case SqlStatement.CompoundStatement(left, _, right) =>
        validateColumnsStmt(left, schema) ++ validateColumnsStmt(right, schema)
      case SqlStatement.WithStatement(ctes, _, query) =>
        // Validate CTEs and main query
        ctes.flatMap(cte => validateColumnsStmt(cte.query, schema)) ++
          validateColumnsStmt(query, schema)

  /** Derive output schema for any statement type. */
  def deriveOutputSchemaStmt(stmt: SqlStatement, inputSchema: ProtoSchema): ProtoSchema =
    stmt match
      case s: SqlStatement.SelectStatement            => deriveOutputSchema(s, inputSchema)
      case SqlStatement.CompoundStatement(left, _, _) =>
        // For set operations, output schema is from the first query
        deriveOutputSchemaStmt(left, inputSchema)
      case SqlStatement.WithStatement(_, _, query) =>
        // For CTEs, output schema is from the main query
        deriveOutputSchemaStmt(query, inputSchema)

  /** Validate columns exist in schema. */
  def validateColumns(stmt: SqlStatement.SelectStatement, schema: ProtoSchema): Vector[String] =
    val fieldNames = schema.fieldNames

    def validateExpr(expr: SqlExpr): Vector[String] = expr match
      case SqlExpr.ColumnRef(name, _) if !fieldNames.contains(name) =>
        Vector(s"Unknown column '$name'. Available: ${fieldNames.mkString(", ")}")
      case SqlExpr.Compare(l, _, r)    => validateExpr(l) ++ validateExpr(r)
      case SqlExpr.Arithmetic(l, _, r) => validateExpr(l) ++ validateExpr(r)
      case SqlExpr.And(l, r)           => validateExpr(l) ++ validateExpr(r)
      case SqlExpr.Or(l, r)            => validateExpr(l) ++ validateExpr(r)
      case SqlExpr.Not(c)              => validateExpr(c)
      case SqlExpr.IsNull(c)           => validateExpr(c)
      case SqlExpr.IsNotNull(c)        => validateExpr(c)
      case SqlExpr.Paren(c)            => validateExpr(c)
      case SqlExpr.Between(v, l, h)    => validateExpr(v) ++ validateExpr(l) ++ validateExpr(h)
      case SqlExpr.NotBetween(v, l, h) => validateExpr(v) ++ validateExpr(l) ++ validateExpr(h)
      case SqlExpr.Like(v, p, e)       =>
        validateExpr(v) ++ validateExpr(p) ++ e.map(validateExpr).getOrElse(Vector.empty)
      case SqlExpr.NotLike(v, p, e) =>
        validateExpr(v) ++ validateExpr(p) ++ e.map(validateExpr).getOrElse(Vector.empty)
      case SqlExpr.In(v, list)                   => validateExpr(v) ++ list.flatMap(validateExpr)
      case SqlExpr.NotIn(v, list)                => validateExpr(v) ++ list.flatMap(validateExpr)
      case SqlExpr.FunctionCall(_, args, _)      => args.flatMap(validateExpr)
      case SqlExpr.CaseWhen(branches, elseValue) =>
        branches.flatMap((c, r) => validateExpr(c) ++ validateExpr(r)) ++
          elseValue.map(validateExpr).getOrElse(Vector.empty)
      case SqlExpr.Cast(e, _)                 => validateExpr(e)
      case SqlExpr.ScalarSubquery(stmt)       => validateSubquery(stmt)
      case SqlExpr.Exists(stmt)               => validateSubquery(stmt)
      case SqlExpr.NotExists(stmt)            => validateSubquery(stmt)
      case SqlExpr.InSubquery(value, stmt)    => validateExpr(value) ++ validateSubquery(stmt)
      case SqlExpr.NotInSubquery(value, stmt) => validateExpr(value) ++ validateSubquery(stmt)
      case SqlExpr.WindowFunction(f, spec)    =>
        validateExpr(f) ++ spec.partitionBy.flatMap(validateExpr) ++ spec.orderBy.flatMap(o =>
          validateExpr(o.expr)
        )
      case SqlExpr.Grouping(columns) => columns.flatMap(validateExpr)
      case _                         => Vector.empty

    def validateSubquery(stmt: SqlStatement.SelectStatement): Vector[String] =
      // Subqueries can reference outer scope columns, so we skip validation for now
      // A more sophisticated implementation would track outer scope
      Vector.empty

    def validateFromClause(from: FromClause): Vector[String] =
      from match
        case FromClause.Table(_)                        => Vector.empty
        case FromClause.Join(left, right, _, condition) =>
          validateFromClause(left) ++ validateFromClause(right) ++
            condition.map(validateExpr).getOrElse(Vector.empty)
        case FromClause.Subquery(stmt, _)      => validateSubquery(stmt)
        case FromClause.Lateral(stmt, _)       => validateSubquery(stmt)
        case FromClause.Pivot(source, spec, _) =>
          validateFromClause(source) ++
            spec.aggregates.flatMap(a => validateExpr(a.aggregate)) ++
            validateExpr(spec.pivotColumn) ++
            spec.pivotValues.flatMap(v => validateExpr(v.value))
        case FromClause.Unpivot(source, spec, _) =>
          validateFromClause(source) ++
            spec.columns.flatMap(c => validateExpr(c.column))
        case FromClause.LateralView(source, spec) =>
          validateFromClause(source) ++
            validateExpr(spec.generator)
        case FromClause.Values(rows, _, _) =>
          rows.flatten.flatMap(validateExpr)

    def validateGroupByClause(gb: GroupByClause): Vector[String] =
      gb match
        case GroupByClause.Simple(exprs)      => exprs.flatMap(validateExpr)
        case GroupByClause.GroupingSets(sets) => sets.flatten.flatMap(validateExpr)
        case GroupByClause.Cube(exprs)        => exprs.flatMap(validateExpr)
        case GroupByClause.Rollup(exprs)      => exprs.flatMap(validateExpr)

    val projErrors = stmt.projections.flatMap(p => validateExpr(p.expr))
    val whereErrors = stmt.where.map(validateExpr).getOrElse(Vector.empty)
    val groupByErrors = stmt.groupBy.map(validateGroupByClause).getOrElse(Vector.empty)
    val havingErrors = stmt.having.map(validateExpr).getOrElse(Vector.empty)
    val orderErrors = stmt.orderBy.flatMap(o => validateExpr(o.expr))
    val joinErrors = validateFromClause(stmt.from)

    projErrors ++ whereErrors ++ groupByErrors ++ havingErrors ++ orderErrors ++ joinErrors

  /** Derive output schema from projections. */
  def deriveOutputSchema(
      stmt: SqlStatement.SelectStatement,
      inputSchema: ProtoSchema
  ): ProtoSchema =
    import protocatalyst.types.ProtoStructField

    // Handle SELECT *
    if stmt.projections.exists(_.expr.isInstanceOf[SqlExpr.Star]) then inputSchema
    else
      val outputFields = stmt.projections.flatMap { proj =>
        proj.expr match
          case SqlExpr.ColumnRef(name, _) =>
            inputSchema(name).map { f =>
              val outputName = proj.alias.getOrElse(name)
              ProtoStructField(outputName, f.dataType, f.nullable)
            }
          case SqlExpr.Star(_) =>
            inputSchema.fields
          case _ =>
            // For complex expressions, we'd need type inference
            None
      }
      ProtoSchema(outputFields)
