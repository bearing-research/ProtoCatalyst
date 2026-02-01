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

// === Test sealed traits (ADTs) ===

// Simple sealed trait with case objects only (should behave like enum)
sealed trait SimpleColor derives ProtoEncoder
case object Red extends SimpleColor
case object Green extends SimpleColor
case object Blue extends SimpleColor

// Sealed trait with data-carrying variants
sealed trait Event derives ProtoEncoder
case class Click(x: Int, y: Int) extends Event derives ProtoEncoder
case class View(page: String) extends Event derives ProtoEncoder
case object Close extends Event

// Sealed trait with nested case class
sealed trait Message derives ProtoEncoder
case object Ping extends Message
case class Pong(id: Long) extends Message derives ProtoEncoder
case class Data(payload: Array[Byte]) extends Message derives ProtoEncoder

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

  test("java.time.LocalTime encoder"):
    val enc = summon[ProtoEncoder[java.time.LocalTime]]
    enc.catalystType match
      case ProtoType.TimeType(6) => () // ok - default microsecond precision
      case other                 => fail(s"Expected TimeType(6), got $other")

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
        assertEquals(fields(1).nullable, true) // Option is nullable
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
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.StringType) // enum as string

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

  // === Java enum encoder tests ===

  test("Java enum encoder uses StringType"):
    val enc = summon[ProtoEncoder[TestJavaEnums.Priority]]
    assertEquals(enc.catalystType, ProtoType.StringType)
    assertEquals(enc.nullable, false)

  test("Java enum encoder has correct ClassTag"):
    val enc = summon[ProtoEncoder[TestJavaEnums.Priority]]
    assertEquals(enc.clsTag.runtimeClass, classOf[TestJavaEnums.Priority])

  test("Java enum with multiple cases"):
    val enc = summon[ProtoEncoder[TestJavaEnums.DayOfWeek]]
    assertEquals(enc.catalystType, ProtoType.StringType)
    assertEquals(enc.clsTag.runtimeClass, classOf[TestJavaEnums.DayOfWeek])

  test("Java enum with custom fields"):
    // HttpStatus has custom fields and constructor, but should still work
    val enc = summon[ProtoEncoder[TestJavaEnums.HttpStatus]]
    assertEquals(enc.catalystType, ProtoType.StringType)
    assertEquals(enc.clsTag.runtimeClass, classOf[TestJavaEnums.HttpStatus])

  test("Option of Java enum"):
    val enc = summon[ProtoEncoder[Option[TestJavaEnums.Priority]]]
    assertEquals(enc.catalystType, ProtoType.StringType)
    assertEquals(enc.nullable, true)

  test("List of Java enums"):
    val enc = summon[ProtoEncoder[List[TestJavaEnums.DayOfWeek]]]
    enc.catalystType match
      case ProtoType.ArrayType(elemType, _) =>
        assertEquals(elemType, ProtoType.StringType)
      case other =>
        fail(s"Expected ArrayType, got $other")

  test("Map with Java enum values"):
    val enc = summon[ProtoEncoder[Map[String, TestJavaEnums.Priority]]]
    enc.catalystType match
      case ProtoType.MapType(keyType, valType, _) =>
        assertEquals(keyType, ProtoType.StringType)
        assertEquals(valType, ProtoType.StringType) // enum as string
      case other =>
        fail(s"Expected MapType, got $other")

  test("case class containing Java enum"):
    case class JavaEnumContainer(name: String, priority: TestJavaEnums.Priority)
    val enc = ProtoEncoder.derived[JavaEnumContainer]

    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "name")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.StringType)
    assertEquals(enc.fields(1).name, "priority")
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.StringType) // Java enum as string

  // === Boxed primitive encoder tests ===

  test("java.lang.Boolean encoder"):
    val enc = summon[ProtoEncoder[java.lang.Boolean]]
    assertEquals(enc.catalystType, ProtoType.BooleanType)
    assertEquals(enc.nullable, true) // Boxed types are nullable
    assertEquals(enc.clsTag.runtimeClass, classOf[java.lang.Boolean])

  test("java.lang.Integer encoder"):
    val enc = summon[ProtoEncoder[java.lang.Integer]]
    assertEquals(enc.catalystType, ProtoType.IntType)
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.lang.Integer])

  test("java.lang.Long encoder"):
    val enc = summon[ProtoEncoder[java.lang.Long]]
    assertEquals(enc.catalystType, ProtoType.LongType)
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.lang.Long])

  test("java.lang.Double encoder"):
    val enc = summon[ProtoEncoder[java.lang.Double]]
    assertEquals(enc.catalystType, ProtoType.DoubleType)
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.lang.Double])

  test("java.lang.Float encoder"):
    val enc = summon[ProtoEncoder[java.lang.Float]]
    assertEquals(enc.catalystType, ProtoType.FloatType)
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.lang.Float])

  test("java.lang.Byte encoder"):
    val enc = summon[ProtoEncoder[java.lang.Byte]]
    assertEquals(enc.catalystType, ProtoType.ByteType)
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.lang.Byte])

  test("java.lang.Short encoder"):
    val enc = summon[ProtoEncoder[java.lang.Short]]
    assertEquals(enc.catalystType, ProtoType.ShortType)
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.lang.Short])

  test("java.lang.Character encoder"):
    val enc = summon[ProtoEncoder[java.lang.Character]]
    assertEquals(enc.catalystType, ProtoType.StringType) // Char stored as String
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.lang.Character])

  test("java.math.BigDecimal encoder"):
    val enc = summon[ProtoEncoder[java.math.BigDecimal]]
    enc.catalystType match
      case ProtoType.DecimalType(precision, scale) =>
        assertEquals(precision, 38)
        assertEquals(scale, 18)
      case other =>
        fail(s"Expected DecimalType, got $other")
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.math.BigDecimal])

  test("java.math.BigInteger encoder"):
    val enc = summon[ProtoEncoder[java.math.BigInteger]]
    enc.catalystType match
      case ProtoType.DecimalType(precision, scale) =>
        assertEquals(precision, 38)
        assertEquals(scale, 0) // BigInteger has no fractional part
      case other =>
        fail(s"Expected DecimalType, got $other")
    assertEquals(enc.nullable, true)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.math.BigInteger])

  test("Option of boxed primitive"):
    val enc = summon[ProtoEncoder[Option[java.lang.Integer]]]
    assertEquals(enc.catalystType, ProtoType.IntType)
    assertEquals(enc.nullable, true)

  test("List of boxed primitives"):
    val enc = summon[ProtoEncoder[List[java.lang.Double]]]
    enc.catalystType match
      case ProtoType.ArrayType(elemType, containsNull) =>
        assertEquals(elemType, ProtoType.DoubleType)
        assertEquals(containsNull, true) // Boxed types are nullable
      case other =>
        fail(s"Expected ArrayType, got $other")

  test("case class containing boxed primitives"):
    case class BoxedContainer(
        count: java.lang.Integer,
        value: java.lang.Double,
        flag: java.lang.Boolean
    )
    val enc = ProtoEncoder.derived[BoxedContainer]

    assertEquals(enc.fields.size, 3)
    assertEquals(enc.fields(0).name, "count")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.IntType)
    assertEquals(enc.fields(0).nullable, true) // Boxed Integer is nullable
    assertEquals(enc.fields(1).name, "value")
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.DoubleType)
    assertEquals(enc.fields(1).nullable, true) // Boxed Double is nullable
    assertEquals(enc.fields(2).name, "flag")
    assertEquals(enc.fields(2).encoder.catalystType, ProtoType.BooleanType)
    assertEquals(enc.fields(2).nullable, true) // Boxed Boolean is nullable

  // === Additional temporal type encoder tests ===

  test("java.time.Duration encoder"):
    val enc = summon[ProtoEncoder[java.time.Duration]]
    assertEquals(enc.catalystType, ProtoType.DayTimeIntervalType)
    assertEquals(enc.nullable, false)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.time.Duration])

  test("java.time.Period encoder"):
    val enc = summon[ProtoEncoder[java.time.Period]]
    assertEquals(enc.catalystType, ProtoType.YearMonthIntervalType)
    assertEquals(enc.nullable, false)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.time.Period])

  test("java.sql.Date encoder"):
    val enc = summon[ProtoEncoder[java.sql.Date]]
    assertEquals(enc.catalystType, ProtoType.DateType)
    assertEquals(enc.nullable, false)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.sql.Date])

  test("java.sql.Timestamp encoder"):
    val enc = summon[ProtoEncoder[java.sql.Timestamp]]
    assertEquals(enc.catalystType, ProtoType.TimestampType)
    assertEquals(enc.nullable, false)
    assertEquals(enc.clsTag.runtimeClass, classOf[java.sql.Timestamp])

  test("Option of Duration"):
    val enc = summon[ProtoEncoder[Option[java.time.Duration]]]
    assertEquals(enc.catalystType, ProtoType.DayTimeIntervalType)
    assertEquals(enc.nullable, true)

  test("List of Periods"):
    val enc = summon[ProtoEncoder[List[java.time.Period]]]
    enc.catalystType match
      case ProtoType.ArrayType(elemType, _) =>
        assertEquals(elemType, ProtoType.YearMonthIntervalType)
      case other =>
        fail(s"Expected ArrayType, got $other")

  test("case class containing temporal types"):
    case class TemporalContainer(
        date: java.time.LocalDate,
        timestamp: java.time.Instant,
        duration: java.time.Duration,
        period: java.time.Period,
        sqlDate: java.sql.Date,
        sqlTimestamp: java.sql.Timestamp
    )
    val enc = ProtoEncoder.derived[TemporalContainer]

    assertEquals(enc.fields.size, 6)
    assertEquals(enc.fields(0).name, "date")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.DateType)
    assertEquals(enc.fields(1).name, "timestamp")
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.TimestampType)
    assertEquals(enc.fields(2).name, "duration")
    assertEquals(enc.fields(2).encoder.catalystType, ProtoType.DayTimeIntervalType)
    assertEquals(enc.fields(3).name, "period")
    assertEquals(enc.fields(3).encoder.catalystType, ProtoType.YearMonthIntervalType)
    assertEquals(enc.fields(4).name, "sqlDate")
    assertEquals(enc.fields(4).encoder.catalystType, ProtoType.DateType)
    assertEquals(enc.fields(5).name, "sqlTimestamp")
    assertEquals(enc.fields(5).encoder.catalystType, ProtoType.TimestampType)

  // === Java Bean encoder tests ===

  test("JavaBeanEncoder simple bean"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.SimplePerson])

    assertEquals(enc.fields.size, 2)
    // Fields are sorted alphabetically
    assertEquals(enc.fields(0).name, "age")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.IntType)
    assertEquals(enc.fields(0).nullable, false) // primitive int
    assertEquals(enc.fields(1).name, "name")
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.StringType)

  test("JavaBeanEncoder with primitives"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.PrimitiveBean])

    assertEquals(enc.fields.size, 8)
    // All primitives should be non-nullable
    enc.fields.foreach { f =>
      assertEquals(f.nullable, false, s"Field ${f.name} should be non-nullable")
    }

  test("JavaBeanEncoder with boxed types"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.BoxedBean])

    assertEquals(enc.fields.size, 4)
    // All boxed types should be nullable
    enc.fields.foreach { f =>
      assertEquals(f.nullable, true, s"Field ${f.name} should be nullable")
    }

  test("JavaBeanEncoder with temporal types"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.TemporalBean])

    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "date")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.DateType)
    assertEquals(enc.fields(1).name, "timestamp")
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.TimestampType)

  test("JavaBeanEncoder with BigDecimal"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.DecimalBean])

    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "amount")
    enc.fields(0).encoder.catalystType match
      case ProtoType.DecimalType(38, 18) => () // ok
      case other                         => fail(s"Expected DecimalType(38, 18), got $other")
    assertEquals(enc.fields(0).nullable, true) // java.math.BigDecimal is nullable

  test("JavaBeanEncoder with enum"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.EnumBean])

    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "name")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.StringType)
    assertEquals(enc.fields(1).name, "priority")
    assertEquals(enc.fields(1).encoder.catalystType, ProtoType.StringType) // enum as string

  test("JavaBeanEncoder with nested bean"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.NestedBean])

    assertEquals(enc.fields.size, 2)
    assertEquals(enc.fields(0).name, "id")
    assertEquals(enc.fields(0).encoder.catalystType, ProtoType.StringType)
    assertEquals(enc.fields(1).name, "person")

    // Check nested struct
    enc.fields(1).encoder.catalystType match
      case ProtoType.StructType(nestedFields) =>
        assertEquals(nestedFields.size, 2)
        assertEquals(nestedFields(0).name, "age")
        assertEquals(nestedFields(1).name, "name")
      case other =>
        fail(s"Expected StructType for nested bean, got $other")

  test("JavaBeanEncoder deeply nested"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.Employee])

    assertEquals(enc.fields.size, 4)
    // Fields sorted: address, employeeId, person, salary
    assertEquals(enc.fields(0).name, "address")
    assertEquals(enc.fields(1).name, "employeeId")
    assertEquals(enc.fields(2).name, "person")
    assertEquals(enc.fields(3).name, "salary")

    // Check address struct
    enc.fields(0).encoder.catalystType match
      case ProtoType.StructType(addressFields) =>
        assertEquals(addressFields.size, 3)
        // city, street, zipCode (sorted)
        assertEquals(addressFields(0).name, "city")
        assertEquals(addressFields(1).name, "street")
        assertEquals(addressFields(2).name, "zipCode")
      case other =>
        fail(s"Expected StructType for address, got $other")

  test("JavaBeanEncoder has correct ClassTag"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.SimplePerson])
    assertEquals(enc.clsTag.runtimeClass, classOf[TestJavaBeans.SimplePerson])

  test("JavaBeanEncoder schema matches struct type"):
    val enc = JavaBeanEncoder(classOf[TestJavaBeans.SimplePerson])

    enc.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(enc.schema.fields.size, 2)
        assertEquals(enc.schema.fields, fields)
      case other =>
        fail(s"Expected StructType, got $other")

  // === UDT encoder tests ===

  test("UDT encoder from explicit fromUDT"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.PointUDT)

    // Check UDT type wrapping
    enc.catalystType match
      case ProtoType.UDTType(className, sqlType) =>
        assert(className.contains("PointUDT"))
        sqlType match
          case ProtoType.StructType(fields) =>
            assertEquals(fields.size, 2)
            assertEquals(fields(0).name, "x")
            assertEquals(fields(0).dataType, ProtoType.DoubleType)
            assertEquals(fields(1).name, "y")
            assertEquals(fields(1).dataType, ProtoType.DoubleType)
          case other =>
            fail(s"Expected StructType as sqlType, got $other")
      case other =>
        fail(s"Expected UDTType, got $other")

  test("UDT encoder has correct ClassTag"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.PointUDT)
    assertEquals(enc.clsTag.runtimeClass, classOf[TestUDTs.Point])

  test("UDT encoder has correct schema"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.PointUDT)
    assertEquals(enc.schema.fields.size, 2)
    assertEquals(enc.schema.fields(0).name, "x")
    assertEquals(enc.schema.fields(1).name, "y")

  test("UDT encoder nullable defaults to true"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.PointUDT)
    assertEquals(enc.nullable, true)

  test("UDT encoder with binary sqlType"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.RGBColorUDT)

    enc.catalystType match
      case ProtoType.UDTType(className, ProtoType.BinaryType) =>
        assert(className.contains("RGBColorUDT"))
      case other =>
        fail(s"Expected UDTType with BinaryType, got $other")

    // Binary types have empty schema
    assertEquals(enc.schema.fields.size, 0)

  test("UDT encoder with array sqlType"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.ComplexNumberUDT)

    enc.catalystType match
      case ProtoType.UDTType(className, ProtoType.ArrayType(ProtoType.DoubleType, false)) =>
        assert(className.contains("ComplexNumberUDT"))
      case other =>
        fail(s"Expected UDTType with ArrayType[Double], got $other")

  test("UDT encoder with primitive sqlType"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.IPAddressUDT)

    enc.catalystType match
      case ProtoType.UDTType(className, ProtoType.IntType) =>
        assert(className.contains("IPAddressUDT"))
      case other =>
        fail(s"Expected UDTType with IntType, got $other")

  test("UDT encoder non-nullable"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.NonEmptyStringUDT)
    assertEquals(enc.nullable, false)

  test("UDT serialization roundtrip - Point"):
    val udt = TestUDTs.PointUDT
    val point = TestUDTs.Point(3.0, 4.0)

    val serialized = udt.serialize(point)
    val deserialized = udt.deserialize(serialized)

    assertEquals(deserialized, point)

  test("UDT serialization roundtrip - RGBColor"):
    val udt = TestUDTs.RGBColorUDT
    val color = TestUDTs.RGBColor(255, 128, 0)

    val serialized = udt.serialize(color)
    val deserialized = udt.deserialize(serialized)

    assertEquals(deserialized, color)

  test("UDT serialization roundtrip - ComplexNumber"):
    val udt = TestUDTs.ComplexNumberUDT
    val complex = TestUDTs.ComplexNumber(3.0, 4.0)

    val serialized = udt.serialize(complex)
    val deserialized = udt.deserialize(serialized)

    assertEquals(deserialized, complex)

  test("UDT serialization roundtrip - IPAddress"):
    val udt = TestUDTs.IPAddressUDT
    val ip = TestUDTs.IPAddress(192, 168, 1, 1)

    val serialized = udt.serialize(ip)
    val deserialized = udt.deserialize(serialized)

    assertEquals(deserialized, ip)

  test("UDT serialization null handling"):
    val udt = TestUDTs.PointUDT
    val serialized = udt.serialize(null)
    assertEquals(serialized, null)
    val deserialized = udt.deserialize(null)
    assertEquals(deserialized, null)

  test("UDT registry stores and retrieves UDTs"):
    ProtoUDT.clearRegistry()

    // Creating encoder should register the UDT
    val enc = ProtoEncoder.fromUDT(TestUDTs.PointUDT)

    // Lookup by UDT class name
    val byClassName = ProtoUDT.lookup(TestUDTs.PointUDT.getClass.getName)
    assert(byClassName.isDefined)

    // Lookup by user class
    val byUserClass = ProtoUDT.lookupByUserClass(classOf[TestUDTs.Point])
    assert(byUserClass.isDefined)

  test("UDT encoder via given instance"):
    // Provide UDT as given
    given ProtoUDT[TestUDTs.Point] = TestUDTs.PointUDT

    // summon should find the udtEncoder
    val enc = summon[ProtoEncoder[TestUDTs.Point]]

    enc.catalystType match
      case ProtoType.UDTType(className, _) =>
        assert(className.contains("PointUDT"))
      case other =>
        fail(s"Expected UDTType, got $other")

  test("UDT typeName"):
    assertEquals(TestUDTs.PointUDT.typeName, "Point")
    assertEquals(TestUDTs.RGBColorUDT.typeName, "RGB")
    assertEquals(TestUDTs.IPAddressUDT.typeName, "IPv4")

  test("UDT protoType"):
    val protoType = TestUDTs.PointUDT.protoType
    protoType match
      case ProtoType.UDTType(className, sqlType) =>
        assert(className.contains("PointUDT"))
        assertEquals(sqlType, TestUDTs.PointUDT.sqlType)
      case other =>
        fail(s"Expected UDTType, got $other")

  test("extractUDT from UDT encoder"):
    val enc = ProtoEncoder.fromUDT(TestUDTs.PointUDT)
    val extracted = ProtoEncoder.extractUDT(enc)
    assert(extracted.isDefined)
    assertEquals(extracted.get.userClass, classOf[TestUDTs.Point])

  test("extractUDT from non-UDT encoder"):
    val enc = summon[ProtoEncoder[String]]
    val extracted = ProtoEncoder.extractUDT(enc)
    assert(extracted.isEmpty)

  // === Sealed trait (ADT) encoder tests ===

  test("sealed trait with case objects only uses StringType (enum-like)"):
    val enc = ProtoEncoder.derived[SimpleColor]
    // All case objects = treated as simple enum
    assertEquals(enc.catalystType, ProtoType.StringType)
    assertEquals(enc.nullable, false)

  test("sealed trait with data-carrying variants uses SumType"):
    val enc = ProtoEncoder.derived[Event]
    // Has data-carrying variants = SumType
    enc.catalystType match
      case ProtoType.SumType(discriminator, variants) =>
        assertEquals(discriminator, "_type")
        assertEquals(variants.size, 3)
        assertEquals(variants(0).name, "Click")
        assertEquals(variants(0).ordinal, 0)
        assertEquals(variants(1).name, "View")
        assertEquals(variants(1).ordinal, 1)
        assertEquals(variants(2).name, "Close")
        assertEquals(variants(2).ordinal, 2)
        // Click and View have data, Close is singleton
        assert(variants(0).dataType.isDefined)
        assert(variants(1).dataType.isDefined)
        assert(variants(2).dataType.isEmpty) // case object
      case other =>
        fail(s"Expected SumType, got $other")

  test("sealed trait encoder has variants populated"):
    val enc = ProtoEncoder.derived[Event]
    assertEquals(enc.variants.size, 3)
    assertEquals(enc.variants(0).name, "Click")
    assertEquals(enc.variants(0).isSingleton, false)
    assertEquals(enc.variants(1).name, "View")
    assertEquals(enc.variants(1).isSingleton, false)
    assertEquals(enc.variants(2).name, "Close")
    assertEquals(enc.variants(2).isSingleton, true)

  test("sealed trait variant encoders have correct types"):
    val enc = ProtoEncoder.derived[Event]

    // Click variant encoder
    enc.variants(0).encoder match
      case Some(clickEnc) =>
        clickEnc.catalystType match
          case ProtoType.StructType(fields) =>
            assertEquals(fields.size, 2)
            assertEquals(fields(0).name, "x")
            assertEquals(fields(0).dataType, ProtoType.IntType)
            assertEquals(fields(1).name, "y")
            assertEquals(fields(1).dataType, ProtoType.IntType)
          case other =>
            fail(s"Expected StructType for Click, got $other")
      case None =>
        fail("Click should have an encoder")

    // View variant encoder
    enc.variants(1).encoder match
      case Some(viewEnc) =>
        viewEnc.catalystType match
          case ProtoType.StructType(fields) =>
            assertEquals(fields.size, 1)
            assertEquals(fields(0).name, "page")
            assertEquals(fields(0).dataType, ProtoType.StringType)
          case other =>
            fail(s"Expected StructType for View, got $other")
      case None =>
        fail("View should have an encoder")

    // Close is a case object - no encoder
    assertEquals(enc.variants(2).encoder, None)

  test("sealed trait encoder has correct ClassTag"):
    val enc = ProtoEncoder.derived[Event]
    assertEquals(enc.clsTag.runtimeClass, classOf[Event])

  test("sealed trait with mixed case objects and classes"):
    val enc = ProtoEncoder.derived[Message]
    enc.catalystType match
      case ProtoType.SumType(_, variants) =>
        assertEquals(variants.size, 3)
        assertEquals(variants(0).name, "Ping")
        assert(variants(0).dataType.isEmpty) // case object
        assertEquals(variants(1).name, "Pong")
        assert(variants(1).dataType.isDefined) // case class
        assertEquals(variants(2).name, "Data")
        assert(variants(2).dataType.isDefined) // case class
      case ProtoType.StringType =>
        // If all singletons, becomes StringType (shouldn't happen here)
        fail("Expected SumType, not StringType")
      case other =>
        fail(s"Expected SumType, got $other")

  test("SumEncoder can determine variant at runtime"):
    val enc = ProtoEncoder.derived[Event].asInstanceOf[ProtoEncoder.SumEncoder[Event]]

    val click: Event = Click(10, 20)
    val view: Event = View("home")
    val close: Event = Close

    assertEquals(enc.variantFor(click).name, "Click")
    assertEquals(enc.variantFor(click).ordinal, 0)
    assertEquals(enc.variantFor(view).name, "View")
    assertEquals(enc.variantFor(view).ordinal, 1)
    assertEquals(enc.variantFor(close).name, "Close")
    assertEquals(enc.variantFor(close).ordinal, 2)

  test("sealed trait can be summoned via given"):
    val enc = summon[ProtoEncoder[Event]]
    enc.catalystType match
      case ProtoType.SumType(_, _) => () // ok
      case other                   =>
        fail(s"Expected SumType, got $other")
