package protocatalyst.encoder

import protocatalyst.types._

/** Field schema for row serialization */
case class FieldSchema(
    name: String,
    dataType: ProtoType,
    nullable: Boolean,
    fieldIndex: Int
)

/** Type converter for internal (Spark-compatible) representations.
  *
  * Spark uses different internal types for efficiency:
  *   - String → UTF8String
  *   - Array/Seq → ArrayData
  *   - Map → MapData
  *   - nested struct → InternalRow
  *
  * This trait abstracts the conversion so the encoder module doesn't need a Spark dependency.
  */
trait InternalTypeConverter:
  /** Convert external value to internal representation */
  def toInternal(value: Any, dataType: ProtoType): Any

  /** Convert internal representation back to external value */
  def fromInternal(value: Any, dataType: ProtoType): Any

object InternalTypeConverter:
  /** Default converter - minimal transformation (for testing without Spark) */
  given default: InternalTypeConverter with
    def toInternal(value: Any, dataType: ProtoType): Any =
      if value == null then null
      else
        dataType match
          // For struct types, extract product elements into Array[Any]
          case ProtoType.StructType(fields) =>
            value match
              case p: Product =>
                fields.zipWithIndex.map { case (field, idx) =>
                  toInternal(p.productElement(idx), field.dataType)
                }.toArray
              case other => other
          // For array types, recursively convert elements
          case ProtoType.ArrayType(elemType, _) =>
            value match
              case seq: Seq[?]   => seq.map(e => toInternal(e, elemType)).toList
              case arr: Array[?] => arr.map(e => toInternal(e, elemType)).toList
              case other         => other
          // For map types, recursively convert keys and values
          case ProtoType.MapType(keyType, valueType, _) =>
            value match
              case m: Map[?, ?] =>
                m.map { case (k, v) => toInternal(k, keyType) -> toInternal(v, valueType) }
              case other => other
          // For other types, pass through unchanged
          case _ => value

    def fromInternal(value: Any, dataType: ProtoType): Any =
      if value == null then null
      else
        dataType match
          // For struct types, convert Array[Any] back to itself (caller handles reconstruction)
          case ProtoType.StructType(_) => value
          // For array types, recursively convert elements back
          case ProtoType.ArrayType(elemType, _) =>
            value match
              case seq: Seq[?]   => seq.map(e => fromInternal(e, elemType)).toVector
              case arr: Array[?] => arr.map(e => fromInternal(e, elemType)).toVector
              case other         => other
          // For map types, recursively convert keys and values back
          case ProtoType.MapType(keyType, valueType, _) =>
            value match
              case m: Map[?, ?] =>
                m.map { case (k, v) => fromInternal(k, keyType) -> fromInternal(v, valueType) }
              case other => other
          // For other types, pass through unchanged
          case _ => value
