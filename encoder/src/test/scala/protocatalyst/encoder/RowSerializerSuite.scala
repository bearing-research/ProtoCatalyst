package protocatalyst.encoder

import protocatalyst.types.*
import scala.deriving.Mirror

// Test case classes for RowSerializer (prefixed to avoid conflicts with ProtoEncoderSuite)
case class RowUser(name: String, age: Int)
case class RowWithOptional(required: String, optional: Option[Int])
case class RowNested(inner: RowUser, value: Double)

class RowSerializerSuite extends munit.FunSuite:

  // Use default converter (no transformation) for basic tests
  given InternalTypeConverter = InternalTypeConverter.default

  // === Basic serialization tests ===

  test("derive RowSerializer for simple case class"):
    val serializer = RowSerializer.derived[RowUser]

    assertEquals(serializer.schema.size, 2)
    assertEquals(serializer.schema(0).name, "name")
    assertEquals(serializer.schema(0).dataType, ProtoType.StringType)
    assertEquals(serializer.schema(1).name, "age")
    assertEquals(serializer.schema(1).dataType, ProtoType.IntType)

  test("serialize simple case class"):
    val serializer = RowSerializer.derived[RowUser]
    val row = RowUser("Alice", 30)

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "Alice")
    assertEquals(serialized(1), 30)

  test("deserialize simple case class"):
    val serializer = RowSerializer.derived[RowUser]
    val data = Array[Any]("Bob", 25)

    val deserialized = serializer.deserialize(data)

    assertEquals(deserialized, RowUser("Bob", 25))

  test("roundtrip serialization"):
    val serializer = RowSerializer.derived[RowUser]
    val original = RowUser("Charlie", 42)

    val serialized = serializer.serialize(original)
    val deserialized = serializer.deserialize(serialized)

    assertEquals(deserialized, original)

  // === Optional field tests ===

  test("serialize with Some value"):
    val serializer = RowSerializer.derived[RowWithOptional]
    val row = RowWithOptional("test", Some(100))

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "test")
    assertEquals(serialized(1), 100)  // Unwrapped from Option

  test("serialize with None value"):
    val serializer = RowSerializer.derived[RowWithOptional]
    val row = RowWithOptional("test", None)

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "test")
    assertEquals(serialized(1), null)  // None becomes null

  test("deserialize with non-null value to Option"):
    val serializer = RowSerializer.derived[RowWithOptional]
    val data = Array[Any]("test", 200)

    val deserialized = serializer.deserialize(data)

    assertEquals(deserialized, RowWithOptional("test", Some(200)))

  test("deserialize with null to None"):
    val serializer = RowSerializer.derived[RowWithOptional]
    val data = Array[Any]("test", null)

    val deserialized = serializer.deserialize(data)

    assertEquals(deserialized, RowWithOptional("test", None))

  test("roundtrip with Option[Some]"):
    val serializer = RowSerializer.derived[RowWithOptional]
    val original = RowWithOptional("roundtrip", Some(42))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip with Option[None]"):
    val serializer = RowSerializer.derived[RowWithOptional]
    val original = RowWithOptional("roundtrip", None)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Schema tests ===

  test("schema has correct field indices"):
    val serializer = RowSerializer.derived[RowUser]

    assertEquals(serializer.schema(0).fieldIndex, 0)
    assertEquals(serializer.schema(1).fieldIndex, 1)

  test("schema nullable matches Option fields"):
    val serializer = RowSerializer.derived[RowWithOptional]

    assertEquals(serializer.schema(0).nullable, false)  // required: String
    assertEquals(serializer.schema(1).nullable, true)   // optional: Option[Int]

  // === RowNested struct tests ===

  test("serialize nested case class"):
    val serializer = RowSerializer.derived[RowNested]
    val row = RowNested(RowUser("inner", 10), 3.14)

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 2)
    // Nested struct is serialized as Array[Any] (correct format for Spark InternalRow)
    val innerArray = serialized(0).asInstanceOf[Array[Any]]
    assertEquals(innerArray(0), "inner")
    assertEquals(innerArray(1), 10)
    assertEquals(serialized(1), 3.14)

  test("nested schema has correct type"):
    val serializer = RowSerializer.derived[RowNested]

    assertEquals(serializer.schema.size, 2)
    assertEquals(serializer.schema(0).name, "inner")
    serializer.schema(0).dataType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(fields(0).name, "name")
        assertEquals(fields(1).name, "age")
      case other =>
        fail(s"Expected StructType, got $other")
    assertEquals(serializer.schema(1).name, "value")
    assertEquals(serializer.schema(1).dataType, ProtoType.DoubleType)

  // === Primitive type coverage ===

  test("all primitive types roundtrip correctly"):
    case class AllPrimitives(
        b: Boolean,
        by: Byte,
        s: Short,
        i: Int,
        l: Long,
        f: Float,
        d: Double,
        str: String
    )

    val serializer = RowSerializer.derived[AllPrimitives]
    val original = AllPrimitives(
      b = true,
      by = 42.toByte,
      s = 1000.toShort,
      i = 100000,
      l = 10000000000L,
      f = 3.14f,
      d = 2.71828,
      str = "hello"
    )

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Empty case class ===

  test("empty case class"):
    case class Empty()

    val serializer = RowSerializer.derived[Empty]

    assertEquals(serializer.schema.size, 0)

    val original = Empty()
    val serialized = serializer.serialize(original)
    assertEquals(serialized.length, 0)

    val deserialized = serializer.deserialize(Array.empty)
    assertEquals(deserialized, original)

  // === Single field case class ===

  test("single field case class"):
    case class Single(value: Int)

    val serializer = RowSerializer.derived[Single]

    assertEquals(serializer.schema.size, 1)
    assertEquals(serializer.schema(0).name, "value")

    val original = Single(42)
    val deserialized = serializer.deserialize(serializer.serialize(original))
    assertEquals(deserialized, original)

  // === Performance sanity check ===

  test("serialize many rows"):
    val serializer = RowSerializer.derived[RowUser]
    val rows = (1 to 10000).map(i => RowUser(s"name$i", i))

    val serialized = rows.map(serializer.serialize)
    val deserialized = serialized.map(serializer.deserialize)

    assertEquals(deserialized, rows)
