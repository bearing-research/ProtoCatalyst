package protocatalyst.encoder
import scala.compiletime._
import scala.deriving.Mirror

/** Inline-specialized row serializer for sum types (sealed traits).
  *
  * Uses compile-time code generation to serialize/deserialize each variant with type-specialized
  * field handling. This eliminates the runtime reflection and placeholder types used in the legacy
  * SumRowSerializer.
  *
  * Row format: [variantName: String, ordinal: Int, variantData: Array[Any] | null]
  *
  * Usage:
  * {{{
  * sealed trait Event derives ProtoEncoder
  * case class Click(x: Int, y: Int) extends Event derives ProtoEncoder
  * case object Close extends Event
  *
  * val serializer = InlineSumRowSerializer.derived[Event]
  * val row = serializer.serialize(Click(10, 20))  // ["Click", 0, [10, 20]]
  * val event = serializer.deserialize(row)        // Click(10, 20)
  * }}}
  */
trait InlineSumRowSerializer[T]:
  def serialize(value: T)(using conv: InternalTypeConverter): Array[Any]
  def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T

object InlineSumRowSerializer:

  /** Derive an inline-specialized sum serializer at compile time.
    *
    * Generates specialized serialization code for each variant of the sealed trait.
    */
  inline def derived[T](using m: Mirror.SumOf[T]): InlineSumRowSerializer[T] =
    // Create serialization function that captures inline expansion
    val serializeFn = (value: T, conv: InternalTypeConverter) =>
      val ordinal = m.ordinal(value)
      val (name, data) = serializeVariant[m.MirroredElemTypes](value, ordinal, 0, conv)
      Array[Any](name, ordinal, data)

    // Create deserialization function that captures inline expansion
    val deserializeFn = (row: Array[Any], conv: InternalTypeConverter) =>
      val ordinal = row(1).asInstanceOf[Int]
      val data = row(2).asInstanceOf[Array[Any]]
      deserializeVariant[m.MirroredElemTypes, T](ordinal, data, 0, conv)

    InlineSumRowSerializerImpl[T](serializeFn, deserializeFn)

  /** Implementation class to avoid anonymous class duplication warning */
  class InlineSumRowSerializerImpl[T](
      serializeFn: (T, InternalTypeConverter) => Array[Any],
      deserializeFn: (Array[Any], InternalTypeConverter) => T
  ) extends InlineSumRowSerializer[T]:
    def serialize(value: T)(using conv: InternalTypeConverter): Array[Any] =
      serializeFn(value, conv)
    def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T =
      deserializeFn(row, conv)

  // ============================================================
  // Compile-time variant serialization
  // ============================================================

  /** Serialize the matching variant using compile-time dispatch */
  inline def serializeVariant[Variants <: Tuple](
      value: Any,
      ordinal: Int,
      idx: Int,
      conv: InternalTypeConverter
  ): (String, Array[Any]) =
    inline erasedValue[Variants] match
      case _: EmptyTuple =>
        throw RuntimeException(s"Unknown variant ordinal: $ordinal")
      case _: (v *: vs) =>
        if ordinal == idx then serializeOneVariant[v](value.asInstanceOf[v], conv)
        else serializeVariant[vs](value, ordinal, idx + 1, conv)

  /** Serialize a single variant using its Mirror */
  private inline def serializeOneVariant[V](
      value: V,
      conv: InternalTypeConverter
  ): (String, Array[Any]) =
    summonFrom {
      case m: Mirror.ProductOf[V] =>
        val name = constValue[m.MirroredLabel]
        inline constValue[Tuple.Size[m.MirroredElemTypes]] match
          case 0 =>
            // Case object - no data
            (name, null)
          case size =>
            // Case class - serialize fields using InlineRowSerializer
            val product = value.asInstanceOf[Product]
            val result = new Array[Any](size)
            InlineRowSerializer.serializeFieldsImpl[m.MirroredElemTypes](product, result, 0, conv)
            (name, result)
      case _ =>
        // Fallback for non-product (shouldn't happen for valid sealed trait)
        ("unknown", null)
    }

  // ============================================================
  // Compile-time variant deserialization
  // ============================================================

  /** Deserialize variant by ordinal using compile-time dispatch */
  inline def deserializeVariant[Variants <: Tuple, T](
      ordinal: Int,
      data: Array[Any],
      idx: Int,
      conv: InternalTypeConverter
  ): T =
    inline erasedValue[Variants] match
      case _: EmptyTuple =>
        throw RuntimeException(s"Unknown variant ordinal: $ordinal")
      case _: (v *: vs) =>
        if ordinal == idx then deserializeOneVariant[v, T](data, conv)
        else deserializeVariant[vs, T](ordinal, data, idx + 1, conv)

  /** Deserialize a single variant using its Mirror */
  private inline def deserializeOneVariant[V, T](
      data: Array[Any],
      conv: InternalTypeConverter
  ): T =
    summonFrom {
      case m: Mirror.ProductOf[V] =>
        inline constValue[Tuple.Size[m.MirroredElemTypes]] match
          case 0 =>
            // Case object - get singleton instance
            getSingleton[V].asInstanceOf[T]
          case size =>
            // Case class - deserialize fields using InlineRowSerializer
            val values = new Array[Any](size)
            InlineRowSerializer.deserializeFieldsImpl[m.MirroredElemTypes](data, values, 0, conv)
            m.fromProduct(InlineRowSerializer.ArrayProduct(values)).asInstanceOf[T]
      case _ =>
        throw RuntimeException("Cannot deserialize non-product variant")
    }

  /** Get singleton instance for case object using ValueOf */
  private inline def getSingleton[V]: V =
    summonInline[ValueOf[V]].value
