package protocatalyst.mock

import protocatalyst.types.*

class MockSchemaConverterSuite extends munit.FunSuite:

  test("converts primitive types to mock"):
    assertEquals(MockSchemaConverter.toMockType(ProtoType.BooleanType), MockDataType.BooleanType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.ByteType), MockDataType.ByteType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.ShortType), MockDataType.ShortType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.IntType), MockDataType.IntegerType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.LongType), MockDataType.LongType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.FloatType), MockDataType.FloatType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.DoubleType), MockDataType.DoubleType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.StringType), MockDataType.StringType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.BinaryType), MockDataType.BinaryType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.DateType), MockDataType.DateType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.TimestampType), MockDataType.TimestampType)
    assertEquals(MockSchemaConverter.toMockType(ProtoType.TimestampNTZType), MockDataType.TimestampNTZType)

  test("converts decimal type"):
    assertEquals(
      MockSchemaConverter.toMockType(ProtoType.DecimalType(18, 2)),
      MockDataType.DecimalType(18, 2)
    )

  test("converts array type"):
    assertEquals(
      MockSchemaConverter.toMockType(ProtoType.ArrayType(ProtoType.IntType, containsNull = true)),
      MockDataType.ArrayType(MockDataType.IntegerType, containsNull = true)
    )

  test("converts map type"):
    assertEquals(
      MockSchemaConverter.toMockType(ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, valueContainsNull = false)),
      MockDataType.MapType(MockDataType.StringType, MockDataType.IntegerType, valueContainsNull = false)
    )

  test("converts struct type"):
    val protoStruct = ProtoType.StructType(Vector(
      ProtoStructField("name", ProtoType.StringType, nullable = false),
      ProtoStructField("age", ProtoType.IntType, nullable = true)
    ))
    val mockStruct = MockSchemaConverter.toMockType(protoStruct)

    mockStruct match
      case MockDataType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(fields(0).name, "name")
        assertEquals(fields(0).dataType, MockDataType.StringType)
        assertEquals(fields(0).nullable, false)
        assertEquals(fields(1).name, "age")
        assertEquals(fields(1).dataType, MockDataType.IntegerType)
        assertEquals(fields(1).nullable, true)
      case _ => fail("Expected StructType")

  test("roundtrip conversion preserves types"):
    val types: Vector[ProtoType] = Vector(
      ProtoType.BooleanType,
      ProtoType.IntType,
      ProtoType.LongType,
      ProtoType.DoubleType,
      ProtoType.StringType,
      ProtoType.DecimalType(10, 5),
      ProtoType.ArrayType(ProtoType.StringType, containsNull = true),
      ProtoType.MapType(ProtoType.IntType, ProtoType.StringType, valueContainsNull = false)
    )

    for pt <- types do
      val mock = MockSchemaConverter.toMockType(pt)
      val back = MockSchemaConverter.toProtoType(mock)
      assertEquals(back, pt, s"Roundtrip failed for $pt")

  test("throws on unresolved type"):
    intercept[IllegalArgumentException] {
      MockSchemaConverter.toMockType(ProtoType.UnresolvedType("test"))
    }

  test("converts ProtoSchema to MockStructType"):
    val mockSchema = MockSchemaConverter.toMockSchema(TestFixtures.simpleUserProtoSchema)

    assertEquals(mockSchema.fields.size, 3)
    assertEquals(mockSchema.fields(0).name, "name")
    assertEquals(mockSchema.fields(1).name, "age")
    assertEquals(mockSchema.fields(2).name, "salary")
