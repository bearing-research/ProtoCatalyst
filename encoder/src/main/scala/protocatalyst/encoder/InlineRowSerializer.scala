package protocatalyst.encoder

import protocatalyst.types.*
import scala.deriving.Mirror
import scala.compiletime.*
import scala.reflect.ClassTag

/**
 * Type-specialized row serializer using compile-time inline expansion.
 *
 * This serializer eliminates runtime type dispatch by generating specialized
 * serialization code for each field type at compile time. It uses the same
 * `inline erasedValue[Types]` pattern as ProtoEncoder derivation.
 *
 * Benefits over RowSerializer:
 *   - No runtime type matching in serializeField
 *   - No isInstanceOf[Option[?]] checks
 *   - Compile-time unrolling of field iteration
 *   - Type-specific code paths for primitives, strings, options
 *
 * Usage:
 * {{{
 * case class Person(name: String, age: Int)
 *
 * val serializer = InlineRowSerializer.derived[Person]
 * val row = serializer.serialize(Person("Alice", 30))
 * val person = serializer.deserialize(row)
 * }}}
 */
trait InlineRowSerializer[T]:
  /** Number of fields in the serialized row */
  def fieldCount: Int

  /** Serialize value to row format (Array[Any]) */
  def serialize(value: T)(using conv: InternalTypeConverter): Array[Any]

  /** Deserialize from row format back to T */
  def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T

object InlineRowSerializer:

  /**
   * Derive an inline-specialized serializer at compile time.
   *
   * The serialization logic is generated inline at the call site,
   * with type-specialized code paths for each field.
   */
  inline def derived[T](using m: Mirror.ProductOf[T]): InlineRowSerializer[T] =
    // Field count is computed at compile time
    val count = constValue[Tuple.Size[m.MirroredElemTypes]]

    // Create serialization/deserialization functions that capture the inline expansion
    val serializeFn = (value: T, conv: InternalTypeConverter) =>
      val product = value.asInstanceOf[Product]
      val result = new Array[Any](count)
      serializeFieldsImpl[m.MirroredElemTypes](product, result, 0, conv)
      result

    val deserializeFn = (row: Array[Any], conv: InternalTypeConverter) =>
      val values = new Array[Any](count)
      deserializeFieldsImpl[m.MirroredElemTypes](row, values, 0, conv)
      m.fromProduct(ArrayProduct(values))

    InlineRowSerializerImpl[T](count, serializeFn, deserializeFn)

  /** Implementation class - avoids anonymous class duplication warning */
  class InlineRowSerializerImpl[T](
      val fieldCount: Int,
      serializeFn: (T, InternalTypeConverter) => Array[Any],
      deserializeFn: (Array[Any], InternalTypeConverter) => T
  ) extends InlineRowSerializer[T]:
    def serialize(value: T)(using conv: InternalTypeConverter): Array[Any] =
      serializeFn(value, conv)
    def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T =
      deserializeFn(row, conv)

  // ============================================================
  // Inline serialization with compile-time type specialization
  // ============================================================

  /** Serialize fields with compile-time type dispatch - public for inline access */
  inline def serializeFieldsImpl[Types <: Tuple](
      product: Product,
      result: Array[Any],
      idx: Int,
      conv: InternalTypeConverter
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      // === Primitives - pass through without conversion ===
      case _: (Boolean *: ts) =>
        result(idx) = product.productElement(idx)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (Byte *: ts) =>
        result(idx) = product.productElement(idx)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (Short *: ts) =>
        result(idx) = product.productElement(idx)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (Int *: ts) =>
        result(idx) = product.productElement(idx)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (Long *: ts) =>
        result(idx) = product.productElement(idx)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (Float *: ts) =>
        result(idx) = product.productElement(idx)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (Double *: ts) =>
        result(idx) = product.productElement(idx)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      // === String - needs UTF8String conversion ===
      case _: (String *: ts) =>
        result(idx) = conv.toInternal(product.productElement(idx), ProtoType.StringType)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      // === BigDecimal ===
      case _: (BigDecimal *: ts) =>
        result(idx) = conv.toInternal(product.productElement(idx), ProtoType.DecimalType(38, 18))
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      // === Binary ===
      case _: (Array[Byte] *: ts) =>
        result(idx) = product.productElement(idx)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      // === Option types - specialized null handling ===
      case _: (Option[t] *: ts) =>
        product.productElement(idx) match
          case Some(v) => result(idx) = serializeOptionValue[t](v.asInstanceOf[t], conv)
          case None => result(idx) = null
          case null => result(idx) = null
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      // === Seq/List/Vector collections ===
      case _: (Seq[t] *: ts) =>
        val seq = product.productElement(idx).asInstanceOf[Seq[?]]
        result(idx) = conv.toInternal(seq, ProtoType.ArrayType(getProtoType[t], true))
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (List[t] *: ts) =>
        val list = product.productElement(idx).asInstanceOf[List[?]]
        result(idx) = conv.toInternal(list, ProtoType.ArrayType(getProtoType[t], true))
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (Vector[t] *: ts) =>
        val vec = product.productElement(idx).asInstanceOf[Vector[?]]
        result(idx) = conv.toInternal(vec, ProtoType.ArrayType(getProtoType[t], true))
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (Set[t] *: ts) =>
        val set = product.productElement(idx).asInstanceOf[Set[?]]
        result(idx) = conv.toInternal(set.toSeq, ProtoType.ArrayType(getProtoType[t], true))
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      // === Map ===
      case _: (Map[k, v] *: ts) =>
        val map = product.productElement(idx).asInstanceOf[Map[?, ?]]
        result(idx) = conv.toInternal(map, ProtoType.MapType(getProtoType[k], getProtoType[v], true))
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      // === Temporal types ===
      case _: (java.time.LocalDate *: ts) =>
        result(idx) = conv.toInternal(product.productElement(idx), ProtoType.DateType)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (java.time.Instant *: ts) =>
        result(idx) = conv.toInternal(product.productElement(idx), ProtoType.TimestampType)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (java.time.LocalDateTime *: ts) =>
        result(idx) = conv.toInternal(product.productElement(idx), ProtoType.TimestampNTZType)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (java.sql.Date *: ts) =>
        result(idx) = conv.toInternal(product.productElement(idx), ProtoType.DateType)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      case _: (java.sql.Timestamp *: ts) =>
        result(idx) = conv.toInternal(product.productElement(idx), ProtoType.TimestampType)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

      // === Fallback for other types (nested products, etc.) ===
      case _: (t *: ts) =>
        val fieldValue = product.productElement(idx)
        result(idx) = serializeAnyField[t](fieldValue, conv)
        serializeFieldsImpl[ts](product, result, idx + 1, conv)

  /** Serialize Option inner value with type specialization */
  private inline def serializeOptionValue[T](value: T, conv: InternalTypeConverter): Any =
    inline erasedValue[T] match
      case _: Int => value
      case _: Long => value
      case _: Double => value
      case _: Float => value
      case _: Boolean => value
      case _: Byte => value
      case _: Short => value
      case _: String => conv.toInternal(value, ProtoType.StringType)
      case _ => conv.toInternal(value, getProtoType[T])

  /** Fallback serialization for complex types */
  private inline def serializeAnyField[T](value: Any, conv: InternalTypeConverter): Any =
    if value == null then null
    else
      summonFrom {
        case m: Mirror.ProductOf[T] =>
          // Nested product - serialize recursively
          val product = value.asInstanceOf[Product]
          val nestedSize = constValue[Tuple.Size[m.MirroredElemTypes]]
          val nested = new Array[Any](nestedSize)
          serializeFieldsImpl[m.MirroredElemTypes](product, nested, 0, conv)
          nested
        case _ =>
          // Unknown type - use generic conversion
          conv.toInternal(value, getProtoType[T])
      }

  /** Get ProtoType for a type at compile time */
  private inline def getProtoType[T]: ProtoType =
    inline erasedValue[T] match
      case _: Boolean => ProtoType.BooleanType
      case _: Byte => ProtoType.ByteType
      case _: Short => ProtoType.ShortType
      case _: Int => ProtoType.IntType
      case _: Long => ProtoType.LongType
      case _: Float => ProtoType.FloatType
      case _: Double => ProtoType.DoubleType
      case _: String => ProtoType.StringType
      case _: BigDecimal => ProtoType.DecimalType(38, 18)
      case _: Array[Byte] => ProtoType.BinaryType
      case _: java.time.LocalDate => ProtoType.DateType
      case _: java.time.Instant => ProtoType.TimestampType
      case _: java.time.LocalDateTime => ProtoType.TimestampNTZType
      case _ => ProtoType.StringType // Fallback

  // ============================================================
  // Inline deserialization with compile-time type specialization
  // ============================================================

  /** Deserialize fields with compile-time type dispatch - public for inline access */
  inline def deserializeFieldsImpl[Types <: Tuple](
      row: Array[Any],
      values: Array[Any],
      idx: Int,
      conv: InternalTypeConverter
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      // === Primitives - pass through ===
      case _: (Boolean *: ts) =>
        values(idx) = row(idx)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Byte *: ts) =>
        values(idx) = row(idx)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Short *: ts) =>
        values(idx) = row(idx)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Int *: ts) =>
        values(idx) = row(idx)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Long *: ts) =>
        values(idx) = row(idx)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Float *: ts) =>
        values(idx) = row(idx)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Double *: ts) =>
        values(idx) = row(idx)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === String - needs conversion from UTF8String ===
      case _: (String *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.StringType)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === BigDecimal ===
      case _: (BigDecimal *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.DecimalType(38, 18))
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === Binary ===
      case _: (Array[Byte] *: ts) =>
        values(idx) = row(idx)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === Option types - null → None, value → Some ===
      case _: (Option[t] *: ts) =>
        val rawValue = row(idx)
        values(idx) =
          if rawValue == null then None
          else Some(deserializeOptionValue[t](rawValue, conv))
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === Collections ===
      case _: (Seq[t] *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.ArrayType(getProtoType[t], true))
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (List[t] *: ts) =>
        val seq = conv.fromInternal(row(idx), ProtoType.ArrayType(getProtoType[t], true))
        values(idx) = seq.asInstanceOf[Seq[?]].toList
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Vector[t] *: ts) =>
        val seq = conv.fromInternal(row(idx), ProtoType.ArrayType(getProtoType[t], true))
        values(idx) = seq.asInstanceOf[Seq[?]].toVector
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Set[t] *: ts) =>
        val seq = conv.fromInternal(row(idx), ProtoType.ArrayType(getProtoType[t], true))
        values(idx) = seq.asInstanceOf[Seq[?]].toSet
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === Map ===
      case _: (Map[k, v] *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.MapType(getProtoType[k], getProtoType[v], true))
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === Temporal types ===
      case _: (java.time.LocalDate *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.DateType)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (java.time.Instant *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.TimestampType)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (java.time.LocalDateTime *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.TimestampNTZType)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (java.sql.Date *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.DateType)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (java.sql.Timestamp *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.TimestampType)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === Fallback for other types ===
      case _: (t *: ts) =>
        values(idx) = deserializeAnyField[t](row(idx), conv)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

  /** Deserialize Option inner value */
  private inline def deserializeOptionValue[T](value: Any, conv: InternalTypeConverter): T =
    inline erasedValue[T] match
      case _: Int => value.asInstanceOf[T]
      case _: Long => value.asInstanceOf[T]
      case _: Double => value.asInstanceOf[T]
      case _: Float => value.asInstanceOf[T]
      case _: Boolean => value.asInstanceOf[T]
      case _: Byte => value.asInstanceOf[T]
      case _: Short => value.asInstanceOf[T]
      case _: String => conv.fromInternal(value, ProtoType.StringType).asInstanceOf[T]
      case _ => conv.fromInternal(value, getProtoType[T]).asInstanceOf[T]

  /** Fallback deserialization for complex types */
  private inline def deserializeAnyField[T](value: Any, conv: InternalTypeConverter): T =
    if value == null then null.asInstanceOf[T]
    else
      summonFrom {
        case m: Mirror.ProductOf[T] =>
          // Nested product - deserialize recursively
          val row = value.asInstanceOf[Array[Any]]
          val nestedSize = constValue[Tuple.Size[m.MirroredElemTypes]]
          val nested = new Array[Any](nestedSize)
          deserializeFieldsImpl[m.MirroredElemTypes](row, nested, 0, conv)
          m.fromProduct(ArrayProduct(nested))
        case _ =>
          // Unknown type - use generic conversion
          conv.fromInternal(value, getProtoType[T]).asInstanceOf[T]
      }

  /** Helper to construct product from array - public for inline access */
  class ArrayProduct(values: Array[Any]) extends Product:
    def productArity: Int = values.length
    def productElement(n: Int): Any = values(n)
    def canEqual(that: Any): Boolean = that.isInstanceOf[ArrayProduct]
