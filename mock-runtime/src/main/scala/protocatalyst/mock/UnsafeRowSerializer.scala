package protocatalyst.mock

import protocatalyst.types.*
import protocatalyst.encoder.*
import scala.deriving.Mirror
import scala.compiletime.*

/**
 * Serializes/deserializes case classes to/from UnsafeRow binary format.
 *
 * This provides the same functionality as RowSerializer but produces
 * the optimized binary format used by Spark for efficient execution:
 *   - Zero-copy serialization
 *   - Cache-friendly memory layout
 *   - 8-byte aligned fields
 *
 * Usage:
 * {{{
 * case class User(name: String, age: Int) derives UnsafeRowSerializer
 *
 * val ser = summon[UnsafeRowSerializer[User]]
 * val row: MockUnsafeRow = ser.serialize(User("Alice", 30))
 * val user: User = ser.deserialize(row)
 * }}}
 */
trait UnsafeRowSerializer[T]:
  /** Schema for this type */
  def schema: MockDataType.StructType

  /** Serialize value to UnsafeRow binary format */
  def serialize(value: T): MockUnsafeRow

  /** Deserialize from UnsafeRow back to T */
  def deserialize(row: MockUnsafeRow): T

  /** Get field schemas as vector */
  def fields: Vector[MockStructField] = schema.fields


object UnsafeRowSerializer:

  /** Derive an UnsafeRowSerializer for type T at compile time */
  inline def derived[T](using m: Mirror.ProductOf[T]): UnsafeRowSerializer[T] =
    val fieldSchemas = deriveFieldSchemas[m.MirroredElemTypes, m.MirroredElemLabels](0)
    val structType = MockDataType.StructType(fieldSchemas)
    ProductUnsafeRowSerializer[T](structType, m)

  /** Derive field schemas from tuple types */
  private inline def deriveFieldSchemas[Types <: Tuple, Labels <: Tuple](idx: Int): Vector[MockStructField] =
    inline (erasedValue[Types], erasedValue[Labels]) match
      case (_: EmptyTuple, _: EmptyTuple) =>
        Vector.empty
      case (_: (t *: ts), _: (l *: ls)) =>
        val name = constValue[l].toString
        val enc = summonEncoder[t]
        val isNullable = isOption[t]
        val mockType = TypeConverter.toMock(enc.catalystType)
        val schema = MockStructField(name, mockType, isNullable)
        schema +: deriveFieldSchemas[ts, ls](idx + 1)

  private inline def summonEncoder[T]: ProtoEncoder[T] =
    summonFrom {
      case enc: ProtoEncoder[T] => enc
      case _: Mirror.ProductOf[T] => ProtoEncoder.derived[T]
      case _ => error("Cannot find ProtoEncoder for field type")
    }

  private inline def isOption[T]: Boolean =
    inline erasedValue[T] match
      case _: Option[?] => true
      case _ => false

  /** Implementation for product types (case classes). Public for inline access. */
  class ProductUnsafeRowSerializer[T](
      val schema: MockDataType.StructType,
      mirror: Mirror.ProductOf[T]
  ) extends UnsafeRowSerializer[T]:

    def serialize(value: T): MockUnsafeRow =
      val product = value.asInstanceOf[Product]
      val writer = UnsafeRowWriter(schema.fields.size)

      schema.fields.zipWithIndex.foreach { case (field, i) =>
        val fieldValue = product.productElement(i)
        writeField(writer, i, fieldValue, field.dataType, field.nullable)
      }

      writer.getRow()

    def deserialize(row: MockUnsafeRow): T =
      val values = new Array[Any](schema.fields.size)

      schema.fields.zipWithIndex.foreach { case (field, i) =>
        values(i) = readField(row, i, field.dataType, field.nullable)
      }

      mirror.fromProduct(ArrayProduct(values))

    private def writeField(
        writer: UnsafeRowWriter,
        ordinal: Int,
        value: Any,
        dataType: MockDataType,
        nullable: Boolean
    ): Unit =
      if value == null then
        writer.setNullAt(ordinal)
      else if nullable then
        value match
          case Some(v) => writeNonNullValue(writer, ordinal, v, dataType)
          case None => writer.setNullAt(ordinal)
          case other => writeNonNullValue(writer, ordinal, other, dataType)
      else
        writeNonNullValue(writer, ordinal, value, dataType)

    private def writeNonNullValue(
        writer: UnsafeRowWriter,
        ordinal: Int,
        value: Any,
        dataType: MockDataType
    ): Unit = dataType match
      case MockDataType.BooleanType => writer.write(ordinal, value.asInstanceOf[Boolean])
      case MockDataType.ByteType => writer.write(ordinal, value.asInstanceOf[Byte])
      case MockDataType.ShortType => writer.write(ordinal, value.asInstanceOf[Short])
      case MockDataType.IntegerType => writer.write(ordinal, value.asInstanceOf[Int])
      case MockDataType.LongType => writer.write(ordinal, value.asInstanceOf[Long])
      case MockDataType.FloatType => writer.write(ordinal, value.asInstanceOf[Float])
      case MockDataType.DoubleType => writer.write(ordinal, value.asInstanceOf[Double])
      case MockDataType.StringType => writer.write(ordinal, value.toString)
      case MockDataType.BinaryType => writer.write(ordinal, value.asInstanceOf[Array[Byte]])
      case MockDataType.DateType => writer.write(ordinal, value.asInstanceOf[Int])
      case MockDataType.TimestampType => writer.write(ordinal, value.asInstanceOf[Long])
      case MockDataType.TimestampNTZType => writer.write(ordinal, value.asInstanceOf[Long])
      case MockDataType.DayTimeIntervalType => writer.write(ordinal, value.asInstanceOf[Long])
      case MockDataType.YearMonthIntervalType => writer.write(ordinal, value.asInstanceOf[Int])
      case _: MockDataType.TimeType => writer.write(ordinal, value.asInstanceOf[Long]) // microseconds since midnight
      case MockDataType.CalendarIntervalType =>
        // CalendarInterval is stored as a struct with (months, days, microseconds)
        throw UnsupportedOperationException("CalendarIntervalType not yet supported in UnsafeRow")
      case MockDataType.VariantType =>
        // Variant is stored as binary
        throw UnsupportedOperationException("VariantType not yet supported in UnsafeRow")
      case _: MockDataType.CharType | _: MockDataType.VarcharType =>
        // Char/Varchar are stored as strings
        writer.write(ordinal, value.asInstanceOf[String].getBytes("UTF-8"))
      case _: MockDataType.DecimalType =>
        value match
          case bd: BigDecimal => writer.write(ordinal, bd.underlying.unscaledValue().longValue())
          case l: Long => writer.write(ordinal, l)
          case i: Int => writer.write(ordinal, i.toLong)
          case _ => throw IllegalArgumentException(s"Cannot serialize decimal from: $value")
      case st: MockDataType.StructType =>
        val nestedWriter = UnsafeRowWriter(st.fields.size)
        val product = value.asInstanceOf[Product]
        st.fields.zipWithIndex.foreach { case (f, idx) =>
          writeField(nestedWriter, idx, product.productElement(idx), f.dataType, f.nullable)
        }
        writer.write(ordinal, nestedWriter.getRow())
      case at: MockDataType.ArrayType =>
        val seq = value match
          case arr: Array[?] => arr.toSeq
          case seq: Seq[?] => seq
          case _ => throw IllegalArgumentException(s"Cannot serialize array from: $value")
        writer.writeArray(ordinal, seq.asInstanceOf[Seq[Any]], at.elementType)
      case mt: MockDataType.MapType =>
        val map = value.asInstanceOf[Map[Any, Any]]
        writer.writeMap(ordinal, map, mt.keyType, mt.valueType)

    private def readField(
        row: MockUnsafeRow,
        ordinal: Int,
        dataType: MockDataType,
        nullable: Boolean
    ): Any =
      if row.isNullAt(ordinal) then
        if nullable then None else null
      else
        val value = readNonNullValue(row, ordinal, dataType)
        if nullable then Some(value) else value

    private def readNonNullValue(
        row: MockUnsafeRow,
        ordinal: Int,
        dataType: MockDataType
    ): Any = dataType match
      case MockDataType.BooleanType => row.getBoolean(ordinal)
      case MockDataType.ByteType => row.getByte(ordinal)
      case MockDataType.ShortType => row.getShort(ordinal)
      case MockDataType.IntegerType => row.getInt(ordinal)
      case MockDataType.LongType => row.getLong(ordinal)
      case MockDataType.FloatType => row.getFloat(ordinal)
      case MockDataType.DoubleType => row.getDouble(ordinal)
      case MockDataType.StringType => row.getString(ordinal)
      case MockDataType.BinaryType => row.getBinary(ordinal)
      case MockDataType.DateType => row.getInt(ordinal)
      case MockDataType.TimestampType => row.getLong(ordinal)
      case MockDataType.TimestampNTZType => row.getLong(ordinal)
      case MockDataType.DayTimeIntervalType => row.getLong(ordinal)
      case MockDataType.YearMonthIntervalType => row.getInt(ordinal)
      case _: MockDataType.TimeType => row.getLong(ordinal)
      case MockDataType.CalendarIntervalType =>
        throw UnsupportedOperationException("CalendarIntervalType not yet supported in UnsafeRow")
      case MockDataType.VariantType =>
        throw UnsupportedOperationException("VariantType not yet supported in UnsafeRow")
      case _: MockDataType.CharType | _: MockDataType.VarcharType => row.getString(ordinal)
      case _: MockDataType.DecimalType => row.getDecimal(ordinal)
      case st: MockDataType.StructType =>
        val nestedRow = row.getStruct(ordinal, st.fields.size)
        readStructAsProduct(nestedRow, st)
      case at: MockDataType.ArrayType =>
        val arrData = row.getArray(ordinal)
        readArray(arrData, at.elementType)
      case mt: MockDataType.MapType =>
        val mapData = row.getMap(ordinal)
        mapData.toMap(mt.keyType, mt.valueType)

    private def readStructAsProduct(
        row: MockUnsafeRow,
        structType: MockDataType.StructType
    ): Product =
      val values = new Array[Any](structType.fields.size)
      structType.fields.zipWithIndex.foreach { case (f, i) =>
        values(i) = readField(row, i, f.dataType, f.nullable)
      }
      ArrayProduct(values)

    private def readArray(arrData: MockUnsafeArrayData, elementType: MockDataType): Seq[Any] =
      (0 until arrData.size).map { i =>
        if arrData.isNullAt(i) then null
        else elementType match
          case MockDataType.BooleanType => arrData.getBoolean(i)
          case MockDataType.IntegerType => arrData.getInt(i)
          case MockDataType.LongType => arrData.getLong(i)
          case MockDataType.DoubleType => arrData.getDouble(i)
          case MockDataType.StringType => arrData.getString(i)
          case _ => throw UnsupportedOperationException(s"Unsupported array element type: $elementType")
      }


  /** Helper to construct product from array */
  private[mock] class ArrayProduct(values: Array[Any]) extends Product:
    def productArity: Int = values.length
    def productElement(n: Int): Any = values(n)
    def canEqual(that: Any): Boolean = that.isInstanceOf[ArrayProduct]
    override def toString: String = s"ArrayProduct(${values.mkString(", ")})"
