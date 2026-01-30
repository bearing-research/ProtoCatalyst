package protocatalyst.encoder

import protocatalyst.types.*
import scala.deriving.Mirror
import scala.compiletime.*
import scala.reflect.ClassTag

/**
 * Serializes/deserializes case classes to/from row format.
 *
 * This is the foundation for Spark's InternalRow serialization.
 * The serialized format uses Array[Any] with Spark-compatible internal types:
 *   - String → UTF8String (via InternalTypeConverter)
 *   - scala collections → ArrayData
 *   - nested structs → nested rows
 *   - primitives → same
 */
trait RowSerializer[T]:
  /** Schema for this type */
  def schema: Vector[FieldSchema]

  /** Serialize value to row format (Array[Any]) */
  def serialize(value: T)(using conv: InternalTypeConverter): Array[Any]

  /** Deserialize from row format back to T */
  def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T

/** Field schema for row serialization */
case class FieldSchema(
    name: String,
    dataType: ProtoType,
    nullable: Boolean,
    fieldIndex: Int
)

/**
 * Type converter for internal (Spark-compatible) representations.
 *
 * Spark uses different internal types for efficiency:
 *   - String → UTF8String
 *   - Array/Seq → ArrayData
 *   - Map → MapData
 *   - nested struct → InternalRow
 *
 * This trait abstracts the conversion so the encoder module
 * doesn't need a Spark dependency.
 */
trait InternalTypeConverter:
  /** Convert external value to internal representation */
  def toInternal(value: Any, dataType: ProtoType): Any

  /** Convert internal representation back to external value */
  def fromInternal(value: Any, dataType: ProtoType): Any

object InternalTypeConverter:
  /** Default converter - no transformation (for testing without Spark) */
  given default: InternalTypeConverter with
    def toInternal(value: Any, dataType: ProtoType): Any = value
    def fromInternal(value: Any, dataType: ProtoType): Any = value

object RowSerializer:

  /** Derive a RowSerializer for type T at compile time */
  inline def derived[T](using m: Mirror.ProductOf[T]): RowSerializer[T] =
    val fieldSchemas = deriveFieldSchemas[m.MirroredElemTypes, m.MirroredElemLabels](0)
    ProductRowSerializer[T](fieldSchemas, m)

  /** Derive field schemas from tuple types */
  private inline def deriveFieldSchemas[Types <: Tuple, Labels <: Tuple](idx: Int): Vector[FieldSchema] =
    inline (erasedValue[Types], erasedValue[Labels]) match
      case (_: EmptyTuple, _: EmptyTuple) =>
        Vector.empty
      case (_: (t *: ts), _: (l *: ls)) =>
        val name = constValue[l].toString
        val enc = summonEncoder[t]
        val isNullable = isOption[t]
        val schema = FieldSchema(name, enc.catalystType, isNullable, idx)
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
  class ProductRowSerializer[T](
      val schema: Vector[FieldSchema],
      mirror: Mirror.ProductOf[T]
  ) extends RowSerializer[T]:

    def serialize(value: T)(using conv: InternalTypeConverter): Array[Any] =
      val product = value.asInstanceOf[Product]
      val result = new Array[Any](schema.size)
      var i = 0
      while i < schema.size do
        val fieldValue = product.productElement(i)
        val fieldSchema = schema(i)
        result(i) = serializeField(fieldValue, fieldSchema.dataType, fieldSchema.nullable, conv)
        i += 1
      result

    def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T =
      val values = new Array[Any](schema.size)
      var i = 0
      while i < schema.size do
        val fieldSchema = schema(i)
        values(i) = deserializeField(row(i), fieldSchema.dataType, fieldSchema.nullable, conv)
        i += 1
      mirror.fromProduct(ArrayProduct(values))

    private def serializeField(
        value: Any,
        dataType: ProtoType,
        nullable: Boolean,
        conv: InternalTypeConverter
    ): Any =
      if value == null then null
      else if nullable && value.isInstanceOf[Option[?]] then
        value.asInstanceOf[Option[?]] match
          case Some(v) => conv.toInternal(v, unwrapOptionType(dataType))
          case None => null
      else
        conv.toInternal(value, dataType)

    private def deserializeField(
        value: Any,
        dataType: ProtoType,
        nullable: Boolean,
        conv: InternalTypeConverter
    ): Any =
      if nullable then
        if value == null then None
        else Some(conv.fromInternal(value, unwrapOptionType(dataType)))
      else
        conv.fromInternal(value, dataType)

    private def unwrapOptionType(dataType: ProtoType): ProtoType =
      dataType // Option types have the same catalyst type as their element

  /** Helper to construct product from array */
  private class ArrayProduct(values: Array[Any]) extends Product:
    def productArity: Int = values.length
    def productElement(n: Int): Any = values(n)
    def canEqual(that: Any): Boolean = that.isInstanceOf[ArrayProduct]
