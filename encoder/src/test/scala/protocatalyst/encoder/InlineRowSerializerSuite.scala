package protocatalyst.encoder

import protocatalyst.types._

// Test case classes for InlineRowSerializer (prefixed to avoid conflicts)
case class InlineUser(name: String, age: Int)
case class InlineWithOptional(required: String, optional: Option[Int])
case class InlineNested(inner: InlineUser, value: Double)
case class InlinePrimitives(
    boolVal: Boolean,
    byteVal: Byte,
    shortVal: Short,
    intVal: Int,
    longVal: Long,
    floatVal: Float,
    doubleVal: Double
)
case class InlineWithString(id: Int, name: String, description: String)
case class InlineEmpty()
case class InlineSingleField(value: Int)
case class InlineWithUserList(name: String, users: List[InlineUser])
case class InlineWithUserMap(name: String, usersByName: Map[String, InlineUser])

class InlineRowSerializerSuite extends munit.FunSuite:

  // Use default converter (no transformation) for basic tests
  given InternalTypeConverter = InternalTypeConverter.default

  // === Basic serialization tests ===

  test("derive InlineRowSerializer for simple case class"):
    val serializer = InlineRowSerializer.derived[InlineUser]
    assertEquals(serializer.fieldCount, 2)

  test("serialize simple case class"):
    val serializer = InlineRowSerializer.derived[InlineUser]
    val row = InlineUser("Alice", 30)

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "Alice")
    assertEquals(serialized(1), 30)

  test("deserialize simple case class"):
    val serializer = InlineRowSerializer.derived[InlineUser]
    val data = Array[Any]("Bob", 25)

    val deserialized = serializer.deserialize(data)

    assertEquals(deserialized, InlineUser("Bob", 25))

  test("roundtrip serialization"):
    val serializer = InlineRowSerializer.derived[InlineUser]
    val original = InlineUser("Charlie", 42)

    val serialized = serializer.serialize(original)
    val deserialized = serializer.deserialize(serialized)

    assertEquals(deserialized, original)

  // === Primitive type tests ===

  test("serialize all primitive types"):
    val serializer = InlineRowSerializer.derived[InlinePrimitives]
    val row = InlinePrimitives(
      boolVal = true,
      byteVal = 42.toByte,
      shortVal = 1000.toShort,
      intVal = 100000,
      longVal = 9999999999L,
      floatVal = 3.14f,
      doubleVal = 2.71828
    )

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 7)
    assertEquals(serialized(0), true)
    assertEquals(serialized(1), 42.toByte)
    assertEquals(serialized(2), 1000.toShort)
    assertEquals(serialized(3), 100000)
    assertEquals(serialized(4), 9999999999L)
    assertEquals(serialized(5), 3.14f)
    assertEquals(serialized(6), 2.71828)

  test("roundtrip all primitive types"):
    val serializer = InlineRowSerializer.derived[InlinePrimitives]
    val original = InlinePrimitives(
      boolVal = false,
      byteVal = -1.toByte,
      shortVal = -100.toShort,
      intVal = -999,
      longVal = -123456789L,
      floatVal = -1.5f,
      doubleVal = -0.001
    )

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Optional field tests ===

  test("serialize with Some value"):
    val serializer = InlineRowSerializer.derived[InlineWithOptional]
    val row = InlineWithOptional("test", Some(100))

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "test")
    assertEquals(serialized(1), 100) // Unwrapped from Option

  test("serialize with None value"):
    val serializer = InlineRowSerializer.derived[InlineWithOptional]
    val row = InlineWithOptional("test", None)

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "test")
    assertEquals(serialized(1), null) // None becomes null

  test("deserialize with non-null value to Option"):
    val serializer = InlineRowSerializer.derived[InlineWithOptional]
    val data = Array[Any]("test", 200)

    val deserialized = serializer.deserialize(data)

    assertEquals(deserialized, InlineWithOptional("test", Some(200)))

  test("deserialize with null to None"):
    val serializer = InlineRowSerializer.derived[InlineWithOptional]
    val data = Array[Any]("test", null)

    val deserialized = serializer.deserialize(data)

    assertEquals(deserialized, InlineWithOptional("test", None))

  test("roundtrip with Option[Some]"):
    val serializer = InlineRowSerializer.derived[InlineWithOptional]
    val original = InlineWithOptional("roundtrip", Some(42))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip with Option[None]"):
    val serializer = InlineRowSerializer.derived[InlineWithOptional]
    val original = InlineWithOptional("roundtrip", None)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === String field tests ===

  test("serialize multiple string fields"):
    val serializer = InlineRowSerializer.derived[InlineWithString]
    val row = InlineWithString(1, "hello", "world")

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 3)
    assertEquals(serialized(0), 1)
    assertEquals(serialized(1), "hello")
    assertEquals(serialized(2), "world")

  test("roundtrip with string fields"):
    val serializer = InlineRowSerializer.derived[InlineWithString]
    val original = InlineWithString(42, "foo", "bar baz")

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Nested case class tests ===

  test("serialize nested case class"):
    val serializer = InlineRowSerializer.derived[InlineNested]
    val row = InlineNested(InlineUser("Nested", 99), 3.14)

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 2)
    // Nested struct is serialized as Array[Any]
    val innerArray = serialized(0).asInstanceOf[Array[Any]]
    assertEquals(innerArray(0), "Nested")
    assertEquals(innerArray(1), 99)
    assertEquals(serialized(1), 3.14)

  test("deserialize nested case class"):
    val serializer = InlineRowSerializer.derived[InlineNested]
    val data = Array[Any](
      Array[Any]("FromArray", 50),
      2.718
    )

    val deserialized = serializer.deserialize(data)

    assertEquals(deserialized.inner, InlineUser("FromArray", 50))
    assertEquals(deserialized.value, 2.718)

  test("roundtrip nested case class"):
    val serializer = InlineRowSerializer.derived[InlineNested]
    val original = InlineNested(InlineUser("Deep", 123), 1.414)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Edge cases ===

  test("serialize empty case class"):
    val serializer = InlineRowSerializer.derived[InlineEmpty]
    val row = InlineEmpty()

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 0)

  test("deserialize empty case class"):
    val serializer = InlineRowSerializer.derived[InlineEmpty]
    val data = Array[Any]()

    val deserialized = serializer.deserialize(data)

    assertEquals(deserialized, InlineEmpty())

  test("serialize single field case class"):
    val serializer = InlineRowSerializer.derived[InlineSingleField]
    val row = InlineSingleField(42)

    val serialized = serializer.serialize(row)

    assertEquals(serialized.length, 1)
    assertEquals(serialized(0), 42)

  test("roundtrip single field case class"):
    val serializer = InlineRowSerializer.derived[InlineSingleField]
    val original = InlineSingleField(999)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Bulk roundtrip test ===

  test("roundtrip 10000 rows"):
    val serializer = InlineRowSerializer.derived[InlineUser]
    val users = (1 to 10000).map(i => InlineUser(s"User$i", i))

    val roundtripped = users.map { user =>
      serializer.deserialize(serializer.serialize(user))
    }

    assertEquals(roundtripped, users)

  // === Collections of custom types ===

  test("serialize List[CustomType]"):
    val serializer = InlineRowSerializer.derived[InlineWithUserList]
    val data = InlineWithUserList("team", List(InlineUser("Alice", 30), InlineUser("Bob", 25)))

    val serialized = serializer.serialize(data)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "team")
    // List of custom types should be serialized as a Seq of Array[Any]
    val userList = serialized(1).asInstanceOf[List[?]]
    assertEquals(userList.length, 2)
    val user0 = userList(0).asInstanceOf[Array[Any]]
    assertEquals(user0(0), "Alice")
    assertEquals(user0(1), 30)
    val user1 = userList(1).asInstanceOf[Array[Any]]
    assertEquals(user1(0), "Bob")
    assertEquals(user1(1), 25)

  test("roundtrip List[CustomType]"):
    val serializer = InlineRowSerializer.derived[InlineWithUserList]
    val original = InlineWithUserList("team", List(InlineUser("Alice", 30), InlineUser("Bob", 25)))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("serialize Map[String, CustomType]"):
    val serializer = InlineRowSerializer.derived[InlineWithUserMap]
    val data = InlineWithUserMap(
      "lookup",
      Map("alice" -> InlineUser("Alice", 30), "bob" -> InlineUser("Bob", 25))
    )

    val serialized = serializer.serialize(data)

    assertEquals(serialized.length, 2)
    assertEquals(serialized(0), "lookup")
    // Map values should be serialized as Array[Any]
    val userMap = serialized(1).asInstanceOf[Map[?, ?]]
    assertEquals(userMap.size, 2)

  test("roundtrip Map[String, CustomType]"):
    val serializer = InlineRowSerializer.derived[InlineWithUserMap]
    val original = InlineWithUserMap(
      "lookup",
      Map("alice" -> InlineUser("Alice", 30), "bob" -> InlineUser("Bob", 25))
    )

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === LocalTime test ===

  case class InlineWithLocalTime(name: String, time: java.time.LocalTime)

  test("roundtrip LocalTime"):
    val serializer = InlineRowSerializer.derived[InlineWithLocalTime]
    val original = InlineWithLocalTime("morning", java.time.LocalTime.of(9, 30, 0))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === BigInt tests ===

  case class InlineWithBigInt(name: String, value: BigInt)

  test("roundtrip BigInt"):
    val serializer = InlineRowSerializer.derived[InlineWithBigInt]
    val original = InlineWithBigInt("test", BigInt("12345678901234567890"))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip BigInt with negative value"):
    val serializer = InlineRowSerializer.derived[InlineWithBigInt]
    val original = InlineWithBigInt("negative", BigInt("-99999999999999999999"))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Duration tests ===

  case class InlineWithDuration(name: String, duration: java.time.Duration)

  test("roundtrip Duration"):
    val serializer = InlineRowSerializer.derived[InlineWithDuration]
    val original = InlineWithDuration("task", java.time.Duration.ofHours(2))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip Duration with minutes and seconds"):
    val serializer = InlineRowSerializer.derived[InlineWithDuration]
    val original = InlineWithDuration("meeting", java.time.Duration.ofMinutes(90).plusSeconds(30))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Period tests ===

  case class InlineWithPeriod(name: String, period: java.time.Period)

  test("roundtrip Period"):
    val serializer = InlineRowSerializer.derived[InlineWithPeriod]
    val original = InlineWithPeriod("subscription", java.time.Period.ofMonths(3))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip Period with years and months"):
    val serializer = InlineRowSerializer.derived[InlineWithPeriod]
    val original = InlineWithPeriod("contract", java.time.Period.of(2, 6, 0))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    // Note: Period comparison is by total months, so days are lost
    assertEquals(deserialized.name, original.name)
    assertEquals(deserialized.period.toTotalMonths, original.period.toTotalMonths)

  // === UUID tests ===

  case class InlineWithUUID(name: String, id: java.util.UUID)

  test("roundtrip UUID"):
    val serializer = InlineRowSerializer.derived[InlineWithUUID]
    val uuid = java.util.UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    val original = InlineWithUUID("entity", uuid)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip random UUID"):
    val serializer = InlineRowSerializer.derived[InlineWithUUID]
    val uuid = java.util.UUID.randomUUID()
    val original = InlineWithUUID("random", uuid)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  case class InlineWithOptionalUUID(name: String, id: Option[java.util.UUID])

  test("roundtrip Option[UUID] with Some"):
    val serializer = InlineRowSerializer.derived[InlineWithOptionalUUID]
    val uuid = java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    val original = InlineWithOptionalUUID("with-id", Some(uuid))

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip Option[UUID] with None"):
    val serializer = InlineRowSerializer.derived[InlineWithOptionalUUID]
    val original = InlineWithOptionalUUID("no-id", None)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("UUID encoder maps to StringType"):
    val enc = summon[ProtoEncoder[java.util.UUID]]
    assertEquals(enc.catalystType, ProtoType.StringType)

  // === Extended Tuple tests ===

  test("roundtrip Tuple6"):
    val serializer = InlineRowSerializer.derived[(Int, Int, Int, Int, Int, Int)]
    val original = (1, 2, 3, 4, 5, 6)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip Tuple10"):
    val serializer = InlineRowSerializer.derived[(Int, Int, Int, Int, Int, Int, Int, Int, Int, Int)]
    val original = (1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  test("roundtrip Tuple with mixed types"):
    val serializer =
      InlineRowSerializer.derived[(String, Int, Double, Boolean, Long, String, Int, Double)]
    val original = ("hello", 42, 3.14, true, 100L, "world", 99, 2.718)

    val deserialized = serializer.deserialize(serializer.serialize(original))

    assertEquals(deserialized, original)

  // === Char/Varchar encoder factory tests ===

  test("charEncoder creates String encoder with CharType"):
    val enc = ProtoEncoder.charEncoder(20)
    assertEquals(enc.catalystType, ProtoType.CharType(20))

  test("varcharEncoder creates String encoder with VarcharType"):
    val enc = ProtoEncoder.varcharEncoder(100)
    assertEquals(enc.catalystType, ProtoType.VarcharType(100))

  test("voidEncoder maps to NullType"):
    val enc = summon[ProtoEncoder[java.lang.Void]]
    assertEquals(enc.catalystType, ProtoType.NullType)
