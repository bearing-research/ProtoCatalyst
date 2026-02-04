package protocatalyst.mock

import java.time.{Duration, Instant, Period}
import java.util.{Base64, UUID}

import scala.io.Source

import protocatalyst.encoder._

/** Case classes matching benchmark-spark module for parity testing.
  *
  * These MUST have identical field names, types, and order as the Scala 2.13 versions in
  * benchmark-spark/src/main/scala/protocatalyst/benchmark/BenchmarkData.scala
  *
  * Named with Parity prefix to avoid conflicts with other test classes in this package.
  */
case class ParitySimple(name: String, age: Int) derives InlineRowSerializer
case class ParityAddress(street: String, city: String, zip: String) derives InlineRowSerializer
case class ParityPerson(name: String, age: Int, address: ParityAddress) derives InlineRowSerializer
case class ParityWithCollections(items: List[String], scores: Map[String, Double])
    derives InlineRowSerializer
case class ParityTeam(name: String, members: List[ParityPerson]) derives InlineRowSerializer
case class ParityDirectory(name: String, personByName: Map[String, ParityPerson])
    derives InlineRowSerializer
case class ParityComplex(
    id: Long,
    name: String,
    scores: List[Double],
    metadata: Map[String, String],
    created: Instant,
    nested: Option[ParityPerson]
) derives InlineRowSerializer

// Duration (DayTimeIntervalType) and Period (YearMonthIntervalType) tests
case class ParityWithDuration(name: String, duration: Duration) derives InlineRowSerializer
case class ParityWithPeriod(name: String, period: Period) derives InlineRowSerializer

// BigInt/BigDecimal tests (DecimalType)
case class ParityWithBigInt(name: String, value: BigInt) derives InlineRowSerializer
case class ParityWithBigDecimal(name: String, value: BigDecimal) derives InlineRowSerializer

// Tuple tests
case class ParityWithTuple2(label: String, pair: (Int, String)) derives InlineRowSerializer
case class ParityWithTuple3(label: String, triple: (String, Int, Double))
    derives InlineRowSerializer
case class ParityWithTuple5(label: String, tuple: (String, Int, Double, Boolean, Long))
    derives InlineRowSerializer

// UUID test (UUID stored as StringType)
case class ParityWithUUID(name: String, id: UUID) derives InlineRowSerializer

// Primitive type tests (Byte, Short, Float)
case class ParityWithByte(name: String, value: Byte) derives InlineRowSerializer
case class ParityWithShort(name: String, value: Short) derives InlineRowSerializer
case class ParityWithFloat(name: String, value: Float) derives InlineRowSerializer

// Date/Time type tests
case class ParityWithLocalDate(name: String, date: java.time.LocalDate) derives InlineRowSerializer
case class ParityWithLocalDateTime(name: String, datetime: java.time.LocalDateTime)
    derives InlineRowSerializer

// Binary type test
case class ParityWithBinary(name: String, data: Array[Byte]) derives InlineRowSerializer

// Edge case tests
case class ParityWithNullString(id: Int, name: String) derives InlineRowSerializer
case class ParityWithOptionalNone(id: Int, person: Option[ParityPerson]) derives InlineRowSerializer
case class ParityEmptyCollections(items: List[String], scores: Map[String, Int])
    derives InlineRowSerializer
case class ParityIntBoundaries(label: String, minVal: Int, maxVal: Int) derives InlineRowSerializer
case class ParityLongBoundaries(label: String, minVal: Long, maxVal: Long)
    derives InlineRowSerializer

/** Test data matching benchmark-spark BenchmarkData object */
object ParityTestData:
  val simple: ParitySimple = ParitySimple("Alice", 30)

  val address: ParityAddress = ParityAddress("123 Main St", "New York", "10001")

  val person: ParityPerson = ParityPerson("Bob", 25, address)

  val withCollections: ParityWithCollections = ParityWithCollections(
    items = List("item1", "item2", "item3"),
    scores = Map("a" -> 1.0, "b" -> 2.0, "c" -> 3.0)
  )

  val team: ParityTeam = ParityTeam(
    name = "Engineering",
    members = List(
      ParityPerson("Alice", 30, ParityAddress("123 Main St", "NYC", "10001")),
      ParityPerson("Bob", 25, ParityAddress("456 Oak Ave", "LA", "90001")),
      ParityPerson("Charlie", 35, ParityAddress("789 Pine Rd", "SF", "94102"))
    )
  )

  val directory: ParityDirectory = ParityDirectory(
    name = "Staff",
    personByName = Map(
      "alice" -> ParityPerson("Alice", 30, ParityAddress("123 Main St", "NYC", "10001")),
      "bob" -> ParityPerson("Bob", 25, ParityAddress("456 Oak Ave", "LA", "90001"))
    )
  )

  val complex: ParityComplex = ParityComplex(
    id = 12345L,
    name = "TestEntity",
    scores = List(1.0, 2.0, 3.0, 4.0, 5.0),
    metadata = Map("key1" -> "value1", "key2" -> "value2"),
    created = Instant.parse("2024-01-15T10:30:00Z"),
    nested = Some(person)
  )

  // Duration/Period test data - must match benchmark-spark BenchmarkData
  val withDuration: ParityWithDuration =
    ParityWithDuration("task", Duration.ofHours(2).plusMinutes(30))
  val withPeriod: ParityWithPeriod = ParityWithPeriod("subscription", Period.of(1, 6, 0))

  // BigInt/BigDecimal test data - must match benchmark-spark BenchmarkData
  val withBigInt: ParityWithBigInt = ParityWithBigInt("large", BigInt("12345678901234567890"))
  val withBigDecimal: ParityWithBigDecimal =
    ParityWithBigDecimal("precise", BigDecimal("123.456789012345678901"))

  // Tuple test data - must match benchmark-spark BenchmarkData
  val withTuple2: ParityWithTuple2 = ParityWithTuple2("pair", (42, "answer"))
  val withTuple3: ParityWithTuple3 = ParityWithTuple3("triple", ("hello", 123, 3.14))
  val withTuple5: ParityWithTuple5 =
    ParityWithTuple5("quintet", ("test", 1, 2.5, true, 9999999999L))

  // UUID test data - must match benchmark-spark BenchmarkData
  val withUUID: ParityWithUUID =
    ParityWithUUID("entity", UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))

  // Primitive type test data - must match benchmark-spark BenchmarkData
  val withByte: ParityWithByte = ParityWithByte("byte", 42.toByte)
  val withShort: ParityWithShort = ParityWithShort("short", 1000.toShort)
  val withFloat: ParityWithFloat = ParityWithFloat("float", 3.14f)

  // Date/Time test data - must match benchmark-spark BenchmarkData
  val withLocalDate: ParityWithLocalDate =
    ParityWithLocalDate("date", java.time.LocalDate.of(2024, 6, 15))
  val withLocalDateTime: ParityWithLocalDateTime =
    ParityWithLocalDateTime("datetime", java.time.LocalDateTime.of(2024, 6, 15, 10, 30, 45))

  // Binary test data - must match benchmark-spark BenchmarkData
  val withBinary: ParityWithBinary =
    ParityWithBinary("binary", Array[Byte](1, 2, 3, 4, 5, 0, -1, -128, 127))

  // Edge case test data - must match benchmark-spark BenchmarkData
  val withNullString: ParityWithNullString = ParityWithNullString(1, null)
  val withOptionalNone: ParityWithOptionalNone = ParityWithOptionalNone(1, None)
  val emptyCollections: ParityEmptyCollections = ParityEmptyCollections(List.empty, Map.empty)
  val intBoundaries: ParityIntBoundaries =
    ParityIntBoundaries("bounds", Int.MinValue, Int.MaxValue)
  val longBoundaries: ParityLongBoundaries =
    ParityLongBoundaries("bounds", Long.MinValue, Long.MaxValue)

/** Spark parity tests using golden files.
  *
  * These tests verify that ProtoCatalyst's InlineRowSerializer produces byte-identical output to
  * Spark's ExpressionEncoder for all supported types.
  */
class SparkParitySuite extends munit.FunSuite:

  given InternalTypeConverter = MockInternalTypeConverter

  /** Load a golden file from test resources */
  def loadGolden(name: String): ujson.Value =
    val stream = getClass.getResourceAsStream(s"/golden/$name.json")
    if stream == null then throw new RuntimeException(s"Golden file not found: /golden/$name.json")
    val content = Source.fromInputStream(stream, "UTF-8").mkString
    stream.close()
    ujson.read(content)

  /** Compare a serialized value against the golden expected value */
  def compareValue(expected: ujson.Value, actual: Any, path: String): Unit =
    val expectedType = expected("type").str
    expectedType match
      case "Null" =>
        assertEquals(actual, null, s"Expected null at $path")

      case "UTF8String" =>
        val expectedBytes = Base64.getDecoder.decode(expected("base64").str)
        actual match
          case utf8: MockUTF8String =>
            assertEquals(
              utf8.getBytes.toSeq,
              expectedBytes.toSeq,
              s"UTF8String bytes mismatch at $path: expected '${new String(expectedBytes)}', got '${utf8.toString}'"
            )
          case other =>
            fail(s"Expected MockUTF8String at $path, got ${
                if other == null then "null"
                else other.getClass.getName
              }")

      case "Int" =>
        assertEquals(actual, expected("value").num.toInt, s"Int mismatch at $path")

      case "Long" =>
        assertEquals(actual, expected("value").num.toLong, s"Long mismatch at $path")

      case "Double" =>
        assertEquals(actual, expected("value").num, s"Double mismatch at $path")

      case "Float" =>
        assertEquals(
          actual.asInstanceOf[Float],
          expected("value").num.toFloat,
          s"Float mismatch at $path"
        )

      case "Boolean" =>
        assertEquals(actual, expected("value").bool, s"Boolean mismatch at $path")

      case "Byte" =>
        assertEquals(
          actual.asInstanceOf[Byte],
          expected("value").num.toByte,
          s"Byte mismatch at $path"
        )

      case "Short" =>
        assertEquals(
          actual.asInstanceOf[Short],
          expected("value").num.toShort,
          s"Short mismatch at $path"
        )

      case "Binary" =>
        val expectedBytes = Base64.getDecoder.decode(expected("base64").str)
        actual match
          case bytes: Array[Byte] =>
            assertEquals(bytes.toSeq, expectedBytes.toSeq, s"Binary bytes mismatch at $path")
          case other =>
            fail(s"Expected Array[Byte] at $path, got ${
                if other == null then "null"
                else other.getClass.getName
              }")

      case "Date" =>
        assertEquals(actual, expected("epochDays").num.toInt, s"Date mismatch at $path")

      case "Timestamp" =>
        assertEquals(actual, expected("micros").num.toLong, s"Timestamp mismatch at $path")

      case "TimestampNTZ" =>
        // LocalDateTime stored as microseconds (Long)
        assertEquals(actual, expected("micros").num.toLong, s"TimestampNTZ mismatch at $path")

      case "DayTimeInterval" =>
        // Duration stored as microseconds (Long)
        assertEquals(actual, expected("micros").num.toLong, s"DayTimeInterval mismatch at $path")

      case "YearMonthInterval" =>
        // Period stored as total months (Int)
        assertEquals(actual, expected("months").num.toInt, s"YearMonthInterval mismatch at $path")

      case "Decimal" =>
        // BigInt/BigDecimal stored as Spark Decimal
        val expectedValue = BigDecimal(expected("value").str)
        actual match
          case bd: java.math.BigDecimal =>
            assertEquals(
              BigDecimal(bd),
              expectedValue,
              s"Decimal mismatch at $path"
            )
          case bd: BigDecimal =>
            assertEquals(bd, expectedValue, s"Decimal mismatch at $path")
          case bi: BigInt =>
            assertEquals(
              BigDecimal(bi),
              expectedValue,
              s"Decimal mismatch at $path"
            )
          case bi: java.math.BigInteger =>
            assertEquals(
              BigDecimal(BigInt(bi)),
              expectedValue,
              s"Decimal mismatch at $path"
            )
          case other =>
            fail(s"Expected Decimal type at $path, got ${
                if other == null then "null" else other.getClass.getName
              }")

      case "ArrayData" =>
        actual match
          case arr: MockArrayData =>
            val expectedElements = expected("elements").arr
            assertEquals(
              arr.numElements,
              expectedElements.length,
              s"ArrayData length mismatch at $path"
            )
            expectedElements.zipWithIndex.foreach { case (elem, i) =>
              compareValue(elem, arr.get(i), s"$path[$i]")
            }
          case other =>
            fail(s"Expected MockArrayData at $path, got ${
                if other == null then "null"
                else other.getClass.getName
              }")

      case "MapData" =>
        actual match
          case map: MockMapData =>
            val expectedKeys = expected("keys").arr
            val expectedValues = expected("values").arr
            assertEquals(
              map.numElements,
              expectedKeys.length,
              s"MapData length mismatch at $path"
            )
            expectedKeys.zipWithIndex.foreach { case (key, i) =>
              compareValue(key, map.keys(i), s"$path.keys[$i]")
            }
            expectedValues.zipWithIndex.foreach { case (value, i) =>
              compareValue(value, map.values(i), s"$path.values[$i]")
            }
          case other =>
            fail(s"Expected MockMapData at $path, got ${
                if other == null then "null"
                else other.getClass.getName
              }")

      case "InternalRow" =>
        actual match
          case row: MockRow =>
            val expectedFields = expected("fields").obj
            expectedFields.zipWithIndex.foreach { case ((fieldName, fieldValue), i) =>
              compareValue(fieldValue, row.get(i), s"$path.$fieldName")
            }
          case arr: Array[?] =>
            // Handle case where nested struct is Array[Any]
            val expectedFields = expected("fields").obj
            expectedFields.zipWithIndex.foreach { case ((fieldName, fieldValue), i) =>
              compareValue(fieldValue, arr(i), s"$path.$fieldName")
            }
          case other =>
            fail(s"Expected MockRow or Array at $path, got ${
                if other == null then "null"
                else other.getClass.getName
              }")

      case other =>
        fail(s"Unknown type '$other' at $path")

  /** Run parity test for a type */
  def runParityTest[T: InlineRowSerializer](
      goldenName: String,
      data: T,
      testCaseName: String = "standard"
  ): Unit =
    val golden = loadGolden(goldenName)
    val serializer = summon[InlineRowSerializer[T]]
    val serialized = serializer.serialize(data)

    // Find the matching test case
    val testCase = golden("testCases").arr
      .find(_("name").str == testCaseName)
      .getOrElse(
        fail(s"Test case '$testCaseName' not found in golden file '$goldenName'")
      )

    val expectedFields = testCase("serialized").obj
    expectedFields.zipWithIndex.foreach { case ((fieldName, expected), i) =>
      compareValue(expected, serialized(i), fieldName)
    }

  // === Parity Tests ===

  test("Simple type parity with Spark"):
    runParityTest("simple", ParityTestData.simple)

  test("Address type parity with Spark"):
    runParityTest("address", ParityTestData.address)

  test("Person (nested struct) parity with Spark"):
    runParityTest("person", ParityTestData.person)

  test("WithCollections (List and Map) parity with Spark"):
    runParityTest("with_collections", ParityTestData.withCollections)

  test("Team (List[Person]) parity with Spark"):
    runParityTest("team", ParityTestData.team)

  test("Directory (Map[String, Person]) parity with Spark"):
    runParityTest("directory", ParityTestData.directory)

  test("Complex (Option, Instant, nested) parity with Spark"):
    runParityTest("complex", ParityTestData.complex)

  test("WithDuration (DayTimeIntervalType) parity with Spark"):
    runParityTest("with_duration", ParityTestData.withDuration)

  test("WithPeriod (YearMonthIntervalType) parity with Spark"):
    runParityTest("with_period", ParityTestData.withPeriod)

  test("WithBigInt (DecimalType) parity with Spark"):
    runParityTest("with_bigint", ParityTestData.withBigInt)

  test("WithBigDecimal (DecimalType) parity with Spark"):
    runParityTest("with_bigdecimal", ParityTestData.withBigDecimal)

  test("WithTuple2 (Tuple2) parity with Spark"):
    runParityTest("with_tuple2", ParityTestData.withTuple2)

  test("WithTuple3 (Tuple3) parity with Spark"):
    runParityTest("with_tuple3", ParityTestData.withTuple3)

  test("WithTuple5 (Tuple5) parity with Spark"):
    runParityTest("with_tuple5", ParityTestData.withTuple5)

  test("WithUUID (UUID as StringType) parity with Spark"):
    runParityTest("with_uuid", ParityTestData.withUUID)

  test("WithByte (ByteType) parity with Spark"):
    runParityTest("with_byte", ParityTestData.withByte)

  test("WithShort (ShortType) parity with Spark"):
    runParityTest("with_short", ParityTestData.withShort)

  test("WithFloat (FloatType) parity with Spark"):
    runParityTest("with_float", ParityTestData.withFloat)

  test("WithLocalDate (DateType) parity with Spark"):
    runParityTest("with_localdate", ParityTestData.withLocalDate)

  test("WithLocalDateTime (TimestampNTZType) parity with Spark"):
    runParityTest("with_localdatetime", ParityTestData.withLocalDateTime)

  test("WithBinary (BinaryType) parity with Spark"):
    runParityTest("with_binary", ParityTestData.withBinary)

  // === Edge Case Parity Tests ===

  test("WithNullString (null String field) parity with Spark"):
    runParityTest("with_null_string", ParityTestData.withNullString)

  test("WithOptionalNone (Option with None) parity with Spark"):
    runParityTest("with_optional_none", ParityTestData.withOptionalNone)

  test("EmptyCollections (empty List and Map) parity with Spark"):
    runParityTest("empty_collections", ParityTestData.emptyCollections)

  test("IntBoundaries (Int.MinValue, Int.MaxValue) parity with Spark"):
    runParityTest("int_boundaries", ParityTestData.intBoundaries)

  test("LongBoundaries (Long.MinValue, Long.MaxValue) parity with Spark"):
    runParityTest("long_boundaries", ParityTestData.longBoundaries)
