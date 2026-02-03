package protocatalyst.benchmark

import java.time._
import java.util.UUID

/** Test data classes for Spark comparison benchmarks.
  *
  * These mirror the classes in protocatalyst.bench but use Scala 2.13 syntax.
  */

// Simple case class - baseline benchmark
case class Simple(name: String, age: Int)

// Duration (DayTimeIntervalType) and Period (YearMonthIntervalType) test
case class WithDuration(name: String, duration: Duration)
case class WithPeriod(name: String, period: Period)

// BigInt/BigDecimal test
case class WithBigInt(name: String, value: BigInt)
case class WithBigDecimal(name: String, value: BigDecimal)

// Address for nested structures
case class Address(street: String, city: String, zip: String)

// Nested case class - tests recursive encoding
case class Person(name: String, age: Int, address: Address)

// Collection fields - tests array/map encoding
case class WithCollections(items: List[String], scores: Map[String, Double])

// Collections of custom types - tests nested struct serialization in arrays/maps
case class Team(name: String, members: List[Person])
case class Directory(name: String, personByName: Map[String, Person])

// Complex case class - comprehensive test
case class Complex(
    id: Long,
    name: String,
    scores: List[Double],
    metadata: Map[String, String],
    created: Instant,
    nested: Option[Person]
)

// Tuple tests - wrap tuples in case classes for ExpressionEncoder
case class WithTuple2(label: String, pair: (Int, String))
case class WithTuple3(label: String, triple: (String, Int, Double))
case class WithTuple5(label: String, tuple: (String, Int, Double, Boolean, Long))

// UUID test
case class WithUUID(name: String, id: UUID)

// Primitive type tests (Byte, Short, Float)
case class WithByte(name: String, value: Byte)
case class WithShort(name: String, value: Short)
case class WithFloat(name: String, value: Float)

// Date/Time type tests
case class WithLocalDate(name: String, date: LocalDate)
case class WithLocalDateTime(name: String, datetime: LocalDateTime)

// Binary type test
case class WithBinary(name: String, data: Array[Byte])

// Edge case tests
case class WithNullString(id: Int, name: String) // name can be null
case class WithOptionalNone(id: Int, person: Option[Person]) // Option with None
case class EmptyCollections(items: List[String], scores: Map[String, Int])
case class IntBoundaries(label: String, minVal: Int, maxVal: Int)
case class LongBoundaries(label: String, minVal: Long, maxVal: Long)

/** Factory for creating test data instances */
object BenchmarkData {
  val simple: Simple = Simple("Alice", 30)

  val address: Address = Address("123 Main St", "New York", "10001")

  val person: Person = Person("Bob", 25, address)

  val withCollections: WithCollections = WithCollections(
    items = List("item1", "item2", "item3"),
    scores = Map("a" -> 1.0, "b" -> 2.0, "c" -> 3.0)
  )

  val team: Team = Team(
    name = "Engineering",
    members = List(
      Person("Alice", 30, Address("123 Main St", "NYC", "10001")),
      Person("Bob", 25, Address("456 Oak Ave", "LA", "90001")),
      Person("Charlie", 35, Address("789 Pine Rd", "SF", "94102"))
    )
  )

  val directory: Directory = Directory(
    name = "Staff",
    personByName = Map(
      "alice" -> Person("Alice", 30, Address("123 Main St", "NYC", "10001")),
      "bob" -> Person("Bob", 25, Address("456 Oak Ave", "LA", "90001"))
    )
  )

  val complex: Complex = Complex(
    id = 12345L,
    name = "TestEntity",
    scores = List(1.0, 2.0, 3.0, 4.0, 5.0),
    metadata = Map("key1" -> "value1", "key2" -> "value2"),
    created = Instant.parse("2024-01-15T10:30:00Z"),
    nested = Some(person)
  )

  // Duration/Period test data
  val withDuration: WithDuration = WithDuration("task", Duration.ofHours(2).plusMinutes(30))
  val withPeriod: WithPeriod = WithPeriod("subscription", Period.of(1, 6, 0))

  // BigInt/BigDecimal test data
  val withBigInt: WithBigInt = WithBigInt("large", BigInt("12345678901234567890"))
  val withBigDecimal: WithBigDecimal =
    WithBigDecimal("precise", BigDecimal("123.456789012345678901"))

  // Tuple test data
  val withTuple2: WithTuple2 = WithTuple2("pair", (42, "answer"))
  val withTuple3: WithTuple3 = WithTuple3("triple", ("hello", 123, 3.14))
  val withTuple5: WithTuple5 =
    WithTuple5("quintet", ("test", 1, 2.5, true, 9999999999L))

  // UUID test data
  val withUUID: WithUUID =
    WithUUID("entity", UUID.fromString("550e8400-e29b-41d4-a716-446655440000"))

  // Primitive type test data
  val withByte: WithByte = WithByte("byte", 42.toByte)
  val withShort: WithShort = WithShort("short", 1000.toShort)
  val withFloat: WithFloat = WithFloat("float", 3.14f)

  // Date/Time test data
  val withLocalDate: WithLocalDate = WithLocalDate("date", LocalDate.of(2024, 6, 15))
  val withLocalDateTime: WithLocalDateTime =
    WithLocalDateTime("datetime", LocalDateTime.of(2024, 6, 15, 10, 30, 45))

  // Binary test data
  val withBinary: WithBinary = WithBinary("binary", Array[Byte](1, 2, 3, 4, 5, 0, -1, -128, 127))

  // Edge case test data
  val withNullString: WithNullString = WithNullString(1, null)
  val withOptionalNone: WithOptionalNone = WithOptionalNone(1, None)
  val emptyCollections: EmptyCollections = EmptyCollections(List.empty, Map.empty)
  val intBoundaries: IntBoundaries = IntBoundaries("bounds", Int.MinValue, Int.MaxValue)
  val longBoundaries: LongBoundaries = LongBoundaries("bounds", Long.MinValue, Long.MaxValue)
}
