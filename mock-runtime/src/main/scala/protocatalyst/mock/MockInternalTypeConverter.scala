package protocatalyst.mock

import protocatalyst.encoder.InternalTypeConverter
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
        val product = value.asInstanceOf[Product]
        val values = fields.zipWithIndex.map { case (field, idx) =>
          val rawValue = product.productElement(idx)
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

      // Primitive types - no conversion needed
      case ProtoType.BooleanType | ProtoType.ByteType | ProtoType.ShortType |
           ProtoType.IntType | ProtoType.LongType | ProtoType.FloatType |
           ProtoType.DoubleType | ProtoType.DateType | ProtoType.TimestampType |
           ProtoType.TimestampNTZType | ProtoType.DayTimeIntervalType |
           ProtoType.YearMonthIntervalType | _: ProtoType.DecimalType =>
        value

      // UDT types - convert using the underlying SQL type
      case ProtoType.UDTType(_, sqlType) =>
        toInternal(value, sqlType)

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

      // Primitive types - no conversion needed
      case ProtoType.BooleanType | ProtoType.ByteType | ProtoType.ShortType |
           ProtoType.IntType | ProtoType.LongType | ProtoType.FloatType |
           ProtoType.DoubleType | ProtoType.DateType | ProtoType.TimestampType |
           ProtoType.TimestampNTZType | ProtoType.DayTimeIntervalType |
           ProtoType.YearMonthIntervalType | _: ProtoType.DecimalType =>
        value

      // UDT types - convert using the underlying SQL type
      case ProtoType.UDTType(_, sqlType) =>
        fromInternal(value, sqlType)

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
