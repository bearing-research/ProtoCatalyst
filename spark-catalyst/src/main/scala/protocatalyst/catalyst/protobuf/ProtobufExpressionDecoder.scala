package protocatalyst.catalyst.protobuf

import io.protocatalyst.proto.{v1 => pb}
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAttribute, UnresolvedFunction}
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate._
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.{CalendarInterval, UTF8String}

import scala.collection.JavaConverters._

/** Decodes protobuf ProtoExprMsg to Spark Expression.
  *
  * Mirrors the JSON ExpressionDecoder but reads from protobuf messages using getXxxCase() pattern
  * matching instead of circe JSON parsing.
  */
object ProtobufExpressionDecoder {

  def decode(msg: pb.ProtoExprMsg): Expression = {
    import pb.ProtoExprMsg.ExprCase._
    msg.getExprCase match {
      // === Leaf nodes ===
      case LITERAL =>
        decodeLiteral(msg.getLiteral.getValue)

      case COLUMN_REF =>
        val ref = msg.getColumnRef
        if (ref.hasQualifier) UnresolvedAttribute(Seq(ref.getQualifier, ref.getName))
        else UnresolvedAttribute(Seq(ref.getName))

      case BOUND_REF =>
        val ref = msg.getBoundRef
        val dataType = ProtobufTypeDecoder.decode(ref.getDataType)
        BoundReference(ref.getIndex, dataType, ref.getNullable)

      // === Comparison ===
      case EQ =>
        decodeBinary(msg.getEq, EqualTo.apply)

      case NOT_EQ =>
        decodeBinary(msg.getNotEq, (l, r) => Not(EqualTo(l, r)))

      case LT =>
        decodeBinary(msg.getLt, LessThan.apply)

      case LT_EQ =>
        decodeBinary(msg.getLtEq, LessThanOrEqual.apply)

      case GT =>
        decodeBinary(msg.getGt, GreaterThan.apply)

      case GT_EQ =>
        decodeBinary(msg.getGtEq, GreaterThanOrEqual.apply)

      // === Logical ===
      case AND =>
        val children = decodeNary(msg.getAnd)
        children.reduceLeft[Expression](And(_, _))

      case OR =>
        val children = decodeNary(msg.getOr)
        children.reduceLeft[Expression](Or(_, _))

      case NOT =>
        decodeUnary(msg.getNot, Not.apply)

      // === Null handling ===
      case IS_NULL =>
        decodeUnary(msg.getIsNull, IsNull.apply)

      case IS_NOT_NULL =>
        decodeUnary(msg.getIsNotNull, IsNotNull.apply)

      case COALESCE =>
        val children = decodeNary(msg.getCoalesce)
        Coalesce(children)

      case NULL_IF =>
        decodeBinary(msg.getNullIf, (l, r) => UnresolvedFunction(Seq("nullif"), Seq(l, r), false))

      // === Arithmetic ===
      case ADD =>
        decodeBinary(msg.getAdd, (l, r) => Add(l, r, EvalMode.LEGACY))

      case SUBTRACT =>
        decodeBinary(msg.getSubtract, (l, r) => Subtract(l, r, EvalMode.LEGACY))

      case MULTIPLY =>
        decodeBinary(msg.getMultiply, (l, r) => Multiply(l, r, EvalMode.LEGACY))

      case DIVIDE =>
        decodeBinary(msg.getDivide, (l, r) => Divide(l, r, EvalMode.LEGACY))

      // === Math functions ===
      case ABS =>
        decodeUnary(msg.getAbs, child => Abs(child, failOnError = false))

      case CEIL =>
        decodeUnary(msg.getCeil, child => Ceil(child))

      case FLOOR =>
        decodeUnary(msg.getFloor, child => Floor(child))

      case ROUND =>
        val m = msg.getRound
        val child = decode(m.getLeft)
        val scale = decode(m.getRight)
        Round(child, scale)

      case TRUNCATE =>
        val m = msg.getTruncate
        val child = decode(m.getLeft)
        val scale = decode(m.getRight)
        UnresolvedFunction(Seq("truncate"), Seq(child, scale), false)

      case SQRT =>
        decodeUnary(msg.getSqrt, child => Sqrt(child))

      case CBRT =>
        decodeUnary(msg.getCbrt, child => Cbrt(child))

      case POW =>
        decodeBinary(msg.getPow, Pow.apply)

      case PMOD =>
        decodeBinary(msg.getPmod, (l, r) => Pmod(l, r))

      case SIGN =>
        decodeUnary(msg.getSign, child => Signum(child))

      case LOG =>
        val m = msg.getLog
        val child = decode(m.getChild)
        if (m.hasBase) {
          val base = decode(m.getBase)
          Logarithm(base, child)
        } else {
          Log(child)
        }

      case EXP =>
        decodeUnary(msg.getExp, child => Exp(child))

      // === String functions ===
      case CONCAT =>
        val children = decodeNary(msg.getConcat)
        Concat(children)

      case SUBSTRING =>
        val m = msg.getSubstring
        val str = decode(m.getStr)
        val pos = decode(m.getPos)
        val len = decode(m.getLen)
        Substring(str, pos, len)

      case UPPER =>
        decodeUnary(msg.getUpper, Upper.apply)

      case LOWER =>
        decodeUnary(msg.getLower, Lower.apply)

      case TRIM =>
        val m = msg.getTrim
        val child = decode(m.getChild)
        val trimStr = if (m.hasTrimStr) Some(decode(m.getTrimStr)) else None
        m.getTrimType match {
          case pb.TrimTypeEnum.TRIM_TYPE_LEADING  => StringTrimLeft(child, trimStr)
          case pb.TrimTypeEnum.TRIM_TYPE_TRAILING => StringTrimRight(child, trimStr)
          case _                                  => StringTrim(child, trimStr)
        }

      case LENGTH =>
        decodeUnary(msg.getLength, child => Length(child))

      case REPLACE =>
        val m = msg.getReplace
        val str = decode(m.getFirst)
        val search = decode(m.getSecond)
        val replace = decode(m.getThird)
        StringReplace(str, search, replace)

      case STRING_LOCATE =>
        val m = msg.getStringLocate
        val substr = decode(m.getSubstr)
        val str = decode(m.getStr)
        val start = if (m.hasStart) decode(m.getStart) else Literal(1)
        StringLocate(substr, str, start)

      case LPAD =>
        val m = msg.getLpad
        val str = decode(m.getFirst)
        val len = decode(m.getSecond)
        val pad = decode(m.getThird)
        StringLPad(str, len, pad)

      case RPAD =>
        val m = msg.getRpad
        val str = decode(m.getFirst)
        val len = decode(m.getSecond)
        val pad = decode(m.getThird)
        StringRPad(str, len, pad)

      case STRING_SPLIT =>
        val m = msg.getStringSplit
        val str = decode(m.getStr)
        val delim = decode(m.getDelimiter)
        StringSplitSQL(str, delim)

      case REVERSE =>
        decodeUnary(msg.getReverse, child => Reverse(child))

      case STRING_REPEAT =>
        val m = msg.getStringRepeat
        val str = decode(m.getLeft)
        val times = decode(m.getRight)
        StringRepeat(str, times)

      // === Pattern matching ===
      case LIKE =>
        val m = msg.getLike
        val value = decode(m.getValue)
        val pattern = decode(m.getPattern)
        Like(value, pattern, '\\')

      // === Aggregates ===
      case COUNT =>
        val m = msg.getCount
        val child = if (m.hasChild) decode(m.getChild) else Literal(1)
        val distinct = m.getDistinct
        AggregateExpression(Count(Seq(child)), Complete, distinct, None)

      case SUM =>
        decodeUnary(msg.getSum, child => AggregateExpression(Sum(child), Complete, false, None))

      case AVG =>
        decodeUnary(msg.getAvg, child => AggregateExpression(Average(child), Complete, false, None))

      case MIN_EXPR =>
        decodeUnary(msg.getMinExpr, child => AggregateExpression(Min(child), Complete, false, None))

      case MAX_EXPR =>
        decodeUnary(msg.getMaxExpr, child => AggregateExpression(Max(child), Complete, false, None))

      // === Control flow ===
      case CASE_WHEN =>
        val m = msg.getCaseWhen
        val branches = m.getBranchesList.asScala.map { branch =>
          (decode(branch.getCondition), decode(branch.getValue))
        }.toSeq
        val elseValue = if (m.hasElseValue) Some(decode(m.getElseValue)) else None
        CaseWhen(branches, elseValue)

      case IF_EXPR =>
        val m = msg.getIfExpr
        val predicate = decode(m.getPredicate)
        val trueValue = decode(m.getTrueValue)
        val falseValue = decode(m.getFalseValue)
        If(predicate, trueValue, falseValue)

      case IN_EXPR =>
        val m = msg.getInExpr
        val value = decode(m.getValue)
        val list = m.getListList.asScala.map(decode).toSeq
        In(value, list)

      // === Cast and Alias ===
      case CAST =>
        val m = msg.getCast
        val child = decode(m.getChild)
        val targetType = ProtobufTypeDecoder.decode(m.getTargetType)
        Cast(child, targetType)

      case ALIAS =>
        val m = msg.getAlias
        val child = decode(m.getChild)
        val name = m.getName
        Alias(child, name)()

      // === Subquery expressions ===
      case SCALAR_SUBQUERY =>
        val plan = ProtobufPlanDecoder.decode(msg.getScalarSubquery.getPlan)
        ScalarSubquery(plan)

      case EXISTS =>
        val plan = ProtobufPlanDecoder.decode(msg.getExists.getPlan)
        Exists(plan)

      case IN_SUBQUERY =>
        val m = msg.getInSubquery
        val value = decode(m.getValue)
        val plan = ProtobufPlanDecoder.decode(m.getPlan)
        InSubquery(Seq(value), ListQuery(plan))

      // === Window functions ===
      case ROW_NUMBER =>
        RowNumber()

      case RANK =>
        Rank(Nil)

      case DENSE_RANK =>
        DenseRank(Nil)

      case NTILE =>
        val n = decode(msg.getNtile.getChild)
        NTile(n)

      case LEAD =>
        val m = msg.getLead
        val input = decode(m.getInput)
        val offset = decode(m.getOffset)
        val default = if (m.hasDefaultValue) decode(m.getDefaultValue) else Literal(null)
        Lead(input, offset, default, false)

      case LAG =>
        val m = msg.getLag
        val input = decode(m.getInput)
        val offset = decode(m.getOffset)
        val default = if (m.hasDefaultValue) decode(m.getDefaultValue) else Literal(null)
        Lag(input, offset, default, false)

      case FIRST_VALUE =>
        val m = msg.getFirstValue
        val input = decode(m.getInput)
        val ignoreNulls = m.getIgnoreNulls
        AggregateExpression(First(input, ignoreNulls), Complete, false, None)

      case LAST_VALUE =>
        val m = msg.getLastValue
        val input = decode(m.getInput)
        val ignoreNulls = m.getIgnoreNulls
        AggregateExpression(Last(input, ignoreNulls), Complete, false, None)

      case NTH_VALUE =>
        val m = msg.getNthValue
        val input = decode(m.getLeft)
        val n = decode(m.getRight)
        NthValue(input, n, false)

      case WINDOW_EXPR =>
        val m = msg.getWindowExpr
        val func = decode(m.getFunction)
        val partitionSpec = m.getPartitionSpecList.asScala.map(decode).toSeq
        val orderSpec = m.getOrderSpecList.asScala.map(decodeSortOrder).toSeq
        val windowFrame =
          if (m.hasFrameSpec) decodeWindowFrame(m.getFrameSpec) else UnspecifiedFrame
        val spec = WindowSpecDefinition(partitionSpec, orderSpec, windowFrame)
        WindowExpression(func, spec)

      // === Date/Time functions ===
      case CURRENT_DATE =>
        CurrentDate()

      case CURRENT_TIMESTAMP =>
        CurrentTimestamp()

      case DATE_ADD =>
        val m = msg.getDateAdd
        val start = decode(m.getLeft)
        val days = decode(m.getRight)
        DateAdd(start, days)

      case DATE_SUB =>
        val m = msg.getDateSub
        val start = decode(m.getLeft)
        val days = decode(m.getRight)
        DateSub(start, days)

      case DATE_DIFF =>
        val m = msg.getDateDiff
        val end = decode(m.getLeft)
        val start = decode(m.getRight)
        DateDiff(end, start)

      case EXTRACT =>
        val m = msg.getExtract
        val fieldName = dateTimeFieldName(m.getField)
        val source = decode(m.getSource)
        UnresolvedFunction(
          Seq("extract"),
          Seq(Literal(UTF8String.fromString(fieldName.toLowerCase), StringType), source),
          false
        )

      case DATE_TRUNC =>
        val m = msg.getDateTrunc
        val fieldName = dateTimeFieldName(m.getField)
        val ts = decode(m.getTimestamp)
        TruncTimestamp(Literal(UTF8String.fromString(fieldName.toLowerCase), StringType), ts)

      case TO_DATE =>
        val m = msg.getToDate
        val str = decode(m.getStr)
        if (m.hasFormat) {
          val fmt = decode(m.getFormat)
          UnresolvedFunction(Seq("to_date"), Seq(str, fmt), false)
        } else {
          UnresolvedFunction(Seq("to_date"), Seq(str), false)
        }

      case TO_TIMESTAMP =>
        val m = msg.getToTimestamp
        val str = decode(m.getStr)
        if (m.hasFormat) {
          val fmt = decode(m.getFormat)
          UnresolvedFunction(Seq("to_timestamp"), Seq(str, fmt), false)
        } else {
          UnresolvedFunction(Seq("to_timestamp"), Seq(str), false)
        }

      case YEAR =>
        decodeUnary(msg.getYear, child => Year(child))

      case MONTH =>
        decodeUnary(msg.getMonth, child => Month(child))

      case DAY_OF_MONTH =>
        decodeUnary(msg.getDayOfMonth, child => DayOfMonth(child))

      case HOUR =>
        decodeUnary(msg.getHour, child => Hour(child))

      case MINUTE =>
        decodeUnary(msg.getMinute, child => Minute(child))

      case SECOND =>
        decodeUnary(msg.getSecond, child => Second(child))

      // === Grouping ===
      case GROUPING =>
        val children = decodeNary(msg.getGrouping)
        if (children.size == 1) Grouping(children.head)
        else GroupingID(children)

      // === Generator expressions ===
      case EXPLODE =>
        decodeUnary(msg.getExplode, child => Explode(child))

      case POS_EXPLODE =>
        decodeUnary(msg.getPosExplode, child => PosExplode(child))

      case INLINE_EXPR =>
        decodeUnary(
          msg.getInlineExpr,
          child => org.apache.spark.sql.catalyst.expressions.Inline(child)
        )

      case STACK =>
        val m = msg.getStack
        val numRows = decode(m.getNumRows)
        val children = m.getChildrenList.asScala.map(decode).toSeq
        UnresolvedFunction(Seq("stack"), numRows +: children, false)

      // === Opaque function call ===
      case OPAQUE_CALL =>
        val m = msg.getOpaqueCall
        val functionName = m.getFunctionName
        val args = m.getArgumentsList.asScala.map(decode).toSeq
        UnresolvedFunction(Seq(functionName), args, false)

      case EXPR_NOT_SET =>
        throw new IllegalArgumentException("ProtoExprMsg expr not set")
    }
  }

  /** Decode a list of ProtoExprMsg to a Seq of Expression. */
  def decodeExprs(msgs: java.util.List[pb.ProtoExprMsg]): Seq[Expression] = {
    msgs.asScala.map(decode).toSeq
  }

  // ---------------------------------------------------------------------------
  // Literal decoding
  // ---------------------------------------------------------------------------

  private def decodeLiteral(msg: pb.LiteralValueMsg): Literal = {
    import pb.LiteralValueMsg.ValueCase._
    msg.getValueCase match {
      case BOOLEAN_VALUE =>
        Literal(msg.getBooleanValue)

      case BYTE_VALUE =>
        Literal(msg.getByteValue.toByte, ByteType)

      case SHORT_VALUE =>
        Literal(msg.getShortValue.toShort, ShortType)

      case INT_VALUE =>
        Literal(msg.getIntValue)

      case LONG_VALUE =>
        Literal(msg.getLongValue)

      case FLOAT_VALUE =>
        Literal(msg.getFloatValue)

      case DOUBLE_VALUE =>
        Literal(msg.getDoubleValue)

      case STRING_VALUE =>
        Literal(UTF8String.fromString(msg.getStringValue), StringType)

      case BINARY_VALUE =>
        Literal(msg.getBinaryValue.toByteArray)

      case DECIMAL_VALUE =>
        val decimal = new java.math.BigDecimal(msg.getDecimalValue)
        Literal(Decimal(decimal))

      case DATE_VALUE =>
        Literal(msg.getDateValue, DateType)

      case TIMESTAMP_VALUE =>
        Literal(msg.getTimestampValue, TimestampType)

      case TIME_VALUE =>
        Literal(msg.getTimeValue, LongType)

      case CALENDAR_INTERVAL_VALUE =>
        val ci = msg.getCalendarIntervalValue
        Literal(new CalendarInterval(ci.getMonths, ci.getDays, ci.getMicroseconds))

      case NULL_VALUE =>
        val dataType = ProtobufTypeDecoder.decode(msg.getNullValue)
        Literal(null, dataType)

      case VALUE_NOT_SET =>
        throw new IllegalArgumentException("LiteralValueMsg value not set")
    }
  }

  // ---------------------------------------------------------------------------
  // Helper decoders for message shape patterns
  // ---------------------------------------------------------------------------

  private def decodeUnary(msg: pb.UnaryExprMsg, f: Expression => Expression): Expression = {
    f(decode(msg.getChild))
  }

  private def decodeBinary(
      msg: pb.BinaryExprMsg,
      f: (Expression, Expression) => Expression
  ): Expression = {
    f(decode(msg.getLeft), decode(msg.getRight))
  }

  private def decodeNary(msg: pb.NaryExprMsg): Seq[Expression] = {
    msg.getChildrenList.asScala.map(decode).toSeq
  }

  // ---------------------------------------------------------------------------
  // SortOrder decoding (for WindowExpr)
  // ---------------------------------------------------------------------------

  private def decodeSortOrder(msg: pb.SortOrderMsg): SortOrder = {
    val child = decode(msg.getChild)
    val direction = msg.getDirection match {
      case pb.SortDirectionEnum.SORT_DIRECTION_ASCENDING  => Ascending
      case pb.SortDirectionEnum.SORT_DIRECTION_DESCENDING => Descending
      case _                                              => Ascending
    }
    val nullOrdering = msg.getNullOrdering match {
      case pb.NullOrderingEnum.NULL_ORDERING_NULLS_FIRST => NullsFirst
      case pb.NullOrderingEnum.NULL_ORDERING_NULLS_LAST  => NullsLast
      case _                                             => NullsFirst
    }
    SortOrder(child, direction, nullOrdering, Seq.empty)
  }

  // ---------------------------------------------------------------------------
  // Window frame decoding
  // ---------------------------------------------------------------------------

  private def decodeWindowFrame(msg: pb.WindowFrameMsg): WindowFrame = {
    val frameType = msg.getFrameType match {
      case pb.FrameTypeEnum.FRAME_TYPE_RANGE => RangeFrame
      case _                                 => RowFrame
    }
    val lower = decodeFrameBound(msg.getLower)
    val upper = decodeFrameBound(msg.getUpper)
    SpecifiedWindowFrame(frameType, lower, upper)
  }

  private def decodeFrameBound(msg: pb.FrameBoundMsg): Expression = {
    import pb.FrameBoundMsg.BoundCase._
    msg.getBoundCase match {
      case UNBOUNDED_PRECEDING => UnboundedPreceding
      case UNBOUNDED_FOLLOWING => UnboundedFollowing
      case CURRENT_ROW         => CurrentRow
      case PRECEDING           => UnaryMinus(Literal(msg.getPreceding.toInt))
      case FOLLOWING           => Literal(msg.getFollowing.toInt)
      case BOUND_NOT_SET       =>
        throw new IllegalArgumentException("FrameBoundMsg bound not set")
    }
  }

  // ---------------------------------------------------------------------------
  // DateTimeField name helper
  // ---------------------------------------------------------------------------

  private def dateTimeFieldName(field: pb.DateTimeFieldEnum): String = field match {
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_YEAR        => "YEAR"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_MONTH       => "MONTH"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY         => "DAY"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_HOUR        => "HOUR"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_MINUTE      => "MINUTE"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_SECOND      => "SECOND"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_QUARTER     => "QUARTER"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_WEEK        => "WEEK"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY_OF_WEEK => "DAYOFWEEK"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_DAY_OF_YEAR => "DAYOFYEAR"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_MICROSECOND => "MICROSECOND"
    case pb.DateTimeFieldEnum.DATE_TIME_FIELD_MILLISECOND => "MILLISECOND"
    case _                                                => "YEAR"
  }
}
