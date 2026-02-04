package protocatalyst.catalyst.json

import io.circe.{DecodingFailure, HCursor, Json}
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAttribute, UnresolvedFunction}
import org.apache.spark.sql.catalyst.expressions.aggregate.{
  AggregateExpression,
  Average,
  Complete,
  Count,
  Max,
  Min,
  Sum
}
import org.apache.spark.sql.catalyst.expressions.{
  Add,
  Alias,
  And,
  BoundReference,
  CaseWhen,
  Cast,
  Coalesce,
  Concat,
  EqualTo,
  EvalMode,
  Expression,
  GreaterThan,
  GreaterThanOrEqual,
  If,
  In,
  IsNotNull,
  IsNull,
  LessThan,
  LessThanOrEqual,
  Literal,
  Lower,
  Multiply,
  Not,
  Or,
  Substring,
  Subtract,
  Upper
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
            (l, r) => org.apache.spark.sql.catalyst.expressions.Divide(l, r, EvalMode.LEGACY)
          )

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

  // Helper for Option[EitherResult].sequence
  implicit class OptionOps[A](opt: Option[EitherResult[A]]) {
    def sequence: EitherResult[Option[A]] = opt match {
      case None         => scala.Right(None)
      case Some(either) => either.map(Some(_))
    }
  }
}
