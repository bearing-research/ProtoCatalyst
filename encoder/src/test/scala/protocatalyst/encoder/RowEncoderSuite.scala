package protocatalyst.encoder

import munit.FunSuite
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.*

class RowEncoderSuite extends FunSuite:

  // Use default converter (identity) for basic tests
  given InternalTypeConverter = InternalTypeConverter.default

  val userSchema = Vector(
    FieldSchema("name", ProtoType.StringType, nullable = false, 0),
    FieldSchema("age", ProtoType.IntType, nullable = false, 1)
  )

  // === Creation tests ===

  test("create RowEncoder from field schemas"):
    val encoder = RowEncoder(userSchema)
    assertEquals(encoder.numFields, 2)
    assertEquals(encoder.fieldNames, Vector("name", "age"))

  test("create from StructType"):
    val structType: ProtoType.StructType = ProtoType.StructType(Vector(
      ProtoStructField("a", ProtoType.IntType, false),
      ProtoStructField("b", ProtoType.StringType, true)
    ))

    val encoder = RowEncoder.fromStructType(structType)
    assertEquals(encoder.numFields, 2)
    assertEquals(encoder.schema(0).name, "a")
    assertEquals(encoder.schema(1).name, "b")
    assertEquals(encoder.schema(1).nullable, true)

  test("create from ProtoSchema"):
    val protoSchema = ProtoSchema(Vector(
      ProtoStructField("id", ProtoType.LongType, false),
      ProtoStructField("value", ProtoType.DoubleType, true)
    ))

    val encoder = RowEncoder.fromProtoSchema(protoSchema)
    assertEquals(encoder.numFields, 2)
    assertEquals(encoder.schema(0).name, "id")
    assertEquals(encoder.schema(1).nullable, true)

  test("convenience factory with of"):
    val encoder = RowEncoder.of(
      ("name", ProtoType.StringType, false),
      ("age", ProtoType.IntType, false)
    )
    assertEquals(encoder.numFields, 2)
    assertEquals(encoder.fieldNames, Vector("name", "age"))

  // === Serialize tests ===

  test("serialize ProtoRow"):
    val encoder = RowEncoder(userSchema)
    val row = ProtoRow("Alice", 30)

    val serialized = encoder.serialize(row)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "Alice")
    assertEquals(serialized(1), 30)

  test("serialize with null in nullable field"):
    val schema = Vector(
      FieldSchema("name", ProtoType.StringType, nullable = false, 0),
      FieldSchema("nickname", ProtoType.StringType, nullable = true, 1)
    )
    val encoder = RowEncoder(schema)
    val row = ProtoRow("Alice", null)

    val serialized = encoder.serialize(row)

    assertEquals(serialized(0), "Alice")
    assertEquals(serialized(1), null)

  test("serialize non-nullable field with null throws"):
    val encoder = RowEncoder(userSchema)
    val row = ProtoRow(null, 30)

    intercept[NullPointerException]:
      encoder.serialize(row)

  test("serialize row length mismatch throws"):
    val encoder = RowEncoder(userSchema)
    val row = ProtoRow("Alice")  // Only 1 field, schema expects 2

    intercept[IllegalArgumentException]:
      encoder.serialize(row)

  // === Deserialize tests ===

  test("deserialize to ProtoRow"):
    val encoder = RowEncoder(userSchema)
    val data = Array[Any]("Bob", 25)

    val row = encoder.deserialize(data)

    assertEquals(row.getString(0), "Bob")
    assertEquals(row.getInt(1), 25)

  test("deserialize with null"):
    val schema = Vector(
      FieldSchema("name", ProtoType.StringType, nullable = false, 0),
      FieldSchema("nickname", ProtoType.StringType, nullable = true, 1)
    )
    val encoder = RowEncoder(schema)
    val data = Array[Any]("Alice", null)

    val row = encoder.deserialize(data)

    assertEquals(row.getString(0), "Alice")
    assertEquals(row.isNullAt(1), true)

  test("deserialize row length mismatch throws"):
    val encoder = RowEncoder(userSchema)
    val data = Array[Any]("Alice")

    intercept[IllegalArgumentException]:
      encoder.deserialize(data)

  // === Roundtrip tests ===

  test("roundtrip serialization"):
    val encoder = RowEncoder(userSchema)
    val original = ProtoRow("Charlie", 42)

    val serialized = encoder.serialize(original)
    val deserialized = encoder.deserialize(serialized)

    assertEquals(deserialized.toSeq, original.toSeq)

  test("roundtrip with various types"):
    val schema = Vector(
      FieldSchema("bool", ProtoType.BooleanType, false, 0),
      FieldSchema("int", ProtoType.IntType, false, 1),
      FieldSchema("long", ProtoType.LongType, false, 2),
      FieldSchema("double", ProtoType.DoubleType, false, 3),
      FieldSchema("string", ProtoType.StringType, false, 4)
    )
    val encoder = RowEncoder(schema)
    val original = ProtoRow(true, 42, 9999999999L, 3.14, "hello")

    val serialized = encoder.serialize(original)
    val deserialized = encoder.deserialize(serialized)

    assertEquals(deserialized.getBoolean(0), true)
    assertEquals(deserialized.getInt(1), 42)
    assertEquals(deserialized.getLong(2), 9999999999L)
    assertEquals(deserialized.getDouble(3), 3.14)
    assertEquals(deserialized.getString(4), "hello")

  // === Field lookup ===

  test("fieldIndex lookup"):
    val encoder = RowEncoder(userSchema)
    assertEquals(encoder.fieldIndex("name"), 0)
    assertEquals(encoder.fieldIndex("age"), 1)
    assertEquals(encoder.fieldIndex("unknown"), -1)

  test("fieldIndex is case insensitive"):
    val encoder = RowEncoder(userSchema)
    assertEquals(encoder.fieldIndex("NAME"), 0)
    assertEquals(encoder.fieldIndex("Age"), 1)
    assertEquals(encoder.fieldIndex("AGE"), 1)

  // === Empty schema ===

  test("empty schema"):
    val encoder = RowEncoder(Vector.empty)
    assertEquals(encoder.numFields, 0)

    val row = ProtoRow.empty
    val serialized = encoder.serialize(row)
    assertEquals(serialized.length, 0)

    val deserialized = encoder.deserialize(Array.empty)
    assertEquals(deserialized.length, 0)
