package protocatalyst.mock

import munit.FunSuite
import protocatalyst.encoder.*
import protocatalyst.types.*

class MockRowEncoderSuite extends FunSuite:

  // Use MockInternalTypeConverter for Spark-like conversions
  given InternalTypeConverter = MockInternalTypeConverter

  val userSchema = Vector(
    FieldSchema("name", ProtoType.StringType, nullable = false, 0),
    FieldSchema("age", ProtoType.IntType, nullable = false, 1)
  )

  // === Basic serialization with type conversion ===

  test("MockRow serialization converts String to MockUTF8String"):
    val encoder = RowEncoder(userSchema)
    val row = MockRow("Alice", 30)

    val serialized = encoder.serialize(row)

    // String should be converted to MockUTF8String
    assert(serialized(0).isInstanceOf[MockUTF8String])
    assertEquals(serialized(0).asInstanceOf[MockUTF8String].value, "Alice")
    assertEquals(serialized(1), 30)

  test("MockRow deserialization converts MockUTF8String to String"):
    val encoder = RowEncoder(userSchema)
    val data = Array[Any](MockUTF8String("Bob"), 25)

    val row = encoder.deserialize(data)

    assertEquals(row.getString(0), "Bob")
    assertEquals(row.getInt(1), 25)

  test("MockRow roundtrip with MockInternalTypeConverter"):
    val encoder = RowEncoder(userSchema)
    val original = MockRow("Charlie", 42)

    val serialized = encoder.serialize(original)
    val deserialized = encoder.deserialize(serialized)

    assertEquals(deserialized.getString(0), "Charlie")
    assertEquals(deserialized.getInt(1), 42)

  // === Nested struct serialization ===

  test("nested struct converts to MockRow"):
    val innerSchema = ProtoType.StructType(
      Vector(
        ProtoStructField("x", ProtoType.IntType, false),
        ProtoStructField("y", ProtoType.IntType, false)
      )
    )
    val schema = Vector(
      FieldSchema("name", ProtoType.StringType, nullable = false, 0),
      FieldSchema("point", innerSchema, nullable = false, 1)
    )

    val encoder = RowEncoder(schema)
    val row = MockRow("test", MockRow(10, 20))

    val serialized = encoder.serialize(row)

    // String converted to MockUTF8String
    assert(serialized(0).isInstanceOf[MockUTF8String])
    // Nested struct is a MockRow
    assert(serialized(1).isInstanceOf[MockRow])
    val nestedRow = serialized(1).asInstanceOf[MockRow]
    assertEquals(nestedRow.getInt(0), 10)
    assertEquals(nestedRow.getInt(1), 20)

  // === Array field serialization ===

  test("array field converts to MockArrayData"):
    val schema = Vector(
      FieldSchema("values", ProtoType.ArrayType(ProtoType.IntType, false), nullable = false, 0)
    )
    val encoder = RowEncoder(schema)
    val row = MockRow(Seq(1, 2, 3))

    val serialized = encoder.serialize(row)

    assert(serialized(0).isInstanceOf[MockArrayData])
    val arrData = serialized(0).asInstanceOf[MockArrayData]
    assertEquals(arrData.values, Vector(1, 2, 3))

  test("string array converts elements to MockUTF8String"):
    val schema = Vector(
      FieldSchema("names", ProtoType.ArrayType(ProtoType.StringType, false), nullable = false, 0)
    )
    val encoder = RowEncoder(schema)
    val row = MockRow(Seq("a", "b", "c"))

    val serialized = encoder.serialize(row)

    val arrData = serialized(0).asInstanceOf[MockArrayData]
    assertEquals(arrData.numElements, 3)
    assert(arrData.get(0).isInstanceOf[MockUTF8String])
    assertEquals(arrData.get(0).asInstanceOf[MockUTF8String].value, "a")

  // === Map field serialization ===

  test("map field converts to MockMapData"):
    val schema = Vector(
      FieldSchema(
        "data",
        ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, false),
        nullable = false,
        0
      )
    )
    val encoder = RowEncoder(schema)
    val row = MockRow(Map("a" -> 1, "b" -> 2))

    val serialized = encoder.serialize(row)

    assert(serialized(0).isInstanceOf[MockMapData])
    val mapData = serialized(0).asInstanceOf[MockMapData]
    assertEquals(mapData.numElements, 2)
    // Keys should be MockUTF8String
    assert(mapData.keys.forall(_.isInstanceOf[MockUTF8String]))

  // === Temporal type serialization ===

  test("date field converts to epoch days"):
    val schema = Vector(
      FieldSchema("date", ProtoType.DateType, nullable = false, 0)
    )
    val encoder = RowEncoder(schema)
    val date = java.time.LocalDate.of(2024, 1, 15)
    val row = MockRow(date)

    val serialized = encoder.serialize(row)

    assertEquals(serialized(0), date.toEpochDay.toInt)

  test("timestamp field converts to microseconds"):
    val schema = Vector(
      FieldSchema("timestamp", ProtoType.TimestampType, nullable = false, 0)
    )
    val encoder = RowEncoder(schema)
    val instant = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val row = MockRow(instant)

    val serialized = encoder.serialize(row)

    assertEquals(serialized(0), instant.toEpochMilli * 1000L)

  // === Nullable fields ===

  test("nullable field with null"):
    val schema = Vector(
      FieldSchema("name", ProtoType.StringType, nullable = false, 0),
      FieldSchema("nickname", ProtoType.StringType, nullable = true, 1)
    )
    val encoder = RowEncoder(schema)
    val row = MockRow("Alice", null)

    val serialized = encoder.serialize(row)

    assert(serialized(0).isInstanceOf[MockUTF8String])
    assertEquals(serialized(1), null)

  // === ProtoRow compatibility ===

  test("MockRow is a ProtoRow"):
    val row: ProtoRow = MockRow("test", 123)
    assertEquals(row.length, 2)
    assertEquals(row.getString(0), "test")
    assertEquals(row.getInt(1), 123)

  test("GenericProtoRow works with RowEncoder"):
    val encoder = RowEncoder(userSchema)
    val row: ProtoRow = ProtoRow("Diana", 28)

    val serialized = encoder.serialize(row)

    assert(serialized(0).isInstanceOf[MockUTF8String])
    assertEquals(serialized(0).asInstanceOf[MockUTF8String].value, "Diana")

  test("MockRow.fromProtoRow converts"):
    val protoRow = ProtoRow("Eve", 35)
    val mockRow = MockRow.fromProtoRow(protoRow)

    assertEquals(mockRow.getString(0), "Eve")
    assertEquals(mockRow.getInt(1), 35)
