package protocatalyst.bench

import java.time._

import protocatalyst.encoder.ProtoEncoder

/** Test data classes for benchmarking.
  *
  * These classes are used across all benchmarks for consistent comparison. All derive ProtoEncoder
  * for compile-time schema derivation.
  */

// Simple case class - baseline benchmark
case class Simple(name: String, age: Int) derives ProtoEncoder

// Address for nested structures
case class Address(street: String, city: String, zip: String) derives ProtoEncoder

// Nested case class - tests recursive encoding
case class Person(name: String, age: Int, address: Address) derives ProtoEncoder

// Collection fields - tests array/map encoding
case class WithCollections(items: List[String], scores: Map[String, Double]) derives ProtoEncoder

// Temporal types - tests date/time encoding
case class Temporal(date: LocalDate, time: Instant) derives ProtoEncoder

// Duration (DayTimeIntervalType) and Period (YearMonthIntervalType) tests
case class WithDuration(name: String, duration: Duration) derives ProtoEncoder
case class WithPeriod(name: String, period: Period) derives ProtoEncoder

// BigInt/BigDecimal tests (DecimalType)
case class WithBigInt(name: String, value: BigInt) derives ProtoEncoder
case class WithBigDecimal(name: String, value: BigDecimal) derives ProtoEncoder

// Collections of custom types - tests nested struct serialization in arrays/maps
case class Team(name: String, members: List[Person]) derives ProtoEncoder
case class Directory(name: String, personByName: Map[String, Person]) derives ProtoEncoder

// Wide schema - tests many fields
case class Wide(
    f1: Int,
    f2: Int,
    f3: Int,
    f4: Int,
    f5: Int,
    f6: Int,
    f7: Int,
    f8: Int,
    f9: Int,
    f10: Int,
    f11: String,
    f12: String,
    f13: String,
    f14: String,
    f15: String,
    f16: Double,
    f17: Double,
    f18: Double,
    f19: Double,
    f20: Double
) derives ProtoEncoder

// Complex case class - comprehensive test
case class Complex(
    id: Long,
    name: String,
    scores: List[Double],
    metadata: Map[String, String],
    created: Instant,
    nested: Option[Person]
) derives ProtoEncoder

/** Factory for creating test data instances */
object BenchmarkData:
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

  val temporal: Temporal = Temporal(
    date = LocalDate.of(2024, 1, 15),
    time = Instant.parse("2024-01-15T10:30:00Z")
  )

  val wide: Wide = Wide(
    f1 = 1,
    f2 = 2,
    f3 = 3,
    f4 = 4,
    f5 = 5,
    f6 = 6,
    f7 = 7,
    f8 = 8,
    f9 = 9,
    f10 = 10,
    f11 = "s1",
    f12 = "s2",
    f13 = "s3",
    f14 = "s4",
    f15 = "s5",
    f16 = 1.0,
    f17 = 2.0,
    f18 = 3.0,
    f19 = 4.0,
    f20 = 5.0
  )

  val complex: Complex = Complex(
    id = 12345L,
    name = "TestEntity",
    scores = List(1.0, 2.0, 3.0, 4.0, 5.0),
    metadata = Map("key1" -> "value1", "key2" -> "value2"),
    created = Instant.parse("2024-01-15T10:30:00Z"),
    nested = Some(person)
  )

  val complexWithoutNested: Complex = complex.copy(nested = None)

  // Duration/Period test data
  val withDuration: WithDuration = WithDuration("task", Duration.ofHours(2).plusMinutes(30))
  val withPeriod: WithPeriod = WithPeriod("subscription", Period.of(1, 6, 0))

  // BigInt/BigDecimal test data
  val withBigInt: WithBigInt = WithBigInt("large", BigInt("12345678901234567890"))
  val withBigDecimal: WithBigDecimal =
    WithBigDecimal("precise", BigDecimal("123.456789012345678901"))
