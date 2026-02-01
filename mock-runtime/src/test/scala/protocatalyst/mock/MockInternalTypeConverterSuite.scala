package protocatalyst.mock

import protocatalyst.encoder._
import protocatalyst.types._

// Test case classes (derive ProtoEncoder for nested type support)
case class User(name: String, age: Int) derives ProtoEncoder
case class Address(street: String, city: String, zip: Option[Int]) derives ProtoEncoder
case class Person(user: User, address: Address, tags: List[String]) derives ProtoEncoder

// Temporal test case classes
case class Event(name: String, date: java.time.LocalDate) derives ProtoEncoder
case class LogEntry(msg: String, timestamp: java.time.Instant) derives ProtoEncoder
case class Appointment(title: String, dateTime: java.time.LocalDateTime) derives ProtoEncoder

// Collections of custom types
case class Team(name: String, members: List[User]) derives ProtoEncoder
case class Directory(name: String, usersByName: Map[String, User]) derives ProtoEncoder

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
    val structType = ProtoType.StructType(
      Vector(
        ProtoStructField("name", ProtoType.StringType, false),
        ProtoStructField("age", ProtoType.IntType, false)
      )
    )

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

  // === Collections of custom types ===

  test("RowSerializer with List[CustomType]"):
    val serializer = RowSerializer.derived[Team]
    val team = Team("Engineering", List(User("Alice", 30), User("Bob", 25)))

    val serialized = serializer.serialize(team)

    assertEquals(serialized.length, 2)
    // Name is converted to MockUTF8String
    assertEquals(serialized(0).asInstanceOf[MockUTF8String].toString, "Engineering")

    // List of User is converted to MockArrayData containing MockRows
    serialized(1) match
      case arr: MockArrayData =>
        assertEquals(arr.numElements, 2)
        // Each User is converted to MockRow
        arr.get(0) match
          case row: MockRow =>
            assertEquals(row.get(0).asInstanceOf[MockUTF8String].toString, "Alice")
            assertEquals(row.get(1), 30)
          case other =>
            fail(s"Expected MockRow for User in list, got $other")
        arr.get(1) match
          case row: MockRow =>
            assertEquals(row.get(0).asInstanceOf[MockUTF8String].toString, "Bob")
            assertEquals(row.get(1), 25)
          case other =>
            fail(s"Expected MockRow for User in list, got $other")
      case other =>
        fail(s"Expected MockArrayData for List[User], got $other")

  test("RowSerializer roundtrip with List[CustomType]"):
    val serializer = RowSerializer.derived[Team]
    val original = Team("Engineering", List(User("Alice", 30), User("Bob", 25)))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("RowSerializer with Map[String, CustomType]"):
    val serializer = RowSerializer.derived[Directory]
    val dir = Directory("Staff", Map("alice" -> User("Alice", 30), "bob" -> User("Bob", 25)))

    val serialized = serializer.serialize(dir)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0).asInstanceOf[MockUTF8String].toString, "Staff")

    // Map[String, User] is converted to MockMapData
    serialized(1) match
      case mapData: MockMapData =>
        assertEquals(mapData.numElements, 2)
        // Keys are MockUTF8String, values are MockRow
        assert(mapData.keys.forall(_.isInstanceOf[MockUTF8String]))
        assert(mapData.values.forall(_.isInstanceOf[MockRow]))
      case other =>
        fail(s"Expected MockMapData for Map[String, User], got $other")

  test("RowSerializer roundtrip with Map[String, CustomType]"):
    val serializer = RowSerializer.derived[Directory]
    val original = Directory("Staff", Map("alice" -> User("Alice", 30), "bob" -> User("Bob", 25)))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Tests for newly added Spark types ===

  test("TimeType converts LocalTime to microseconds"):
    val localTime = java.time.LocalTime.of(10, 30, 45, 123456000) // 10:30:45.123456
    val result = MockInternalTypeConverter.toInternal(localTime, ProtoType.TimeType(6))

    val expectedMicros = localTime.toNanoOfDay / 1000
    assertEquals(result, expectedMicros)

  test("TimeType converts String to microseconds"):
    val timeStr = "14:30:00"
    val result = MockInternalTypeConverter.toInternal(timeStr, ProtoType.TimeType(6))

    val expectedMicros = java.time.LocalTime.parse(timeStr).toNanoOfDay / 1000
    assertEquals(result, expectedMicros)

  test("TimeType passes through Long (already internal)"):
    val micros = 12345678L
    val result = MockInternalTypeConverter.toInternal(micros, ProtoType.TimeType(6))
    assertEquals(result, micros)

  test("TimeType fromInternal converts microseconds to LocalTime"):
    val micros = 38445123456L // 10:40:45.123456
    val result = MockInternalTypeConverter.fromInternal(micros, ProtoType.TimeType(6))

    val expected = java.time.LocalTime.ofNanoOfDay(micros * 1000)
    assertEquals(result, expected)

  test("TimeType roundtrip"):
    val original = java.time.LocalTime.of(9, 15, 30, 500000000) // 9:15:30.5
    val internal = MockInternalTypeConverter.toInternal(original, ProtoType.TimeType(6))
    val back = MockInternalTypeConverter.fromInternal(internal, ProtoType.TimeType(6))

    assertEquals(back.asInstanceOf[java.time.LocalTime].toSecondOfDay, original.toSecondOfDay)

  test("CalendarIntervalType passes through value"):
    val interval = (12, 5, 1000000L) // (months, days, microseconds)
    val result = MockInternalTypeConverter.toInternal(interval, ProtoType.CalendarIntervalType)
    assertEquals(result, interval)

  test("CalendarIntervalType fromInternal passes through"):
    val interval = (6, 15, 500000L)
    val result = MockInternalTypeConverter.fromInternal(interval, ProtoType.CalendarIntervalType)
    assertEquals(result, interval)

  test("VariantType passes through binary"):
    val data = Array[Byte](1, 2, 3, 4, 5)
    val result = MockInternalTypeConverter.toInternal(data, ProtoType.VariantType)
    assert(result.asInstanceOf[Array[Byte]].sameElements(data))

  test("VariantType fromInternal passes through"):
    val data = Array[Byte](10, 20, 30)
    val result = MockInternalTypeConverter.fromInternal(data, ProtoType.VariantType)
    assert(result.asInstanceOf[Array[Byte]].sameElements(data))

  test("CharType converts String to MockUTF8String"):
    val str = "hello"
    val result = MockInternalTypeConverter.toInternal(str, ProtoType.CharType(10))

    result match
      case utf8: MockUTF8String =>
        assertEquals(utf8.toString, "hello")
      case other =>
        fail(s"Expected MockUTF8String, got $other")

  test("CharType fromInternal converts back to String"):
    val utf8 = MockUTF8String("world")
    val result = MockInternalTypeConverter.fromInternal(utf8, ProtoType.CharType(10))
    assertEquals(result, "world")

  test("VarcharType converts String to MockUTF8String"):
    val str = "variable length string"
    val result = MockInternalTypeConverter.toInternal(str, ProtoType.VarcharType(255))

    result match
      case utf8: MockUTF8String =>
        assertEquals(utf8.toString, str)
      case other =>
        fail(s"Expected MockUTF8String, got $other")

  test("VarcharType fromInternal converts back to String"):
    val utf8 = MockUTF8String("test")
    val result = MockInternalTypeConverter.fromInternal(utf8, ProtoType.VarcharType(50))
    assertEquals(result, "test")

  test("CharType and VarcharType roundtrip"):
    val original = "roundtrip test"

    val charInternal = MockInternalTypeConverter.toInternal(original, ProtoType.CharType(20))
    val charBack = MockInternalTypeConverter.fromInternal(charInternal, ProtoType.CharType(20))
    assertEquals(charBack, original)

    val varcharInternal = MockInternalTypeConverter.toInternal(original, ProtoType.VarcharType(100))
    val varcharBack =
      MockInternalTypeConverter.fromInternal(varcharInternal, ProtoType.VarcharType(100))
    assertEquals(varcharBack, original)

  test("DayTimeIntervalType passes through Duration internal value"):
    val durationNanos = 3600000000000L // 1 hour in nanos (but stored differently)
    val result = MockInternalTypeConverter.toInternal(durationNanos, ProtoType.DayTimeIntervalType)
    assertEquals(result, durationNanos)

  test("YearMonthIntervalType passes through Period internal value"):
    val months = 14 // 1 year 2 months
    val result = MockInternalTypeConverter.toInternal(months, ProtoType.YearMonthIntervalType)
    assertEquals(result, months)
