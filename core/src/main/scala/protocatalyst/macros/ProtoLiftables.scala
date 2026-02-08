package protocatalyst.macros

import scala.quoted._

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

/** ToExpr instances for lifting ProtoCatalyst IR types to compile-time expressions.
  *
  * These instances enable embedding optimized plans as constants in bytecode during macro
  * expansion.
  */
object ProtoLiftables:

  // ============================================================
  // Collection helpers
  // ============================================================

  given vectorToExpr[T: ToExpr: Type]: ToExpr[Vector[T]] with
    def apply(v: Vector[T])(using Quotes): Expr[Vector[T]] =
      '{ Vector(${ Varargs(v.map(Expr(_))) }*) }

  given tuple2ToExpr[A: ToExpr: Type, B: ToExpr: Type]: ToExpr[(A, B)] with
    def apply(t: (A, B))(using Quotes): Expr[(A, B)] =
      '{ (${ Expr(t._1) }, ${ Expr(t._2) }) }

  given mapToExpr[K: ToExpr: Type, V: ToExpr: Type]: ToExpr[Map[K, V]] with
    def apply(m: Map[K, V])(using Quotes): Expr[Map[K, V]] =
      '{ Map(${ Varargs(m.toSeq.map(kv => Expr(kv))) }*) }

  // ============================================================
  // ProtoType and related types
  // ============================================================

  given ToExpr[ProtoStructField] with
    def apply(f: ProtoStructField)(using Quotes): Expr[ProtoStructField] =
      '{
        ProtoStructField(
          ${ Expr(f.name) },
          ${ Expr(f.dataType) },
          ${ Expr(f.nullable) },
          ${ Expr(f.metadata) }
        )
      }

  given ToExpr[SumVariant] with
    def apply(v: SumVariant)(using Quotes): Expr[SumVariant] =
      '{ SumVariant(${ Expr(v.name) }, ${ Expr(v.ordinal) }, ${ Expr(v.dataType) }) }

  given ToExpr[ProtoType] with
    def apply(t: ProtoType)(using Quotes): Expr[ProtoType] = t match
      case ProtoType.BooleanType           => '{ ProtoType.BooleanType }
      case ProtoType.ByteType              => '{ ProtoType.ByteType }
      case ProtoType.ShortType             => '{ ProtoType.ShortType }
      case ProtoType.IntegerType           => '{ ProtoType.IntegerType }
      case ProtoType.LongType              => '{ ProtoType.LongType }
      case ProtoType.FloatType             => '{ ProtoType.FloatType }
      case ProtoType.DoubleType            => '{ ProtoType.DoubleType }
      case ProtoType.StringType            => '{ ProtoType.StringType }
      case ProtoType.BinaryType            => '{ ProtoType.BinaryType }
      case ProtoType.DateType              => '{ ProtoType.DateType }
      case ProtoType.TimestampType         => '{ ProtoType.TimestampType }
      case ProtoType.TimestampNTZType      => '{ ProtoType.TimestampNTZType }
      case ProtoType.DayTimeIntervalType   => '{ ProtoType.DayTimeIntervalType }
      case ProtoType.YearMonthIntervalType => '{ ProtoType.YearMonthIntervalType }
      case ProtoType.CalendarIntervalType  => '{ ProtoType.CalendarIntervalType }
      case ProtoType.VariantType           => '{ ProtoType.VariantType }
      case ProtoType.NullType              => '{ ProtoType.NullType }
      case ProtoType.TimeType(precision)   =>
        '{ ProtoType.TimeType(${ Expr(precision) }) }
      case ProtoType.CharType(length) =>
        '{ ProtoType.CharType(${ Expr(length) }) }
      case ProtoType.VarcharType(length) =>
        '{ ProtoType.VarcharType(${ Expr(length) }) }
      case ProtoType.DecimalType(precision, scale) =>
        '{ ProtoType.DecimalType(${ Expr(precision) }, ${ Expr(scale) }) }
      case ProtoType.ArrayType(elementType, containsNull) =>
        '{ ProtoType.ArrayType(${ Expr(elementType) }, ${ Expr(containsNull) }) }
      case ProtoType.MapType(keyType, valueType, valueContainsNull) =>
        '{
          ProtoType.MapType(${ Expr(keyType) }, ${ Expr(valueType) }, ${ Expr(valueContainsNull) })
        }
      case ProtoType.StructType(fields) =>
        '{ ProtoType.StructType(${ Expr(fields) }) }
      case ProtoType.UDTType(udtClassName, sqlType) =>
        '{ ProtoType.UDTType(${ Expr(udtClassName) }, ${ Expr(sqlType) }) }
      case ProtoType.UnresolvedType(hint) =>
        '{ ProtoType.UnresolvedType(${ Expr(hint) }) }
      case ProtoType.SumType(discriminatorField, variants) =>
        '{ ProtoType.SumType(${ Expr(discriminatorField) }, ${ Expr(variants) }) }

  // ============================================================
  // LiteralValue
  // ============================================================

  given ToExpr[LiteralValue] with
    def apply(v: LiteralValue)(using Quotes): Expr[LiteralValue] = v match
      case LiteralValue.BooleanValue(value) =>
        '{ LiteralValue.BooleanValue(${ Expr(value) }) }
      case LiteralValue.ByteValue(value) =>
        '{ LiteralValue.ByteValue(${ Expr(value) }) }
      case LiteralValue.ShortValue(value) =>
        '{ LiteralValue.ShortValue(${ Expr(value) }) }
      case LiteralValue.IntValue(value) =>
        '{ LiteralValue.IntValue(${ Expr(value) }) }
      case LiteralValue.LongValue(value) =>
        '{ LiteralValue.LongValue(${ Expr(value) }) }
      case LiteralValue.FloatValue(value) =>
        '{ LiteralValue.FloatValue(${ Expr(value) }) }
      case LiteralValue.DoubleValue(value) =>
        '{ LiteralValue.DoubleValue(${ Expr(value) }) }
      case LiteralValue.StringValue(value) =>
        '{ LiteralValue.StringValue(${ Expr(value) }) }
      case LiteralValue.BinaryValue(value) =>
        val arr = Expr(value.toArray)
        '{ LiteralValue.BinaryValue(scala.collection.immutable.ArraySeq.unsafeWrapArray($arr)) }
      case LiteralValue.DecimalValue(value) =>
        '{ LiteralValue.DecimalValue(${ Expr(value) }) }
      case LiteralValue.DateValue(epochDays) =>
        '{ LiteralValue.DateValue(${ Expr(epochDays) }) }
      case LiteralValue.TimestampValue(epochMicros) =>
        '{ LiteralValue.TimestampValue(${ Expr(epochMicros) }) }
      case LiteralValue.TimeValue(microsSinceMidnight) =>
        '{ LiteralValue.TimeValue(${ Expr(microsSinceMidnight) }) }
      case LiteralValue.CalendarIntervalValue(months, days, microseconds) =>
        '{
          LiteralValue.CalendarIntervalValue(
            ${ Expr(months) },
            ${ Expr(days) },
            ${ Expr(microseconds) }
          )
        }
      case LiteralValue.NullValue(dataType) =>
        '{ LiteralValue.NullValue(${ Expr(dataType) }) }

  // ============================================================
  // Expression helper types
  // ============================================================

  given ToExpr[TrimType] with
    def apply(t: TrimType)(using Quotes): Expr[TrimType] = t match
      case TrimType.Both     => '{ TrimType.Both }
      case TrimType.Leading  => '{ TrimType.Leading }
      case TrimType.Trailing => '{ TrimType.Trailing }

  given ToExpr[DateTimeField] with
    def apply(f: DateTimeField)(using Quotes): Expr[DateTimeField] = f match
      case DateTimeField.Year        => '{ DateTimeField.Year }
      case DateTimeField.Month       => '{ DateTimeField.Month }
      case DateTimeField.Day         => '{ DateTimeField.Day }
      case DateTimeField.Hour        => '{ DateTimeField.Hour }
      case DateTimeField.Minute      => '{ DateTimeField.Minute }
      case DateTimeField.Second      => '{ DateTimeField.Second }
      case DateTimeField.Quarter     => '{ DateTimeField.Quarter }
      case DateTimeField.Week        => '{ DateTimeField.Week }
      case DateTimeField.DayOfWeek   => '{ DateTimeField.DayOfWeek }
      case DateTimeField.DayOfYear   => '{ DateTimeField.DayOfYear }
      case DateTimeField.Microsecond => '{ DateTimeField.Microsecond }
      case DateTimeField.Millisecond => '{ DateTimeField.Millisecond }

  given ToExpr[FrameType] with
    def apply(t: FrameType)(using Quotes): Expr[FrameType] = t match
      case FrameType.Rows  => '{ FrameType.Rows }
      case FrameType.Range => '{ FrameType.Range }

  given ToExpr[FrameBound] with
    def apply(b: FrameBound)(using Quotes): Expr[FrameBound] = b match
      case FrameBound.UnboundedPreceding => '{ FrameBound.UnboundedPreceding }
      case FrameBound.UnboundedFollowing => '{ FrameBound.UnboundedFollowing }
      case FrameBound.CurrentRow         => '{ FrameBound.CurrentRow }
      case FrameBound.Preceding(n)       => '{ FrameBound.Preceding(${ Expr(n) }) }
      case FrameBound.Following(n)       => '{ FrameBound.Following(${ Expr(n) }) }

  given ToExpr[WindowFrame] with
    def apply(f: WindowFrame)(using Quotes): Expr[WindowFrame] =
      '{ WindowFrame(${ Expr(f.frameType) }, ${ Expr(f.lower) }, ${ Expr(f.upper) }) }

  // ============================================================
  // ProtoExpr
  // ============================================================

  given ToExpr[ProtoExpr] with
    def apply(e: ProtoExpr)(using Quotes): Expr[ProtoExpr] = e match
      // Leaf nodes
      case ProtoExpr.Literal(value) =>
        '{ ProtoExpr.Literal(${ Expr(value) }) }
      case ProtoExpr.ColumnRef(name, qualifier, resolvedType, nullable) =>
        '{
          ProtoExpr.ColumnRef(
            ${ Expr(name) },
            ${ Expr(qualifier) },
            ${ Expr(resolvedType) },
            ${ Expr(nullable) }
          )
        }
      case ProtoExpr.BoundRef(index, dataType, nullable) =>
        '{ ProtoExpr.BoundRef(${ Expr(index) }, ${ Expr(dataType) }, ${ Expr(nullable) }) }

      // Comparison
      case ProtoExpr.Eq(left, right)    => '{ ProtoExpr.Eq(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.NotEq(left, right) => '{ ProtoExpr.NotEq(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.Lt(left, right)    => '{ ProtoExpr.Lt(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.LtEq(left, right)  => '{ ProtoExpr.LtEq(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.Gt(left, right)    => '{ ProtoExpr.Gt(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.GtEq(left, right)  => '{ ProtoExpr.GtEq(${ Expr(left) }, ${ Expr(right) }) }

      // Logical
      case ProtoExpr.And(children) => '{ ProtoExpr.And(${ Expr(children) }) }
      case ProtoExpr.Or(children)  => '{ ProtoExpr.Or(${ Expr(children) }) }
      case ProtoExpr.Not(child)    => '{ ProtoExpr.Not(${ Expr(child) }) }

      // Null handling
      case ProtoExpr.IsNull(child)       => '{ ProtoExpr.IsNull(${ Expr(child) }) }
      case ProtoExpr.IsNotNull(child)    => '{ ProtoExpr.IsNotNull(${ Expr(child) }) }
      case ProtoExpr.Coalesce(children)  => '{ ProtoExpr.Coalesce(${ Expr(children) }) }
      case ProtoExpr.NullIf(left, right) => '{ ProtoExpr.NullIf(${ Expr(left) }, ${ Expr(right) }) }

      // Arithmetic
      case ProtoExpr.Add(left, right)      => '{ ProtoExpr.Add(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.Subtract(left, right) =>
        '{ ProtoExpr.Subtract(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.Multiply(left, right) =>
        '{ ProtoExpr.Multiply(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.Divide(left, right) => '{ ProtoExpr.Divide(${ Expr(left) }, ${ Expr(right) }) }

      // Math functions
      case ProtoExpr.Abs(child)          => '{ ProtoExpr.Abs(${ Expr(child) }) }
      case ProtoExpr.Ceil(child)         => '{ ProtoExpr.Ceil(${ Expr(child) }) }
      case ProtoExpr.Floor(child)        => '{ ProtoExpr.Floor(${ Expr(child) }) }
      case ProtoExpr.Round(child, scale) => '{ ProtoExpr.Round(${ Expr(child) }, ${ Expr(scale) }) }
      case ProtoExpr.Truncate(child, s)  => '{ ProtoExpr.Truncate(${ Expr(child) }, ${ Expr(s) }) }
      case ProtoExpr.Sqrt(child)         => '{ ProtoExpr.Sqrt(${ Expr(child) }) }
      case ProtoExpr.Cbrt(child)         => '{ ProtoExpr.Cbrt(${ Expr(child) }) }
      case ProtoExpr.Pow(left, right)    => '{ ProtoExpr.Pow(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.Pmod(left, right)   => '{ ProtoExpr.Pmod(${ Expr(left) }, ${ Expr(right) }) }
      case ProtoExpr.Sign(child)         => '{ ProtoExpr.Sign(${ Expr(child) }) }
      case ProtoExpr.Log(child, base)    => '{ ProtoExpr.Log(${ Expr(child) }, ${ Expr(base) }) }
      case ProtoExpr.Exp(child)          => '{ ProtoExpr.Exp(${ Expr(child) }) }

      // String
      case ProtoExpr.Concat(children)         => '{ ProtoExpr.Concat(${ Expr(children) }) }
      case ProtoExpr.Substring(str, pos, len) =>
        '{ ProtoExpr.Substring(${ Expr(str) }, ${ Expr(pos) }, ${ Expr(len) }) }
      case ProtoExpr.Upper(child)                   => '{ ProtoExpr.Upper(${ Expr(child) }) }
      case ProtoExpr.Lower(child)                   => '{ ProtoExpr.Lower(${ Expr(child) }) }
      case ProtoExpr.Trim(child, trimStr, trimType) =>
        '{ ProtoExpr.Trim(${ Expr(child) }, ${ Expr(trimStr) }, ${ Expr(trimType) }) }
      case ProtoExpr.Length(child)                 => '{ ProtoExpr.Length(${ Expr(child) }) }
      case ProtoExpr.Replace(str, search, replace) =>
        '{ ProtoExpr.Replace(${ Expr(str) }, ${ Expr(search) }, ${ Expr(replace) }) }
      case ProtoExpr.StringLocate(substr, str, start) =>
        '{ ProtoExpr.StringLocate(${ Expr(substr) }, ${ Expr(str) }, ${ Expr(start) }) }
      case ProtoExpr.Lpad(str, len, pad) =>
        '{ ProtoExpr.Lpad(${ Expr(str) }, ${ Expr(len) }, ${ Expr(pad) }) }
      case ProtoExpr.Rpad(str, len, pad) =>
        '{ ProtoExpr.Rpad(${ Expr(str) }, ${ Expr(len) }, ${ Expr(pad) }) }
      case ProtoExpr.StringSplit(str, delimiter, limit) =>
        '{ ProtoExpr.StringSplit(${ Expr(str) }, ${ Expr(delimiter) }, ${ Expr(limit) }) }
      case ProtoExpr.Reverse(child)           => '{ ProtoExpr.Reverse(${ Expr(child) }) }
      case ProtoExpr.StringRepeat(str, times) =>
        '{ ProtoExpr.StringRepeat(${ Expr(str) }, ${ Expr(times) }) }

      // Aggregates
      case ProtoExpr.Count(child, distinct) =>
        '{ ProtoExpr.Count(${ Expr(child) }, ${ Expr(distinct) }) }
      case ProtoExpr.Sum(child) => '{ ProtoExpr.Sum(${ Expr(child) }) }
      case ProtoExpr.Avg(child) => '{ ProtoExpr.Avg(${ Expr(child) }) }
      case ProtoExpr.Min(child) => '{ ProtoExpr.Min(${ Expr(child) }) }
      case ProtoExpr.Max(child) => '{ ProtoExpr.Max(${ Expr(child) }) }

      // Control flow
      case ProtoExpr.CaseWhen(branches, elseValue) =>
        '{ ProtoExpr.CaseWhen(${ Expr(branches) }, ${ Expr(elseValue) }) }
      case ProtoExpr.If(predicate, trueValue, falseValue) =>
        '{ ProtoExpr.If(${ Expr(predicate) }, ${ Expr(trueValue) }, ${ Expr(falseValue) }) }
      case ProtoExpr.In(value, list) =>
        '{ ProtoExpr.In(${ Expr(value) }, ${ Expr(list) }) }

      // Pattern matching
      case ProtoExpr.Like(value, pattern, escape) =>
        '{ ProtoExpr.Like(${ Expr(value) }, ${ Expr(pattern) }, ${ Expr(escape) }) }

      // Cast and alias
      case ProtoExpr.Cast(child, targetType) =>
        '{ ProtoExpr.Cast(${ Expr(child) }, ${ Expr(targetType) }) }
      case ProtoExpr.Alias(child, name) =>
        '{ ProtoExpr.Alias(${ Expr(child) }, ${ Expr(name) }) }

      // Subquery expressions
      case ProtoExpr.ScalarSubquery(plan) =>
        '{ ProtoExpr.ScalarSubquery(${ Expr(plan) }) }
      case ProtoExpr.Exists(plan) =>
        '{ ProtoExpr.Exists(${ Expr(plan) }) }
      case ProtoExpr.InSubquery(value, plan) =>
        '{ ProtoExpr.InSubquery(${ Expr(value) }, ${ Expr(plan) }) }

      // Window functions
      case ProtoExpr.RowNumber()                  => '{ ProtoExpr.RowNumber() }
      case ProtoExpr.Rank()                       => '{ ProtoExpr.Rank() }
      case ProtoExpr.DenseRank()                  => '{ ProtoExpr.DenseRank() }
      case ProtoExpr.Ntile(n)                     => '{ ProtoExpr.Ntile(${ Expr(n) }) }
      case ProtoExpr.Lead(input, offset, default) =>
        '{ ProtoExpr.Lead(${ Expr(input) }, ${ Expr(offset) }, ${ Expr(default) }) }
      case ProtoExpr.Lag(input, offset, default) =>
        '{ ProtoExpr.Lag(${ Expr(input) }, ${ Expr(offset) }, ${ Expr(default) }) }
      case ProtoExpr.FirstValue(input, ignoreNulls) =>
        '{ ProtoExpr.FirstValue(${ Expr(input) }, ${ Expr(ignoreNulls) }) }
      case ProtoExpr.LastValue(input, ignoreNulls) =>
        '{ ProtoExpr.LastValue(${ Expr(input) }, ${ Expr(ignoreNulls) }) }
      case ProtoExpr.NthValue(input, n) =>
        '{ ProtoExpr.NthValue(${ Expr(input) }, ${ Expr(n) }) }

      // Window specification wrapper
      case ProtoExpr.WindowExpr(function, partitionSpec, orderSpec, frameSpec) =>
        '{
          ProtoExpr.WindowExpr(
            ${ Expr(function) },
            ${ Expr(partitionSpec) },
            ${ Expr(orderSpec) },
            ${ Expr(frameSpec) }
          )
        }

      // Date/Time functions
      case ProtoExpr.CurrentDate()        => '{ ProtoExpr.CurrentDate() }
      case ProtoExpr.CurrentTimestamp()   => '{ ProtoExpr.CurrentTimestamp() }
      case ProtoExpr.DateAdd(start, days) =>
        '{ ProtoExpr.DateAdd(${ Expr(start) }, ${ Expr(days) }) }
      case ProtoExpr.DateSub(start, days) =>
        '{ ProtoExpr.DateSub(${ Expr(start) }, ${ Expr(days) }) }
      case ProtoExpr.DateDiff(end, start) =>
        '{ ProtoExpr.DateDiff(${ Expr(end) }, ${ Expr(start) }) }
      case ProtoExpr.Extract(field, source) =>
        '{ ProtoExpr.Extract(${ Expr(field) }, ${ Expr(source) }) }
      case ProtoExpr.DateTrunc(field, timestamp) =>
        '{ ProtoExpr.DateTrunc(${ Expr(field) }, ${ Expr(timestamp) }) }
      case ProtoExpr.ToDate(str, format) =>
        '{ ProtoExpr.ToDate(${ Expr(str) }, ${ Expr(format) }) }
      case ProtoExpr.ToTimestamp(str, format) =>
        '{ ProtoExpr.ToTimestamp(${ Expr(str) }, ${ Expr(format) }) }
      case ProtoExpr.Year(child)       => '{ ProtoExpr.Year(${ Expr(child) }) }
      case ProtoExpr.Month(child)      => '{ ProtoExpr.Month(${ Expr(child) }) }
      case ProtoExpr.DayOfMonth(child) => '{ ProtoExpr.DayOfMonth(${ Expr(child) }) }
      case ProtoExpr.Hour(child)       => '{ ProtoExpr.Hour(${ Expr(child) }) }
      case ProtoExpr.Minute(child)     => '{ ProtoExpr.Minute(${ Expr(child) }) }
      case ProtoExpr.Second(child)     => '{ ProtoExpr.Second(${ Expr(child) }) }

      // Grouping
      case ProtoExpr.Grouping(columns) => '{ ProtoExpr.Grouping(${ Expr(columns) }) }

      // Generator functions
      case ProtoExpr.Explode(child)           => '{ ProtoExpr.Explode(${ Expr(child) }) }
      case ProtoExpr.PosExplode(child)        => '{ ProtoExpr.PosExplode(${ Expr(child) }) }
      case ProtoExpr.Inline(child)            => '{ ProtoExpr.Inline(${ Expr(child) }) }
      case ProtoExpr.Stack(numRows, children) =>
        '{ ProtoExpr.Stack(${ Expr(numRows) }, ${ Expr(children) }) }

      // Opaque function call
      case ProtoExpr.OpaqueCall(functionName, arguments, returnType, deterministic) =>
        '{
          ProtoExpr.OpaqueCall(
            ${ Expr(functionName) },
            ${ Expr(arguments) },
            ${ Expr(returnType) },
            ${ Expr(deterministic) }
          )
        }

  // ============================================================
  // Plan helper types
  // ============================================================

  given ToExpr[JoinType] with
    def apply(t: JoinType)(using Quotes): Expr[JoinType] = t match
      case JoinType.Inner      => '{ JoinType.Inner }
      case JoinType.LeftOuter  => '{ JoinType.LeftOuter }
      case JoinType.RightOuter => '{ JoinType.RightOuter }
      case JoinType.FullOuter  => '{ JoinType.FullOuter }
      case JoinType.LeftSemi   => '{ JoinType.LeftSemi }
      case JoinType.LeftAnti   => '{ JoinType.LeftAnti }
      case JoinType.Cross      => '{ JoinType.Cross }

  given ToExpr[SortDirection] with
    def apply(d: SortDirection)(using Quotes): Expr[SortDirection] = d match
      case SortDirection.Ascending  => '{ SortDirection.Ascending }
      case SortDirection.Descending => '{ SortDirection.Descending }

  given ToExpr[NullOrdering] with
    def apply(o: NullOrdering)(using Quotes): Expr[NullOrdering] = o match
      case NullOrdering.NullsFirst => '{ NullOrdering.NullsFirst }
      case NullOrdering.NullsLast  => '{ NullOrdering.NullsLast }

  given ToExpr[SortOrder] with
    def apply(s: SortOrder)(using Quotes): Expr[SortOrder] =
      '{ SortOrder(${ Expr(s.child) }, ${ Expr(s.direction) }, ${ Expr(s.nullOrdering) }) }

  given ToExpr[HintParam] with
    def apply(p: HintParam)(using Quotes): Expr[HintParam] = p match
      case HintParam.StringVal(value) =>
        '{ HintParam.StringVal(${ Expr(value) }) }
      case HintParam.IntVal(value) =>
        '{ HintParam.IntVal(${ Expr(value) }) }

  given ToExpr[PlanHint] with
    def apply(h: PlanHint)(using Quotes): Expr[PlanHint] =
      '{ PlanHint(${ Expr(h.name) }, ${ Expr(h.params) }) }

  // ============================================================
  // Schema types
  // ============================================================

  given ToExpr[SchemaFingerprint] with
    def apply(fp: SchemaFingerprint)(using Quotes): Expr[SchemaFingerprint] =
      '{ SchemaFingerprint.fromLong(${ Expr(fp.toLong) }) }

  given ToExpr[FieldContract] with
    def apply(f: FieldContract)(using Quotes): Expr[FieldContract] =
      '{
        FieldContract(
          ${ Expr(f.name) },
          ${ Expr(f.expectedType) },
          ${ Expr(f.expectedNullable) },
          ${ Expr(f.position) }
        )
      }

  given ToExpr[SchemaContract] with
    def apply(c: SchemaContract)(using Quotes): Expr[SchemaContract] =
      '{
        SchemaContract(
          ${ Expr(c.relationName) },
          ${ Expr(c.requiredFields) },
          ${ Expr(c.fingerprint) }
        )
      }

  given ToExpr[ProtoSchema] with
    def apply(s: ProtoSchema)(using Quotes): Expr[ProtoSchema] =
      '{ ProtoSchema(${ Expr(s.fields) }, ${ Expr(s.fingerprint) }) }

  // ============================================================
  // ProtoLogicalPlan
  // ============================================================

  given ToExpr[ProtoLogicalPlan] with
    def apply(p: ProtoLogicalPlan)(using Quotes): Expr[ProtoLogicalPlan] = p match
      case ProtoLogicalPlan.RelationRef(name, alias, schemaContract) =>
        '{
          ProtoLogicalPlan.RelationRef(${ Expr(name) }, ${ Expr(alias) }, ${ Expr(schemaContract) })
        }

      case ProtoLogicalPlan.Values(rows, schema) =>
        '{ ProtoLogicalPlan.Values(${ Expr(rows) }, ${ Expr(schema) }) }

      case ProtoLogicalPlan.Project(projectList, child) =>
        '{ ProtoLogicalPlan.Project(${ Expr(projectList) }, ${ Expr(child) }) }

      case ProtoLogicalPlan.Filter(condition, child) =>
        '{ ProtoLogicalPlan.Filter(${ Expr(condition) }, ${ Expr(child) }) }

      case ProtoLogicalPlan.Aggregate(groupingExprs, aggregateExprs, child) =>
        '{
          ProtoLogicalPlan.Aggregate(
            ${ Expr(groupingExprs) },
            ${ Expr(aggregateExprs) },
            ${ Expr(child) }
          )
        }

      case ProtoLogicalPlan.Sort(order, child) =>
        '{ ProtoLogicalPlan.Sort(${ Expr(order) }, ${ Expr(child) }) }

      case ProtoLogicalPlan.Limit(limit, child) =>
        '{ ProtoLogicalPlan.Limit(${ Expr(limit) }, ${ Expr(child) }) }

      case ProtoLogicalPlan.Distinct(child) =>
        '{ ProtoLogicalPlan.Distinct(${ Expr(child) }) }

      case ProtoLogicalPlan.SubqueryAlias(alias, child) =>
        '{ ProtoLogicalPlan.SubqueryAlias(${ Expr(alias) }, ${ Expr(child) }) }

      case ProtoLogicalPlan.Join(left, right, joinType, condition) =>
        '{
          ProtoLogicalPlan.Join(
            ${ Expr(left) },
            ${ Expr(right) },
            ${ Expr(joinType) },
            ${ Expr(condition) }
          )
        }

      case ProtoLogicalPlan.Union(children, byName, allowMissingColumns) =>
        '{
          ProtoLogicalPlan.Union(
            ${ Expr(children) },
            ${ Expr(byName) },
            ${ Expr(allowMissingColumns) }
          )
        }

      case ProtoLogicalPlan.Intersect(left, right, isAll) =>
        '{ ProtoLogicalPlan.Intersect(${ Expr(left) }, ${ Expr(right) }, ${ Expr(isAll) }) }

      case ProtoLogicalPlan.Except(left, right, isAll) =>
        '{ ProtoLogicalPlan.Except(${ Expr(left) }, ${ Expr(right) }, ${ Expr(isAll) }) }

      case ProtoLogicalPlan.Window(windowExprs, partitionSpec, orderSpec, child) =>
        '{
          ProtoLogicalPlan.Window(
            ${ Expr(windowExprs) },
            ${ Expr(partitionSpec) },
            ${ Expr(orderSpec) },
            ${ Expr(child) }
          )
        }

      case ProtoLogicalPlan.With(cteRelations, recursive, child) =>
        '{ ProtoLogicalPlan.With(${ Expr(cteRelations) }, ${ Expr(recursive) }, ${ Expr(child) }) }

      case ProtoLogicalPlan.Pivot(groupingExprs, pivotColumn, pivotValues, aggregates, child) =>
        '{
          ProtoLogicalPlan.Pivot(
            ${ Expr(groupingExprs) },
            ${ Expr(pivotColumn) },
            ${ Expr(pivotValues) },
            ${ Expr(aggregates) },
            ${ Expr(child) }
          )
        }

      case ProtoLogicalPlan.Unpivot(
            valueColumnName,
            variableColumnName,
            columns,
            includeNulls,
            child
          ) =>
        '{
          ProtoLogicalPlan.Unpivot(
            ${ Expr(valueColumnName) },
            ${ Expr(variableColumnName) },
            ${ Expr(columns) },
            ${ Expr(includeNulls) },
            ${ Expr(child) }
          )
        }

      case ProtoLogicalPlan.LateralJoin(left, lateral, condition) =>
        '{ ProtoLogicalPlan.LateralJoin(${ Expr(left) }, ${ Expr(lateral) }, ${ Expr(condition) }) }

      case ProtoLogicalPlan.Generate(generator, generatorOutput, outer, child) =>
        '{
          ProtoLogicalPlan.Generate(
            ${ Expr(generator) },
            ${ Expr(generatorOutput) },
            ${ Expr(outer) },
            ${ Expr(child) }
          )
        }

      case ProtoLogicalPlan.ResolvedHint(hints, child) =>
        '{ ProtoLogicalPlan.ResolvedHint(${ Expr(hints) }, ${ Expr(child) }) }
