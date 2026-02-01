package protocatalyst.mock

import java.nio.{ByteBuffer, ByteOrder}

import scala.collection.mutable.ArrayBuffer

/** Writer for constructing MockUnsafeRow instances.
  *
  * Handles the complexity of the binary layout:
  *   - Null bitmap management
  *   - Fixed-width value storage
  *   - Variable-width data accumulation
  *
  * Usage:
  * {{{
  * val writer = UnsafeRowWriter(3)
  * writer.write(0, 42)              // int
  * writer.write(1, "hello")         // string
  * writer.setNullAt(2)              // null
  * val row = writer.getRow()
  * }}}
  */
class UnsafeRowWriter(numFields: Int):
  import UnsafeRowWriter.*

  private val nullBitmapSize = MockUnsafeRow.calculateNullBitmapSize(numFields)
  private val fixedWidthSize = MockUnsafeRow.calculateFixedWidthSize(numFields)
  private val fixedWidthOffset = nullBitmapSize

  // Fixed region: null bitmap + fixed-width values
  private val fixedRegion = new Array[Byte](nullBitmapSize + fixedWidthSize)

  // Variable-length data accumulator
  private val variableRegion = ArrayBuffer[Byte]()

  // Track which fields have been written
  private val written = new Array[Boolean](numFields)

  /** Reset the writer for reuse.
    */
  def reset(): Unit =
    java.util.Arrays.fill(fixedRegion, 0.toByte)
    variableRegion.clear()
    java.util.Arrays.fill(written, false)

  /** Mark a field as null.
    */
  def setNullAt(ordinal: Int): Unit =
    assertValidOrdinal(ordinal)
    val bitSetIdx = ordinal / 64
    val bitIdx = ordinal % 64
    val offset = bitSetIdx * 8
    val word = getLong(fixedRegion, offset)
    putLong(fixedRegion, offset, word | (1L << bitIdx))
    written(ordinal) = true

  // ============================================
  // Fixed-Width Writers
  // ============================================

  def write(ordinal: Int, value: Boolean): Unit =
    assertValidOrdinal(ordinal)
    clearNullBit(ordinal)
    val offset = getFieldOffset(ordinal)
    fixedRegion(offset) = if value then 1.toByte else 0.toByte
    written(ordinal) = true

  def write(ordinal: Int, value: Byte): Unit =
    assertValidOrdinal(ordinal)
    clearNullBit(ordinal)
    val offset = getFieldOffset(ordinal)
    fixedRegion(offset) = value
    written(ordinal) = true

  def write(ordinal: Int, value: Short): Unit =
    assertValidOrdinal(ordinal)
    clearNullBit(ordinal)
    val offset = getFieldOffset(ordinal)
    putShort(fixedRegion, offset, value)
    written(ordinal) = true

  def write(ordinal: Int, value: Int): Unit =
    assertValidOrdinal(ordinal)
    clearNullBit(ordinal)
    val offset = getFieldOffset(ordinal)
    putInt(fixedRegion, offset, value)
    written(ordinal) = true

  def write(ordinal: Int, value: Long): Unit =
    assertValidOrdinal(ordinal)
    clearNullBit(ordinal)
    val offset = getFieldOffset(ordinal)
    putLong(fixedRegion, offset, value)
    written(ordinal) = true

  def write(ordinal: Int, value: Float): Unit =
    assertValidOrdinal(ordinal)
    clearNullBit(ordinal)
    val offset = getFieldOffset(ordinal)
    putInt(fixedRegion, offset, java.lang.Float.floatToIntBits(value))
    written(ordinal) = true

  def write(ordinal: Int, value: Double): Unit =
    assertValidOrdinal(ordinal)
    clearNullBit(ordinal)
    val offset = getFieldOffset(ordinal)
    putLong(fixedRegion, offset, java.lang.Double.doubleToLongBits(value))
    written(ordinal) = true

  // ============================================
  // Variable-Width Writers
  // ============================================

  def write(ordinal: Int, value: String): Unit =
    if value == null then setNullAt(ordinal)
    else write(ordinal, value.getBytes("UTF-8"))

  def write(ordinal: Int, value: MockUTF8String): Unit =
    if value == null then setNullAt(ordinal)
    else write(ordinal, value.getBytes)

  def write(ordinal: Int, value: Array[Byte]): Unit =
    assertValidOrdinal(ordinal)
    if value == null then setNullAt(ordinal)
    else
      clearNullBit(ordinal)
      // Offset is relative to the start of the row
      val offset = fixedRegion.length + variableRegion.length
      val length = value.length

      // Store offset (upper 32 bits) and length (lower 32 bits)
      val offsetAndSize = (offset.toLong << 32) | (length.toLong & 0xffffffffL)
      val fieldOffset = getFieldOffset(ordinal)
      putLong(fixedRegion, fieldOffset, offsetAndSize)

      // Append the actual bytes
      variableRegion ++= value

      // Pad to 8-byte alignment
      val padding = roundUpTo8(length) - length
      (0 until padding).foreach(_ => variableRegion += 0.toByte)

      written(ordinal) = true

  /** Write a nested struct as an UnsafeRow.
    */
  def write(ordinal: Int, value: MockUnsafeRow): Unit =
    assertValidOrdinal(ordinal)
    if value == null then setNullAt(ordinal)
    else write(ordinal, value.getBytes)

  /** Write an array in UnsafeArrayData format.
    */
  def writeArray(ordinal: Int, elements: Seq[Any], elementType: MockDataType): Unit =
    assertValidOrdinal(ordinal)
    if elements == null then setNullAt(ordinal)
    else
      val arrayBytes = UnsafeArrayWriter.write(elements, elementType)
      write(ordinal, arrayBytes)

  /** Write a map in UnsafeMapData format.
    */
  def writeMap(
      ordinal: Int,
      entries: Map[Any, Any],
      keyType: MockDataType,
      valueType: MockDataType
  ): Unit =
    assertValidOrdinal(ordinal)
    if entries == null then setNullAt(ordinal)
    else
      val mapBytes = UnsafeMapWriter.write(entries, keyType, valueType)
      write(ordinal, mapBytes)

  // ============================================
  // Type-Aware Writer
  // ============================================

  def write(ordinal: Int, value: Any, dataType: MockDataType): Unit =
    if value == null then setNullAt(ordinal)
    else
      dataType match
        case MockDataType.BooleanType => write(ordinal, value.asInstanceOf[Boolean])
        case MockDataType.ByteType    => write(ordinal, value.asInstanceOf[Byte])
        case MockDataType.ShortType   => write(ordinal, value.asInstanceOf[Short])
        case MockDataType.IntegerType => write(ordinal, value.asInstanceOf[Int])
        case MockDataType.LongType    => write(ordinal, value.asInstanceOf[Long])
        case MockDataType.FloatType   => write(ordinal, value.asInstanceOf[Float])
        case MockDataType.DoubleType  => write(ordinal, value.asInstanceOf[Double])
        case MockDataType.StringType  =>
          value match
            case s: String         => write(ordinal, s)
            case u: MockUTF8String => write(ordinal, u)
            case _                 => write(ordinal, value.toString)
        case MockDataType.BinaryType => write(ordinal, value.asInstanceOf[Array[Byte]])
        case MockDataType.DateType   => write(ordinal, value.asInstanceOf[Int]) // Days since epoch
        case MockDataType.TimestampType =>
          write(ordinal, value.asInstanceOf[Long]) // Micros since epoch
        case MockDataType.TimestampNTZType      => write(ordinal, value.asInstanceOf[Long])
        case MockDataType.DayTimeIntervalType   => write(ordinal, value.asInstanceOf[Long])
        case MockDataType.YearMonthIntervalType => write(ordinal, value.asInstanceOf[Int])
        case _: MockDataType.TimeType           => write(ordinal, value.asInstanceOf[Long])
        case MockDataType.CalendarIntervalType  =>
          throw UnsupportedOperationException("CalendarIntervalType not yet supported in UnsafeRow")
        case MockDataType.VariantType =>
          throw UnsupportedOperationException("VariantType not yet supported in UnsafeRow")
        case _: MockDataType.CharType | _: MockDataType.VarcharType =>
          value match
            case s: String         => write(ordinal, s)
            case u: MockUTF8String => write(ordinal, u)
            case _                 => write(ordinal, value.toString)
        case _: MockDataType.DecimalType =>
          value match
            case bd: BigDecimal => write(ordinal, bd.underlying.unscaledValue().longValue())
            case l: Long        => write(ordinal, l)
            case _ => throw IllegalArgumentException(s"Cannot write decimal from: $value")
        case st: MockDataType.StructType =>
          value match
            case row: MockUnsafeRow => write(ordinal, row)
            case row: MockRow       => writeStruct(ordinal, row, st)
            case _ => throw IllegalArgumentException(s"Cannot write struct from: $value")
        case at: MockDataType.ArrayType =>
          value match
            case arr: MockUnsafeArrayData =>
              write(ordinal, arr.toArray(at.elementType).toSeq, at.elementType)
            case arr: Seq[?]   => writeArray(ordinal, arr.asInstanceOf[Seq[Any]], at.elementType)
            case arr: Array[?] =>
              writeArray(ordinal, arr.toSeq.asInstanceOf[Seq[Any]], at.elementType)
            case _ => throw IllegalArgumentException(s"Cannot write array from: $value")
        case mt: MockDataType.MapType =>
          value match
            case m: Map[?, ?] =>
              writeMap(ordinal, m.asInstanceOf[Map[Any, Any]], mt.keyType, mt.valueType)
            case _ => throw IllegalArgumentException(s"Cannot write map from: $value")

  /** Write a MockRow as a nested struct.
    */
  def writeStruct(ordinal: Int, row: MockRow, structType: MockDataType.StructType): Unit =
    val nestedWriter = UnsafeRowWriter(structType.fields.size)
    structType.fields.zipWithIndex.foreach { case (field, i) =>
      val value = row.get(i)
      nestedWriter.write(i, value, field.dataType)
    }
    write(ordinal, nestedWriter.getRow())

  // ============================================
  // Result Builder
  // ============================================

  /** Build and return the completed UnsafeRow.
    */
  def getRow(): MockUnsafeRow =
    // Verify all fields have been written
    (0 until numFields).foreach { i =>
      if !written(i) then throw IllegalStateException(s"Field $i was not written")
    }

    val totalSize = fixedRegion.length + variableRegion.length
    val result = new Array[Byte](totalSize)

    System.arraycopy(fixedRegion, 0, result, 0, fixedRegion.length)
    if variableRegion.nonEmpty then
      System.arraycopy(variableRegion.toArray, 0, result, fixedRegion.length, variableRegion.length)

    MockUnsafeRow.fromBytes(result, numFields)

  // ============================================
  // Helpers
  // ============================================

  private def getFieldOffset(ordinal: Int): Int =
    fixedWidthOffset + ordinal * 8

  private def clearNullBit(ordinal: Int): Unit =
    val bitSetIdx = ordinal / 64
    val bitIdx = ordinal % 64
    val offset = bitSetIdx * 8
    val word = getLong(fixedRegion, offset)
    putLong(fixedRegion, offset, word & ~(1L << bitIdx))

  private def assertValidOrdinal(ordinal: Int): Unit =
    if ordinal < 0 || ordinal >= numFields then
      throw IndexOutOfBoundsException(s"Ordinal $ordinal out of bounds [0, $numFields)")

object UnsafeRowWriter:
  /** Round up to the nearest multiple of 8 */
  def roundUpTo8(n: Int): Int = ((n + 7) / 8) * 8

  /** Read a Long from a byte array (little-endian) */
  def getLong(arr: Array[Byte], offset: Int): Long =
    ByteBuffer.wrap(arr, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong

  /** Write a Long to a byte array (little-endian) */
  def putLong(arr: Array[Byte], offset: Int, value: Long): Unit =
    ByteBuffer.wrap(arr, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)

  /** Write an Int to a byte array (little-endian) */
  def putInt(arr: Array[Byte], offset: Int, value: Int): Unit =
    ByteBuffer.wrap(arr, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)

  /** Write a Short to a byte array (little-endian) */
  def putShort(arr: Array[Byte], offset: Int, value: Short): Unit =
    ByteBuffer.wrap(arr, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value)

  /** Create an UnsafeRow from a MockRow and schema.
    */
  def fromMockRow(row: MockRow, schema: MockDataType.StructType): MockUnsafeRow =
    val writer = UnsafeRowWriter(schema.fields.size)
    schema.fields.zipWithIndex.foreach { case (field, i) =>
      writer.write(i, row.get(i), field.dataType)
    }
    writer.getRow()

/** Writer for UnsafeArrayData format.
  */
object UnsafeArrayWriter:
  import UnsafeRowWriter.*

  def write(elements: Seq[Any], elementType: MockDataType): Array[Byte] =
    val numElements = elements.size
    val nullBitmapSize = MockUnsafeRow.calculateNullBitmapSize(numElements)
    val fixedRegionSize = 8 + nullBitmapSize + numElements * 8 // 8 for numElements

    val fixedRegion = new Array[Byte](fixedRegionSize)
    val variableRegion = ArrayBuffer[Byte]()

    // Write numElements
    putLong(fixedRegion, 0, numElements.toLong)

    // Write each element
    elements.zipWithIndex.foreach { case (elem, i) =>
      if elem == null then
        // Set null bit
        val bitSetIdx = i / 64
        val bitIdx = i % 64
        val offset = 8 + bitSetIdx * 8
        val word = getLong(fixedRegion, offset)
        putLong(fixedRegion, offset, word | (1L << bitIdx))
      else
        val valueOffset = 8 + nullBitmapSize + i * 8
        elementType match
          case MockDataType.BooleanType =>
            fixedRegion(valueOffset) = if elem.asInstanceOf[Boolean] then 1.toByte else 0.toByte
          case MockDataType.ByteType =>
            fixedRegion(valueOffset) = elem.asInstanceOf[Byte]
          case MockDataType.ShortType =>
            putShort(fixedRegion, valueOffset, elem.asInstanceOf[Short])
          case MockDataType.IntegerType =>
            putInt(fixedRegion, valueOffset, elem.asInstanceOf[Int])
          case MockDataType.LongType =>
            putLong(fixedRegion, valueOffset, elem.asInstanceOf[Long])
          case MockDataType.FloatType =>
            putInt(
              fixedRegion,
              valueOffset,
              java.lang.Float.floatToIntBits(elem.asInstanceOf[Float])
            )
          case MockDataType.DoubleType =>
            putLong(
              fixedRegion,
              valueOffset,
              java.lang.Double.doubleToLongBits(elem.asInstanceOf[Double])
            )
          case MockDataType.StringType =>
            val bytes = elem match
              case s: String         => s.getBytes("UTF-8")
              case u: MockUTF8String => u.getBytes
              case other             => other.toString.getBytes("UTF-8")
            val varOffset = fixedRegion.length + variableRegion.length
            val offsetAndSize = (varOffset.toLong << 32) | (bytes.length.toLong & 0xffffffffL)
            putLong(fixedRegion, valueOffset, offsetAndSize)
            variableRegion ++= bytes
            val padding = roundUpTo8(bytes.length) - bytes.length
            (0 until padding).foreach(_ => variableRegion += 0.toByte)
          case _ =>
            throw UnsupportedOperationException(s"Unsupported array element type: $elementType")
    }

    val result = new Array[Byte](fixedRegion.length + variableRegion.length)
    System.arraycopy(fixedRegion, 0, result, 0, fixedRegion.length)
    if variableRegion.nonEmpty then
      System.arraycopy(variableRegion.toArray, 0, result, fixedRegion.length, variableRegion.length)
    result

/** Writer for UnsafeMapData format.
  */
object UnsafeMapWriter:
  import UnsafeRowWriter.*

  def write(entries: Map[Any, Any], keyType: MockDataType, valueType: MockDataType): Array[Byte] =
    val keys = entries.keys.toSeq
    val values = entries.values.toSeq

    val keyArrayBytes = UnsafeArrayWriter.write(keys, keyType)
    val valueArrayBytes = UnsafeArrayWriter.write(values, valueType)

    // Layout: [keyArraySize (8 bytes)][keyArrayData][valueArrayData]
    val result = new Array[Byte](8 + keyArrayBytes.length + valueArrayBytes.length)
    putLong(result, 0, keyArrayBytes.length.toLong)
    System.arraycopy(keyArrayBytes, 0, result, 8, keyArrayBytes.length)
    System.arraycopy(valueArrayBytes, 0, result, 8 + keyArrayBytes.length, valueArrayBytes.length)

    result
