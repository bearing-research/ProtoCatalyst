package protocatalyst.encoder.spark

import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal, BigInteger => JBigInt}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, LocalTime, Period}

import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.catalyst.util.{
  ArrayBasedMapData,
  DateTimeUtils,
  GenericArrayData,
  IntervalUtils
}
import org.apache.spark.sql.types.{Decimal => SparkDecimal}
import org.apache.spark.unsafe.types.UTF8String

import protocatalyst.encoder.InternalTypeConverter
import protocatalyst.types.ProtoType

/** `InternalTypeConverter` that produces Spark's `InternalRow`-compatible internal values.
  *
  * Strings become `UTF8String`, decimals become `o.a.s.s.t.Decimal`, dates/timestamps become
  * epoch days / micros, nested structs become `GenericInternalRow`, collections become
  * `GenericArrayData` / `ArrayBasedMapData`. Reverse direction (`fromInternal`) returns the
  * external JDK types the user's case class expects.
  *
  * Plug into the existing `InlineRowSerializer.derived[T]` via `given`:
  * {{{
  * given InternalTypeConverter = SparkInternalTypeConverter
  * val arr: Array[Any] = serializer.serialize(value)
  * // arr now contains UTF8String, Decimal, etc. — ready to wrap in GenericInternalRow.
  * }}}
  */
object SparkInternalTypeConverter extends InternalTypeConverter:

  def toInternal(value: Any, dataType: ProtoType): Any =
    if value == null then null
    else
      dataType match
        case ProtoType.BooleanType | ProtoType.ByteType | ProtoType.ShortType |
            ProtoType.IntegerType | ProtoType.LongType | ProtoType.FloatType |
            ProtoType.DoubleType | ProtoType.NullType =>
          value

        case ProtoType.StringType | ProtoType.CharType(_) | ProtoType.VarcharType(_) =>
          value match
            case s: String     => UTF8String.fromString(s)
            case u: UTF8String => u
            case c: Character  => UTF8String.fromString(c.toString)
            case other         => UTF8String.fromString(other.toString)

        case ProtoType.BinaryType => value

        case ProtoType.DecimalType(p, s) =>
          value match
            case sd: SparkDecimal => sd
            case bd: BigDecimal   => SparkDecimal(bd.bigDecimal, p, s)
            case jbd: JBigDecimal => SparkDecimal(jbd, p, s)
            case bi: BigInt       => SparkDecimal(new JBigDecimal(bi.bigInteger), p, s)
            case jbi: JBigInt     => SparkDecimal(new JBigDecimal(jbi), p, s)
            case other            =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to Spark Decimal")

        case ProtoType.DateType =>
          value match
            case ld: LocalDate => DateTimeUtils.localDateToDays(ld)
            case d: jsql.Date  => DateTimeUtils.fromJavaDate(d)
            case i: Int        => i
            case other         =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to DateType")

        case ProtoType.TimestampType =>
          value match
            case i: Instant            => DateTimeUtils.instantToMicros(i)
            case t: jsql.Timestamp     => DateTimeUtils.fromJavaTimestamp(t)
            case ud: java.util.Date    => DateTimeUtils.fromJavaTimestamp(new jsql.Timestamp(ud.getTime))
            case odt: java.time.OffsetDateTime => DateTimeUtils.instantToMicros(odt.toInstant)
            case zdt: java.time.ZonedDateTime  => DateTimeUtils.instantToMicros(zdt.toInstant)
            case l: Long               => l
            case other                 =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to TimestampType")

        case ProtoType.TimestampNTZType =>
          value match
            case ldt: LocalDateTime => DateTimeUtils.localDateTimeToMicros(ldt)
            case l: Long            => l
            case other              =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to TimestampNTZType")

        case ProtoType.TimeType(_) =>
          value match
            case lt: LocalTime => lt.toNanoOfDay
            case l: Long       => l
            case other         =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to TimeType")

        case ProtoType.DayTimeIntervalType =>
          value match
            case d: Duration => IntervalUtils.durationToMicros(d)
            case l: Long     => l
            case other       =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to DayTimeIntervalType")

        case ProtoType.YearMonthIntervalType =>
          value match
            case p: Period => IntervalUtils.periodToMonths(p)
            case i: Int    => i
            case other     =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to YearMonthIntervalType")

        case ProtoType.ArrayType(elemType, _) =>
          def convertIterable(it: Iterable[Any]): Array[Any] =
            val out = new Array[Any](it.size)
            var i = 0
            it.foreach { e =>
              out(i) = toInternal(e, elemType)
              i += 1
            }
            out
          val items: Array[Any] = value match
            case arr: Array[_]   => convertIterable(arr.toSeq.asInstanceOf[Seq[Any]])
            case it: Iterable[_] => convertIterable(it.asInstanceOf[Iterable[Any]])
            case other           =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to ArrayType")
          new GenericArrayData(items)

        case ProtoType.MapType(keyType, valueType, _) =>
          value match
            case m: Map[_, _] =>
              val keys = new Array[Any](m.size)
              val values = new Array[Any](m.size)
              var i = 0
              m.foreach { case (k, v) =>
                keys(i) = toInternal(k, keyType)
                values(i) = toInternal(v, valueType)
                i += 1
              }
              new ArrayBasedMapData(new GenericArrayData(keys), new GenericArrayData(values))
            case other =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to MapType")

        case ProtoType.StructType(fields) =>
          value match
            case p: Product =>
              val arr = new Array[Any](fields.length)
              var i = 0
              while i < fields.length do
                arr(i) = toInternal(p.productElement(i), fields(i).dataType)
                i += 1
              new GenericInternalRow(arr)
            case other =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} to StructType")

        case ProtoType.CalendarIntervalType | ProtoType.VariantType =>
          // Spark-class external types — see docs/ENCODER_PARITY.md. Passed through unchanged
          // (caller is responsible for already having a CalendarInterval or VariantVal).
          value

        case ProtoType.UDTType(_, sqlType) =>
          toInternal(value, sqlType)

        case ProtoType.SumType(_, _) =>
          throw IllegalArgumentException(
            "ProtoType.SumType cannot be serialized to Spark InternalRow — resolve sum types " +
              "to a discriminated struct first."
          )

        case ProtoType.UnresolvedType(hint) =>
          throw IllegalArgumentException(s"Cannot serialize unresolved type: $hint")

  def fromInternal(value: Any, dataType: ProtoType): Any =
    if value == null then null
    else
      dataType match
        case ProtoType.BooleanType | ProtoType.ByteType | ProtoType.ShortType |
            ProtoType.IntegerType | ProtoType.LongType | ProtoType.FloatType |
            ProtoType.DoubleType | ProtoType.NullType =>
          value

        case ProtoType.StringType | ProtoType.CharType(_) | ProtoType.VarcharType(_) =>
          value match
            case u: UTF8String => u.toString
            case s: String     => s
            case other         => other.toString

        case ProtoType.BinaryType => value

        case ProtoType.DecimalType(_, _) =>
          value match
            case sd: SparkDecimal => BigDecimal(sd.toJavaBigDecimal)
            case bd: BigDecimal   => bd
            case jbd: JBigDecimal => BigDecimal(jbd)
            case other            =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from DecimalType")

        case ProtoType.DateType =>
          value match
            case i: Int        => DateTimeUtils.daysToLocalDate(i)
            case ld: LocalDate => ld
            case other         =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from DateType")

        case ProtoType.TimestampType =>
          value match
            case l: Long    => DateTimeUtils.microsToInstant(l)
            case i: Instant => i
            case other      =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from TimestampType")

        case ProtoType.TimestampNTZType =>
          value match
            case l: Long            => DateTimeUtils.microsToLocalDateTime(l)
            case ldt: LocalDateTime => ldt
            case other              =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from TimestampNTZType")

        case ProtoType.TimeType(_) =>
          value match
            case l: Long       => LocalTime.ofNanoOfDay(l)
            case lt: LocalTime => lt
            case other         =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from TimeType")

        case ProtoType.DayTimeIntervalType =>
          value match
            case l: Long     => Duration.of(l, java.time.temporal.ChronoUnit.MICROS)
            case d: Duration => d
            case other       =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from DayTimeIntervalType")

        case ProtoType.YearMonthIntervalType =>
          value match
            case i: Int    => Period.ofMonths(i)
            case p: Period => p
            case other     =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from YearMonthIntervalType")

        case ProtoType.ArrayType(elemType, _) =>
          value match
            case ga: GenericArrayData =>
              val arr = ga.array
              arr.iterator.map(e => fromInternal(e, elemType)).toVector
            case seq: Seq[_] =>
              seq.map(e => fromInternal(e, elemType)).toVector
            case arr: Array[_] =>
              arr.iterator.map(e => fromInternal(e, elemType)).toVector
            case other =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from ArrayType")

        case ProtoType.MapType(keyType, valueType, _) =>
          value match
            case abm: ArrayBasedMapData =>
              val keys = abm.keyArray.asInstanceOf[GenericArrayData].array
              val values = abm.valueArray.asInstanceOf[GenericArrayData].array
              keys.iterator
                .zip(values.iterator)
                .map { case (k, v) => fromInternal(k, keyType) -> fromInternal(v, valueType) }
                .toMap
            case m: Map[_, _] =>
              m.map { case (k, v) => fromInternal(k, keyType) -> fromInternal(v, valueType) }
            case other =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from MapType")

        case ProtoType.StructType(fields) =>
          value match
            case row: GenericInternalRow =>
              val arr = new Array[Any](fields.length)
              var i = 0
              while i < fields.length do
                arr(i) = fromInternal(row.values(i), fields(i).dataType)
                i += 1
              arr
            case other =>
              throw IllegalArgumentException(s"Cannot convert ${other.getClass} from StructType")

        case ProtoType.CalendarIntervalType | ProtoType.VariantType =>
          value

        case ProtoType.UDTType(_, sqlType) =>
          fromInternal(value, sqlType)

        case ProtoType.SumType(_, _) =>
          throw IllegalArgumentException(
            "ProtoType.SumType cannot be deserialized from Spark InternalRow."
          )

        case ProtoType.UnresolvedType(hint) =>
          throw IllegalArgumentException(s"Cannot deserialize unresolved type: $hint")
