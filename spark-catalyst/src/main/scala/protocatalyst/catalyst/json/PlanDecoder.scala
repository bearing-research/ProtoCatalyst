package protocatalyst.catalyst.json

import io.circe.{DecodingFailure, HCursor, Json}
import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.analysis.{
  UnresolvedAttribute,
  UnresolvedFunction,
  UnresolvedGenerator,
  UnresolvedInlineTable,
  UnresolvedRelation
}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.plans._
import org.apache.spark.sql.catalyst.plans.logical._

/** Decodes JSON (upickle format) directly to Spark LogicalPlan.
  *
  * Handles all ProtoLogicalPlan types defined in JsonArtifactCodec.
  */
object PlanDecoder {

  // Explicit Either types to avoid conflict with Spark's Left/Right expressions
  private type EitherResult[A] = scala.Either[DecodingFailure, A]
  private def success[A](a: A): EitherResult[A] = scala.Right(a)
  private def failure[A](msg: String, history: List[io.circe.CursorOp]): EitherResult[A] =
    scala.Left(DecodingFailure(msg, history))

  def decode(json: Json): EitherResult[LogicalPlan] =
    decode(json.hcursor)

  /** Normalize short type name to full path or return as-is if already full path */
  private def normalizePlanType(shortName: String): String = {
    if (shortName.contains(".")) shortName
    else s"protocatalyst.plan.ProtoLogicalPlan.$shortName"
  }

  def decode(c: HCursor): EitherResult[LogicalPlan] = {
    c.get[String]("$type").flatMap { rawPlanType =>
      val planType = normalizePlanType(rawPlanType)
      planType match {
        // === Leaf nodes ===
        case "protocatalyst.plan.ProtoLogicalPlan.RelationRef" =>
          for {
            name <- c.get[String]("name")
            alias <- c.get[Option[String]]("alias")
          } yield {
            val base = UnresolvedRelation(Seq(name))
            alias.fold[LogicalPlan](base)(a => SubqueryAlias(a, base))
          }

        case "protocatalyst.plan.ProtoLogicalPlan.Values" =>
          for {
            rowsJson <- c.get[Vector[Json]]("rows")
            rows <- decodeRows(rowsJson)
            schemaJson <- c.get[Json]("schema")
            schema <- decodeSchema(schemaJson.hcursor)
          } yield {
            // Create UnresolvedInlineTable with the column names and values
            val columnNames = schema.map(_.name)
            UnresolvedInlineTable(columnNames, rows.map(_.toSeq))
          }

        // === Unary nodes ===
        case "protocatalyst.plan.ProtoLogicalPlan.Project" =>
          for {
            projectListJson <- c.get[Vector[Json]]("projectList")
            projectList <- ExpressionDecoder.decodeExprs(projectListJson)
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield Project(projectList.map(asNamedExpression), child)

        case "protocatalyst.plan.ProtoLogicalPlan.Filter" =>
          for {
            conditionJson <- c.get[Json]("condition")
            condition <- ExpressionDecoder.decode(conditionJson)
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield Filter(condition, child)

        case "protocatalyst.plan.ProtoLogicalPlan.Aggregate" =>
          for {
            groupingJson <- c.get[Vector[Json]]("groupingExprs")
            grouping <- ExpressionDecoder.decodeExprs(groupingJson)
            aggregateJson <- c.get[Vector[Json]]("aggregateExprs")
            aggregate <- ExpressionDecoder.decodeExprs(aggregateJson)
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield Aggregate(grouping, aggregate.map(asNamedExpression), child)

        case "protocatalyst.plan.ProtoLogicalPlan.Sort" =>
          for {
            orderJson <- c.get[Vector[Json]]("order")
            order <- decodeSortOrders(orderJson)
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield Sort(order, global = true, child)

        case "protocatalyst.plan.ProtoLogicalPlan.Limit" =>
          for {
            limit <- c.get[Int]("limit")
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield GlobalLimit(Literal(limit), LocalLimit(Literal(limit), child))

        case "protocatalyst.plan.ProtoLogicalPlan.Distinct" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield Distinct(child)

        case "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias" =>
          for {
            alias <- c.get[String]("alias")
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield SubqueryAlias(alias, child)

        // === Binary nodes ===
        case "protocatalyst.plan.ProtoLogicalPlan.Join" =>
          for {
            leftJson <- c.get[Json]("left")
            left <- decode(leftJson)
            rightJson <- c.get[Json]("right")
            right <- decode(rightJson)
            joinTypeStr <- c.get[String]("joinType")
            joinType = decodeJoinType(joinTypeStr)
            conditionJson <- c.get[Option[Json]]("condition")
            condition <- optionSequence(conditionJson.map(ExpressionDecoder.decode))
          } yield Join(left, right, joinType, condition, JoinHint.NONE)

        case "protocatalyst.plan.ProtoLogicalPlan.Union" =>
          for {
            childrenJson <- c.get[Vector[Json]]("children")
            children <- decodePlans(childrenJson)
            byName <- c.get[Boolean]("byName")
            allowMissingColumns <- c.get[Boolean]("allowMissingColumns")
          } yield Union(children, byName, allowMissingColumns)

        case "protocatalyst.plan.ProtoLogicalPlan.Intersect" =>
          for {
            leftJson <- c.get[Json]("left")
            left <- decode(leftJson)
            rightJson <- c.get[Json]("right")
            right <- decode(rightJson)
            isAll <- c.get[Boolean]("isAll")
          } yield Intersect(left, right, isAll)

        case "protocatalyst.plan.ProtoLogicalPlan.Except" =>
          for {
            leftJson <- c.get[Json]("left")
            left <- decode(leftJson)
            rightJson <- c.get[Json]("right")
            right <- decode(rightJson)
            isAll <- c.get[Boolean]("isAll")
          } yield Except(left, right, isAll)

        // === Window ===
        case "protocatalyst.plan.ProtoLogicalPlan.Window" =>
          for {
            windowExprsJson <- c.get[Vector[Json]]("windowExprs")
            windowExprs <- ExpressionDecoder.decodeExprs(windowExprsJson)
            partitionSpecJson <- c.get[Vector[Json]]("partitionSpec")
            partitionSpec <- ExpressionDecoder.decodeExprs(partitionSpecJson)
            orderSpecJson <- c.get[Vector[Json]]("orderSpec")
            orderSpec <- decodeSortOrders(orderSpecJson)
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield Window(windowExprs.map(asNamedExpression), partitionSpec, orderSpec, child)

        // === CTE ===
        case "protocatalyst.plan.ProtoLogicalPlan.With" =>
          for {
            cteRelationsJson <- c.get[Vector[Json]]("cteRelations")
            cteRelations <- decodeCTERelations(cteRelationsJson)
            recursive <- c.get[Boolean]("recursive")
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield {
            // Spark's WithCTE wraps CTEs
            val cteDefs = cteRelations.map { case (name, plan) =>
              CTERelationDef(SubqueryAlias(name, plan))
            }
            WithCTE(child, cteDefs)
          }

        // === Pivot/Unpivot ===
        case "protocatalyst.plan.ProtoLogicalPlan.Pivot" =>
          for {
            groupingJson <- c.get[Vector[Json]]("groupingExprs")
            grouping <- ExpressionDecoder.decodeExprs(groupingJson)
            pivotColJson <- c.get[Json]("pivotColumn")
            pivotCol <- ExpressionDecoder.decode(pivotColJson)
            pivotValsJson <- c.get[Vector[Json]]("pivotValues")
            pivotVals <- ExpressionDecoder.decodeExprs(pivotValsJson)
            aggJson <- c.get[Vector[Json]]("aggregates")
            aggs <- ExpressionDecoder.decodeExprs(aggJson)
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield {
            val groupOpt = if (grouping.isEmpty) None else Some(grouping.map(asNamedExpression))
            Pivot(groupOpt, pivotCol, pivotVals, aggs, child)
          }

        case "protocatalyst.plan.ProtoLogicalPlan.Unpivot" =>
          for {
            valueColName <- c.get[String]("valueColumnName")
            varColName <- c.get[String]("variableColumnName")
            columnsJson <- c.get[Vector[Json]]("columns")
            columns <- decodeUnpivotColumns(columnsJson)
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield {
            val (colExprs, aliases) = columns.unzip
            val values = Some(colExprs.map(e => Seq(asNamedExpression(e))))
            val aliasOpt = Some(aliases)
            Unpivot(None, values, aliasOpt, varColName, Seq(valueColName), child)
          }

        // === Lateral ===
        case "protocatalyst.plan.ProtoLogicalPlan.LateralJoin" =>
          for {
            leftJson <- c.get[Json]("left")
            left <- decode(leftJson)
            lateralJson <- c.get[Json]("lateral")
            lateral <- decode(lateralJson)
            conditionJson <- c.get[Option[Json]]("condition")
            condition <- optionSequence(conditionJson.map(ExpressionDecoder.decode))
          } yield LateralJoin(left, LateralSubquery(lateral), Inner, condition)

        // === Generator (LATERAL VIEW) ===
        case "protocatalyst.plan.ProtoLogicalPlan.Generate" =>
          for {
            generatorJson <- c.get[Json]("generator")
            generatorExpr <- ExpressionDecoder.decode(generatorJson)
            generatorOutputNames <- c.get[Vector[String]]("generatorOutput")
            outer <- c.get[Boolean]("outer")
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield {
            val generator = toGenerator(generatorExpr)
            val genOutput = generatorOutputNames.map(name => UnresolvedAttribute(Seq(name)))
            Generate(generator, Seq.empty, outer, None, genOutput, child)
          }

        // === Hints ===
        case "protocatalyst.plan.ProtoLogicalPlan.ResolvedHint" =>
          for {
            hintsJson <- c.get[Vector[Json]]("hints")
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield {
            // Spark uses UnresolvedHint for unresolved hints
            // For now, just return the child since hints need catalog resolution
            child
          }

        case other =>
          failure(s"Unknown ProtoLogicalPlan type: $other", c.history)
      }
    }
  }

  private def asNamedExpression(expr: Expression): NamedExpression = expr match {
    case ne: NamedExpression => ne
    case other               => Alias(other, other.sql)()
  }

  private def decodeJoinType(str: String): JoinType = str match {
    case "Inner"      => Inner
    case "LeftOuter"  => LeftOuter
    case "RightOuter" => RightOuter
    case "FullOuter"  => FullOuter
    case "LeftSemi"   => LeftSemi
    case "LeftAnti"   => LeftAnti
    case "Cross"      => Cross
    case _            => Inner // Default
  }

  private def decodeSortOrders(jsons: Vector[Json]): EitherResult[Seq[SortOrder]] = {
    var result: Vector[SortOrder] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decodeSortOrder(iter.next().hcursor) match {
        case scala.Right(order) => result = result :+ order
        case scala.Left(err)    => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None      => scala.Right(result)
    }
  }

  private def decodeSortOrder(c: HCursor): EitherResult[SortOrder] = {
    for {
      childJson <- c.get[Json]("child")
      child <- ExpressionDecoder.decode(childJson)
      directionStr <- c.get[String]("direction")
      nullOrderingStr <- c.get[String]("nullOrdering")
    } yield {
      val direction = directionStr match {
        case "Ascending"  => Ascending
        case "Descending" => Descending
        case _            => Ascending
      }
      val nullOrdering = nullOrderingStr match {
        case "NullsFirst" => NullsFirst
        case "NullsLast"  => NullsLast
        case _            => if (direction == Ascending) NullsFirst else NullsLast
      }
      SortOrder(child, direction, nullOrdering, Seq.empty)
    }
  }

  private def decodePlans(jsons: Vector[Json]): EitherResult[Seq[LogicalPlan]] = {
    var result: Vector[LogicalPlan] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decode(iter.next()) match {
        case scala.Right(plan) => result = result :+ plan
        case scala.Left(err)   => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None      => scala.Right(result)
    }
  }

  private def decodeRows(jsons: Vector[Json]): EitherResult[Seq[Seq[Expression]]] = {
    var result: Vector[Seq[Expression]] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decodeRow(iter.next()) match {
        case scala.Right(row) => result = result :+ row
        case scala.Left(err)  => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None      => scala.Right(result)
    }
  }

  private def decodeRow(json: Json): EitherResult[Seq[Expression]] = {
    json.asArray match {
      case Some(arr) =>
        ExpressionDecoder.decodeExprs(arr.toVector)
      case None =>
        failure("Expected array for row", Nil)
    }
  }

  private def decodeSchema(
      c: HCursor
  ): EitherResult[Seq[org.apache.spark.sql.types.StructField]] = {
    for {
      fieldsJson <- c.get[Vector[Json]]("fields")
      fields <- decodeStructFields(fieldsJson)
    } yield fields
  }

  private def decodeStructFields(
      jsons: Vector[Json]
  ): EitherResult[Seq[org.apache.spark.sql.types.StructField]] = {
    var result: Vector[org.apache.spark.sql.types.StructField] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      TypeDecoder.decodeStructField(iter.next().hcursor) match {
        case scala.Right(field) => result = result :+ field
        case scala.Left(err)    => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None      => scala.Right(result)
    }
  }

  private def decodeCTERelations(jsons: Vector[Json]): EitherResult[Seq[(String, LogicalPlan)]] = {
    var result: Vector[(String, LogicalPlan)] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decodeCTERelation(iter.next()) match {
        case scala.Right(rel) => result = result :+ rel
        case scala.Left(err)  => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None      => scala.Right(result)
    }
  }

  private def decodeCTERelation(json: Json): EitherResult[(String, LogicalPlan)] = {
    // CTEs are stored as tuples (name, plan) which upickle serializes as arrays
    json.asArray match {
      case Some(arr) if arr.length == 2 =>
        for {
          name <- arr(0).as[String].left.map(e => DecodingFailure(e.message, Nil))
          plan <- decode(arr(1))
        } yield (name, plan)
      case _ =>
        failure("Expected array of 2 elements for CTE relation", Nil)
    }
  }

  /** Convert a decoded expression to a Spark Generator type. */
  private def toGenerator(expr: Expression): Generator = expr match {
    case g: Generator           => g
    case uf: UnresolvedFunction =>
      UnresolvedGenerator(FunctionIdentifier(uf.nameParts.mkString(".")), uf.arguments)
    case other =>
      UnresolvedGenerator(FunctionIdentifier(other.sql), Seq(other))
  }

  /** Decode unpivot columns — upickle serializes tuples as 2-element JSON arrays. */
  private def decodeUnpivotColumns(
      jsons: Vector[Json]
  ): EitherResult[Seq[(Expression, Option[String])]] = {
    var result: Vector[(Expression, Option[String])] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decodeUnpivotColumn(iter.next()) match {
        case scala.Right(col) => result = result :+ col
        case scala.Left(err)  => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None      => scala.Right(result)
    }
  }

  private def decodeUnpivotColumn(json: Json): EitherResult[(Expression, Option[String])] = {
    json.asArray match {
      case Some(arr) if arr.length == 2 =>
        for {
          expr <- ExpressionDecoder.decode(arr(0))
          alias <- arr(1).as[Option[String]].left.map(e => DecodingFailure(e.message, Nil))
        } yield (expr, alias)
      case _ =>
        failure("Expected array of 2 elements for unpivot column tuple", Nil)
    }
  }

  private def optionSequence[A](opt: Option[EitherResult[A]]): EitherResult[Option[A]] = opt match {
    case None         => scala.Right(None)
    case Some(either) => either.map(Some(_))
  }
}
