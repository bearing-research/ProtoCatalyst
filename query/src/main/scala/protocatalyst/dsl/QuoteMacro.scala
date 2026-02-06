package protocatalyst.dsl

import scala.quoted._

import protocatalyst.artifact._
import protocatalyst.encoder._
import protocatalyst.expr.ProtoExpr
import protocatalyst.macros.ProtoLiftables.given
import protocatalyst.optimizer.Optimizer
import protocatalyst.plan._
import protocatalyst.query._
import protocatalyst.schema._
import protocatalyst.sql.CompileTimeSchemaDerivation
import protocatalyst.types._

/** Compile-time DSL quotation macro.
  *
  * Captures DSL expressions at compile time, transforms them to ProtoLogicalPlan, optimizes, and
  * embeds the optimized plan as a bytecode constant.
  *
  * Usage:
  * {{{
  * case class User(name: String, age: Int, salary: Double) derives ProtoEncoder
  *
  * val query = quote {
  *   Table[User]("users")
  *     .filter(_.age > 18)
  *     .select(_.name)
  * }
  * }}}
  */
object QuoteMacro:

  /** Quote a DSL query expression and compile it at compile time.
    *
    * The query expression is analyzed at compile time, transformed to a ProtoLogicalPlan,
    * optimized, and embedded as a bytecode constant. No runtime query building or optimization
    * occurs.
    */
  inline def quote[A](inline q: Query[A])(using enc: ProtoEncoder[A]): CompiledQuery[A] =
    ${ quoteImpl[A]('q, 'enc) }

  /** Quote a table expression directly. */
  inline def quote[A](inline t: Table[A])(using enc: ProtoEncoder[A]): CompiledQuery[A] =
    ${ quoteTableImpl[A]('t, 'enc) }

  private def quoteImpl[A: Type](
      queryExpr: Expr[Query[A]],
      encExpr: Expr[ProtoEncoder[A]]
  )(using Quotes): Expr[CompiledQuery[A]] =
    import quotes.reflect.*

    // Derive schema at compile time
    val schemaResult = CompileTimeSchemaDerivation.deriveSchema[A]
    schemaResult match
      case Left(err) =>
        report.errorAndAbort(s"Failed to derive schema for ${Type.show[A]}: $err")

      case Right(schema) =>
        // Parse the query expression AST and extract the plan
        extractQueryPlan(queryExpr.asTerm, schema) match
          case Left(err) =>
            report.errorAndAbort(s"Failed to parse query expression: $err")

          case Right((plan, tableName)) =>
            // Optimize the plan at compile time
            val optimizedPlan = Optimizer.optimize(plan)

            // Build schema contract
            val contract = SchemaContract(
              tableName,
              schema.fields.zipWithIndex.map { case (f, i) =>
                FieldContract(f.name, f.dataType, f.nullable, i)
              },
              schema.fingerprint
            )

            // Embed the optimized plan as a compile-time constant
            '{
              val artifact = CompiledArtifact(
                formatVersion = ArtifactVersion.current,
                protocatalystVersion = "0.1.0-SNAPSHOT",
                compiledAt = System.currentTimeMillis(),
                contentHash = ${ Expr(optimizedPlan.hashCode().toLong) },
                schemaContracts = ${ Expr(Vector(contract)) },
                plan = ${ Expr(optimizedPlan) },
                outputSchema = ${ Expr(schema) },
                sourceInfo = Some(SourceInfo("dsl-quote-compile-time", 0, None))
              )
              CompiledQuery.fromArtifact(artifact, $encExpr)
            }

  private def quoteTableImpl[A: Type](
      tableExpr: Expr[Table[A]],
      encExpr: Expr[ProtoEncoder[A]]
  )(using Quotes): Expr[CompiledQuery[A]] =
    import quotes.reflect.*

    val schemaResult = CompileTimeSchemaDerivation.deriveSchema[A]
    schemaResult match
      case Left(err) =>
        report.errorAndAbort(s"Failed to derive schema for ${Type.show[A]}: $err")

      case Right(schema) =>
        extractTableName(tableExpr.asTerm) match
          case Left(err) =>
            report.errorAndAbort(s"Failed to extract table name: $err")

          case Right(tableName) =>
            val contract = SchemaContract(
              tableName,
              schema.fields.zipWithIndex.map { case (f, i) =>
                FieldContract(f.name, f.dataType, f.nullable, i)
              },
              schema.fingerprint
            )

            val plan = ProtoLogicalPlan.RelationRef(tableName, None, contract)
            val optimizedPlan = Optimizer.optimize(plan)

            '{
              val artifact = CompiledArtifact(
                formatVersion = ArtifactVersion.current,
                protocatalystVersion = "0.1.0-SNAPSHOT",
                compiledAt = System.currentTimeMillis(),
                contentHash = ${ Expr(optimizedPlan.hashCode().toLong) },
                schemaContracts = ${ Expr(Vector(contract)) },
                plan = ${ Expr(optimizedPlan) },
                outputSchema = ${ Expr(schema) },
                sourceInfo = Some(SourceInfo("dsl-quote-compile-time", 0, None))
              )
              CompiledQuery.fromArtifact(artifact, $encExpr)
            }

  // ============================================================================
  // AST Extraction Helpers
  // ============================================================================

  // Result type: (plan, tableName, inputSchema)
  // The inputSchema is derived from the Table and propagated up
  private type PlanResult = (ProtoLogicalPlan, String, ProtoSchema)

  private def extractQueryPlan(using
      q: Quotes
  )(term: q.reflect.Term, fallbackSchema: ProtoSchema): Either[String, (ProtoLogicalPlan, String)] =
    extractQueryPlanRec(term, fallbackSchema).map { case (plan, name, _) => (plan, name) }

  private def extractQueryPlanRec(using
      Quotes
  )(
      term: quotes.reflect.Term,
      fallbackSchema: ProtoSchema
  ): Either[String, PlanResult] =
    matchPlan(term, fallbackSchema)

  private def matchPlan(using
      Quotes
  )(
      term: quotes.reflect.Term,
      fallbackSchema: ProtoSchema
  ): Either[String, PlanResult] =
    import quotes.reflect.*

    term match

      // Handle Inlined wrapper
      case Inlined(_, _, inner) =>
        extractQueryPlanRec(inner, fallbackSchema)

      // Handle Block wrapper
      case Block(_, expr) =>
        extractQueryPlanRec(expr, fallbackSchema)

      // Handle Typed wrapper
      case Typed(expr, _) =>
        extractQueryPlanRec(expr, fallbackSchema)

      // Table[A]("tableName").toQuery
      case Apply(Select(tableExpr, "toQuery"), Nil) =>
        extractTablePlan(tableExpr, fallbackSchema)

      // query.filter(predicate) - TypeApply version
      case Apply(Apply(TypeApply(Select(child, "filter"), _), List(predicate)), _) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          filterExpr <- extractPredicateExpr(predicate, childSchema)
        yield (ProtoLogicalPlan.Filter(filterExpr, childPlan), tableName, childSchema)

      // query.filter(predicate) - non-TypeApply version
      case Apply(Select(child, "filter"), List(predicate)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          filterExpr <- extractPredicateExpr(predicate, childSchema)
        yield (ProtoLogicalPlan.Filter(filterExpr, childPlan), tableName, childSchema)

      // query.where(predicate) - alias for filter
      case Apply(Apply(TypeApply(Select(child, "where"), _), List(predicate)), _) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          filterExpr <- extractPredicateExpr(predicate, childSchema)
        yield (ProtoLogicalPlan.Filter(filterExpr, childPlan), tableName, childSchema)

      case Apply(Select(child, "where"), List(predicate)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          filterExpr <- extractPredicateExpr(predicate, childSchema)
        yield (ProtoLogicalPlan.Filter(filterExpr, childPlan), tableName, childSchema)

      // query.select(exprs*) - TypeApply version with implicits (single or multiple)
      case Apply(Apply(TypeApply(Select(child, "select"), _), selectors), _) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          projExprs <- extractProjectExprs(selectors, childSchema)
        yield (ProtoLogicalPlan.Project(projExprs, childPlan), tableName, childSchema)

      // query.select(exprs*) - non-TypeApply version (single or multiple)
      case Apply(Select(child, "select"), selectors) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          projExprs <- extractProjectExprs(selectors, childSchema)
        yield (ProtoLogicalPlan.Project(projExprs, childPlan), tableName, childSchema)

      // query.select[B1, B2](e1, e2)(enc1, enc2) - tuple overload with 2 implicits blocks
      case Apply(Apply(Apply(Select(child, "select"), selectors), _), _) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          projExprs <- extractProjectExprs(selectors, childSchema)
        yield (ProtoLogicalPlan.Project(projExprs, childPlan), tableName, childSchema)

      // query.limit(n)
      case Apply(Select(child, "limit"), List(limitExpr)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          n <- extractIntLiteral(limitExpr)
        yield (ProtoLogicalPlan.Limit(n, childPlan), tableName, childSchema)

      // query.distinct
      case Select(child, "distinct") =>
        for (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
        yield (ProtoLogicalPlan.Distinct(childPlan), tableName, childSchema)

      // query.orderBy(_.field.asc, _.field2.desc)
      case Apply(Select(child, "orderBy"), sortExprs) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          sortOrders <- extractSortOrders(sortExprs, childSchema)
        yield (ProtoLogicalPlan.Sort(sortOrders, global = true, childPlan), tableName, childSchema)

      // query.as("alias")
      case Apply(Select(child, "as"), List(aliasExpr)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          alias <- extractStringLiteral(aliasExpr)
        yield (ProtoLogicalPlan.SubqueryAlias(alias, childPlan), tableName, childSchema)

      // query.union(otherQuery)
      case Apply(Select(child, "union"), List(otherExpr)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
        yield (
          ProtoLogicalPlan.Union(
            Vector(childPlan, otherPlan),
            byName = false,
            allowMissingColumns = false
          ),
          tableName,
          childSchema
        )

      // query.intersect(otherQuery)
      case Apply(Select(child, "intersect"), List(otherExpr)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
        yield (
          ProtoLogicalPlan.Intersect(childPlan, otherPlan, isAll = false),
          tableName,
          childSchema
        )

      // query.intersectAll(otherQuery)
      case Apply(Select(child, "intersectAll"), List(otherExpr)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
        yield (
          ProtoLogicalPlan.Intersect(childPlan, otherPlan, isAll = true),
          tableName,
          childSchema
        )

      // query.except(otherQuery)
      case Apply(Select(child, "except"), List(otherExpr)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
        yield (ProtoLogicalPlan.Except(childPlan, otherPlan, isAll = false), tableName, childSchema)

      // query.exceptAll(otherQuery)
      case Apply(Select(child, "exceptAll"), List(otherExpr)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
        yield (ProtoLogicalPlan.Except(childPlan, otherPlan, isAll = true), tableName, childSchema)

      // query.crossJoin(otherQuery)(using encoder)
      case Apply(Apply(TypeApply(Select(child, "crossJoin"), _), List(otherExpr)), _) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
        yield (
          ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.Cross, None),
          tableName,
          childSchema
        )

      // query.crossJoin(otherQuery) - without explicit TypeApply
      case Apply(Select(child, "crossJoin"), List(otherExpr)) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
        yield (
          ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.Cross, None),
          tableName,
          childSchema
        )

      // query.join[B](otherQuery).on(condition)(implicits) - inner join with condition
      // Pattern: Apply(Apply(Select(Apply(TypeApply(Select(child, "join"), _), List(other)), "on"), List(cond)), List(implicits))
      case Apply(
            Apply(
              Select(Apply(TypeApply(Select(child, "join"), _), List(otherExpr)), "on"),
              List(condExpr)
            ),
            _
          ) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
          leftSchema <- extractSchemaFromQueryExpr(child)
          rightSchema <- extractSchemaFromQueryExpr(otherExpr)
          condition <- extractJoinCondition(condExpr, leftSchema, rightSchema)
        yield (
          ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.Inner, Some(condition)),
          tableName,
          childSchema
        )

      // query.leftJoin[B](otherQuery).on(condition)(implicits)
      case Apply(
            Apply(
              Select(Apply(TypeApply(Select(child, "leftJoin"), _), List(otherExpr)), "on"),
              List(condExpr)
            ),
            _
          ) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
          leftSchema <- extractSchemaFromQueryExpr(child)
          rightSchema <- extractSchemaFromQueryExpr(otherExpr)
          condition <- extractJoinCondition(condExpr, leftSchema, rightSchema)
        yield (
          ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.LeftOuter, Some(condition)),
          tableName,
          childSchema
        )

      // query.rightJoin[B](otherQuery).on(condition)(implicits)
      case Apply(
            Apply(
              Select(Apply(TypeApply(Select(child, "rightJoin"), _), List(otherExpr)), "on"),
              List(condExpr)
            ),
            _
          ) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(child, fallbackSchema)
          (otherPlan, _, _) <- extractQueryPlanRec(otherExpr, fallbackSchema)
          leftSchema <- extractSchemaFromQueryExpr(child)
          rightSchema <- extractSchemaFromQueryExpr(otherExpr)
          condition <- extractJoinCondition(condExpr, leftSchema, rightSchema)
        yield (
          ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.RightOuter, Some(condition)),
          tableName,
          childSchema
        )

      // groupedQuery.agg[B](aggExprs*)(enc) - aggregate with implicits
      // Pattern: Apply(Apply(TypeApply(Select(groupedQuery, "agg"), _), List(aggExprs...)), List(enc))
      case Apply(Apply(TypeApply(Select(groupedQueryExpr, "agg"), _), aggExprs), _) =>
        extractGroupedQueryPlan(groupedQueryExpr, fallbackSchema, aggExprs)

      // groupedQuery.agg(aggExprs*)(enc) - aggregate without TypeApply
      case Apply(Apply(Select(groupedQueryExpr, "agg"), aggExprs), _) =>
        extractGroupedQueryPlan(groupedQueryExpr, fallbackSchema, aggExprs)

      // Table.apply[A]("tableName") - direct table creation
      case Apply(Apply(TypeApply(Select(_, "apply"), typeArgs), List(tableNameExpr)), _) =>
        for
          tableName <- extractStringLiteral(tableNameExpr)
          tableSchema <- deriveSchemaFromTypeTree(typeArgs.headOption, fallbackSchema)
        yield
          val contract = buildSchemaContract(tableName, tableSchema)
          (ProtoLogicalPlan.RelationRef(tableName, None, contract), tableName, tableSchema)

      // Table[A]("tableName") - alternative pattern
      case Apply(TypeApply(Apply(_, List(tableNameExpr)), typeArgs), _) =>
        for
          tableName <- extractStringLiteral(tableNameExpr)
          tableSchema <- deriveSchemaFromTypeTree(typeArgs.headOption, fallbackSchema)
        yield
          val contract = buildSchemaContract(tableName, tableSchema)
          (ProtoLogicalPlan.RelationRef(tableName, None, contract), tableName, tableSchema)

      // table.toQuery
      case Select(tableExpr, "toQuery") =>
        extractTablePlan(tableExpr, fallbackSchema)

      // Direct Table expression
      case _ if isTableExpression(term) =>
        extractTablePlan(term, fallbackSchema)

      case other =>
        Left(s"Unsupported query expression: ${other.show}")

  private def extractTablePlan(using
      q: Quotes
  )(term: q.reflect.Term, fallbackSchema: ProtoSchema): Either[String, PlanResult] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractTablePlan(inner, fallbackSchema)

      case Block(_, expr) =>
        extractTablePlan(expr, fallbackSchema)

      case Typed(expr, _) =>
        extractTablePlan(expr, fallbackSchema)

      // Table.apply[A]("tableName")(using enc)
      case Apply(Apply(TypeApply(Select(_, "apply"), typeArgs), List(tableNameExpr)), _) =>
        for
          tableName <- extractStringLiteral(tableNameExpr)
          tableSchema <- deriveSchemaFromTypeTree(typeArgs.headOption, fallbackSchema)
        yield
          val contract = buildSchemaContract(tableName, tableSchema)
          (ProtoLogicalPlan.RelationRef(tableName, None, contract), tableName, tableSchema)

      // Table[A]("tableName")
      case Apply(TypeApply(Apply(Ident("Table"), List(tableNameExpr)), typeArgs), _) =>
        for
          tableName <- extractStringLiteral(tableNameExpr)
          tableSchema <- deriveSchemaFromTypeTree(typeArgs.headOption, fallbackSchema)
        yield
          val contract = buildSchemaContract(tableName, tableSchema)
          (ProtoLogicalPlan.RelationRef(tableName, None, contract), tableName, tableSchema)

      case other =>
        Left(s"Cannot extract table from: ${other.show}")

  /** Derive schema from a type tree if available, otherwise use fallback */
  private def deriveSchemaFromTypeTree(using
      q: Quotes
  )(typeTreeOpt: Option[q.reflect.Tree], fallback: ProtoSchema): Either[String, ProtoSchema] =
    import q.reflect.*

    typeTreeOpt match
      case Some(typeTree: TypeTree) =>
        deriveSchemaFromTypeRepr(typeTree.tpe)
      case _ =>
        Right(fallback)

  private def isTableExpression(using q: Quotes)(term: q.reflect.Term): Boolean =
    term.tpe.typeSymbol.name == "Table"

  private def extractTableName(using q: Quotes)(term: q.reflect.Term): Either[String, String] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractTableName(inner)

      case Block(_, expr) =>
        extractTableName(expr)

      case Typed(expr, _) =>
        extractTableName(expr)

      case Apply(Apply(TypeApply(Select(_, "apply"), _), List(tableNameExpr)), _) =>
        extractStringLiteral(tableNameExpr)

      case other =>
        Left(s"Cannot extract table name from: ${other.show}")

  // ============================================================================
  // Sort Order Extraction
  // ============================================================================

  private def extractSortOrders(using
      q: Quotes
  )(terms: List[q.reflect.Term], schema: ProtoSchema): Either[String, Vector[SortOrder]] =
    val results = terms.map(extractSortOrder(_, schema))
    val errors = results.collect { case Left(err) => err }
    if errors.nonEmpty then Left(errors.mkString("; "))
    else Right(results.collect { case Right(so) => so }.toVector)

  private def extractSortOrder(using
      q: Quotes
  )(term: q.reflect.Term, schema: ProtoSchema): Either[String, SortOrder] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractSortOrder(inner, schema)

      case Block(_, expr) =>
        extractSortOrder(expr, schema)

      case Typed(expr, _) =>
        extractSortOrder(expr, schema)

      // _.field.asc / _.field.desc
      case Select(exprTerm, sortMethod)
          if Set("asc", "desc", "ascNullsFirst", "descNullsLast").contains(sortMethod) =>
        extractValueExpr(exprTerm, schema).map { expr =>
          val (direction, nullOrdering) = sortMethod match
            case "asc"           => (SortDirection.Ascending, NullOrdering.NullsLast)
            case "desc"          => (SortDirection.Descending, NullOrdering.NullsFirst)
            case "ascNullsFirst" => (SortDirection.Ascending, NullOrdering.NullsFirst)
            case "descNullsLast" => (SortDirection.Descending, NullOrdering.NullsLast)
            case _               => (SortDirection.Ascending, NullOrdering.NullsLast)
          SortOrder(expr, direction, nullOrdering)
        }

      // Default: ascending sort without explicit direction
      case other =>
        extractValueExpr(other, schema).map { expr =>
          SortOrder(expr, SortDirection.Ascending, NullOrdering.NullsLast)
        }

  // ============================================================================
  // Projection Extraction
  // ============================================================================

  private def extractProjectionExpr(using
      q: Quotes
  )(term: q.reflect.Term, schema: ProtoSchema): Either[String, ProtoExpr] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractProjectionExpr(inner, schema)

      case Block(List(DefDef(_, _, _, Some(body))), _) =>
        // Lambda expression: f => body
        extractValueExpr(body, schema)

      case Block(_, expr) =>
        extractProjectionExpr(expr, schema)

      case Typed(expr, _) =>
        extractProjectionExpr(expr, schema)

      case other =>
        // Try as a value expression directly
        extractValueExpr(other, schema)

  /** Extract multiple projection expressions from select arguments */
  private def extractProjectExprs(using
      q: Quotes
  )(
      terms: List[q.reflect.Term],
      schema: ProtoSchema
  ): Either[String, Vector[ProtoExpr]] =
    import q.reflect.*

    // Handle varargs wrappers - extract elements from Repeated/SeqLiteral
    val unwrappedTerms = terms.flatMap { term =>
      term match
        case Typed(Repeated(elems, _), _)                  => elems
        case Repeated(elems, _)                            => elems
        case t if t.getClass.getSimpleName == "SeqLiteral" =>
          // SeqLiteral isn't directly matchable, use reflection
          try
            val elemsField = t.getClass.getMethod("elems")
            elemsField.invoke(t).asInstanceOf[List[Term]]
          catch case _: Exception => List(t)
        case other => List(other)
    }

    val results = unwrappedTerms.map(extractProjectionExpr(_, schema))
    val errors = results.collect { case Left(err) => err }
    if errors.nonEmpty then Left(errors.mkString("; "))
    else Right(results.collect { case Right(expr) => expr }.toVector)

  // ============================================================================
  // Predicate/Expression Extraction
  // ============================================================================

  private def extractPredicateExpr(using
      q: Quotes
  )(term: q.reflect.Term, schema: ProtoSchema): Either[String, ProtoExpr] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractPredicateExpr(inner, schema)

      case Block(List(DefDef(_, _, _, Some(body))), _) =>
        // Lambda expression: f => body
        extractPredicateExpr(body, schema)

      case Block(_, expr) =>
        extractPredicateExpr(expr, schema)

      case Typed(expr, _) =>
        extractPredicateExpr(expr, schema)

      // _.field > value (comparison with literal on right)
      case Apply(Select(leftExpr, op), List(rightExpr))
          if Set(">", ">=", "<", "<=", "===", "=!=").contains(op) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield makeComparison(op, left, right)

      // _.field > value (via extension method with TypeApply)
      case Apply(Apply(TypeApply(Select(_, op), _), List(leftExpr)), List(rightExpr))
          if Set(">", ">=", "<", "<=", "===", "=!=").contains(op) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield makeComparison(op, left, right)

      // _.field > value (via extension method without TypeApply - Expr$package pattern)
      case Apply(Apply(Select(_, op), List(leftExpr)), List(rightExpr))
          if Set(">", ">=", "<", "<=", "===", "=!=").contains(op) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield makeComparison(op, left, right)

      // expr && expr
      case Apply(Select(leftExpr, "&&"), List(rightExpr)) =>
        for
          left <- extractPredicateExpr(leftExpr, schema)
          right <- extractPredicateExpr(rightExpr, schema)
        yield ProtoExpr.And(Vector(left, right))

      // expr || expr
      case Apply(Select(leftExpr, "||"), List(rightExpr)) =>
        for
          left <- extractPredicateExpr(leftExpr, schema)
          right <- extractPredicateExpr(rightExpr, schema)
        yield ProtoExpr.Or(Vector(left, right))

      // !expr - direct method call
      case Select(inner, "unary_!") =>
        extractPredicateExpr(inner, schema).map(ProtoExpr.Not(_))

      // !expr - via extension method: Expr$package.unary_!(innerExpr)
      case Apply(fun, List(inner)) if extractMethodName(fun).contains("unary_!") =>
        extractPredicateExpr(inner, schema).map(ProtoExpr.Not(_))

      // expr.isNull - direct method call
      case Select(inner, "isNull") =>
        extractValueExpr(inner, schema).map(ProtoExpr.IsNull(_))

      // expr.isNull - via extension method: Expr$package.isNull[T](innerExpr)
      case Apply(fun, List(inner)) if extractMethodName(fun).contains("isNull") =>
        extractValueExpr(inner, schema).map(ProtoExpr.IsNull(_))

      // expr.isNotNull - direct method call
      case Select(inner, "isNotNull") =>
        extractValueExpr(inner, schema).map(ProtoExpr.IsNotNull(_))

      // expr.isNotNull - via extension method: Expr$package.isNotNull[T](innerExpr)
      case Apply(fun, List(inner)) if extractMethodName(fun).contains("isNotNull") =>
        extractValueExpr(inner, schema).map(ProtoExpr.IsNotNull(_))

      // Literal true/false
      case Literal(BooleanConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.BooleanValue(v)))

      // Extension method call: Expr$package.op(leftExpr)(rightExpr) - curried form
      case Apply(Apply(fun, List(leftExpr)), List(rightExpr)) =>
        val methodOpt = extractMethodName(fun)
        methodOpt match
          case Some(op) if Set(">", ">=", "<", "<=", "===", "=!=").contains(op) =>
            for
              left <- extractValueExpr(leftExpr, schema)
              right <- extractValueExpr(rightExpr, schema)
            yield makeComparison(op, left, right)
          case Some("&&") =>
            for
              left <- extractPredicateExpr(leftExpr, schema)
              right <- extractPredicateExpr(rightExpr, schema)
            yield ProtoExpr.And(Vector(left, right))
          case Some("||") =>
            for
              left <- extractPredicateExpr(leftExpr, schema)
              right <- extractPredicateExpr(rightExpr, schema)
            yield ProtoExpr.Or(Vector(left, right))
          case Some(op) =>
            Left(s"Unsupported operator '$op' in: ${term.show}")
          case None =>
            Left(
              s"Could not extract method name from fun[${fun.getClass.getSimpleName}]=${fun.show} in: ${term.show}"
            )

      case other =>
        Left(s"Unsupported predicate expression: ${other.show}")

  private def extractValueExpr(using
      q: Quotes
  )(term: q.reflect.Term, schema: ProtoSchema): Either[String, ProtoExpr] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractValueExpr(inner, schema)

      case Block(_, expr) =>
        extractValueExpr(expr, schema)

      case Typed(expr, _) =>
        extractValueExpr(expr, schema)

      // _.fieldName via selectDynamic
      case Apply(Select(_, "selectDynamic"), List(Literal(StringConstant(fieldName)))) =>
        schema.fields.find(_.name == fieldName) match
          case Some(field) =>
            Right(ProtoExpr.ColumnRef(fieldName, None, field.dataType, field.nullable))
          case None =>
            Left(s"Field '$fieldName' not found in schema")

      // Direct field access via Dynamic
      case Select(_, fieldName)
          if !fieldName.startsWith("$") && schema.fields.exists(_.name == fieldName) =>
        schema.fields.find(_.name == fieldName) match
          case Some(field) =>
            Right(ProtoExpr.ColumnRef(fieldName, None, field.dataType, field.nullable))
          case None =>
            Left(s"Field '$fieldName' not found")

      // Literals
      case Literal(IntConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.IntValue(v)))

      case Literal(LongConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.LongValue(v)))

      case Literal(DoubleConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.DoubleValue(v)))

      case Literal(FloatConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.FloatValue(v)))

      case Literal(StringConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.StringValue(v)))

      case Literal(BooleanConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.BooleanValue(v)))

      // Expr.lit(value)
      case Apply(Select(_, "lit"), List(valueExpr)) =>
        extractValueExpr(valueExpr, schema)

      // FieldSelector.typedColumn[A, T](fs, "fieldName")(using enc) - from transparent inline macro
      case Apply(Apply(TypeApply(Select(_, "typedColumn"), _), List(_, fieldNameExpr)), _) =>
        extractStringLiteral(fieldNameExpr).flatMap { fieldName =>
          schema.fields.find(_.name == fieldName) match
            case Some(field) =>
              Right(ProtoExpr.ColumnRef(fieldName, None, field.dataType, field.nullable))
            case None =>
              Left(s"Field '$fieldName' not found in schema")
        }

      // Arithmetic via extension method with TypeApply: Expr$package.op[T](left)(right)
      case Apply(Apply(TypeApply(Select(_, op), _), List(leftExpr)), List(rightExpr))
          if Set("+", "-", "*", "/").contains(op) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield op match
          case "+" => ProtoExpr.Add(left, right)
          case "-" => ProtoExpr.Subtract(left, right)
          case "*" => ProtoExpr.Multiply(left, right)
          case "/" => ProtoExpr.Divide(left, right)
          case _   => ProtoExpr.Add(left, right) // unreachable

      // Column.apply[A, T]("name")(using enc) - explicit column reference
      case Apply(Apply(TypeApply(Select(_, "apply"), _), List(nameExpr)), _) =>
        extractStringLiteral(nameExpr).flatMap { colName =>
          schema.fields.find(_.name == colName) match
            case Some(field) =>
              Right(ProtoExpr.ColumnRef(colName, None, field.dataType, field.nullable))
            case None =>
              Right(
                ProtoExpr.ColumnRef(colName, None, ProtoType.StringType, nullable = false)
              )
        }

      // Arithmetic: left + right
      case Apply(Select(leftExpr, "+"), List(rightExpr)) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield ProtoExpr.Add(left, right)

      // Arithmetic: left - right
      case Apply(Select(leftExpr, "-"), List(rightExpr)) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield ProtoExpr.Subtract(left, right)

      // Arithmetic: left * right
      case Apply(Select(leftExpr, "*"), List(rightExpr)) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield ProtoExpr.Multiply(left, right)

      // Arithmetic: left / right
      case Apply(Select(leftExpr, "/"), List(rightExpr)) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield ProtoExpr.Divide(left, right)

      // Unary minus: -expr
      case Select(inner, "unary_-") =>
        extractValueExpr(inner, schema).map { expr =>
          ProtoExpr.Multiply(expr, ProtoExpr.Literal(LiteralValue.IntValue(-1)))
        }

      // String operations: expr.upper, expr.lower
      case Select(inner, "upper") =>
        extractValueExpr(inner, schema).map(ProtoExpr.Upper(_))

      case Select(inner, "lower") =>
        extractValueExpr(inner, schema).map(ProtoExpr.Lower(_))

      // String operations via extension method: Expr$package.upper(innerExpr)
      case Apply(fun, List(inner)) if extractMethodName(fun).contains("upper") =>
        extractValueExpr(inner, schema).map(ProtoExpr.Upper(_))

      case Apply(fun, List(inner)) if extractMethodName(fun).contains("lower") =>
        extractValueExpr(inner, schema).map(ProtoExpr.Lower(_))

      // String concat: str1 ++ str2
      case Apply(Select(leftExpr, "++"), List(rightExpr)) =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield ProtoExpr.Concat(Vector(left, right))

      // String concat via extension method: Expr$package.++(leftExpr)(rightExpr)
      case Apply(Apply(fun, List(leftExpr)), List(rightExpr))
          if extractMethodName(fun).contains("++") =>
        for
          left <- extractValueExpr(leftExpr, schema)
          right <- extractValueExpr(rightExpr, schema)
        yield ProtoExpr.Concat(Vector(left, right))

      // Arithmetic via extension method: Expr$package.+(leftExpr)(rightExpr) - curried form
      case Apply(Apply(fun, List(leftExpr)), List(rightExpr)) =>
        extractMethodName(fun) match
          case Some("+") =>
            for
              left <- extractValueExpr(leftExpr, schema)
              right <- extractValueExpr(rightExpr, schema)
            yield ProtoExpr.Add(left, right)
          case Some("-") =>
            for
              left <- extractValueExpr(leftExpr, schema)
              right <- extractValueExpr(rightExpr, schema)
            yield ProtoExpr.Subtract(left, right)
          case Some("*") =>
            for
              left <- extractValueExpr(leftExpr, schema)
              right <- extractValueExpr(rightExpr, schema)
            yield ProtoExpr.Multiply(left, right)
          case Some("/") =>
            for
              left <- extractValueExpr(leftExpr, schema)
              right <- extractValueExpr(rightExpr, schema)
            yield ProtoExpr.Divide(left, right)
          case Some(op) =>
            Left(s"Unsupported arithmetic operator '$op' in: ${term.show}")
          case None =>
            Left(s"Could not extract method name in: ${term.show}")

      case other =>
        Left(s"Unsupported value expression: ${other.show}")

  private def makeComparison(op: String, left: ProtoExpr, right: ProtoExpr): ProtoExpr =
    op match
      case ">"   => ProtoExpr.Gt(left, right)
      case ">="  => ProtoExpr.GtEq(left, right)
      case "<"   => ProtoExpr.Lt(left, right)
      case "<="  => ProtoExpr.LtEq(left, right)
      case "===" => ProtoExpr.Eq(left, right)
      case "=!=" => ProtoExpr.NotEq(left, right)
      case _     => ProtoExpr.Eq(left, right) // fallback

  private def extractMethodName(using q: Quotes)(term: q.reflect.Term): Option[String] =
    import q.reflect.*
    term match
      case Select(_, name)      => Some(name)
      case TypeApply(inner, _)  => extractMethodName(inner)
      case Inlined(_, _, inner) => extractMethodName(inner)
      case Apply(inner, _)      => extractMethodName(inner)
      case Ident(name)          => Some(name)
      case _                    => None

  // ============================================================================
  // Utility Helpers
  // ============================================================================

  private def extractStringLiteral(using q: Quotes)(term: q.reflect.Term): Either[String, String] =
    import q.reflect.*
    term match
      case Literal(StringConstant(s)) => Right(s)
      case Inlined(_, _, inner)       => extractStringLiteral(inner)
      case other                      => Left(s"Expected string literal, got: ${other.show}")

  private def extractIntLiteral(using q: Quotes)(term: q.reflect.Term): Either[String, Int] =
    import q.reflect.*
    term match
      case Literal(IntConstant(n)) => Right(n)
      case Inlined(_, _, inner)    => extractIntLiteral(inner)
      case other                   => Left(s"Expected int literal, got: ${other.show}")

  private def buildSchemaContract(tableName: String, schema: ProtoSchema): SchemaContract =
    SchemaContract(
      tableName,
      schema.fields.zipWithIndex.map { case (f, i) =>
        FieldContract(f.name, f.dataType, f.nullable, i)
      },
      schema.fingerprint
    )

  // ============================================================================
  // GroupBy/Aggregate Extraction
  // ============================================================================

  /** Extract a GroupedQuery and create an Aggregate plan.
    *
    * Handles patterns like:
    * {{{
    * Table[User]("users").groupBy(_.deptId).agg(count, sum(_.salary))
    * }}}
    */
  private def extractGroupedQueryPlan(using
      q: Quotes
  )(
      groupedQueryExpr: q.reflect.Term,
      schema: ProtoSchema,
      aggExprs: List[q.reflect.Term]
  ): Either[String, PlanResult] =
    import q.reflect.*

    groupedQueryExpr match
      case Inlined(_, _, inner) =>
        extractGroupedQueryPlan(inner, schema, aggExprs)

      case Block(_, expr) =>
        extractGroupedQueryPlan(expr, schema, aggExprs)

      case Typed(expr, _) =>
        extractGroupedQueryPlan(expr, schema, aggExprs)

      // query.groupBy[K](keys*) with TypeApply
      case Apply(TypeApply(Select(childQuery, "groupBy"), _), groupingExprs) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(childQuery, schema)
          groupingProtoExprs <- extractGroupingExprs(groupingExprs, childSchema)
          aggProtoExprs <- extractAggregateExprs(aggExprs, childSchema)
        yield (
          ProtoLogicalPlan.Aggregate(groupingProtoExprs, aggProtoExprs, childPlan),
          tableName,
          childSchema
        )

      // query.groupBy(keys*) without TypeApply
      case Apply(Select(childQuery, "groupBy"), groupingExprs) =>
        for
          (childPlan, tableName, childSchema) <- extractQueryPlanRec(childQuery, schema)
          groupingProtoExprs <- extractGroupingExprs(groupingExprs, childSchema)
          aggProtoExprs <- extractAggregateExprs(aggExprs, childSchema)
        yield (
          ProtoLogicalPlan.Aggregate(groupingProtoExprs, aggProtoExprs, childPlan),
          tableName,
          childSchema
        )

      case other =>
        Left(s"Unsupported grouped query expression: ${other.show}")

  /** Extract grouping expressions from groupBy arguments */
  private def extractGroupingExprs(using
      q: Quotes
  )(
      terms: List[q.reflect.Term],
      schema: ProtoSchema
  ): Either[String, Vector[ProtoExpr]] =
    import q.reflect.*

    // Handle varargs wrappers - extract elements from Repeated/SeqLiteral
    val unwrappedTerms = terms.flatMap { term =>
      term match
        case Typed(Repeated(elems, _), _)                  => elems
        case Repeated(elems, _)                            => elems
        case t if t.getClass.getSimpleName == "SeqLiteral" =>
          // SeqLiteral isn't directly matchable, use reflection
          try
            val elemsField = t.getClass.getMethod("elems")
            elemsField.invoke(t).asInstanceOf[List[Term]]
          catch case _: Exception => List(t)
        case other => List(other)
    }

    val results = unwrappedTerms.map(extractValueExprFromLambda(_, schema))
    val errors = results.collect { case Left(err) => err }
    if errors.nonEmpty then Left(errors.mkString("; "))
    else Right(results.collect { case Right(expr) => expr }.toVector)

  /** Extract a value expression, unwrapping lambda if present */
  private def extractValueExprFromLambda(using
      q: Quotes
  )(
      term: q.reflect.Term,
      schema: ProtoSchema
  ): Either[String, ProtoExpr] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractValueExprFromLambda(inner, schema)

      case Block(List(DefDef(_, _, _, Some(body))), _) =>
        // Lambda: _ => body
        extractValueExpr(body, schema)

      case Block(_, expr) =>
        extractValueExprFromLambda(expr, schema)

      case Typed(expr, _) =>
        extractValueExprFromLambda(expr, schema)

      case other =>
        extractValueExpr(other, schema)

  /** Extract aggregate expressions from agg arguments */
  private def extractAggregateExprs(using
      q: Quotes
  )(
      terms: List[q.reflect.Term],
      schema: ProtoSchema
  ): Either[String, Vector[ProtoExpr]] =
    import q.reflect.*

    // Handle varargs wrappers - extract elements from Repeated/SeqLiteral
    val unwrappedTerms = terms.flatMap { term =>
      term match
        case Typed(Repeated(elems, _), _)                  => elems
        case Repeated(elems, _)                            => elems
        case t if t.getClass.getSimpleName == "SeqLiteral" =>
          try
            val elemsField = t.getClass.getMethod("elems")
            elemsField.invoke(t).asInstanceOf[List[Term]]
          catch case _: Exception => List(t)
        case other => List(other)
    }

    val results = unwrappedTerms.map(extractAggregateExpr(_, schema))
    val errors = results.collect { case Left(err) => err }
    if errors.nonEmpty then Left(errors.mkString("; "))
    else Right(results.collect { case Right(expr) => expr }.toVector)

  /** Extract a single aggregate expression */
  private def extractAggregateExpr(using
      q: Quotes
  )(
      term: q.reflect.Term,
      schema: ProtoSchema
  ): Either[String, ProtoExpr] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractAggregateExpr(inner, schema)

      case Block(_, expr) =>
        extractAggregateExpr(expr, schema)

      case Typed(expr, _) =>
        extractAggregateExpr(expr, schema)

      // functions.count (parameterless) - count all rows
      // Match Select(_, "count") or Ident("count")
      case sel @ Select(_, _) if extractMethodName(sel).contains("count") =>
        Right(ProtoExpr.Count(ProtoExpr.Literal(LiteralValue.IntValue(1)), distinct = false))

      // count imported directly
      case Ident("count") =>
        Right(ProtoExpr.Count(ProtoExpr.Literal(LiteralValue.IntValue(1)), distinct = false))

      // functions.count(expr) - count non-null values with 2 Apply (one for implicits)
      case Apply(Apply(TypeApply(fun, _), List(argExpr)), _)
          if extractMethodName(fun).contains("count") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Count(_, distinct = false))

      // functions.count(expr) with TypeApply and one Apply
      case Apply(TypeApply(fun, _), List(argExpr)) if extractMethodName(fun).contains("count") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Count(_, distinct = false))

      // functions.count(expr) without TypeApply
      case Apply(fun, List(argExpr)) if extractMethodName(fun).contains("count") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Count(_, distinct = false))

      // functions.countDistinct(expr)
      case Apply(Apply(TypeApply(fun, _), List(argExpr)), _)
          if extractMethodName(fun).contains("countDistinct") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Count(_, distinct = true))

      case Apply(TypeApply(fun, _), List(argExpr))
          if extractMethodName(fun).contains("countDistinct") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Count(_, distinct = true))

      case Apply(fun, List(argExpr)) if extractMethodName(fun).contains("countDistinct") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Count(_, distinct = true))

      // functions.sum(expr)(using Numeric, enc) - with implicits
      case Apply(Apply(TypeApply(fun, _), List(argExpr)), _)
          if extractMethodName(fun).contains("sum") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Sum(_))

      // functions.sum(expr) without implicits visible
      case Apply(TypeApply(fun, _), List(argExpr)) if extractMethodName(fun).contains("sum") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Sum(_))

      case Apply(fun, List(argExpr)) if extractMethodName(fun).contains("sum") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Sum(_))

      // functions.avg(expr)
      case Apply(Apply(TypeApply(fun, _), List(argExpr)), _)
          if extractMethodName(fun).contains("avg") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Avg(_))

      case Apply(TypeApply(fun, _), List(argExpr)) if extractMethodName(fun).contains("avg") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Avg(_))

      case Apply(fun, List(argExpr)) if extractMethodName(fun).contains("avg") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Avg(_))

      // functions.min(expr)(using Ordering, enc)
      case Apply(Apply(TypeApply(fun, _), List(argExpr)), _)
          if extractMethodName(fun).contains("min") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Min(_))

      case Apply(TypeApply(fun, _), List(argExpr)) if extractMethodName(fun).contains("min") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Min(_))

      case Apply(fun, List(argExpr)) if extractMethodName(fun).contains("min") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Min(_))

      // functions.max(expr)(using Ordering, enc)
      case Apply(Apply(TypeApply(fun, _), List(argExpr)), _)
          if extractMethodName(fun).contains("max") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Max(_))

      case Apply(TypeApply(fun, _), List(argExpr)) if extractMethodName(fun).contains("max") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Max(_))

      case Apply(fun, List(argExpr)) if extractMethodName(fun).contains("max") =>
        extractValueExprFromLambda(argExpr, schema).map(ProtoExpr.Max(_))

      case other =>
        Left(s"Unsupported aggregate expression: ${other.show}")

  // ============================================================================
  // Join Condition Extraction (two-schema support)
  // ============================================================================

  /** Extract type from a Query or Table expression and derive its schema */
  private def extractSchemaFromQueryExpr(using
      q: Quotes
  )(term: q.reflect.Term): Either[String, ProtoSchema] =
    import q.reflect.*

    // First try to get type from the term's type
    val termType = term.tpe.dealias.simplified.widen

    // Extract the type parameter A from Query[A] or Table[A]
    termType match
      case AppliedType(tycon, List(innerType)) =>
        val tyName = tycon.typeSymbol.name
        if tyName == "Query" || tyName == "Table" then deriveSchemaFromTypeRepr(innerType)
        else Left(s"Expected Query or Table, got: $tyName")

      case _ =>
        // Try to look at the AST structure to find the type
        term match
          case Select(inner, "toQuery") =>
            // table.toQuery - get type from inner Table[A]
            extractSchemaFromQueryExpr(inner)
          case Apply(Select(inner, "toQuery"), _) =>
            extractSchemaFromQueryExpr(inner)
          case Inlined(_, _, inner) =>
            extractSchemaFromQueryExpr(inner)
          case _ =>
            Left(s"Cannot extract schema from type: ${termType.show}")

  /** Derive ProtoSchema from a TypeRepr at compile time */
  private def deriveSchemaFromTypeRepr(using
      q: Quotes
  )(tpe: q.reflect.TypeRepr): Either[String, ProtoSchema] =
    import q.reflect.*

    val dealised = tpe.dealias

    dealised.classSymbol match
      case Some(sym) if sym.flags.is(Flags.Case) =>
        val caseFields = sym.caseFields
        val fieldsResult =
          caseFields.foldLeft[Either[String, Vector[ProtoStructField]]](Right(Vector.empty)) {
            (acc, field) =>
              acc.flatMap { fields =>
                val fieldName = field.name
                val fieldType = dealised.memberType(field)
                deriveProtoTypeFromTypeRepr(fieldType).map { protoType =>
                  val nullable = isTypeNullable(fieldType)
                  fields :+ ProtoStructField(fieldName, protoType, nullable)
                }
              }
          }
        fieldsResult.map(ProtoSchema(_))

      case Some(_) =>
        Left(s"Type ${tpe.show} is not a case class")

      case None =>
        Left(s"Type ${tpe.show} has no class symbol")

  private def isTypeNullable(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*
    tpe.dealias match
      case AppliedType(tycon, _) if tycon.typeSymbol.fullName == "scala.Option" => true
      case _                                                                    => false

  private def deriveProtoTypeFromTypeRepr(using
      q: Quotes
  )(tpe: q.reflect.TypeRepr): Either[String, ProtoType] =
    import q.reflect.*

    val dealised = tpe.dealias

    if dealised =:= TypeRepr.of[Boolean] then Right(ProtoType.BooleanType)
    else if dealised =:= TypeRepr.of[Int] then Right(ProtoType.IntegerType)
    else if dealised =:= TypeRepr.of[Long] then Right(ProtoType.LongType)
    else if dealised =:= TypeRepr.of[Double] then Right(ProtoType.DoubleType)
    else if dealised =:= TypeRepr.of[Float] then Right(ProtoType.FloatType)
    else if dealised =:= TypeRepr.of[String] then Right(ProtoType.StringType)
    else if dealised =:= TypeRepr.of[Short] then Right(ProtoType.ShortType)
    else if dealised =:= TypeRepr.of[Byte] then Right(ProtoType.ByteType)
    else
      // Handle Option[T]
      dealised match
        case AppliedType(tycon, List(inner)) if tycon.typeSymbol.fullName == "scala.Option" =>
          deriveProtoTypeFromTypeRepr(inner)
        case _ =>
          Left(s"Unsupported type for join schema: ${dealised.show}")

  /** Extract join condition from a two-parameter lambda.
    *
    * The lambda has the form: (leftSelector, rightSelector) => condition where leftSelector
    * accesses the left schema and rightSelector accesses the right schema.
    */
  private def extractJoinCondition(using
      q: Quotes
  )(
      condExpr: q.reflect.Term,
      leftSchema: ProtoSchema,
      rightSchema: ProtoSchema
  ): Either[String, ProtoExpr] =
    import q.reflect.*

    condExpr match
      case Inlined(_, _, inner) =>
        extractJoinCondition(inner, leftSchema, rightSchema)

      case Block(List(DefDef(_, paramss, _, Some(body))), _) =>
        // Extract parameter names from ParamClauses
        val params = paramss.flatMap {
          case TermParamClause(params) => params.map(_.name)
          case _                       => Nil
        }
        params match
          case List(leftParamName, rightParamName) =>
            extractJoinPredicateExpr(body, leftSchema, rightSchema, leftParamName, rightParamName)
          case List(singleParamName) =>
            // Single-parameter lambda - fall back to single schema
            extractPredicateExpr(body, leftSchema)
          case _ =>
            Left(s"Join condition must have 1 or 2 parameters, got: $params")

      case _ =>
        // Not a lambda - try as a direct expression
        extractPredicateExpr(condExpr, leftSchema)

  /** Extract a predicate expression in a join context, with two schemas */
  private def extractJoinPredicateExpr(using
      q: Quotes
  )(
      term: q.reflect.Term,
      leftSchema: ProtoSchema,
      rightSchema: ProtoSchema,
      leftParam: String,
      rightParam: String
  ): Either[String, ProtoExpr] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractJoinPredicateExpr(inner, leftSchema, rightSchema, leftParam, rightParam)

      case Block(_, expr) =>
        extractJoinPredicateExpr(expr, leftSchema, rightSchema, leftParam, rightParam)

      case Typed(expr, _) =>
        extractJoinPredicateExpr(expr, leftSchema, rightSchema, leftParam, rightParam)

      // Comparison: left.field === right.field (direct method call)
      case Apply(Select(leftExpr, op), List(rightExpr))
          if Set(">", ">=", "<", "<=", "===", "=!=").contains(op) =>
        for
          left <- extractJoinValueExpr(leftExpr, leftSchema, rightSchema, leftParam, rightParam)
          right <- extractJoinValueExpr(rightExpr, leftSchema, rightSchema, leftParam, rightParam)
        yield makeComparison(op, left, right)

      // Comparison with TypeApply: left.===[T](right) - e.g., from === with type param
      case Apply(TypeApply(Select(leftExpr, op), _), List(rightExpr))
          if Set(">", ">=", "<", "<=", "===", "=!=").contains(op) =>
        for
          left <- extractJoinValueExpr(leftExpr, leftSchema, rightSchema, leftParam, rightParam)
          right <- extractJoinValueExpr(rightExpr, leftSchema, rightSchema, leftParam, rightParam)
        yield makeComparison(op, left, right)

      // Comparison with curried implicit evidence: left.===[T](right)(evidence)
      // Pattern: Apply(Apply(TypeApply(Select(left, "==="), _), List(right)), List(evidence))
      case Apply(Apply(TypeApply(Select(leftExpr, op), _), List(rightExpr)), _)
          if Set(">", ">=", "<", "<=", "===", "=!=").contains(op) =>
        for
          left <- extractJoinValueExpr(leftExpr, leftSchema, rightSchema, leftParam, rightParam)
          right <- extractJoinValueExpr(rightExpr, leftSchema, rightSchema, leftParam, rightParam)
        yield makeComparison(op, left, right)

      // Comparison via extension method (curried form)
      case Apply(Apply(fun, List(leftExpr)), List(rightExpr)) =>
        extractMethodName(fun) match
          case Some(op) if Set(">", ">=", "<", "<=", "===", "=!=").contains(op) =>
            for
              left <- extractJoinValueExpr(leftExpr, leftSchema, rightSchema, leftParam, rightParam)
              right <- extractJoinValueExpr(
                rightExpr,
                leftSchema,
                rightSchema,
                leftParam,
                rightParam
              )
            yield makeComparison(op, left, right)
          case Some("&&") =>
            for
              left <- extractJoinPredicateExpr(
                leftExpr,
                leftSchema,
                rightSchema,
                leftParam,
                rightParam
              )
              right <- extractJoinPredicateExpr(
                rightExpr,
                leftSchema,
                rightSchema,
                leftParam,
                rightParam
              )
            yield ProtoExpr.And(Vector(left, right))
          case Some("||") =>
            for
              left <- extractJoinPredicateExpr(
                leftExpr,
                leftSchema,
                rightSchema,
                leftParam,
                rightParam
              )
              right <- extractJoinPredicateExpr(
                rightExpr,
                leftSchema,
                rightSchema,
                leftParam,
                rightParam
              )
            yield ProtoExpr.Or(Vector(left, right))
          case _ =>
            Left(s"Unsupported join condition expression: ${term.show}")

      // Boolean AND
      case Apply(Select(leftExpr, "&&"), List(rightExpr)) =>
        for
          left <- extractJoinPredicateExpr(leftExpr, leftSchema, rightSchema, leftParam, rightParam)
          right <- extractJoinPredicateExpr(
            rightExpr,
            leftSchema,
            rightSchema,
            leftParam,
            rightParam
          )
        yield ProtoExpr.And(Vector(left, right))

      // Boolean OR
      case Apply(Select(leftExpr, "||"), List(rightExpr)) =>
        for
          left <- extractJoinPredicateExpr(leftExpr, leftSchema, rightSchema, leftParam, rightParam)
          right <- extractJoinPredicateExpr(
            rightExpr,
            leftSchema,
            rightSchema,
            leftParam,
            rightParam
          )
        yield ProtoExpr.Or(Vector(left, right))

      case other =>
        Left(s"Unsupported join predicate expression: ${other.show}")

  /** Extract a value expression in a join context, mapping to left or right schema */
  private def extractJoinValueExpr(using
      q: Quotes
  )(
      term: q.reflect.Term,
      leftSchema: ProtoSchema,
      rightSchema: ProtoSchema,
      leftParam: String,
      rightParam: String
  ): Either[String, ProtoExpr] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractJoinValueExpr(inner, leftSchema, rightSchema, leftParam, rightParam)

      case Block(_, expr) =>
        extractJoinValueExpr(expr, leftSchema, rightSchema, leftParam, rightParam)

      case Typed(expr, _) =>
        extractJoinValueExpr(expr, leftSchema, rightSchema, leftParam, rightParam)

      // FieldSelector.typedColumn[A, T](fs, "fieldName")(using enc) - from transparent inline macro
      // The fs arg may be a synthetic binding (FieldSelector_this) due to inline expansion,
      // so we resolve the join side using the type parameter A and field name.
      // Note: args may be Inlined-wrapped, so we use extractStringLiteral to unwrap.
      case Apply(Apply(TypeApply(Select(_, "typedColumn"), typeArgs), List(_, fieldNameExpr)), _) =>
        extractStringLiteral(fieldNameExpr).flatMap { fieldName =>
          lookupFieldInJoinSchemaByType(fieldName, typeArgs, leftSchema, rightSchema)
        }

      // Column.apply[A, T]("fieldName")(using enc) - explicit column reference
      case Apply(Apply(TypeApply(Select(_, "apply"), _), List(fieldNameExpr)), _) =>
        extractStringLiteral(fieldNameExpr).flatMap { fieldName =>
          lookupFieldInJoinSchemaByName(fieldName, leftSchema, rightSchema)
        }

      // leftParam.fieldName via selectDynamic (legacy fallback)
      case Apply(
            Select(Ident(paramName), "selectDynamic"),
            List(Literal(StringConstant(fieldName)))
          ) =>
        lookupFieldInJoinSchema(
          paramName,
          fieldName,
          leftSchema,
          rightSchema,
          leftParam,
          rightParam
        )

      // Literals
      case Literal(IntConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.IntValue(v)))

      case Literal(LongConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.LongValue(v)))

      case Literal(DoubleConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.DoubleValue(v)))

      case Literal(StringConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.StringValue(v)))

      case Literal(BooleanConstant(v)) =>
        Right(ProtoExpr.Literal(LiteralValue.BooleanValue(v)))

      case other =>
        Left(s"Unsupported join value expression: ${other.show}")

  /** Look up a field in either left or right schema based on the parameter name */
  private def lookupFieldInJoinSchema(
      paramName: String,
      fieldName: String,
      leftSchema: ProtoSchema,
      rightSchema: ProtoSchema,
      leftParam: String,
      rightParam: String
  ): Either[String, ProtoExpr] =
    val (schema, qualifier) =
      if paramName == leftParam then (leftSchema, Some("_1"))
      else if paramName == rightParam then (rightSchema, Some("_2"))
      else
        return Left(
          s"Unknown parameter '$paramName' in join condition (expected '$leftParam' or '$rightParam')"
        )

    schema.fields.find(_.name == fieldName) match
      case Some(field) =>
        // Use qualified column reference for join conditions
        Right(ProtoExpr.ColumnRef(fieldName, qualifier, field.dataType, field.nullable))
      case None =>
        Left(s"Field '$fieldName' not found in ${
            if paramName == leftParam then "left" else "right"
          } schema")

  /** Look up a field using the type parameter A from typedColumn[A, T] to determine left/right.
    * Falls back to field name uniqueness if type matching fails.
    */
  private def lookupFieldInJoinSchemaByType(using
      q: Quotes
  )(
      fieldName: String,
      typeArgs: List[q.reflect.Tree],
      leftSchema: ProtoSchema,
      rightSchema: ProtoSchema
  ): Either[String, ProtoExpr] =
    import q.reflect.*

    // Try type parameter A to determine left/right
    typeArgs match
      case (typeTreeA: TypeTree) :: _ =>
        deriveSchemaFromTypeRepr(typeTreeA.tpe) match
          case Right(schema) =>
            val matchesLeft = schema.fields.map(_.name).toSet == leftSchema.fields.map(_.name).toSet
            val matchesRight =
              schema.fields.map(_.name).toSet == rightSchema.fields.map(_.name).toSet
            if matchesLeft then
              leftSchema.fields.find(_.name == fieldName) match
                case Some(f) =>
                  Right(ProtoExpr.ColumnRef(fieldName, Some("_1"), f.dataType, f.nullable))
                case None => Left(s"Field '$fieldName' not found in left schema")
            else if matchesRight then
              rightSchema.fields.find(_.name == fieldName) match
                case Some(f) =>
                  Right(ProtoExpr.ColumnRef(fieldName, Some("_2"), f.dataType, f.nullable))
                case None => Left(s"Field '$fieldName' not found in right schema")
            else
              // Type didn't match either schema, fall back to name uniqueness
              lookupFieldInJoinSchemaByName(fieldName, leftSchema, rightSchema)
          case Left(_) =>
            lookupFieldInJoinSchemaByName(fieldName, leftSchema, rightSchema)
      case _ =>
        lookupFieldInJoinSchemaByName(fieldName, leftSchema, rightSchema)

  /** Look up a field by name uniqueness across both join schemas */
  private def lookupFieldInJoinSchemaByName(
      fieldName: String,
      leftSchema: ProtoSchema,
      rightSchema: ProtoSchema
  ): Either[String, ProtoExpr] =
    val leftField = leftSchema.fields.find(_.name == fieldName)
    val rightField = rightSchema.fields.find(_.name == fieldName)
    (leftField, rightField) match
      case (Some(f), None) =>
        Right(ProtoExpr.ColumnRef(fieldName, Some("_1"), f.dataType, f.nullable))
      case (None, Some(f)) =>
        Right(ProtoExpr.ColumnRef(fieldName, Some("_2"), f.dataType, f.nullable))
      case (Some(_), Some(_)) =>
        Left(s"Ambiguous field '$fieldName' found in both join schemas")
      case (None, None) =>
        Left(s"Field '$fieldName' not found in either join schema")
