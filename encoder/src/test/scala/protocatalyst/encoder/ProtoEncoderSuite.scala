package protocatalyst.encoder

import protocatalyst.types.*
import scala.reflect.{ClassTag, classTag}

// Test case classes with derived encoders for use with summon
case class Person(name: String, age: Int) derives ProtoEncoder
case class Address(street: String, city: String, zip: Int) derives ProtoEncoder
case class Employee(person: Person, address: Address, salary: Double) derives ProtoEncoder
case class OptionalFields(required: String, optional: Option[Int]) derives ProtoEncoder
case class WithCollections(tags: List[String], scores: Map[String, Int]) derives ProtoEncoder

// Test enums
enum Color derives ProtoEncoder:
  case Red, Green, Blue

enum Status derives ProtoEncoder:
  case Pending, Active, Completed, Cancelled

// Case class containing enum
case class Task(name: String, status: Status) derives ProtoEncoder

class ProtoEncoderSuite extends munit.FunSuite:

  // === Primitive encoder tests ===

  test("primitive encoders exist"):
    val intEnc = summon[ProtoEncoder[Int]]
    assertEquals(intEnc.catalystType, ProtoType.IntType)
    assertEquals(intEnc.nullable, false)

  test("string encoder"):
    val strEnc = summon[ProtoEncoder[String]]
    assertEquals(strEnc.catalystType, ProtoType.StringType)

  test("option encoder is nullable"):
    val optEnc = summon[ProtoEncoder[Option[Int]]]
    assertEquals(optEnc.nullable, true)
    assertEquals(optEnc.catalystType, ProtoType.IntType)

  // === Case class derivation tests ===

  test("derive encoder for simple case class"):
    val enc = ProtoEncoder.derived[Person]

    assertEquals(enc.nullable, false)
    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "name")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.StringType)
    assertEquals(enc.fields(1).name, "age")
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.IntType)

  test("case class schema has correct struct type"):
    val enc = ProtoEncoder.derived[Person]

    enc.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(fields(0).name, "name")
        assertEquals(fields(0).dataType, ProtoType.StringType)
        assertEquals(fields(0).nullable, false)
        assertEquals(fields(1).name, "age")
        assertEquals(fields(1).dataType, ProtoType.IntType)
        assertEquals(fields(1).nullable, false)
      case other =>
        fail(s"Expected StructType, got $other")

  test("case class schema fingerprint is stable"):
    val enc1 = ProtoEncoder.derived[Person]
    val enc2 = ProtoEncoder.derived[Person]

    assertEquals(enc1.schema.fingerprint, enc2.schema.fingerprint)

  test("nested case class derivation"):
    val enc = ProtoEncoder.derived[Employee]

    assertEquals(enc.fields.size, 3)
    assertEquals(enc.fields(0).name, "person")
    assertEquals(enc.fields(1).name, "address")
    assertEquals(enc.fields(2).name, "salary")

    // Check nested Person struct
    enc.fields(0).encoder.catalystType match
      case ProtoType.StructType(personFields) =>
        assertEquals(personFields.size, 2)
        assertEquals(personFields(0).name, "name")
        assertEquals(personFields(1).name, "age")
      case other =>
        fail(s"Expected nested StructType for person, got $other")

    // Check nested Address struct
    enc.fields(1).encoder.catalystType match
      case ProtoType.StructType(addressFields) =>
        assertEquals(addressFields.size, 3)
        assertEquals(addressFields(0).name, "street")
        assertEquals(addressFields(1).name, "city")
        assertEquals(addressFields(2).name, "zip")
      case other =>
        fail(s"Expected nested StructType for address, got $other")

  test("optional fields are marked nullable"):
    val enc = ProtoEncoder.derived[OptionalFields]

    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "required")
    assertEquals(enc.fields(0).nullable, false)
    assertEquals(enc.fields(1).name, "optional")
    assertEquals(enc.fields(1).nullable, true)

  // === Collection encoder tests ===

  test("list encoder produces ArrayType"):
    val enc = summon[ProtoEncoder[List[Int]]]

    enc.catalystType match
      case ProtoType.ArrayType(elemType, containsNull) =>
        assertEquals(elemType, ProtoType.IntType)
        assertEquals(containsNull, false)
      case other =>
        fail(s"Expected ArrayType, got $other")

  test("map encoder produces MapType"):
    val enc = summon[ProtoEncoder[Map[String, Int]]]

    enc.catalystType match
      case ProtoType.MapType(keyType, valType, valContainsNull) =>
        assertEquals(keyType, ProtoType.StringType)
        assertEquals(valType, ProtoType.IntType)
        assertEquals(valContainsNull, false)
      case other =>
        fail(s"Expected MapType, got $other")

  test("case class with collections"):
    val enc = ProtoEncoder.derived[WithCollections]

    assertEquals(enc.fields.size, 2)

    enc.fields(0).encoder.catalystType match
      case ProtoType.ArrayType(ProtoType.StringType, _) => () // ok
      case other => fail(s"Expected ArrayType[String], got $other")

    enc.fields(1).encoder.catalystType match
      case ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, _) => () // ok
      case other => fail(s"Expected MapType[String, Int], got $other")

  // === java.time encoder tests ===

  test("java.time.LocalDate encoder"):
    val enc = summon[ProtoEncoder[java.time.LocalDate]]
    assertEquals(enc.catalystType, ProtoType.DateType)

  test("java.time.Instant encoder"):
    val enc = summon[ProtoEncoder[java.time.Instant]]
    assertEquals(enc.catalystType, ProtoType.TimestampType)

  test("java.time.LocalDateTime encoder"):
    val enc = summon[ProtoEncoder[java.time.LocalDateTime]]
    assertEquals(enc.catalystType, ProtoType.TimestampNTZType)

  // === Tuple encoder tests ===

  test("tuple2 encoder"):
    val enc = summon[ProtoEncoder[(String, Int)]]

    enc.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(fields(0).name, "_1")
        assertEquals(fields(0).dataType, ProtoType.StringType)
        assertEquals(fields(1).name, "_2")
        assertEquals(fields(1).dataType, ProtoType.IntType)
      case other =>
        fail(s"Expected StructType for tuple, got $other")

  test("tuple3 encoder"):
    val enc = summon[ProtoEncoder[(String, Int, Double)]]

    enc.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 3)
        assertEquals(fields(0).name, "_1")
        assertEquals(fields(1).name, "_2")
        assertEquals(fields(2).name, "_3")
        assertEquals(fields(0).dataType, ProtoType.StringType)
        assertEquals(fields(1).dataType, ProtoType.IntType)
        assertEquals(fields(2).dataType, ProtoType.DoubleType)
      case other =>
        fail(s"Expected StructType for tuple, got $other")

  test("tuple with nested case class"):
    val enc = summon[ProtoEncoder[(Person, Int)]]

    enc.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(fields(0).name, "_1")
        assertEquals(fields(1).name, "_2")
        // First field should be a struct (Person)
        fields(0).dataType match
          case ProtoType.StructType(personFields) =>
            assertEquals(personFields.size, 2)
            assertEquals(personFields(0).name, "name")
            assertEquals(personFields(1).name, "age")
          case other =>
            fail(s"Expected nested StructType for Person, got $other")
      case other =>
        fail(s"Expected StructType for tuple, got $other")

  test("tuple with option"):
    val enc = summon[ProtoEncoder[(String, Option[Int])]]

    enc.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(fields(0).nullable, false)
        assertEquals(fields(1).nullable, true)  // Option is nullable
      case other =>
        fail(s"Expected StructType for tuple, got $other")

  test("case class containing tuple"):
    case class WithTuple(name: String, coords: (Double, Double))
    val enc = ProtoEncoder.derived[WithTuple]

    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "name")
    assertEquals(enc.fields(1).name, "coords")

    enc.fields(1).encoder.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(fields(0).name, "_1")
        assertEquals(fields(1).name, "_2")
      case other =>
        fail(s"Expected StructType for tuple field, got $other")

  // === ClassTag tests ===

  test("primitive encoder has correct ClassTag"):
    val intEnc = summon[ProtoEncoder[Int]]
    assertEquals(intEnc.clsTag, classTag[Int])

    val strEnc = summon[ProtoEncoder[String]]
    assertEquals(strEnc.clsTag, classTag[String])

    val boolEnc = summon[ProtoEncoder[Boolean]]
    assertEquals(boolEnc.clsTag, classTag[Boolean])

  test("derived case class has correct ClassTag"):
    val enc = ProtoEncoder.derived[Person]
    assertEquals(enc.clsTag.runtimeClass, classOf[Person])

  test("nested case class has correct ClassTag"):
    val enc = ProtoEncoder.derived[Employee]
    assertEquals(enc.clsTag.runtimeClass, classOf[Employee])

    // Nested encoder should also have correct ClassTag
    val personEnc = enc.fields(0).encoder
    assertEquals(personEnc.clsTag.runtimeClass, classOf[Person])

  test("option encoder has correct ClassTag"):
    val enc = summon[ProtoEncoder[Option[String]]]
    assertEquals(enc.clsTag.runtimeClass, classOf[Option[?]])

  test("list encoder has correct ClassTag"):
    val enc = summon[ProtoEncoder[List[Int]]]
    assertEquals(enc.clsTag.runtimeClass, classOf[List[?]])

  test("map encoder has correct ClassTag"):
    val enc = summon[ProtoEncoder[Map[String, Int]]]
    assertEquals(enc.clsTag.runtimeClass, classOf[Map[?, ?]])

  test("tuple encoder has correct ClassTag"):
    val enc2 = summon[ProtoEncoder[(String, Int)]]
    assertEquals(enc2.clsTag.runtimeClass, classOf[Tuple2[?, ?]])

    val enc3 = summon[ProtoEncoder[(String, Int, Double)]]
    assertEquals(enc3.clsTag.runtimeClass, classOf[Tuple3[?, ?, ?]])

  test("array encoder has correct ClassTag"):
    val enc = summon[ProtoEncoder[Array[Int]]]
    assertEquals(enc.clsTag.runtimeClass, classOf[Array[Int]])

  test("ClassTag allows creating arrays"):
    val enc = ProtoEncoder.derived[Person]
    val arr = enc.clsTag.newArray(5)
    assertEquals(arr.length, 5)
    assert(arr.isInstanceOf[Array[Person]])

  // === Enum encoder tests ===

  test("enum encoder uses StringType"):
    val enc = ProtoEncoder.derived[Color]
    assertEquals(enc.catalystType, ProtoType.StringType)
    assertEquals(enc.nullable, false)

  test("enum encoder has correct ClassTag"):
    val enc = ProtoEncoder.derived[Color]
    assertEquals(enc.clsTag.runtimeClass, classOf[Color])

  test("enum with multiple cases"):
    val enc = ProtoEncoder.derived[Status]
    assertEquals(enc.catalystType, ProtoType.StringType)
    assertEquals(enc.clsTag.runtimeClass, classOf[Status])

  test("case class containing enum"):
    val enc = ProtoEncoder.derived[Task]

    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "name")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.StringType)
    assertEquals(enc.fields(1).name, "status")
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.StringType)  // enum as string

  test("enum can be summoned via given"):
    val enc = summon[ProtoEncoder[Color]]
    assertEquals(enc.catalystType, ProtoType.StringType)

  test("option of enum"):
    val enc = summon[ProtoEncoder[Option[Status]]]
    assertEquals(enc.catalystType, ProtoType.StringType)
    assertEquals(enc.nullable, true)

  test("list of enums"):
    val enc = summon[ProtoEncoder[List[Color]]]
    enc.catalystType match
      case ProtoType.ArrayType(elemType, _) =>
        assertEquals(elemType, ProtoType.StringType)
      case other =>
        fail(s"Expected ArrayType, got $other")
