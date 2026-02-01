package protocatalyst.mock

import java.nio.{ByteBuffer, ByteOrder}
import scala.collection.mutable.ArrayBuffer

/**
 * Mock implementation of Spark's UnsafeRow binary format.
 *
 * UnsafeRow is Spark's optimized row format that stores data in a contiguous
 * byte array for CPU cache efficiency and zero-copy serialization.
 *
 * Memory Layout:
 * {{{
 * [Null Bitmap][Fixed-Width Values][Variable-Width Data]
 *
 * - Null Bitmap: 1 bit per field, rounded up to 8-byte word boundary
 * - Fixed-Width: 8 bytes per field (primitives stored directly, var-length as offset+length)
 * - Variable-Width: Actual bytes for strings, arrays, nested rows
 * }}}
 *
 * For a row with N fields:
 * - Null bitmap size: ((N + 63) / 64) * 8 bytes
 * - Fixed-width region: N * 8 bytes
 * - Variable-width region: varies based on actual data
 */
class MockUnsafeRow private (
    private[mock] val baseArray: Array[Byte],
    private[mock] val baseOffset: Int,
    private[mock] val sizeInBytes: Int,
    val numFields: Int
):
  import MockUnsafeRow.*

  /** Number of 8-byte words needed for the null bitmap */
  private val nullBitmapWidthInBytes: Int = calculateNullBitmapSize(numFields)

  /** Offset to the fixed-width value region */
  private val fixedWidthOffset: Int = baseOffset + nullBitmapWidthInBytes

  // ============================================
  // Null Handling
  // ============================================

  def isNullAt(ordinal: Int): Boolean =
    assertValidOrdinal(ordinal)
    val bitSetIdx = ordinal / 64
    val bitIdx = ordinal % 64
    val word = readLongAt(baseOffset + bitSetIdx * 8)
    (word & (1L << bitIdx)) != 0

  private[mock] def setNullAt(ordinal: Int): Unit =
    assertValidOrdinal(ordinal)
    val bitSetIdx = ordinal / 64
    val bitIdx = ordinal % 64
    val offset = baseOffset + bitSetIdx * 8
    val word = readLongAt(offset)
    writeLongAt(offset, word | (1L << bitIdx))

  private[mock] def clearNullAt(ordinal: Int): Unit =
    assertValidOrdinal(ordinal)
    val bitSetIdx = ordinal / 64
    val bitIdx = ordinal % 64
    val offset = baseOffset + bitSetIdx * 8
    val word = readLongAt(offset)
    writeLongAt(offset, word & ~(1L << bitIdx))

  // ============================================
  // Fixed-Width Accessors
  // ============================================

  private def getFieldOffset(ordinal: Int): Int =
    fixedWidthOffset + ordinal * 8

  def getBoolean(ordinal: Int): Boolean =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then throw NullPointerException(s"Field $ordinal is null")
    baseArray(getFieldOffset(ordinal)) != 0

  def getByte(ordinal: Int): Byte =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then throw NullPointerException(s"Field $ordinal is null")
    baseArray(getFieldOffset(ordinal))

  def getShort(ordinal: Int): Short =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then throw NullPointerException(s"Field $ordinal is null")
    readShortAt(getFieldOffset(ordinal))

  def getInt(ordinal: Int): Int =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then throw NullPointerException(s"Field $ordinal is null")
    readIntAt(getFieldOffset(ordinal))

  def getLong(ordinal: Int): Long =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then throw NullPointerException(s"Field $ordinal is null")
    readLongAt(getFieldOffset(ordinal))

  def getFloat(ordinal: Int): Float =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then throw NullPointerException(s"Field $ordinal is null")
    java.lang.Float.intBitsToFloat(readIntAt(getFieldOffset(ordinal)))

  def getDouble(ordinal: Int): Double =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then throw NullPointerException(s"Field $ordinal is null")
    java.lang.Double.longBitsToDouble(readLongAt(getFieldOffset(ordinal)))

  // ============================================
  // Variable-Width Accessors
  // ============================================

  /**
   * Get the offset and length of a variable-length field.
   * The fixed-width slot stores: upper 32 bits = offset, lower 32 bits = length
   */
  private def getOffsetAndSize(ordinal: Int): (Int, Int) =
    val offsetAndSize = readLongAt(getFieldOffset(ordinal))
    val offset = (offsetAndSize >> 32).toInt
    val size = offsetAndSize.toInt
    (baseOffset + offset, size)

  def getBinary(ordinal: Int): Array[Byte] =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then null
    else
      val (offset, size) = getOffsetAndSize(ordinal)
      val result = new Array[Byte](size)
      System.arraycopy(baseArray, offset, result, 0, size)
      result

  def getString(ordinal: Int): String =
    val bytes = getBinary(ordinal)
    if bytes == null then null
    else new String(bytes, "UTF-8")

  def getUTF8String(ordinal: Int): MockUTF8String =
    val bytes = getBinary(ordinal)
    if bytes == null then null
    else MockUTF8String.fromBytes(bytes)

  /**
   * Get a nested struct as an UnsafeRow.
   * The variable-length region contains another UnsafeRow's bytes.
   */
  def getStruct(ordinal: Int, numFields: Int): MockUnsafeRow =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then null
    else
      val (offset, size) = getOffsetAndSize(ordinal)
      new MockUnsafeRow(baseArray, offset, size, numFields)

  def getArray(ordinal: Int): MockUnsafeArrayData =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then null
    else
      val (offset, size) = getOffsetAndSize(ordinal)
      new MockUnsafeArrayData(baseArray, offset, size)

  def getMap(ordinal: Int): MockUnsafeMapData =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then null
    else
      val (offset, size) = getOffsetAndSize(ordinal)
      new MockUnsafeMapData(baseArray, offset, size)

  // ============================================
  // Generic Accessor
  // ============================================

  def get(ordinal: Int, dataType: MockDataType): Any =
    if isNullAt(ordinal) then null
    else dataType match
      case MockDataType.BooleanType => getBoolean(ordinal)
      case MockDataType.ByteType => getByte(ordinal)
      case MockDataType.ShortType => getShort(ordinal)
      case MockDataType.IntegerType => getInt(ordinal)
      case MockDataType.LongType => getLong(ordinal)
      case MockDataType.FloatType => getFloat(ordinal)
      case MockDataType.DoubleType => getDouble(ordinal)
      case MockDataType.StringType => getUTF8String(ordinal)
      case MockDataType.BinaryType => getBinary(ordinal)
      case MockDataType.DateType => getInt(ordinal) // Days since epoch
      case MockDataType.TimestampType => getLong(ordinal) // Micros since epoch
      case MockDataType.TimestampNTZType => getLong(ordinal)
      case MockDataType.DayTimeIntervalType => getLong(ordinal) // Duration as micros
      case MockDataType.YearMonthIntervalType => getInt(ordinal) // Period as months
      case _: MockDataType.TimeType => getLong(ordinal) // Time as micros since midnight
      case MockDataType.CalendarIntervalType =>
        throw UnsupportedOperationException("CalendarIntervalType not yet supported in UnsafeRow")
      case MockDataType.VariantType =>
        throw UnsupportedOperationException("VariantType not yet supported in UnsafeRow")
      case _: MockDataType.CharType | _: MockDataType.VarcharType => getUTF8String(ordinal)
      case _: MockDataType.DecimalType => getDecimal(ordinal)
      case st: MockDataType.StructType => getStruct(ordinal, st.fields.size)
      case _: MockDataType.ArrayType => getArray(ordinal)
      case _: MockDataType.MapType => getMap(ordinal)

  def getDecimal(ordinal: Int): BigDecimal =
    assertValidOrdinal(ordinal)
    if isNullAt(ordinal) then null
    else
      // For simplicity, store decimals as their unscaled Long value
      // Real Spark has more complex handling for large decimals
      val unscaledLong = readLongAt(getFieldOffset(ordinal))
      BigDecimal(unscaledLong)

  // ============================================
  // Utility Methods
  // ============================================

  def copy(): MockUnsafeRow =
    val newArray = new Array[Byte](sizeInBytes)
    System.arraycopy(baseArray, baseOffset, newArray, 0, sizeInBytes)
    new MockUnsafeRow(newArray, 0, sizeInBytes, numFields)

  def getBytes: Array[Byte] =
    if baseOffset == 0 && baseArray.length == sizeInBytes then baseArray
    else
      val result = new Array[Byte](sizeInBytes)
      System.arraycopy(baseArray, baseOffset, result, 0, sizeInBytes)
      result

  def toMockRow(schema: MockDataType.StructType): MockRow =
    val values = schema.fields.zipWithIndex.map { case (field, i) =>
      get(i, field.dataType)
    }
    MockRow(values)

  override def toString: String =
    s"MockUnsafeRow[numFields=$numFields, sizeInBytes=$sizeInBytes]"

  override def equals(other: Any): Boolean = other match
    case that: MockUnsafeRow =>
      if this.numFields != that.numFields || this.sizeInBytes != that.sizeInBytes then false
      else
        var i = 0
        while i < sizeInBytes do
          if this.baseArray(this.baseOffset + i) != that.baseArray(that.baseOffset + i) then
            return false
          i += 1
        true
    case _ => false

  override def hashCode(): Int =
    var hash = 17
    var i = 0
    while i < sizeInBytes do
      hash = 31 * hash + baseArray(baseOffset + i)
      i += 1
    hash

  // ============================================
  // Low-Level Memory Access
  // ============================================

  private def readLongAt(offset: Int): Long =
    ByteBuffer.wrap(baseArray, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong

  private def writeLongAt(offset: Int, value: Long): Unit =
    ByteBuffer.wrap(baseArray, offset, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(value)

  private def readIntAt(offset: Int): Int =
    ByteBuffer.wrap(baseArray, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt

  private def readShortAt(offset: Int): Short =
    ByteBuffer.wrap(baseArray, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort

  private def assertValidOrdinal(ordinal: Int): Unit =
    if ordinal < 0 || ordinal >= numFields then
      throw IndexOutOfBoundsException(s"Ordinal $ordinal out of bounds [0, $numFields)")


object MockUnsafeRow:

  /** Calculate the size of the null bitmap in bytes (8-byte aligned) */
  def calculateNullBitmapSize(numFields: Int): Int =
    ((numFields + 63) / 64) * 8

  /** Calculate the fixed-width region size */
  def calculateFixedWidthSize(numFields: Int): Int =
    numFields * 8

  /** Calculate the minimum row size (without variable-length data) */
  def calculateMinimumSize(numFields: Int): Int =
    calculateNullBitmapSize(numFields) + calculateFixedWidthSize(numFields)

  /** Create an UnsafeRow from an existing byte array */
  def fromBytes(bytes: Array[Byte], numFields: Int): MockUnsafeRow =
    new MockUnsafeRow(bytes, 0, bytes.length, numFields)

  /** Create an UnsafeRow pointing to a region within a byte array */
  def pointTo(array: Array[Byte], offset: Int, sizeInBytes: Int, numFields: Int): MockUnsafeRow =
    new MockUnsafeRow(array, offset, sizeInBytes, numFields)


/**
 * Mock UnsafeArrayData - binary format for arrays.
 *
 * Layout:
 * {{{
 * [numElements (8 bytes)][null bitmap][fixed-width values][variable-width data]
 * }}}
 */
class MockUnsafeArrayData private[mock] (
    private val baseArray: Array[Byte],
    private val baseOffset: Int,
    private val sizeInBytes: Int
):
  private val numElements: Int =
    ByteBuffer.wrap(baseArray, baseOffset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong.toInt

  private val nullBitmapSize: Int = MockUnsafeRow.calculateNullBitmapSize(numElements)
  private val fixedWidthOffset: Int = baseOffset + 8 + nullBitmapSize

  def size: Int = numElements

  def isNullAt(ordinal: Int): Boolean =
    if ordinal < 0 || ordinal >= numElements then
      throw IndexOutOfBoundsException(s"Ordinal $ordinal out of bounds [0, $numElements)")
    val bitSetIdx = ordinal / 64
    val bitIdx = ordinal % 64
    val word = readLongAt(baseOffset + 8 + bitSetIdx * 8)
    (word & (1L << bitIdx)) != 0

  def getBoolean(ordinal: Int): Boolean =
    if isNullAt(ordinal) then throw NullPointerException(s"Element $ordinal is null")
    baseArray(fixedWidthOffset + ordinal * 8) != 0

  def getInt(ordinal: Int): Int =
    if isNullAt(ordinal) then throw NullPointerException(s"Element $ordinal is null")
    ByteBuffer.wrap(baseArray, fixedWidthOffset + ordinal * 8, 4)
      .order(ByteOrder.LITTLE_ENDIAN).getInt

  def getLong(ordinal: Int): Long =
    if isNullAt(ordinal) then throw NullPointerException(s"Element $ordinal is null")
    readLongAt(fixedWidthOffset + ordinal * 8)

  def getDouble(ordinal: Int): Double =
    if isNullAt(ordinal) then throw NullPointerException(s"Element $ordinal is null")
    java.lang.Double.longBitsToDouble(readLongAt(fixedWidthOffset + ordinal * 8))

  def getString(ordinal: Int): String =
    if isNullAt(ordinal) then null
    else
      val offsetAndSize = readLongAt(fixedWidthOffset + ordinal * 8)
      val offset = baseOffset + (offsetAndSize >> 32).toInt
      val size = offsetAndSize.toInt
      new String(baseArray, offset, size, "UTF-8")

  def toArray[T](dataType: MockDataType): Array[Any] =
    (0 until numElements).map { i =>
      if isNullAt(i) then null
      else dataType match
        case MockDataType.BooleanType => getBoolean(i)
        case MockDataType.IntegerType => getInt(i)
        case MockDataType.LongType => getLong(i)
        case MockDataType.DoubleType => getDouble(i)
        case MockDataType.StringType => getString(i)
        case _ => throw UnsupportedOperationException(s"Unsupported array element type: $dataType")
    }.toArray

  private def readLongAt(offset: Int): Long =
    ByteBuffer.wrap(baseArray, offset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong


/**
 * Mock UnsafeMapData - binary format for maps.
 *
 * Layout:
 * {{{
 * [keyArraySize (8 bytes)][keyArrayData...][valueArrayData...]
 * }}}
 */
class MockUnsafeMapData private[mock] (
    private val baseArray: Array[Byte],
    private val baseOffset: Int,
    private val sizeInBytes: Int
):
  private val keyArraySize: Int =
    ByteBuffer.wrap(baseArray, baseOffset, 8).order(ByteOrder.LITTLE_ENDIAN).getLong.toInt

  val keyArray: MockUnsafeArrayData =
    new MockUnsafeArrayData(baseArray, baseOffset + 8, keyArraySize)

  val valueArray: MockUnsafeArrayData =
    new MockUnsafeArrayData(baseArray, baseOffset + 8 + keyArraySize, sizeInBytes - 8 - keyArraySize)

  def numElements: Int = keyArray.size

  def toMap(keyType: MockDataType, valueType: MockDataType): Map[Any, Any] =
    val keys = keyArray.toArray(keyType)
    val values = valueArray.toArray(valueType)
    keys.zip(values).toMap
