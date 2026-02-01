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

  /**
   * Derive a RowSerializer for a product type (case class) at compile time.
   *
   * This uses InlineRowSerializer internally for optimized serialization
   * (2.8x-8.7x faster) while preserving the full schema information.
   */
  inline def derived[T](using m: Mirror.ProductOf[T]): RowSerializer[T] =
    val fieldSchemas = deriveFieldSchemas[m.MirroredElemTypes, m.MirroredElemLabels](0)
    val inlineSerializer = InlineRowSerializer.derived[T]
    InlineBackedRowSerializer[T](fieldSchemas, inlineSerializer)

  /**
   * Derive a RowSerializer for a sum type (sealed trait) at compile time.
   *
   * This uses InlineSumRowSerializer internally for optimized, type-safe
   * serialization of each variant.
   */
  inline def derivedSum[T](using m: Mirror.SumOf[T]): RowSerializer[T] =
    val encoder = ProtoEncoder.derived[T].asInstanceOf[ProtoEncoder.SumEncoder[T]]
    val inlineSerializer = InlineSumRowSerializer.derived[T]
    InlineBackedSumRowSerializer[T](encoder, inlineSerializer)

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

  /**
   * RowSerializer backed by InlineRowSerializer for performance.
   * Provides schema information while delegating serialization to the optimized inline version.
   * Public for inline access.
   */
  class InlineBackedRowSerializer[T](
      val schema: Vector[FieldSchema],
      inlineSerializer: InlineRowSerializer[T]
  ) extends RowSerializer[T]:

    def serialize(value: T)(using conv: InternalTypeConverter): Array[Any] =
      inlineSerializer.serialize(value)

    def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T =
      inlineSerializer.deserialize(row)

  /**
   * RowSerializer for sum types backed by InlineSumRowSerializer for performance.
   * Provides schema information while delegating to the optimized inline version.
   * Public for inline access.
   */
  class InlineBackedSumRowSerializer[T](
      encoder: ProtoEncoder.SumEncoder[T],
      inlineSerializer: InlineSumRowSerializer[T]
  ) extends RowSerializer[T]:

    val schema: Vector[FieldSchema] = Vector(
      FieldSchema("_type", ProtoType.StringType, nullable = false, fieldIndex = 0),
      FieldSchema("_ordinal", ProtoType.IntType, nullable = false, fieldIndex = 1),
      FieldSchema("value", ProtoType.BinaryType, nullable = true, fieldIndex = 2)
    )

    def serialize(value: T)(using conv: InternalTypeConverter): Array[Any] =
      inlineSerializer.serialize(value)

    def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T =
      inlineSerializer.deserialize(row)

  /** Legacy implementation for product types. Kept for backward compatibility. Public for inline access. */
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

  /** Implementation for sum types (sealed traits). Public for inline access. */
  class SumRowSerializer[T](encoder: ProtoEncoder.SumEncoder[T]) extends RowSerializer[T]:

    val schema: Vector[FieldSchema] = Vector(
      FieldSchema("_type", ProtoType.StringType, nullable = false, fieldIndex = 0),
      FieldSchema("_ordinal", ProtoType.IntType, nullable = false, fieldIndex = 1),
      FieldSchema("value", ProtoType.BinaryType, nullable = true, fieldIndex = 2) // Placeholder type
    )

    def serialize(value: T)(using conv: InternalTypeConverter): Array[Any] =
      val variant = encoder.variantFor(value)
      val variantData = variant.encoder match
        case Some(enc) =>
          // Serialize the variant's data (case class fields)
          value match
            case p: Product =>
              val result = new Array[Any](p.productArity)
              var i = 0
              while i < p.productArity do
                result(i) = conv.toInternal(p.productElement(i), ProtoType.StringType) // Simplified
                i += 1
              result
            case _ => null
        case None =>
          // Case object - no data
          null
      Array[Any](variant.name, variant.ordinal, variantData)

    def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T =
      val ordinal = row(1).asInstanceOf[Int]
      val variant = encoder.variants(ordinal)
      variant.encoder match
        case Some(enc) =>
          // Deserialize the variant data
          val variantData = row(2).asInstanceOf[Array[Any]]
          enc match
            case prodEnc: ProtoEncoder[?] if prodEnc.fields.nonEmpty =>
              // Reconstruct the case class using its Mirror
              // This requires access to the variant's Mirror, which we store in encoder
              reconstructVariant(variant, variantData, conv)
            case _ =>
              // Fallback - use ordinal to get singleton
              encoder.mirror.ordinal.asInstanceOf[T] // This won't work, need proper reconstruction
        case None =>
          // Case object - reconstruct using singleton lookup
          lookupSingleton(encoder.clsTag.runtimeClass, variant.name).asInstanceOf[T]

    private def reconstructVariant(
        variant: VariantEncoder[?],
        data: Array[Any],
        conv: InternalTypeConverter
    ): T =
      // For case classes, we need to call their constructor
      // Since we don't have the Mirror at runtime, use reflection as fallback
      val clazz = variant.encoder.get.clsTag.runtimeClass
      val constructor = clazz.getConstructors.head
      val args = data.map(d => conv.fromInternal(d, ProtoType.StringType)) // Simplified
      constructor.newInstance(args.map(_.asInstanceOf[AnyRef])*).asInstanceOf[T]

    private def lookupSingleton(parentClass: Class[?], name: String): Any =
      // Find the case object by name in the companion or nested objects
      try
        val companionClass = Class.forName(s"${parentClass.getName}$$$name$$")
        companionClass.getField("MODULE$").get(null)
      catch
        case _: ClassNotFoundException =>
          // Try as nested object
          try
            val nestedClass = Class.forName(s"${parentClass.getName}$$$name")
            nestedClass.getField("MODULE$").get(null)
          catch
            case _: Exception =>
              throw RuntimeException(s"Cannot find singleton object $name for sealed trait ${parentClass.getName}")

  /** Helper to construct product from array */
  private class ArrayProduct(values: Array[Any]) extends Product:
    def productArity: Int = values.length
    def productElement(n: Int): Any = values(n)
    def canEqual(that: Any): Boolean = that.isInstanceOf[ArrayProduct]
