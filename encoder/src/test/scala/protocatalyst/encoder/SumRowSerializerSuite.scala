package protocatalyst.encoder

import protocatalyst.types._

// Test sealed traits - defined at package level for proper derivation
sealed trait SumEvent derives ProtoEncoder
case class SumClick(x: Int, y: Int) extends SumEvent derives ProtoEncoder
case class SumView(page: String) extends SumEvent derives ProtoEncoder
case object SumClose extends SumEvent

// Mixed with different field types
sealed trait SumMessage derives ProtoEncoder
case object SumPing extends SumMessage
case class SumPong(id: Long) extends SumMessage derives ProtoEncoder
case class SumData(payload: String, size: Int) extends SumMessage derives ProtoEncoder

// Nested variant fields
case class SumAddress(street: String, city: String) derives ProtoEncoder
sealed trait SumPerson derives ProtoEncoder
case class SumEmployee(name: String, address: SumAddress) extends SumPerson derives ProtoEncoder
case class SumContractor(company: String) extends SumPerson derives ProtoEncoder

class SumRowSerializerSuite extends munit.FunSuite:

  given InternalTypeConverter = InternalTypeConverter.default

  // === Basic serialization tests ===

  test("serialize case class variant with Int fields"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val click: SumEvent = SumClick(10, 20)

    val row = serializer.serialize(click)

    assertEquals(row.length, 3)
    assertEquals(row(0), "SumClick")
    assertEquals(row(1), 0)
    val data = row(2).asInstanceOf[Array[Any]]
    assertEquals(data.length, 2)
    assertEquals(data(0), 10)
    assertEquals(data(1), 20)

  test("serialize case class variant with String field"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val view: SumEvent = SumView("homepage")

    val row = serializer.serialize(view)

    assertEquals(row(0), "SumView")
    assertEquals(row(1), 1)
    val data = row(2).asInstanceOf[Array[Any]]
    assertEquals(data.length, 1)
    assertEquals(data(0), "homepage")

  test("serialize case object variant"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val close: SumEvent = SumClose

    val row = serializer.serialize(close)

    assertEquals(row(0), "SumClose")
    assertEquals(row(1), 2)
    assertEquals(row(2), null) // Case object has no data

  // === Deserialization tests ===

  test("deserialize case class variant"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val row = Array[Any]("SumClick", 0, Array[Any](100, 200))

    val event = serializer.deserialize(row)

    assertEquals(event, SumClick(100, 200))

  test("deserialize case object variant"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val row = Array[Any]("SumClose", 2, null)

    val event = serializer.deserialize(row)

    assertEquals(event, SumClose)

  // === Roundtrip tests ===

  test("roundtrip case class with Int fields"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val original: SumEvent = SumClick(42, 84)

    val row = serializer.serialize(original)
    val deserialized = serializer.deserialize(row)

    assertEquals(deserialized, original)

  test("roundtrip case class with String field"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val original: SumEvent = SumView("products/123")

    val row = serializer.serialize(original)
    val deserialized = serializer.deserialize(row)

    assertEquals(deserialized, original)

  test("roundtrip case object"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val original: SumEvent = SumClose

    val row = serializer.serialize(original)
    val deserialized = serializer.deserialize(row)

    assertEquals(deserialized, original)

  test("roundtrip all variants"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val events: List[SumEvent] = List(
      SumClick(1, 2),
      SumClick(0, 0),
      SumView("page1"),
      SumView(""),
      SumClose
    )

    events.foreach { original =>
      val deserialized = serializer.deserialize(serializer.serialize(original))
      assertEquals(deserialized, original, s"Failed for $original")
    }

  // === Mixed case object/class tests ===

  test("roundtrip mixed sealed trait - case object"):
    val serializer = RowSerializer.derivedSum[SumMessage]
    val original: SumMessage = SumPing

    val row = serializer.serialize(original)
    val deserialized = serializer.deserialize(row)

    assertEquals(deserialized, original)

  test("roundtrip mixed sealed trait - case class with Long"):
    val serializer = RowSerializer.derivedSum[SumMessage]
    val original: SumMessage = SumPong(123456789L)

    val row = serializer.serialize(original)
    val deserialized = serializer.deserialize(row)

    assertEquals(deserialized, original)

  test("roundtrip mixed sealed trait - case class with multiple fields"):
    val serializer = RowSerializer.derivedSum[SumMessage]
    val original: SumMessage = SumData("hello world", 11)

    val row = serializer.serialize(original)
    val deserialized = serializer.deserialize(row)

    assertEquals(deserialized, original)

  // === Nested variant fields ===

  test("roundtrip variant with nested case class"):
    val serializer = RowSerializer.derivedSum[SumPerson]
    val original: SumPerson = SumEmployee("Alice", SumAddress("123 Main St", "NYC"))

    val row = serializer.serialize(original)
    val deserialized = serializer.deserialize(row)

    assertEquals(deserialized, original)

  test("roundtrip variant with simple field"):
    val serializer = RowSerializer.derivedSum[SumPerson]
    val original: SumPerson = SumContractor("Acme Corp")

    val row = serializer.serialize(original)
    val deserialized = serializer.deserialize(row)

    assertEquals(deserialized, original)

  // === Schema tests ===

  test("schema has correct structure"):
    val serializer = RowSerializer.derivedSum[SumEvent]

    assertEquals(serializer.schema.length, 3)
    assertEquals(serializer.schema(0).name, "_type")
    assertEquals(serializer.schema(0).dataType, ProtoType.StringType)
    assertEquals(serializer.schema(1).name, "_ordinal")
    assertEquals(serializer.schema(1).dataType, ProtoType.IntType)
    assertEquals(serializer.schema(2).name, "value")

  // === Bulk test ===

  test("roundtrip 1000 mixed variants"):
    val serializer = RowSerializer.derivedSum[SumEvent]
    val events = (1 to 1000).map { i =>
      i % 3 match
        case 0 => SumClick(i, i * 2)
        case 1 => SumView(s"page$i")
        case 2 => SumClose
    }

    events.foreach { original =>
      val deserialized = serializer.deserialize(serializer.serialize(original))
      assertEquals(deserialized, original)
    }
