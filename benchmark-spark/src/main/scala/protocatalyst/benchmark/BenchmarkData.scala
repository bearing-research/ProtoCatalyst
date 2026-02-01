package protocatalyst.benchmark

import java.time._

/** Test data classes for Spark comparison benchmarks.
  *
  * These mirror the classes in protocatalyst.bench but use Scala 2.13 syntax.
  */

// Simple case class - baseline benchmark
case class Simple(name: String, age: Int)

// Address for nested structures
case class Address(street: String, city: String, zip: String)

// Nested case class - tests recursive encoding
case class Person(name: String, age: Int, address: Address)

// Collection fields - tests array/map encoding
case class WithCollections(items: List[String], scores: Map[String, Double])

// Collections of custom types - tests nested struct serialization in arrays/maps
case class Team(name: String, members: List[Person])

// Complex case class - comprehensive test
case class Complex(
    id: Long,
    name: String,
    scores: List[Double],
    metadata: Map[String, String],
    created: Instant,
    nested: Option[Person]
)

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

  val complex: Complex = Complex(
    id = 12345L,
    name = "TestEntity",
    scores = List(1.0, 2.0, 3.0, 4.0, 5.0),
    metadata = Map("key1" -> "value1", "key2" -> "value2"),
    created = Instant.parse("2024-01-15T10:30:00Z"),
    nested = Some(person)
  )
}
