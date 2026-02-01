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

      // === Collections (order matters: specific types before general Seq) ===
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

      case _: (Seq[t] *: ts) =>
        val seq = product.productElement(idx).asInstanceOf[Seq[?]]
        result(idx) = conv.toInternal(seq, ProtoType.ArrayType(getProtoType[t], true))
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

      case _: (java.time.LocalTime *: ts) =>
        result(idx) = conv.toInternal(product.productElement(idx), ProtoType.TimeType(6))
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
        case enc: ProtoEncoder[T] =>
          // Use the encoder's catalystType to get proper conversion (e.g., StructType for nested products)
          // This allows MockInternalTypeConverter to wrap in MockRow
          conv.toInternal(value, enc.catalystType)
        case m: Mirror.ProductOf[T] =>
          // Fallback for products without explicit encoder - derive one
          val enc = ProtoEncoder.derived[T]
          conv.toInternal(value, enc.catalystType)
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
      case _ => getProtoTypeFromEncoder[T]

  /** Get ProtoType by summoning encoder - handles custom types like case classes */
  private inline def getProtoTypeFromEncoder[T]: ProtoType =
    summonFrom {
      case enc: ProtoEncoder[T] => enc.catalystType
      case m: Mirror.ProductOf[T] => ProtoEncoder.derived[T].catalystType
      case _ => ProtoType.StringType // Final fallback for truly unknown types
    }

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

      // === Collections (order matters: specific types before general Seq) ===
      case _: (List[t] *: ts) =>
        val rawSeq = conv.fromInternal(row(idx), ProtoType.ArrayType(getProtoType[t], true))
        values(idx) = deserializeSeqElementsToList[t](rawSeq.asInstanceOf[Seq[?]], conv)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Vector[t] *: ts) =>
        val rawSeq = conv.fromInternal(row(idx), ProtoType.ArrayType(getProtoType[t], true))
        values(idx) = deserializeSeqElements[t](rawSeq.asInstanceOf[Seq[?]], conv).toVector
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Seq[t] *: ts) =>
        val rawSeq = conv.fromInternal(row(idx), ProtoType.ArrayType(getProtoType[t], true))
        values(idx) = deserializeSeqElements[t](rawSeq.asInstanceOf[Seq[?]], conv)
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      case _: (Set[t] *: ts) =>
        val rawSeq = conv.fromInternal(row(idx), ProtoType.ArrayType(getProtoType[t], true))
        values(idx) = deserializeSeqElements[t](rawSeq.asInstanceOf[Seq[?]], conv).toSet
        deserializeFieldsImpl[ts](row, values, idx + 1, conv)

      // === Map ===
      case _: (Map[k, v] *: ts) =>
        val rawMap = conv.fromInternal(row(idx), ProtoType.MapType(getProtoType[k], getProtoType[v], true))
        values(idx) = deserializeMapElements[k, v](rawMap.asInstanceOf[Map[?, ?]], conv)
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

      case _: (java.time.LocalTime *: ts) =>
        values(idx) = conv.fromInternal(row(idx), ProtoType.TimeType(6))
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
      // Use deserializeAnyField for complex types (nested structs, collections, etc.)
      case _ => deserializeAnyField[T](value, conv)

  /** Deserialize sequence elements to List, handling custom types (e.g., List[User]) */
  private inline def deserializeSeqElementsToList[T](seq: Seq[?], conv: InternalTypeConverter): List[T] =
    var result: List[T] = Nil
    seq.foreach(elem => result = deserializeAnyField[T](elem, conv) :: result)
    result.reverse

  /** Deserialize sequence elements, handling custom types (e.g., Seq[User], Vector[User]) */
  private inline def deserializeSeqElements[T](seq: Seq[?], conv: InternalTypeConverter): Seq[T] =
    seq.map(elem => deserializeAnyField[T](elem, conv))

  /** Deserialize map elements, handling custom value types (e.g., Map[String, User]) */
  private inline def deserializeMapElements[K, V](map: Map[?, ?], conv: InternalTypeConverter): Map[K, V] =
    map.map { case (k, v) =>
      deserializeAnyField[K](k, conv) -> deserializeAnyField[V](v, conv)
    }

  /** Fallback deserialization for complex types */
  private inline def deserializeAnyField[T](value: Any, conv: InternalTypeConverter): T =
    if value == null then null.asInstanceOf[T]
    else
      // First try type-specific handling for collections
      inline erasedValue[T] match
        case _: List[t] =>
          val rawSeq = conv.fromInternal(value, ProtoType.ArrayType(getProtoType[t], true))
          deserializeSeqElementsToList[t](rawSeq.asInstanceOf[Seq[?]], conv).asInstanceOf[T]
        case _: Vector[t] =>
          val rawSeq = conv.fromInternal(value, ProtoType.ArrayType(getProtoType[t], true))
          deserializeSeqElements[t](rawSeq.asInstanceOf[Seq[?]], conv).toVector.asInstanceOf[T]
        case _: Set[t] =>
          val rawSeq = conv.fromInternal(value, ProtoType.ArrayType(getProtoType[t], true))
          deserializeSeqElements[t](rawSeq.asInstanceOf[Seq[?]], conv).toSet.asInstanceOf[T]
        case _: Seq[t] =>
          val rawSeq = conv.fromInternal(value, ProtoType.ArrayType(getProtoType[t], true))
          deserializeSeqElements[t](rawSeq.asInstanceOf[Seq[?]], conv).asInstanceOf[T]
        case _: Map[k, v] =>
          val rawMap = conv.fromInternal(value, ProtoType.MapType(getProtoType[k], getProtoType[v], true))
          deserializeMapElements[k, v](rawMap.asInstanceOf[Map[?, ?]], conv).asInstanceOf[T]
        case _ =>
          // Try product deserialization
          summonFrom {
            case m: Mirror.ProductOf[T] =>
              // Nested product - deserialize recursively
              // Handle:
              //   - ProtoRow (from MockInternalTypeConverter)
              //   - Array[Any] (from converters that serialize to arrays)
              //   - Product (from default converter that passes through unchanged)
              val row: Array[Any] = value match
                case pr: ProtoRow => pr.toSeq.toArray
                case arr: Array[Any @unchecked] => arr
                case p: Product => p.productIterator.toArray
                case other => throw IllegalArgumentException(
                  s"Expected ProtoRow, Array[Any], or Product for nested product, got ${other.getClass.getName}"
                )
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
