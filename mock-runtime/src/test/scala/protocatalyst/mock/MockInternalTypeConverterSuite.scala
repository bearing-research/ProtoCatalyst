package protocatalyst.mock

import protocatalyst.encoder.*
import protocatalyst.types.*

// Test case classes
case class User(name: String, age: Int)
case class Address(street: String, city: String, zip: Option[Int])
case class Person(user: User, address: Address, tags: List[String])

// Temporal test case classes
case class Event(name: String, date: java.time.LocalDate)
case class LogEntry(msg: String, timestamp: java.time.Instant)
case class Appointment(title: String, dateTime: java.time.LocalDateTime)

class MockInternalTypeConverterSuite extends munit.FunSuite:

  // Use MockInternalTypeConverter for Spark-like conversions
  given InternalTypeConverter = MockInternalTypeConverter

  // === String conversion tests ===

  test("String converts to MockUTF8String"):
    val result = MockInternalTypeConverter.toInternal("hello", ProtoType.StringType)

    result match
      case utf8: MockUTF8String =>
        assertEquals(utf8.toString, "hello")
      case other =>
        fail(s"Expected MockUTF8String, got $other")

  test("MockUTF8String converts back to String"):
    val utf8 = MockUTF8String("world")
    val result = MockInternalTypeConverter.fromInternal(utf8, ProtoType.StringType)

    assertEquals(result, "world")

  // === Primitive types (no conversion) ===

  test("Int passes through unchanged"):
    val result = MockInternalTypeConverter.toInternal(42, ProtoType.IntType)
    assertEquals(result, 42)

  test("Long passes through unchanged"):
    val result = MockInternalTypeConverter.toInternal(100L, ProtoType.LongType)
    assertEquals(result, 100L)

  test("Double passes through unchanged"):
    val result = MockInternalTypeConverter.toInternal(3.14, ProtoType.DoubleType)
    assertEquals(result, 3.14)

  test("Boolean passes through unchanged"):
    val result = MockInternalTypeConverter.toInternal(true, ProtoType.BooleanType)
    assertEquals(result, true)

  // === Array/Collection conversion ===

  test("List[String] converts to MockArrayData with MockUTF8String elements"):
    val list = List("a", "b", "c")
    val result = MockInternalTypeConverter.toInternal(
      list,
      ProtoType.ArrayType(ProtoType.StringType, false)
    )

    result match
      case arr: MockArrayData =>
        assertEquals(arr.numElements, 3)
        assertEquals(arr.get(0).asInstanceOf[MockUTF8String].toString, "a")
        assertEquals(arr.get(1).asInstanceOf[MockUTF8String].toString, "b")
        assertEquals(arr.get(2).asInstanceOf[MockUTF8String].toString, "c")
      case other =>
        fail(s"Expected MockArrayData, got $other")

  test("MockArrayData converts back to Vector"):
    val arr = MockArrayData(Vector(MockUTF8String("x"), MockUTF8String("y")))
    val result = MockInternalTypeConverter.fromInternal(
      arr,
      ProtoType.ArrayType(ProtoType.StringType, false)
    )

    assertEquals(result, Vector("x", "y"))

  test("List[Int] elements are not converted"):
    val list = List(1, 2, 3)
    val result = MockInternalTypeConverter.toInternal(
      list,
      ProtoType.ArrayType(ProtoType.IntType, false)
    )

    result match
      case arr: MockArrayData =>
        assertEquals(arr.numElements, 3)
        assertEquals(arr.get(0), 1)
        assertEquals(arr.get(1), 2)
        assertEquals(arr.get(2), 3)
      case other =>
        fail(s"Expected MockArrayData, got $other")

  // === Map conversion ===

  test("Map[String, Int] converts to MockMapData"):
    val map = Map("a" -> 1, "b" -> 2)
    val result = MockInternalTypeConverter.toInternal(
      map,
      ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, false)
    )

    result match
      case mapData: MockMapData =>
        assertEquals(mapData.numElements, 2)
        // Keys are UTF8String, values are Int
        assert(mapData.keys.forall(_.isInstanceOf[MockUTF8String]))
        assert(mapData.values.forall(_.isInstanceOf[Int]))
      case other =>
        fail(s"Expected MockMapData, got $other")

  // === Struct conversion ===

  test("nested struct converts to MockRow"):
    val user = User("Alice", 30)
    val structType = ProtoType.StructType(Vector(
      ProtoStructField("name", ProtoType.StringType, false),
      ProtoStructField("age", ProtoType.IntType, false)
    ))

    val result = MockInternalTypeConverter.toInternal(user, structType)

    result match
      case row: MockRow =>
        assertEquals(row.size, 2)
        assertEquals(row.get(0).asInstanceOf[MockUTF8String].toString, "Alice")
        assertEquals(row.get(1), 30)
      case other =>
        fail(s"Expected MockRow, got $other")

  // === null handling ===

  test("null passes through unchanged"):
    val result = MockInternalTypeConverter.toInternal(null, ProtoType.StringType)
    assertEquals(result, null)

  test("null converts back to null"):
    val result = MockInternalTypeConverter.fromInternal(null, ProtoType.StringType)
    assertEquals(result, null)

  // === RowSerializer integration ===

  test("RowSerializer with MockInternalTypeConverter - simple case class"):
    val serializer = RowSerializer.derived[User]
    val user = User("Bob", 25)

    val serialized = serializer.serialize(user)

    assertEquals(serialized.length, 2)
    // With MockInternalTypeConverter, String becomes MockUTF8String
    assertEquals(serialized(0).asInstanceOf[MockUTF8String].toString, "Bob")
    assertEquals(serialized(1), 25)

  test("RowSerializer roundtrip with MockInternalTypeConverter"):
    val serializer = RowSerializer.derived[User]
    val original = User("Charlie", 42)

    val serialized = serializer.serialize(original)
    val deserialized = serializer.deserialize(serialized)

    assertEquals(deserialized, original)

  test("RowSerializer with optional fields"):
    val serializer = RowSerializer.derived[Address]

    // With Some value
    val addr1 = Address("123 Main St", "NYC", Some(10001))
    val serialized1 = serializer.serialize(addr1)
    assertEquals(serialized1(0).asInstanceOf[MockUTF8String].toString, "123 Main St")
    assertEquals(serialized1(1).asInstanceOf[MockUTF8String].toString, "NYC")
    assertEquals(serialized1(2), 10001)

    // With None
    val addr2 = Address("456 Oak Ave", "LA", None)
    val serialized2 = serializer.serialize(addr2)
    assertEquals(serialized2(2), null)

    // Roundtrip
    val deserialized1 = serializer.deserialize(serialized1)
    val deserialized2 = serializer.deserialize(serialized2)
    assertEquals(deserialized1, addr1)
    assertEquals(deserialized2, addr2)

  test("RowSerializer with nested structs and collections"):
    val serializer = RowSerializer.derived[Person]
    val person = Person(
      user = User("Diana", 28),
      address = Address("789 Pine Rd", "SF", Some(94102)),
      tags = List("developer", "scala")
    )

    val serialized = serializer.serialize(person)

    assertEquals(serialized.length, 3)

    // First field is User struct - converted to MockRow
    serialized(0) match
      case row: MockRow =>
        assertEquals(row.get(0).asInstanceOf[MockUTF8String].toString, "Diana")
        assertEquals(row.get(1), 28)
      case other =>
        fail(s"Expected MockRow for nested User, got $other")

    // Second field is Address struct - converted to MockRow
    serialized(1) match
      case row: MockRow =>
        assertEquals(row.get(0).asInstanceOf[MockUTF8String].toString, "789 Pine Rd")
        assertEquals(row.get(1).asInstanceOf[MockUTF8String].toString, "SF")
        assertEquals(row.get(2), 94102)
      case other =>
        fail(s"Expected MockRow for nested Address, got $other")

    // Third field is List[String] - converted to MockArrayData
    serialized(2) match
      case arr: MockArrayData =>
        assertEquals(arr.numElements, 2)
        assertEquals(arr.get(0).asInstanceOf[MockUTF8String].toString, "developer")
        assertEquals(arr.get(1).asInstanceOf[MockUTF8String].toString, "scala")
      case other =>
        fail(s"Expected MockArrayData for tags, got $other")

  // === MockRow interop ===

  test("MockRow can be created from RowSerializer output"):
    val serializer = RowSerializer.derived[User]
    val user = User("Eve", 35)

    val serialized = serializer.serialize(user)
    val mockRow = MockRow.fromSeq(serialized.toSeq)

    assertEquals(mockRow.size, 2)
    assertEquals(mockRow.get(0).asInstanceOf[MockUTF8String].toString, "Eve")
    assertEquals(mockRow.getInt(1), 35)

  // === Temporal type integration tests ===

  test("RowSerializer roundtrip with LocalDate field"):
    val serializer = RowSerializer.derived[Event]
    val date = java.time.LocalDate.of(2024, 1, 15)
    val event = Event("Conference", date)

    val serialized = serializer.serialize(event)
    assertEquals(serialized.length, 2)

    // String field converted to MockUTF8String
    assertEquals(serialized(0).asInstanceOf[MockUTF8String].toString, "Conference")
    // Date field converted to epoch days (Int)
    assertEquals(serialized(1), date.toEpochDay.toInt)

    // Roundtrip
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, event)

  test("RowSerializer roundtrip with Instant field"):
    val serializer = RowSerializer.derived[LogEntry]
    val instant = java.time.Instant.parse("2024-01-15T10:30:00Z")
    val log = LogEntry("Test message", instant)

    val serialized = serializer.serialize(log)
    assertEquals(serialized.length, 2)

    // String field converted to MockUTF8String
    assertEquals(serialized(0).asInstanceOf[MockUTF8String].toString, "Test message")
    // Timestamp field converted to microseconds (Long)
    assertEquals(serialized(1), instant.toEpochMilli * 1000L)

    // Roundtrip - compare at millisecond precision due to internal representation
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized.msg, log.msg)
    assertEquals(deserialized.timestamp.toEpochMilli, log.timestamp.toEpochMilli)

  test("RowSerializer roundtrip with LocalDateTime field"):
    val serializer = RowSerializer.derived[Appointment]
    val ldt = java.time.LocalDateTime.of(2024, 1, 15, 10, 30, 0)
    val appt = Appointment("Meeting", ldt)

    val serialized = serializer.serialize(appt)
    assertEquals(serialized.length, 2)

    // Roundtrip
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, appt)
