package protocatalyst.mock

import protocatalyst.expr._
import protocatalyst.types._

/** Evaluates ProtoExpr against MockRow data. Used for testing canonicalization and constant
  * folding.
  */
object ExpressionEvaluator:

  sealed trait EvalResult
  case class Success(value: Any) extends EvalResult
  case class EvalError(message: String) extends EvalResult

  def eval(expr: ProtoExpr, row: MockRow = MockRow.empty): EvalResult =
    try Success(evalInternal(expr, row))
    catch case e: Exception => EvalError(e.getMessage)

  private def evalInternal(expr: ProtoExpr, row: MockRow): Any =
    import ProtoExpr.*
    expr match
      // Literals
      case Literal(LiteralValue.BooleanValue(v))                => v
      case Literal(LiteralValue.ByteValue(v))                   => v
      case Literal(LiteralValue.ShortValue(v))                  => v
      case Literal(LiteralValue.IntValue(v))                    => v
      case Literal(LiteralValue.LongValue(v))                   => v
      case Literal(LiteralValue.FloatValue(v))                  => v
      case Literal(LiteralValue.DoubleValue(v))                 => v
      case Literal(LiteralValue.StringValue(v))                 => v
      case Literal(LiteralValue.BinaryValue(v))                 => v
      case Literal(LiteralValue.DecimalValue(v))                => v
      case Literal(LiteralValue.DateValue(v))                   => v
      case Literal(LiteralValue.TimestampValue(v))              => v
      case Literal(LiteralValue.TimeValue(v))                   => v
      case Literal(LiteralValue.CalendarIntervalValue(m, d, u)) => (m, d, u)
      case Literal(LiteralValue.NullValue(_))                   => null

      // Bound references
      case BoundRef(ordinal, _, _) => row.get(ordinal)

      // Comparisons
      case Eq(l, r) =>
        val lv = evalInternal(l, row)
        val rv = evalInternal(r, row)
        if lv == null || rv == null then null else lv == rv

      case NotEq(l, r) =>
        val lv = evalInternal(l, row)
        val rv = evalInternal(r, row)
        if lv == null || rv == null then null else lv != rv

      case Lt(l, r)   => compareNumeric(l, r, row, _ < _)
      case LtEq(l, r) => compareNumeric(l, r, row, _ <= _)
      case Gt(l, r)   => compareNumeric(l, r, row, _ > _)
      case GtEq(l, r) => compareNumeric(l, r, row, _ >= _)

      // Logical - three-valued logic
      case And(children) =>
        val results = children.map(evalInternal(_, row))
        if results.contains(false) then false
        else if results.contains(null) then null
        else true

      case Or(children) =>
        val results = children.map(evalInternal(_, row))
        if results.contains(true) then true
        else if results.contains(null) then null
        else false

      case Not(child) =>
        evalInternal(child, row) match
          case null       => null
          case b: Boolean => !b
          case other      => throw IllegalArgumentException(s"NOT requires boolean, got $other")

      // Null handling
      case IsNull(child)    => evalInternal(child, row) == null
      case IsNotNull(child) => evalInternal(child, row) != null

      case Coalesce(children) =>
        children.iterator.map(evalInternal(_, row)).find(_ != null).orNull

      case NullIf(l, r) =>
        val lv = evalInternal(l, row)
        val rv = evalInternal(r, row)
        if lv == rv then null else lv

      // Arithmetic
      case Add(l, r)      => numericOp(l, r, row, _ + _, _ + _, _ + _)
      case Subtract(l, r) => numericOp(l, r, row, _ - _, _ - _, _ - _)
      case Multiply(l, r) => numericOp(l, r, row, _ * _, _ * _, _ * _)
      case Divide(l, r)   =>
        val rv = evalInternal(r, row)
        if rv == 0 || rv == 0.0 || rv == 0L then null
        else numericOp(l, r, row, _ / _, _ / _, _ / _)

      // Math functions
      case Abs(child) =>
        evalInternal(child, row) match
          case null           => null
          case i: Int         => math.abs(i)
          case l: Long        => math.abs(l)
          case f: Float       => math.abs(f)
          case d: Double      => math.abs(d)
          case bd: BigDecimal => bd.abs
          case n: Number      => math.abs(n.doubleValue)

      case Ceil(child) =>
        evalInternal(child, row) match
          case null      => null
          case d: Double => math.ceil(d).toLong
          case f: Float  => math.ceil(f.toDouble).toLong
          case n: Number => math.ceil(n.doubleValue).toLong

      case Floor(child) =>
        evalInternal(child, row) match
          case null      => null
          case d: Double => math.floor(d).toLong
          case f: Float  => math.floor(f.toDouble).toLong
          case n: Number => math.floor(n.doubleValue).toLong

      case Round(child, scale) =>
        val v = evalInternal(child, row)
        val s = evalInternal(scale, row)
        if v == null || s == null then null
        else
          val scaleInt = toInt(s)
          val value = toDouble(v)
          BigDecimal(value).setScale(scaleInt, BigDecimal.RoundingMode.HALF_UP).toDouble

      case Truncate(child, scale) =>
        val v = evalInternal(child, row)
        val s = evalInternal(scale, row)
        if v == null || s == null then null
        else
          val scaleInt = toInt(s)
          val value = toDouble(v)
          BigDecimal(value).setScale(scaleInt, BigDecimal.RoundingMode.DOWN).toDouble

      case Sqrt(child) =>
        evalInternal(child, row) match
          case null      => null
          case n: Number => math.sqrt(n.doubleValue)

      case Cbrt(child) =>
        evalInternal(child, row) match
          case null      => null
          case n: Number => math.cbrt(n.doubleValue)

      case Pow(l, r) =>
        val lv = evalInternal(l, row)
        val rv = evalInternal(r, row)
        if lv == null || rv == null then null
        else math.pow(toDouble(lv), toDouble(rv))

      case Pmod(l, r) =>
        val lv = evalInternal(l, row)
        val rv = evalInternal(r, row)
        if lv == null || rv == null then null
        else
          val result = toDouble(lv) % toDouble(rv)
          if result < 0 then result + math.abs(toDouble(rv)) else result

      case Sign(child) =>
        evalInternal(child, row) match
          case null      => null
          case i: Int    => math.signum(i)
          case l: Long   => math.signum(l)
          case f: Float  => math.signum(f)
          case d: Double => math.signum(d)
          case n: Number => math.signum(n.doubleValue)

      case Log(child, base) =>
        val v = evalInternal(child, row)
        if v == null then null
        else
          val logValue = math.log(toDouble(v))
          base match
            case Some(b) =>
              val bv = evalInternal(b, row)
              if bv == null then null
              else logValue / math.log(toDouble(bv))
            case None => logValue

      case Exp(child) =>
        evalInternal(child, row) match
          case null      => null
          case n: Number => math.exp(n.doubleValue)

      // String operations
      case Concat(children) =>
        val parts = children.map(evalInternal(_, row))
        if parts.contains(null) then null
        else parts.mkString

      case Upper(child) =>
        evalInternal(child, row) match
          case null      => null
          case s: String => s.toUpperCase
          case other     => other.toString.toUpperCase

      case Lower(child) =>
        evalInternal(child, row) match
          case null      => null
          case s: String => s.toLowerCase
          case other     => other.toString.toLowerCase

      case Substring(str, pos, len) =>
        val s = evalInternal(str, row)
        val p = evalInternal(pos, row)
        val l = evalInternal(len, row)
        if s == null || p == null || l == null then null
        else
          val sStr = s.toString
          val pInt = toInt(p)
          val lInt = toInt(l)
          // Spark uses 1-based indexing
          val start = math.max(0, pInt - 1)
          val end = math.min(sStr.length, start + lInt)
          if start >= sStr.length then ""
          else sStr.substring(start, end)

      case Trim(child, trimStr, trimType) =>
        val s = evalInternal(child, row)
        if s == null then null
        else
          val str = s.toString
          val chars = trimStr.map(e => evalInternal(e, row)).getOrElse(" ").toString
          trimType match
            case TrimType.Both     => str.stripPrefix(chars).stripSuffix(chars)
            case TrimType.Leading  => str.stripPrefix(chars)
            case TrimType.Trailing => str.stripSuffix(chars)

      case Length(child) =>
        evalInternal(child, row) match
          case null      => null
          case s: String => s.length
          case other     => other.toString.length

      case Replace(str, search, replace) =>
        val s = evalInternal(str, row)
        val srch = evalInternal(search, row)
        val repl = evalInternal(replace, row)
        if s == null || srch == null || repl == null then null
        else s.toString.replace(srch.toString, repl.toString)

      case StringLocate(substr, str, start) =>
        val sub = evalInternal(substr, row)
        val s = evalInternal(str, row)
        if sub == null || s == null then null
        else
          val startPos = start.map(e => toInt(evalInternal(e, row)) - 1).getOrElse(0)
          val idx = s.toString.indexOf(sub.toString, startPos)
          if idx < 0 then 0 else idx + 1 // 1-based index, 0 if not found

      case Lpad(str, len, pad) =>
        val s = evalInternal(str, row)
        val l = evalInternal(len, row)
        val p = evalInternal(pad, row)
        if s == null || l == null || p == null then null
        else
          val sStr = s.toString
          val lInt = toInt(l)
          val pStr = p.toString
          if sStr.length >= lInt then sStr.take(lInt)
          else (pStr * ((lInt - sStr.length) / pStr.length + 1)).take(lInt - sStr.length) + sStr

      case Rpad(str, len, pad) =>
        val s = evalInternal(str, row)
        val l = evalInternal(len, row)
        val p = evalInternal(pad, row)
        if s == null || l == null || p == null then null
        else
          val sStr = s.toString
          val lInt = toInt(l)
          val pStr = p.toString
          if sStr.length >= lInt then sStr.take(lInt)
          else sStr + (pStr * ((lInt - sStr.length) / pStr.length + 1)).take(lInt - sStr.length)

      case StringSplit(str, delimiter, limit) =>
        val s = evalInternal(str, row)
        val d = evalInternal(delimiter, row)
        if s == null || d == null then null
        else
          val lim = limit.map(e => toInt(evalInternal(e, row))).getOrElse(-1)
          s.toString.split(java.util.regex.Pattern.quote(d.toString), lim).toSeq

      case Reverse(child) =>
        evalInternal(child, row) match
          case null      => null
          case s: String => s.reverse
          case other     => other.toString.reverse

      case StringRepeat(str, times) =>
        val s = evalInternal(str, row)
        val t = evalInternal(times, row)
        if s == null || t == null then null
        else s.toString * toInt(t)

      // Control flow
      case If(pred, trueVal, falseVal) =>
        evalInternal(pred, row) match
          case true  => evalInternal(trueVal, row)
          case false => evalInternal(falseVal, row)
          case null  => null
          case other => throw IllegalArgumentException(s"IF predicate must be boolean, got $other")

      case CaseWhen(branches, elseValue) =>
        branches.find { case (cond, _) =>
          evalInternal(cond, row) == true
        } match
          case Some((_, result)) => evalInternal(result, row)
          case None              => elseValue.map(evalInternal(_, row)).orNull

      case In(value, list) =>
        val v = evalInternal(value, row)
        if v == null then null
        else
          val listVals = list.map(evalInternal(_, row))
          if listVals.contains(v) then true
          else if listVals.contains(null) then null
          else false

      // Cast
      case Cast(child, targetType) =>
        val v = evalInternal(child, row)
        if v == null then null else castValue(v, targetType)

      // Alias is transparent
      case Alias(child, _) => evalInternal(child, row)

      // ColumnRef should be bound before evaluation
      case ColumnRef(name, _, _, _) =>
        throw IllegalStateException(s"Unbound column reference: $name")

      // Aggregates require special handling
      case Count(_, _) | Sum(_) | Avg(_) | Min(_) | Max(_) =>
        throw IllegalStateException("Aggregate functions cannot be evaluated row-by-row")

      case Like(value, pattern, escape) =>
        val v = evalInternal(value, row)
        val p = evalInternal(pattern, row)
        if v == null || p == null then null
        else
          val patternStr = p.toString
          // Convert SQL LIKE pattern to regex
          val escapeChar = escape
            .map(e => evalInternal(e, row).toString.headOption.getOrElse('\\'))
            .getOrElse('\\')
          val regex = likePatternToRegex(patternStr, escapeChar)
          v.toString.matches(regex)

      case OpaqueCall(name, _, _, _) =>
        throw IllegalStateException(s"Cannot evaluate opaque function: $name")

      // Subquery expressions require actual query execution
      case ScalarSubquery(_) =>
        throw IllegalStateException("Scalar subquery cannot be evaluated row-by-row")

      case Exists(_) =>
        throw IllegalStateException("EXISTS subquery cannot be evaluated row-by-row")

      case InSubquery(_, _) =>
        throw IllegalStateException("IN subquery cannot be evaluated row-by-row")

      // Window functions require aggregate computation over partitions
      case RowNumber() | Rank() | DenseRank() | Ntile(_) | Lead(_, _, _) | Lag(_, _, _) |
          FirstValue(_, _) | LastValue(_, _) | NthValue(_, _) | WindowExpr(_, _, _, _) =>
        throw IllegalStateException("Window functions cannot be evaluated row-by-row")

  private def compareNumeric(
      l: ProtoExpr,
      r: ProtoExpr,
      row: MockRow,
      op: (Double, Double) => Boolean
  ): Any =
    val lv = evalInternal(l, row)
    val rv = evalInternal(r, row)
    if lv == null || rv == null then null
    else op(toDouble(lv), toDouble(rv))

  private def numericOp(
      l: ProtoExpr,
      r: ProtoExpr,
      row: MockRow,
      intOp: (Int, Int) => Int,
      longOp: (Long, Long) => Long,
      doubleOp: (Double, Double) => Double
  ): Any =
    val lv = evalInternal(l, row)
    val rv = evalInternal(r, row)
    if lv == null || rv == null then null
    else
      (lv, rv) match
        case (a: Int, b: Int)   => intOp(a, b)
        case (a: Long, b: Long) => longOp(a, b)
        case (a: Long, b: Int)  => longOp(a, b.toLong)
        case (a: Int, b: Long)  => longOp(a.toLong, b)
        case _                  => doubleOp(toDouble(lv), toDouble(rv))

  private def toDouble(v: Any): Double = v match
    case i: Int         => i.toDouble
    case l: Long        => l.toDouble
    case f: Float       => f.toDouble
    case d: Double      => d
    case bd: BigDecimal => bd.toDouble
    case s: Short       => s.toDouble
    case b: Byte        => b.toDouble
    case other          => other.toString.toDouble

  private def toInt(v: Any): Int = v match
    case i: Int    => i
    case l: Long   => l.toInt
    case s: Short  => s.toInt
    case b: Byte   => b.toInt
    case d: Double => d.toInt
    case f: Float  => f.toInt
    case n: Number => n.intValue
    case other     => other.toString.toInt

  private def castValue(v: Any, targetType: ProtoType): Any =
    targetType match
      case ProtoType.StringType  => v.toString
      case ProtoType.IntegerType     => toInt(v)
      case ProtoType.LongType    => v.asInstanceOf[Number].longValue
      case ProtoType.DoubleType  => toDouble(v)
      case ProtoType.FloatType   => toDouble(v).toFloat
      case ProtoType.BooleanType =>
        v match
          case b: Boolean => b
          case s: String  => s.toBoolean
          case n: Number  => n.intValue != 0
          case _          => throw IllegalArgumentException(s"Cannot cast $v to Boolean")
      case ProtoType.ShortType => v.asInstanceOf[Number].shortValue
      case ProtoType.ByteType  => v.asInstanceOf[Number].byteValue
      case _                   => v // Passthrough for unsupported casts

  /** Convert SQL LIKE pattern to regex. */
  private def likePatternToRegex(pattern: String, escapeChar: Char): String =
    val sb = new StringBuilder("^")
    var i = 0
    while i < pattern.length do
      val c = pattern.charAt(i)
      if c == escapeChar && i + 1 < pattern.length then
        // Escaped character - add literally
        val next = pattern.charAt(i + 1)
        sb.append(java.util.regex.Pattern.quote(next.toString))
        i += 2
      else if c == '%' then
        sb.append(".*")
        i += 1
      else if c == '_' then
        sb.append(".")
        i += 1
      else
        sb.append(java.util.regex.Pattern.quote(c.toString))
        i += 1
    sb.append("$")
    sb.toString
