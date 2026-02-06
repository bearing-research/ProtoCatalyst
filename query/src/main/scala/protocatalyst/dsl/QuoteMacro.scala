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
    * The query expression is analyzed at compile time, transformed to a ProtoLogicalPlan, optimized,
    * and embedded as a bytecode constant. No runtime query building or optimization occurs.
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

  private def extractQueryPlan(using
      q: Quotes
  )(term: q.reflect.Term, schema: ProtoSchema): Either[String, (ProtoLogicalPlan, String)] =
    extractQueryPlanRec(term, schema)

  private def extractQueryPlanRec(using Quotes)(
      term: quotes.reflect.Term,
      schema: ProtoSchema
  ): Either[String, (ProtoLogicalPlan, String)] =
    matchPlan(term, schema)

  private def matchPlan(using Quotes)(
      term: quotes.reflect.Term,
      schema: ProtoSchema
  ): Either[String, (ProtoLogicalPlan, String)] =
    import quotes.reflect.*

    term match

      // Handle Inlined wrapper
      case Inlined(_, _, inner) =>
        extractQueryPlanRec(inner, schema)

      // Handle Block wrapper
      case Block(_, expr) =>
        extractQueryPlanRec(expr, schema)

      // Handle Typed wrapper
      case Typed(expr, _) =>
        extractQueryPlanRec(expr, schema)

      // Table[A]("tableName").toQuery
      case Apply(Select(tableExpr, "toQuery"), Nil) =>
        extractTablePlan(tableExpr, schema)

      // query.filter(predicate) - TypeApply version
      case Apply(Apply(TypeApply(Select(child, "filter"), _), List(predicate)), _) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          filterExpr <- extractPredicateExpr(predicate, schema)
        yield (ProtoLogicalPlan.Filter(filterExpr, childPlan), tableName)

      // query.filter(predicate) - non-TypeApply version
      case Apply(Select(child, "filter"), List(predicate)) =>
        val childResult = extractQueryPlanRec(child, schema)
        val predResult = extractPredicateExpr(predicate, schema)
        for
          (childPlan, tableName) <- childResult
          filterExpr <- predResult
        yield (ProtoLogicalPlan.Filter(filterExpr, childPlan), tableName)

      // query.where(predicate) - alias for filter
      case Apply(Apply(TypeApply(Select(child, "where"), _), List(predicate)), _) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          filterExpr <- extractPredicateExpr(predicate, schema)
        yield (ProtoLogicalPlan.Filter(filterExpr, childPlan), tableName)

      case Apply(Select(child, "where"), List(predicate)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          filterExpr <- extractPredicateExpr(predicate, schema)
        yield (ProtoLogicalPlan.Filter(filterExpr, childPlan), tableName)

      // query.select(_.field) - TypeApply version with implicits
      case Apply(Apply(TypeApply(Select(child, "select"), _), List(selector)), _) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          projExpr <- extractProjectionExpr(selector, schema)
        yield (ProtoLogicalPlan.Project(Vector(projExpr), childPlan), tableName)

      // query.select(_.field) - non-TypeApply version
      case Apply(Select(child, "select"), List(selector)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          projExpr <- extractProjectionExpr(selector, schema)
        yield (ProtoLogicalPlan.Project(Vector(projExpr), childPlan), tableName)

      // query.limit(n)
      case Apply(Select(child, "limit"), List(limitExpr)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          n <- extractIntLiteral(limitExpr)
        yield (ProtoLogicalPlan.Limit(n, childPlan), tableName)

      // query.distinct
      case Select(child, "distinct") =>
        for (childPlan, tableName) <- extractQueryPlanRec(child, schema)
        yield (ProtoLogicalPlan.Distinct(childPlan), tableName)

      // query.orderBy(_.field.asc, _.field2.desc)
      case Apply(Select(child, "orderBy"), sortExprs) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          sortOrders <- extractSortOrders(sortExprs, schema)
        yield (ProtoLogicalPlan.Sort(sortOrders, global = true, childPlan), tableName)

      // query.as("alias")
      case Apply(Select(child, "as"), List(aliasExpr)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          alias <- extractStringLiteral(aliasExpr)
        yield (ProtoLogicalPlan.SubqueryAlias(alias, childPlan), tableName)

      // query.union(otherQuery)
      case Apply(Select(child, "union"), List(otherExpr)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
        yield (
          ProtoLogicalPlan.Union(Vector(childPlan, otherPlan), byName = false, allowMissingColumns = false),
          tableName
        )

      // query.intersect(otherQuery)
      case Apply(Select(child, "intersect"), List(otherExpr)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
        yield (ProtoLogicalPlan.Intersect(childPlan, otherPlan, isAll = false), tableName)

      // query.intersectAll(otherQuery)
      case Apply(Select(child, "intersectAll"), List(otherExpr)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
        yield (ProtoLogicalPlan.Intersect(childPlan, otherPlan, isAll = true), tableName)

      // query.except(otherQuery)
      case Apply(Select(child, "except"), List(otherExpr)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
        yield (ProtoLogicalPlan.Except(childPlan, otherPlan, isAll = false), tableName)

      // query.exceptAll(otherQuery)
      case Apply(Select(child, "exceptAll"), List(otherExpr)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
        yield (ProtoLogicalPlan.Except(childPlan, otherPlan, isAll = true), tableName)

      // query.crossJoin(otherQuery)(using encoder)
      case Apply(Apply(TypeApply(Select(child, "crossJoin"), _), List(otherExpr)), _) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
        yield (ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.Cross, None), tableName)

      // query.crossJoin(otherQuery) - without explicit TypeApply
      case Apply(Select(child, "crossJoin"), List(otherExpr)) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
        yield (ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.Cross, None), tableName)

      // query.join(otherQuery).on(condition) - inner join with condition
      case Apply(Apply(TypeApply(Select(Apply(Select(child, "join"), List(otherExpr)), "on"), _), List(condExpr)), _) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
          condition <- extractPredicateExpr(condExpr, schema)
        yield (ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.Inner, Some(condition)), tableName)

      // query.leftJoin(otherQuery).on(condition)
      case Apply(Apply(TypeApply(Select(Apply(Select(child, "leftJoin"), List(otherExpr)), "on"), _), List(condExpr)), _) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
          condition <- extractPredicateExpr(condExpr, schema)
        yield (ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.LeftOuter, Some(condition)), tableName)

      // query.rightJoin(otherQuery).on(condition)
      case Apply(Apply(TypeApply(Select(Apply(Select(child, "rightJoin"), List(otherExpr)), "on"), _), List(condExpr)), _) =>
        for
          (childPlan, tableName) <- extractQueryPlanRec(child, schema)
          (otherPlan, _) <- extractQueryPlanRec(otherExpr, schema)
          condition <- extractPredicateExpr(condExpr, schema)
        yield (ProtoLogicalPlan.Join(childPlan, otherPlan, JoinType.RightOuter, Some(condition)), tableName)

      // Table.apply[A]("tableName") - direct table creation
      case Apply(Apply(TypeApply(Select(_, "apply"), _), List(tableNameExpr)), _) =>
        extractStringLiteral(tableNameExpr).map { tableName =>
          val contract = buildSchemaContract(tableName, schema)
          (ProtoLogicalPlan.RelationRef(tableName, None, contract), tableName)
        }

      // Table[A]("tableName") - alternative pattern
      case Apply(TypeApply(Apply(_, List(tableNameExpr)), _), _) =>
        extractStringLiteral(tableNameExpr).flatMap { tableName =>
          val contract = buildSchemaContract(tableName, schema)
          Right((ProtoLogicalPlan.RelationRef(tableName, None, contract), tableName))
        }

      // table.toQuery
      case Select(tableExpr, "toQuery") =>
        extractTablePlan(tableExpr, schema)

      // Direct Table expression
      case _ if isTableExpression(term) =>
        extractTablePlan(term, schema)

      case other =>
        Left(s"Unsupported query expression: ${other.show}")

  private def extractTablePlan(using
      q: Quotes
  )(term: q.reflect.Term, schema: ProtoSchema): Either[String, (ProtoLogicalPlan, String)] =
    import q.reflect.*

    term match
      case Inlined(_, _, inner) =>
        extractTablePlan(inner, schema)

      case Block(_, expr) =>
        extractTablePlan(expr, schema)

      case Typed(expr, _) =>
        extractTablePlan(expr, schema)

      // Table.apply[A]("tableName")(using enc)
      case Apply(Apply(TypeApply(Select(_, "apply"), _), List(tableNameExpr)), _) =>
        extractStringLiteral(tableNameExpr).map { tableName =>
          val contract = buildSchemaContract(tableName, schema)
          (ProtoLogicalPlan.RelationRef(tableName, None, contract), tableName)
        }

      // Table[A]("tableName")
      case Apply(TypeApply(Apply(Ident("Table"), List(tableNameExpr)), _), _) =>
        extractStringLiteral(tableNameExpr).map { tableName =>
          val contract = buildSchemaContract(tableName, schema)
          (ProtoLogicalPlan.RelationRef(tableName, None, contract), tableName)
        }

      case other =>
        Left(s"Cannot extract table from: ${other.show}")

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
            Left(s"Could not extract method name from fun[${fun.getClass.getSimpleName}]=${fun.show} in: ${term.show}")

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
      case Select(_, fieldName) if !fieldName.startsWith("$") && schema.fields.exists(_.name == fieldName) =>
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
      case Apply(Apply(fun, List(leftExpr)), List(rightExpr)) if extractMethodName(fun).contains("++") =>
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
      case Select(_, name)        => Some(name)
      case TypeApply(inner, _)    => extractMethodName(inner)
      case Inlined(_, _, inner)   => extractMethodName(inner)
      case Apply(inner, _)        => extractMethodName(inner)
      case Ident(name)            => Some(name)
      case _                      => None

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
