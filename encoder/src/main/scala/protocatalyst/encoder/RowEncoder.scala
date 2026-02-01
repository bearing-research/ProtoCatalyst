package protocatalyst.encoder

import protocatalyst.schema.ProtoSchema
import protocatalyst.types.*

/** Encoder for ProtoRow (generic, schema-less row type).
  *
  * Unlike ProtoEncoder which derives schema at compile-time from case classes, RowEncoder takes
  * schema at runtime and handles the dynamic Row type.
  *
  * This mirrors Spark's RowEncoder which:
  *   - Takes StructType schema at construction
  *   - Serializes Row to InternalRow using schema
  *   - Deserializes InternalRow back to Row
  *
  * Usage:
  * {{{
  * val schema = Vector(
  *   FieldSchema("name", ProtoType.StringType, nullable = false, 0),
  *   FieldSchema("age", ProtoType.IntType, nullable = false, 1)
  * )
  * val encoder = RowEncoder(schema)
  *
  * val row = ProtoRow("Alice", 30)
  * val serialized: Array[Any] = encoder.serialize(row)
  * val deserialized: ProtoRow = encoder.deserialize(serialized)
  * }}}
  */
trait RowEncoder:
  /** The schema this encoder uses */
  def schema: Vector[FieldSchema]

  /** Serialize ProtoRow to internal row format (Array[Any]) */
  def serialize(row: ProtoRow)(using conv: InternalTypeConverter): Array[Any]

  /** Deserialize from internal row format back to ProtoRow */
  def deserialize(row: Array[Any])(using conv: InternalTypeConverter): ProtoRow

  /** Number of fields */
  def numFields: Int = schema.size

  /** Field names in order */
  def fieldNames: Vector[String] = schema.map(_.name)

  /** Get field index by name, or -1 if not found */
  def fieldIndex(name: String): Int =
    schema.indexWhere(_.name.equalsIgnoreCase(name))

object RowEncoder:
  /** Create a RowEncoder from field schemas */
  def apply(schema: Vector[FieldSchema]): RowEncoder =
    GenericRowEncoder(schema)

  /** Create from ProtoSchema */
  def fromProtoSchema(protoSchema: ProtoSchema): RowEncoder =
    val fieldSchemas = protoSchema.fields.zipWithIndex.map { case (f, idx) =>
      FieldSchema(f.name, f.dataType, f.nullable, idx)
    }
    GenericRowEncoder(fieldSchemas)

  /** Create from ProtoType.StructType */
  def fromStructType(structType: ProtoType.StructType): RowEncoder =
    val fieldSchemas = structType.fields.zipWithIndex.map { case (f, idx) =>
      FieldSchema(f.name, f.dataType, f.nullable, idx)
    }
    GenericRowEncoder(fieldSchemas)

  /** Create from field definitions (convenience) */
  def of(fields: (String, ProtoType, Boolean)*): RowEncoder =
    val fieldSchemas = fields.zipWithIndex.map { case ((name, dataType, nullable), idx) =>
      FieldSchema(name, dataType, nullable, idx)
    }.toVector
    GenericRowEncoder(fieldSchemas)

/** Default implementation of RowEncoder using runtime schema.
  */
class GenericRowEncoder(val schema: Vector[FieldSchema]) extends RowEncoder:

  def serialize(row: ProtoRow)(using conv: InternalTypeConverter): Array[Any] =
    require(
      row.length == schema.size,
      s"Row length ${row.length} does not match schema size ${schema.size}"
    )

    val result = new Array[Any](schema.size)
    var i = 0
    while i < schema.size do
      val fieldValue = row.get(i)
      val fieldSchema = schema(i)
      result(i) = serializeField(fieldValue, fieldSchema.dataType, fieldSchema.nullable, conv)
      i += 1
    result

  def deserialize(row: Array[Any])(using conv: InternalTypeConverter): ProtoRow =
    require(
      row.length == schema.size,
      s"Row length ${row.length} does not match schema size ${schema.size}"
    )

    val values = new Array[Any](schema.size)
    var i = 0
    while i < schema.size do
      val fieldSchema = schema(i)
      values(i) = deserializeField(row(i), fieldSchema.dataType, conv)
      i += 1
    GenericProtoRow(values.toVector)

  private def serializeField(
      value: Any,
      dataType: ProtoType,
      nullable: Boolean,
      conv: InternalTypeConverter
  ): Any =
    if value == null then
      if !nullable then throw NullPointerException(s"Non-nullable field cannot be null")
      null
    else conv.toInternal(value, dataType)

  private def deserializeField(
      value: Any,
      dataType: ProtoType,
      conv: InternalTypeConverter
  ): Any =
    if value == null then null
    else conv.fromInternal(value, dataType)
