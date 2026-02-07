package protocatalyst.catalyst.json

import io.circe.{DecodingFailure, HCursor, Json}
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAttribute, UnresolvedFunction}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.{
  AggregateExpression,
  Average,
  Complete,
  Count,
  First,
  Last,
  Max,
  Min,
  Sum
}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String

/** Decodes JSON (upickle format) directly to Spark Expression.
  *
  * Handles all ProtoExpr types defined in JsonArtifactCodec.
  */
object ExpressionDecoder {

  // Explicit Either imports to avoid conflict with Spark's Left/Right expressions
  private type EitherResult[A] = scala.Either[DecodingFailure, A]
  private def success[A](a: A): EitherResult[A] = scala.Right(a)
  private def failure[A](msg: String, history: List[io.circe.CursorOp]): EitherResult[A] =
    scala.Left(DecodingFailure(msg, history))

  def decode(json: Json): EitherResult[Expression] =
    decode(json.hcursor)

  /** Normalize short type name to full path or return as-is if already full path */
  private def normalizeExprType(shortName: String): String = {
    if (shortName.contains(".")) shortName
    else s"protocatalyst.expr.ProtoExpr.$shortName"
  }

  def decode(c: HCursor): EitherResult[Expression] = {
    c.get[String]("$type").flatMap { rawExprType =>
      val exprType = normalizeExprType(rawExprType)
      exprType match {
        // === Leaf nodes ===
        case "protocatalyst.expr.ProtoExpr.Literal" =>
          decodeLiteral(c)

        case "protocatalyst.expr.ProtoExpr.ColumnRef" =>
          for {
            name <- c.get[String]("name")
            qualifier <- c.get[Option[String]]("qualifier")
          } yield qualifier match {
            case Some(q) => UnresolvedAttribute(Seq(q, name))
            case None    => UnresolvedAttribute(Seq(name))
          }

        case "protocatalyst.expr.ProtoExpr.BoundRef" =>
          for {
            index <- c.get[Int]("index")
            dataTypeJson <- c.get[Json]("dataType")
            dataType <- TypeDecoder.decode(dataTypeJson)
            nullable <- c.get[Boolean]("nullable")
          } yield BoundReference(index, dataType, nullable)

        // === Comparison ===
        case "protocatalyst.expr.ProtoExpr.Eq" =>
          decodeBinary(c, EqualTo.apply)

        case "protocatalyst.expr.ProtoExpr.NotEq" =>
          decodeBinary(c, (l, r) => Not(EqualTo(l, r)))

        case "protocatalyst.expr.ProtoExpr.Lt" =>
          decodeBinary(c, LessThan.apply)

        case "protocatalyst.expr.ProtoExpr.LtEq" =>
          decodeBinary(c, LessThanOrEqual.apply)

        case "protocatalyst.expr.ProtoExpr.Gt" =>
          decodeBinary(c, GreaterThan.apply)

        case "protocatalyst.expr.ProtoExpr.GtEq" =>
          decodeBinary(c, GreaterThanOrEqual.apply)

        // === Logical ===
        case "protocatalyst.expr.ProtoExpr.And" =>
          for {
            childrenJson <- c.get[Vector[Json]]("children")
            children <- decodeExprs(childrenJson)
          } yield children.reduceLeft[Expression](And(_, _))

        case "protocatalyst.expr.ProtoExpr.Or" =>
          for {
            childrenJson <- c.get[Vector[Json]]("children")
            children <- decodeExprs(childrenJson)
          } yield children.reduceLeft[Expression](Or(_, _))

        case "protocatalyst.expr.ProtoExpr.Not" =>
          decodeUnary(c, Not.apply)

        // === Null handling ===
        case "protocatalyst.expr.ProtoExpr.IsNull" =>
          decodeUnary(c, IsNull.apply)

        case "protocatalyst.expr.ProtoExpr.IsNotNull" =>
          decodeUnary(c, IsNotNull.apply)

        case "protocatalyst.expr.ProtoExpr.Coalesce" =>
          for {
            childrenJson <- c.get[Vector[Json]]("children")
            children <- decodeExprs(childrenJson)
          } yield Coalesce(children)

        case "protocatalyst.expr.ProtoExpr.NullIf" =>
          decodeBinary(c, (l, r) => UnresolvedFunction(Seq("nullif"), Seq(l, r), false))

        // === Arithmetic ===
        case "protocatalyst.expr.ProtoExpr.Add" =>
          decodeBinary(c, Add.apply(_, _, EvalMode.LEGACY))

        case "protocatalyst.expr.ProtoExpr.Subtract" =>
          decodeBinary(c, Subtract.apply(_, _, EvalMode.LEGACY))

        case "protocatalyst.expr.ProtoExpr.Multiply" =>
          decodeBinary(c, Multiply.apply(_, _, EvalMode.LEGACY))

        case "protocatalyst.expr.ProtoExpr.Divide" =>
          decodeBinary(
            c,
            (l, r) => Divide(l, r, EvalMode.LEGACY)
          )

        // === Math functions ===
        case "protocatalyst.expr.ProtoExpr.Abs" =>
          decodeUnary(c, child => Abs(child, failOnError = false))

        case "protocatalyst.expr.ProtoExpr.Ceil" =>
          decodeUnary(c, child => Ceil(child))

        case "protocatalyst.expr.ProtoExpr.Floor" =>
          decodeUnary(c, child => Floor(child))

        case "protocatalyst.expr.ProtoExpr.Round" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
            scaleJson <- c.get[Json]("scale")
            scale <- decode(scaleJson)
          } yield Round(child, scale)

        case "protocatalyst.expr.ProtoExpr.Truncate" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
            scaleJson <- c.get[Json]("scale")
            scale <- decode(scaleJson)
          } yield UnresolvedFunction(Seq("truncate"), Seq(child, scale), false)

        case "protocatalyst.expr.ProtoExpr.Sqrt" =>
          decodeUnary(c, child => Sqrt(child))

        case "protocatalyst.expr.ProtoExpr.Cbrt" =>
          decodeUnary(c, child => Cbrt(child))

        case "protocatalyst.expr.ProtoExpr.Pow" =>
          decodeBinary(c, Pow.apply)

        case "protocatalyst.expr.ProtoExpr.Pmod" =>
          decodeBinary(c, (l, r) => Pmod(l, r))

        case "protocatalyst.expr.ProtoExpr.Sign" =>
          decodeUnary(c, child => Signum(child))

        case "protocatalyst.expr.ProtoExpr.Log" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
            baseJson <- c.get[Option[Json]]("base")
            base <- baseJson.map(decode).sequence
          } yield base match {
            case Some(b) => Logarithm(b, child)
            case None    => Log(child)
          }

        case "protocatalyst.expr.ProtoExpr.Exp" =>
          decodeUnary(c, child => Exp(child))

        // === String functions ===
        case "protocatalyst.expr.ProtoExpr.Concat" =>
          for {
            childrenJson <- c.get[Vector[Json]]("children")
            children <- decodeExprs(childrenJson)
          } yield Concat(children)

        case "protocatalyst.expr.ProtoExpr.Substring" =>
          for {
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            posJson <- c.get[Json]("pos")
            pos <- decode(posJson)
            lenJson <- c.get[Json]("len")
            len <- decode(lenJson)
          } yield Substring(str, pos, len)

        case "protocatalyst.expr.ProtoExpr.Upper" =>
          decodeUnary(c, Upper.apply)

        case "protocatalyst.expr.ProtoExpr.Lower" =>
          decodeUnary(c, Lower.apply)

        case "protocatalyst.expr.ProtoExpr.Trim" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
            trimStrJson <- c.get[Option[Json]]("trimStr")
            trimStr <- trimStrJson.map(decode).sequence
            trimType <- c.get[String]("trimType")
          } yield trimType match {
            case "Leading"  => StringTrimLeft(child, trimStr)
            case "Trailing" => StringTrimRight(child, trimStr)
            case _          => StringTrim(child, trimStr)
          }

        case "protocatalyst.expr.ProtoExpr.Length" =>
          decodeUnary(c, child => Length(child))

        case "protocatalyst.expr.ProtoExpr.Replace" =>
          for {
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            searchJson <- c.get[Json]("search")
            search <- decode(searchJson)
            replaceJson <- c.get[Json]("replace")
            replace <- decode(replaceJson)
          } yield StringReplace(str, search, replace)

        case "protocatalyst.expr.ProtoExpr.StringLocate" =>
          for {
            substrJson <- c.get[Json]("substr")
            substr <- decode(substrJson)
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            startJson <- c.get[Option[Json]]("start")
            start <- startJson.map(decode).sequence
          } yield StringLocate(substr, str, start.getOrElse(Literal(1)))

        case "protocatalyst.expr.ProtoExpr.Lpad" =>
          for {
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            lenJson <- c.get[Json]("len")
            len <- decode(lenJson)
            padJson <- c.get[Json]("pad")
            pad <- decode(padJson)
          } yield StringLPad(str, len, pad)

        case "protocatalyst.expr.ProtoExpr.Rpad" =>
          for {
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            lenJson <- c.get[Json]("len")
            len <- decode(lenJson)
            padJson <- c.get[Json]("pad")
            pad <- decode(padJson)
          } yield StringRPad(str, len, pad)

        case "protocatalyst.expr.ProtoExpr.StringSplit" =>
          for {
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            delimJson <- c.get[Json]("delimiter")
            delim <- decode(delimJson)
          } yield StringSplitSQL(str, delim)

        case "protocatalyst.expr.ProtoExpr.Reverse" =>
          decodeUnary(c, child => Reverse(child))

        case "protocatalyst.expr.ProtoExpr.StringRepeat" =>
          for {
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            timesJson <- c.get[Json]("times")
            times <- decode(timesJson)
          } yield StringRepeat(str, times)

        // === Pattern matching ===
        case "protocatalyst.expr.ProtoExpr.Like" =>
          for {
            valueJson <- c.get[Json]("value")
            value <- decode(valueJson)
            patternJson <- c.get[Json]("pattern")
            pattern <- decode(patternJson)
          } yield Like(value, pattern, '\\')

        // === Aggregates ===
        case "protocatalyst.expr.ProtoExpr.Count" =>
          // Count can use either "child" (single) or "children" (array) depending on JSON format
          val childResult = c.get[Json]("child").flatMap(decode).map(ch => Seq(ch))
          val childrenResult = c.get[Vector[Json]]("children").flatMap(decodeExprs)

          childResult.orElse(childrenResult).map { children =>
            // COUNT(*) has empty children, COUNT(col) has one child
            val aggFunc = if (children.isEmpty) Count(Seq(Literal(1))) else Count(children)
            AggregateExpression(aggFunc, Complete, false, None)
          }

        case "protocatalyst.expr.ProtoExpr.Sum" =>
          decodeUnary(c, child => AggregateExpression(Sum(child), Complete, false, None))

        case "protocatalyst.expr.ProtoExpr.Avg" =>
          decodeUnary(c, child => AggregateExpression(Average(child), Complete, false, None))

        case "protocatalyst.expr.ProtoExpr.Min" =>
          decodeUnary(c, child => AggregateExpression(Min(child), Complete, false, None))

        case "protocatalyst.expr.ProtoExpr.Max" =>
          decodeUnary(c, child => AggregateExpression(Max(child), Complete, false, None))

        // === Control flow ===
        case "protocatalyst.expr.ProtoExpr.CaseWhen" =>
          for {
            branchesJson <- c.get[Vector[Json]]("branches")
            branches <- decodeBranches(branchesJson)
            elseValueJson <- c.get[Option[Json]]("elseValue")
            elseValue <- elseValueJson.map(decode).sequence
          } yield CaseWhen(branches, elseValue)

        case "protocatalyst.expr.ProtoExpr.If" =>
          for {
            predicateJson <- c.get[Json]("predicate")
            predicate <- decode(predicateJson)
            trueValueJson <- c.get[Json]("trueValue")
            trueValue <- decode(trueValueJson)
            falseValueJson <- c.get[Json]("falseValue")
            falseValue <- decode(falseValueJson)
          } yield If(predicate, trueValue, falseValue)

        case "protocatalyst.expr.ProtoExpr.In" =>
          for {
            valueJson <- c.get[Json]("value")
            value <- decode(valueJson)
            listJson <- c.get[Vector[Json]]("list")
            list <- decodeExprs(listJson)
          } yield In(value, list)

        // === Cast and Alias ===
        case "protocatalyst.expr.ProtoExpr.Cast" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
            targetTypeJson <- c.get[Json]("targetType")
            targetType <- TypeDecoder.decode(targetTypeJson)
          } yield Cast(child, targetType)

        case "protocatalyst.expr.ProtoExpr.Alias" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
            name <- c.get[String]("name")
          } yield Alias(child, name)()

        // === Generator expressions ===
        case "protocatalyst.expr.ProtoExpr.Explode" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield Explode(child)

        case "protocatalyst.expr.ProtoExpr.PosExplode" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield PosExplode(child)

        case "protocatalyst.expr.ProtoExpr.Inline" =>
          for {
            childJson <- c.get[Json]("child")
            child <- decode(childJson)
          } yield org.apache.spark.sql.catalyst.expressions.Inline(child)

        case "protocatalyst.expr.ProtoExpr.Stack" =>
          for {
            numRowsJson <- c.get[Json]("numRows")
            numRows <- decode(numRowsJson)
            childrenJson <- c.get[Vector[Json]]("children")
            children <- decodeExprs(childrenJson)
          } yield UnresolvedFunction(Seq("stack"), numRows +: children, false)

        // === Subquery expressions ===
        case "protocatalyst.expr.ProtoExpr.ScalarSubquery" =>
          for {
            planJson <- c.get[Json]("plan")
            plan <- PlanDecoder.decode(planJson)
          } yield ScalarSubquery(plan)

        case "protocatalyst.expr.ProtoExpr.Exists" =>
          for {
            planJson <- c.get[Json]("plan")
            plan <- PlanDecoder.decode(planJson)
          } yield Exists(plan)

        case "protocatalyst.expr.ProtoExpr.InSubquery" =>
          for {
            valueJson <- c.get[Json]("value")
            value <- decode(valueJson)
            planJson <- c.get[Json]("plan")
            plan <- PlanDecoder.decode(planJson)
          } yield InSubquery(Seq(value), ListQuery(plan))

        // === Window functions ===
        case "protocatalyst.expr.ProtoExpr.RowNumber" =>
          success(RowNumber())

        case "protocatalyst.expr.ProtoExpr.Rank" =>
          success(Rank(Nil))

        case "protocatalyst.expr.ProtoExpr.DenseRank" =>
          success(DenseRank(Nil))

        case "protocatalyst.expr.ProtoExpr.Ntile" =>
          for {
            nJson <- c.get[Json]("n")
            n <- decode(nJson)
          } yield NTile(n)

        case "protocatalyst.expr.ProtoExpr.Lead" =>
          for {
            inputJson <- c.get[Json]("input")
            input <- decode(inputJson)
            offsetJson <- c.get[Json]("offset")
            offset <- decode(offsetJson)
            defaultJson <- c.get[Option[Json]]("default")
            default <- defaultJson.map(decode).sequence
          } yield Lead(input, offset, default.getOrElse(Literal(null)), false)

        case "protocatalyst.expr.ProtoExpr.Lag" =>
          for {
            inputJson <- c.get[Json]("input")
            input <- decode(inputJson)
            offsetJson <- c.get[Json]("offset")
            offset <- decode(offsetJson)
            defaultJson <- c.get[Option[Json]]("default")
            default <- defaultJson.map(decode).sequence
          } yield Lag(input, offset, default.getOrElse(Literal(null)), false)

        case "protocatalyst.expr.ProtoExpr.FirstValue" =>
          for {
            inputJson <- c.get[Json]("input")
            input <- decode(inputJson)
            ignoreNulls <- c.get[Boolean]("ignoreNulls")
          } yield AggregateExpression(First(input, ignoreNulls), Complete, false, None)

        case "protocatalyst.expr.ProtoExpr.LastValue" =>
          for {
            inputJson <- c.get[Json]("input")
            input <- decode(inputJson)
            ignoreNulls <- c.get[Boolean]("ignoreNulls")
          } yield AggregateExpression(Last(input, ignoreNulls), Complete, false, None)

        case "protocatalyst.expr.ProtoExpr.NthValue" =>
          for {
            inputJson <- c.get[Json]("input")
            input <- decode(inputJson)
            nJson <- c.get[Json]("n")
            n <- decode(nJson)
          } yield NthValue(input, n, false)

        case "protocatalyst.expr.ProtoExpr.WindowExpr" =>
          for {
            funcJson <- c.get[Json]("function")
            func <- decode(funcJson)
            partJson <- c.get[Vector[Json]]("partitionSpec")
            partitionSpec <- decodeExprs(partJson)
            orderJson <- c.get[Vector[Json]]("orderSpec")
            orderSpec <- decodeSortOrders(orderJson)
            frameJson <- c.get[Option[Json]]("frameSpec")
            frame <- frameJson.map(decodeWindowFrame).sequence
          } yield {
            val windowFrame = frame.getOrElse(UnspecifiedFrame)
            val spec = WindowSpecDefinition(partitionSpec, orderSpec, windowFrame)
            WindowExpression(func, spec)
          }

        // === Date/Time functions ===
        case "protocatalyst.expr.ProtoExpr.CurrentDate" =>
          success(CurrentDate())

        case "protocatalyst.expr.ProtoExpr.CurrentTimestamp" =>
          success(CurrentTimestamp())

        case "protocatalyst.expr.ProtoExpr.DateAdd" =>
          for {
            startJson <- c.get[Json]("start")
            start <- decode(startJson)
            daysJson <- c.get[Json]("days")
            days <- decode(daysJson)
          } yield DateAdd(start, days)

        case "protocatalyst.expr.ProtoExpr.DateSub" =>
          for {
            startJson <- c.get[Json]("start")
            start <- decode(startJson)
            daysJson <- c.get[Json]("days")
            days <- decode(daysJson)
          } yield DateSub(start, days)

        case "protocatalyst.expr.ProtoExpr.DateDiff" =>
          for {
            endJson <- c.get[Json]("end")
            end <- decode(endJson)
            startJson <- c.get[Json]("start")
            start <- decode(startJson)
          } yield DateDiff(end, start)

        case "protocatalyst.expr.ProtoExpr.Extract" =>
          for {
            field <- c.get[String]("field")
            sourceJson <- c.get[Json]("source")
            source <- decode(sourceJson)
          } yield UnresolvedFunction(
            Seq("extract"),
            Seq(Literal(UTF8String.fromString(field.toLowerCase), StringType), source),
            false
          )

        case "protocatalyst.expr.ProtoExpr.DateTrunc" =>
          for {
            field <- c.get[String]("field")
            tsJson <- c.get[Json]("timestamp")
            ts <- decode(tsJson)
          } yield TruncTimestamp(Literal(UTF8String.fromString(field.toLowerCase), StringType), ts)

        case "protocatalyst.expr.ProtoExpr.ToDate" =>
          for {
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            fmtJson <- c.get[Option[Json]]("format")
            fmt <- fmtJson.map(decode).sequence
          } yield fmt match {
            case Some(f) =>
              UnresolvedFunction(Seq("to_date"), Seq(str, f), false)
            case None =>
              UnresolvedFunction(Seq("to_date"), Seq(str), false)
          }

        case "protocatalyst.expr.ProtoExpr.ToTimestamp" =>
          for {
            strJson <- c.get[Json]("str")
            str <- decode(strJson)
            fmtJson <- c.get[Option[Json]]("format")
            fmt <- fmtJson.map(decode).sequence
          } yield fmt match {
            case Some(f) =>
              UnresolvedFunction(Seq("to_timestamp"), Seq(str, f), false)
            case None =>
              UnresolvedFunction(Seq("to_timestamp"), Seq(str), false)
          }

        case "protocatalyst.expr.ProtoExpr.Year" =>
          decodeUnary(c, child => Year(child))

        case "protocatalyst.expr.ProtoExpr.Month" =>
          decodeUnary(c, child => Month(child))

        case "protocatalyst.expr.ProtoExpr.DayOfMonth" =>
          decodeUnary(c, child => DayOfMonth(child))

        case "protocatalyst.expr.ProtoExpr.Hour" =>
          decodeUnary(c, child => Hour(child))

        case "protocatalyst.expr.ProtoExpr.Minute" =>
          decodeUnary(c, child => Minute(child))

        case "protocatalyst.expr.ProtoExpr.Second" =>
          decodeUnary(c, child => Second(child))

        // === Grouping ===
        case "protocatalyst.expr.ProtoExpr.Grouping" =>
          for {
            columnsJson <- c.get[Vector[Json]]("columns")
            columns <- decodeExprs(columnsJson)
          } yield {
            // Grouping takes a single child; for multiple columns use GroupingID
            if (columns.size == 1) Grouping(columns.head)
            else GroupingID(columns)
          }

        // === Opaque function call ===
        case "protocatalyst.expr.ProtoExpr.OpaqueCall" =>
          for {
            functionName <- c.get[String]("functionName")
            argsJson <- c.get[Vector[Json]]("arguments")
            args <- decodeExprs(argsJson)
          } yield UnresolvedFunction(Seq(functionName), args, false)

        case other =>
          failure(s"Unknown ProtoExpr type: $other", c.history)
      }
    }
  }

  /** Normalize short literal value type name */
  private def normalizeLiteralType(shortName: String): String = {
    if (shortName.contains(".")) {
      // Handle both protocatalyst.expr.LiteralValue and protocatalyst.types.LiteralValue
      shortName.replace("protocatalyst.expr.LiteralValue", "protocatalyst.types.LiteralValue")
    } else {
      s"protocatalyst.types.LiteralValue.$shortName"
    }
  }

  private def decodeLiteral(c: HCursor): EitherResult[Literal] = {
    c.get[Json]("value").flatMap { valueJson =>
      val vc = valueJson.hcursor
      vc.get[String]("$type").flatMap { rawLitType =>
        val litType = normalizeLiteralType(rawLitType)
        litType match {
          case "protocatalyst.types.LiteralValue.BooleanValue" =>
            vc.get[Boolean]("value").map(Literal(_))

          case "protocatalyst.types.LiteralValue.ByteValue" =>
            vc.get[Byte]("value").map(Literal(_))

          case "protocatalyst.types.LiteralValue.ShortValue" =>
            vc.get[Short]("value").map(Literal(_))

          case "protocatalyst.types.LiteralValue.IntValue" =>
            vc.get[Int]("value").map(Literal(_))

          case "protocatalyst.types.LiteralValue.LongValue" =>
            vc.get[Long]("value").map(Literal(_))

          case "protocatalyst.types.LiteralValue.FloatValue" =>
            vc.get[Float]("value").map(Literal(_))

          case "protocatalyst.types.LiteralValue.DoubleValue" =>
            vc.get[Double]("value").map(Literal(_))

          case "protocatalyst.types.LiteralValue.StringValue" =>
            vc.get[String]("value").map(v => Literal(UTF8String.fromString(v), StringType))

          case "protocatalyst.types.LiteralValue.BinaryValue" =>
            vc.get[String]("value").map { base64 =>
              val bytes = java.util.Base64.getDecoder.decode(base64)
              Literal(bytes)
            }

          case "protocatalyst.types.LiteralValue.DecimalValue" =>
            vc.get[String]("value").map { str =>
              val decimal = new java.math.BigDecimal(str)
              Literal(Decimal(decimal))
            }

          case "protocatalyst.types.LiteralValue.DateValue" =>
            vc.get[Int]("epochDays").map(Literal(_, DateType))

          case "protocatalyst.types.LiteralValue.TimestampValue" =>
            vc.get[Long]("epochMicros").map(Literal(_, TimestampType))

          case "protocatalyst.types.LiteralValue.NullValue" =>
            vc.get[Json]("dataType").flatMap { dtJson =>
              TypeDecoder.decode(dtJson).map(dt => Literal(null, dt))
            }

          case other =>
            failure(s"Unknown LiteralValue type: $other", vc.history)
        }
      }
    }
  }

  private def decodeUnary(c: HCursor, f: Expression => Expression): EitherResult[Expression] = {
    for {
      childJson <- c.get[Json]("child")
      child <- decode(childJson)
    } yield f(child)
  }

  private def decodeBinary(
      c: HCursor,
      f: (Expression, Expression) => Expression
  ): EitherResult[Expression] = {
    for {
      leftJson <- c.get[Json]("left")
      left <- decode(leftJson)
      rightJson <- c.get[Json]("right")
      right <- decode(rightJson)
    } yield f(left, right)
  }

  def decodeExprs(jsons: Vector[Json]): EitherResult[Seq[Expression]] = {
    var result: Vector[Expression] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decode(iter.next()) match {
        case scala.Right(expr) => result = result :+ expr
        case scala.Left(err)   => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None      => scala.Right(result)
    }
  }

  private def decodeBranches(jsons: Vector[Json]): EitherResult[Seq[(Expression, Expression)]] = {
    var result: Vector[(Expression, Expression)] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decodeBranch(iter.next().hcursor) match {
        case scala.Right(branch) => result = result :+ branch
        case scala.Left(err)     => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None      => scala.Right(result)
    }
  }

  private def decodeBranch(c: HCursor): EitherResult[(Expression, Expression)] = {
    // Branches are stored as tuples which upickle serializes as arrays
    c.focus.flatMap(_.asArray) match {
      case Some(arr) if arr.length == 2 =>
        for {
          when <- decode(arr(0))
          thenVal <- decode(arr(1))
        } yield (when, thenVal)
      case _ =>
        failure("Expected array of 2 elements for branch", c.history)
    }
  }

  private def decodeSortOrders(jsons: Vector[Json]): EitherResult[Seq[SortOrder]] = {
    var result: Vector[SortOrder] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decodeSortOrder(iter.next().hcursor) match {
        case scala.Right(so) => result = result :+ so
        case scala.Left(err) => error = Some(err)
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
      child <- decode(childJson)
      dir <- c.get[String]("direction")
      nullOrd <- c.get[String]("nullOrdering")
    } yield {
      val direction = dir match {
        case "Descending" => Descending
        case _            => Ascending
      }
      val nullOrdering = nullOrd match {
        case "NullsLast"  => NullsLast
        case "NullsFirst" => NullsFirst
        case _            =>
          if (direction == Ascending) NullsFirst else NullsLast
      }
      SortOrder(child, direction, nullOrdering, Seq.empty)
    }
  }

  private def decodeWindowFrame(json: Json): EitherResult[WindowFrame] = {
    val c = json.hcursor
    for {
      frameType <- c.get[String]("frameType")
      lowerJson <- c.get[Json]("lower")
      lower <- decodeFrameBound(lowerJson)
      upperJson <- c.get[Json]("upper")
      upper <- decodeFrameBound(upperJson)
    } yield {
      val ft = frameType match {
        case "Range" => RangeFrame
        case _       => RowFrame
      }
      SpecifiedWindowFrame(ft, lower, upper)
    }
  }

  private def decodeFrameBound(json: Json): EitherResult[Expression] = {
    val c = json.hcursor
    c.get[String]("$type").flatMap {
      case t if t.endsWith("UnboundedPreceding") => success(UnboundedPreceding)
      case t if t.endsWith("UnboundedFollowing") => success(UnboundedFollowing)
      case t if t.endsWith("CurrentRow")         => success(CurrentRow)
      case t if t.endsWith("Preceding")          =>
        c.get[Long]("n").map(n => UnaryMinus(Literal(n.toInt)))
      case t if t.endsWith("Following") =>
        c.get[Long]("n").map(n => Literal(n.toInt))
      case other =>
        failure(s"Unknown FrameBound type: $other", c.history)
    }
  }

  // Helper for Option[EitherResult].sequence
  implicit class OptionOps[A](opt: Option[EitherResult[A]]) {
    def sequence: EitherResult[Option[A]] = opt match {
      case None         => scala.Right(None)
      case Some(either) => either.map(Some(_))
    }
  }
}
