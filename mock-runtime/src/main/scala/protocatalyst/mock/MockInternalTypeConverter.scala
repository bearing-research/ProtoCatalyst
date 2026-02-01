package protocatalyst.mock

import protocatalyst.encoder.{InternalTypeConverter, ProtoRow, GenericProtoRow}
import protocatalyst.types.*

/**
 * Mock internal type converter that simulates Spark's type conversions.
 *
 * In real Spark:
 *   - String → UTF8String
 *   - Array/Seq → GenericArrayData
 *   - Map → ArrayBasedMapData
 *   - nested struct → GenericInternalRow
 *
 * This mock version:
 *   - String → MockUTF8String (wrapper for testing)
 *   - Array/Seq → MockArrayData
 *   - Map → MockMapData
 *   - nested struct → MockRow
 */
object MockInternalTypeConverter extends InternalTypeConverter:

  override def toInternal(value: Any, dataType: ProtoType): Any =
    if value == null then null
    else dataType match
      case ProtoType.StringType =>
        MockUTF8String(value.asInstanceOf[String])

      case ProtoType.BinaryType =>
        // Binary stays as Array[Byte]
        value

      case ProtoType.ArrayType(elemType, _) =>
        val seq = value match
          case arr: Array[?] => arr.toSeq
          case seq: Seq[?] => seq
          case set: Set[?] => set.toSeq
          case other => throw IllegalArgumentException(s"Expected array-like, got: ${other.getClass}")
        MockArrayData(seq.map(toInternal(_, elemType)).toVector)

      case ProtoType.MapType(keyType, valueType, _) =>
        val map = value.asInstanceOf[Map[?, ?]]
        val keys = map.keys.map(toInternal(_, keyType)).toVector
        val values = map.values.map(toInternal(_, valueType)).toVector
        MockMapData(keys, values)

      case ProtoType.StructType(fields) =>
        // Handle both ProtoRow and Product types
        val rowValues: Int => Any = value match
          case row: ProtoRow => row.get
          case product: Product => product.productElement
          case other => throw IllegalArgumentException(
            s"Expected ProtoRow or Product, got: ${other.getClass}"
          )

        val values = fields.zipWithIndex.map { case (field, idx) =>
          val rawValue = rowValues(idx)
          // Handle Option fields - unwrap Some/None to value/null
          val unwrapped = if field.nullable then
            rawValue match
              case Some(v) => v
              case None => null
              case other => other  // Already unwrapped
          else
            rawValue
          toInternal(unwrapped, field.dataType)
        }
        MockRow(values)

      // Temporal types with lenient serialization - accept multiple input types
      case ProtoType.DateType =>
        value match
          case d: java.sql.Date => d.toLocalDate.toEpochDay.toInt
          case ld: java.time.LocalDate => ld.toEpochDay.toInt
          case s: String => java.time.LocalDate.parse(s).toEpochDay.toInt
          case epochDays: Int => epochDays  // Already internal representation
          case other => throw IllegalArgumentException(
            s"DateType expects java.sql.Date, LocalDate, String, or Int, got: ${other.getClass}"
          )

      case ProtoType.TimestampType =>
        value match
          case ts: java.sql.Timestamp => ts.toInstant.toEpochMilli * 1000L
          case i: java.time.Instant => i.toEpochMilli * 1000L
          case s: String => java.time.Instant.parse(s).toEpochMilli * 1000L
          case micros: Long => micros  // Already internal representation
          case other => throw IllegalArgumentException(
            s"TimestampType expects java.sql.Timestamp, Instant, String, or Long, got: ${other.getClass}"
          )

      case ProtoType.TimestampNTZType =>
        value match
          case ldt: java.time.LocalDateTime =>
            ldt.toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + ldt.getNano / 1000
          case s: String =>
            val ldt = java.time.LocalDateTime.parse(s)
            ldt.toEpochSecond(java.time.ZoneOffset.UTC) * 1000000L + ldt.getNano / 1000
          case micros: Long => micros
          case other => throw IllegalArgumentException(
            s"TimestampNTZType expects LocalDateTime, String, or Long, got: ${other.getClass}"
          )

      case _: ProtoType.TimeType =>
        // LocalTime stored as microseconds since midnight (0-86399999999)
        value match
          case lt: java.time.LocalTime => lt.toNanoOfDay / 1000
          case s: String => java.time.LocalTime.parse(s).toNanoOfDay / 1000
          case micros: Long => micros
          case other => throw IllegalArgumentException(
            s"TimeType expects LocalTime, String, or Long, got: ${other.getClass}"
          )

      case ProtoType.CalendarIntervalType =>
        // CalendarInterval stored as (months, days, microseconds) tuple
        value // Pass through - would need CalendarInterval class for proper handling

      case ProtoType.VariantType =>
        // Variant stored as binary blob - pass through
        value

      case _: ProtoType.CharType | _: ProtoType.VarcharType =>
        // Char/Varchar are treated as strings internally
        MockUTF8String(value.asInstanceOf[String])

      // Other primitive types - no conversion needed
      case ProtoType.BooleanType | ProtoType.ByteType | ProtoType.ShortType |
           ProtoType.IntType | ProtoType.LongType | ProtoType.FloatType |
           ProtoType.DoubleType | ProtoType.DayTimeIntervalType |
           ProtoType.YearMonthIntervalType | _: ProtoType.DecimalType =>
        value

      // UDT types - convert using the underlying SQL type
      case ProtoType.UDTType(_, sqlType) =>
        toInternal(value, sqlType)

      // Sum types (sealed traits) - convert discriminator and variant data
      case ProtoType.SumType(discriminatorField, variants) =>
        // For sum types, the value should be an Array[Any] with [discriminator, ordinal, variantData]
        value match
          case arr: Array[Any @unchecked] if arr.length >= 2 =>
            MockRow(Vector(
              toInternal(arr(0), ProtoType.StringType),  // discriminator
              toInternal(arr(1), ProtoType.IntType)      // ordinal
            ))
          case other => throw IllegalArgumentException(
            s"SumType expects Array[Any] with [discriminator, ordinal, ...], got: ${other.getClass}"
          )

      case ProtoType.UnresolvedType(hint) =>
        throw IllegalArgumentException(s"Cannot convert unresolved type: $hint")

  override def fromInternal(value: Any, dataType: ProtoType): Any =
    if value == null then null
    else dataType match
      case ProtoType.StringType =>
        value match
          case utf8: MockUTF8String => utf8.toString
          case s: String => s  // Already converted
          case other => throw IllegalArgumentException(s"Expected MockUTF8String, got: ${other.getClass}")

      case ProtoType.BinaryType =>
        value

      case ProtoType.ArrayType(elemType, _) =>
        value match
          case arr: MockArrayData =>
            arr.values.map(fromInternal(_, elemType))
          case seq: Seq[?] =>
            seq.map(fromInternal(_, elemType))
          case other => throw IllegalArgumentException(s"Expected MockArrayData, got: ${other.getClass}")

      case ProtoType.MapType(keyType, valueType, _) =>
        value match
          case mapData: MockMapData =>
            mapData.keys.zip(mapData.values).map { case (k, v) =>
              fromInternal(k, keyType) -> fromInternal(v, valueType)
            }.toMap
          case m: Map[?, ?] => m  // Already converted

      case ProtoType.StructType(fields) =>
        // For struct types, we return the MockRow as-is
        // The caller is responsible for further deserialization
        value

      // Temporal types - strict deserialization to standard java.time types
      case ProtoType.DateType =>
        java.time.LocalDate.ofEpochDay(value.asInstanceOf[Int].toLong)

      case ProtoType.TimestampType =>
        java.time.Instant.ofEpochMilli(value.asInstanceOf[Long] / 1000)

      case ProtoType.TimestampNTZType =>
        val micros = value.asInstanceOf[Long]
        java.time.LocalDateTime.ofEpochSecond(
          micros / 1000000,
          ((micros % 1000000) * 1000).toInt,
          java.time.ZoneOffset.UTC
        )

      case _: ProtoType.TimeType =>
        // Convert microseconds since midnight back to LocalTime
        val micros = value.asInstanceOf[Long]
        java.time.LocalTime.ofNanoOfDay(micros * 1000)

      case ProtoType.CalendarIntervalType =>
        // Would need CalendarInterval class for proper handling
        value

      case ProtoType.VariantType =>
        // Pass through
        value

      case _: ProtoType.CharType | _: ProtoType.VarcharType =>
        // Convert back to String
        value match
          case utf8: MockUTF8String => utf8.toString
          case s: String => s
          case other => throw IllegalArgumentException(s"Expected MockUTF8String or String, got: ${other.getClass}")

      // Other primitive types - no conversion needed
      case ProtoType.BooleanType | ProtoType.ByteType | ProtoType.ShortType |
           ProtoType.IntType | ProtoType.LongType | ProtoType.FloatType |
           ProtoType.DoubleType | ProtoType.DayTimeIntervalType |
           ProtoType.YearMonthIntervalType | _: ProtoType.DecimalType =>
        value

      // UDT types - convert using the underlying SQL type
      case ProtoType.UDTType(_, sqlType) =>
        fromInternal(value, sqlType)

      // Sum types (sealed traits) - return as-is for now
      case ProtoType.SumType(_, _) =>
        value

      case ProtoType.UnresolvedType(hint) =>
        throw IllegalArgumentException(s"Cannot convert unresolved type: $hint")

/**
 * Mock UTF8String - simulates Spark's org.apache.spark.unsafe.types.UTF8String.
 * In real Spark, UTF8String stores strings as UTF-8 encoded bytes for efficiency.
 */
case class MockUTF8String(value: String):
  override def toString: String = value
  def getBytes: Array[Byte] = value.getBytes("UTF-8")
  def numBytes: Int = getBytes.length

  def compareTo(other: MockUTF8String): Int =
    this.value.compareTo(other.value)

object MockUTF8String:
  def fromString(s: String): MockUTF8String =
    if s == null then null else MockUTF8String(s)

  def fromBytes(bytes: Array[Byte]): MockUTF8String =
    if bytes == null then null
    else MockUTF8String(new String(bytes, "UTF-8"))

  val EMPTY: MockUTF8String = MockUTF8String("")

/**
 * Mock ArrayData - simulates Spark's org.apache.spark.sql.catalyst.util.ArrayData.
 */
case class MockArrayData(values: Vector[Any]):
  def numElements: Int = values.size
  def get(ordinal: Int): Any = values(ordinal)
  def toSeq: Seq[Any] = values

object MockArrayData:
  def apply(values: Any*): MockArrayData = MockArrayData(values.toVector)

/**
 * Mock MapData - simulates Spark's org.apache.spark.sql.catalyst.util.MapData.
 */
case class MockMapData(keys: Vector[Any], values: Vector[Any]):
  require(keys.size == values.size, "Keys and values must have same size")
  def numElements: Int = keys.size
  def keyArray: MockArrayData = MockArrayData(keys)
  def valueArray: MockArrayData = MockArrayData(values)
