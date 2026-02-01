package protocatalyst.encoder

import protocatalyst.types.*

// ========== Test Case Classes for Nested Type Support ==========

// Basic nested types
case class NestedAddress(street: String, city: String) derives ProtoEncoder
case class NestedPerson(name: String, age: Int, address: NestedAddress) derives ProtoEncoder

// Collection of nested types
case class Team(name: String, members: List[NestedPerson]) derives ProtoEncoder
case class Department(name: String, teams: List[Team]) derives ProtoEncoder // 3 levels deep

// Optional nested types
case class EmployeeRecord(
    name: String,
    homeAddress: Option[NestedAddress],
    workAddress: Option[NestedAddress]
) derives ProtoEncoder

// Map with struct values
case class AddressBook(contacts: Map[String, NestedAddress]) derives ProtoEncoder

// Nested collections
case class Matrix(rows: List[List[Int]]) derives ProtoEncoder
case class NestedMaps(data: Map[String, Map[String, Int]]) derives ProtoEncoder

// Complex combination
case class Organization(
    name: String,
    headquarters: NestedAddress,
    departments: List[Department],
    locations: Map[String, NestedAddress],
    parentOrg: Option[String]
) derives ProtoEncoder

class NestedTypeSuite extends munit.FunSuite:

  // Use default converter
  given InternalTypeConverter = InternalTypeConverter.default

  // ========== ProtoEncoder Schema Tests ==========

  test("encoder for nested case class"):
    val enc = summon[ProtoEncoder[NestedPerson]]
    assertEquals(enc.fields.size, 3)
    assertEquals(enc.fields(0).name, "name")
    assertEquals(enc.fields(1).name, "age")
    assertEquals(enc.fields(2).name, "address")

    // Verify address is a StructType
    enc.fields(2).encoder.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
        assertEquals(fields(0).name, "street")
        assertEquals(fields(1).name, "city")
      case other =>
        fail(s"Expected StructType for address, got $other")

  test("encoder for List of nested case class"):
    val enc = summon[ProtoEncoder[List[NestedPerson]]]

    enc.catalystType match
      case ProtoType.ArrayType(elemType, _) =>
        elemType match
          case ProtoType.StructType(fields) =>
            assertEquals(fields.size, 3)
            assertEquals(fields(0).name, "name")
          case other =>
            fail(s"Expected StructType element, got $other")
      case other =>
        fail(s"Expected ArrayType, got $other")

  test("encoder for case class with List of nested types"):
    val enc = summon[ProtoEncoder[Team]]
    assertEquals(enc.fields.size, 2)

    enc.fields(1).encoder.catalystType match
      case ProtoType.ArrayType(ProtoType.StructType(personFields), _) =>
        assertEquals(personFields.size, 3)
      case other =>
        fail(s"Expected ArrayType[StructType], got $other")

  test("encoder for deeply nested structure (3 levels)"):
    val enc = summon[ProtoEncoder[Department]]

    // Department -> List[Team] -> Team.members: List[Person] -> Person.address: Address
    enc.fields(1).encoder.catalystType match
      case ProtoType.ArrayType(ProtoType.StructType(teamFields), _) =>
        assertEquals(teamFields.size, 2) // name, members
        teamFields(1).dataType match
          case ProtoType.ArrayType(ProtoType.StructType(personFields), _) =>
            assertEquals(personFields.size, 3) // name, age, address
          case other =>
            fail(s"Expected Team.members to be ArrayType[StructType], got $other")
      case other =>
        fail(s"Expected ArrayType[Team], got $other")

  test("encoder for Optional nested type"):
    val enc = summon[ProtoEncoder[EmployeeRecord]]

    assertEquals(enc.fields(1).nullable, true)
    assertEquals(enc.fields(2).nullable, true)

    enc.fields(1).encoder.catalystType match
      case ProtoType.StructType(fields) =>
        assertEquals(fields.size, 2)
      case other =>
        fail(s"Expected StructType for homeAddress, got $other")

  test("encoder for Map with struct values"):
    val enc = summon[ProtoEncoder[AddressBook]]

    enc.fields(0).encoder.catalystType match
      case ProtoType.MapType(keyType, valueType, _) =>
        assertEquals(keyType, ProtoType.StringType)
        valueType match
          case ProtoType.StructType(fields) =>
            assertEquals(fields.size, 2)
          case other =>
            fail(s"Expected StructType value, got $other")
      case other =>
        fail(s"Expected MapType, got $other")

  test("encoder for nested List (List[List[Int]])"):
    val enc = summon[ProtoEncoder[Matrix]]

    enc.fields(0).encoder.catalystType match
      case ProtoType.ArrayType(innerType, _) =>
        innerType match
          case ProtoType.ArrayType(ProtoType.IntType, _) => () // ok
          case other => fail(s"Expected ArrayType[Int], got $other")
      case other =>
        fail(s"Expected ArrayType[ArrayType[Int]], got $other")

  test("encoder for nested Map (Map[String, Map[String, Int]])"):
    val enc = summon[ProtoEncoder[NestedMaps]]

    enc.fields(0).encoder.catalystType match
      case ProtoType.MapType(ProtoType.StringType, innerType, _) =>
        innerType match
          case ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, _) => () // ok
          case other => fail(s"Expected MapType[String, Int], got $other")
      case other =>
        fail(s"Expected MapType[String, MapType], got $other")

  test("encoder for complex Organization"):
    val enc = summon[ProtoEncoder[Organization]]
    assertEquals(enc.fields.size, 5)
    assertEquals(enc.fields(0).name, "name")
    assertEquals(enc.fields(1).name, "headquarters")
    assertEquals(enc.fields(2).name, "departments")
    assertEquals(enc.fields(3).name, "locations")
    assertEquals(enc.fields(4).name, "parentOrg")

  // ========== InlineRowSerializer Tests ==========

  test("serialize/deserialize nested case class"):
    val serializer = InlineRowSerializer.derived[NestedPerson]
    val person = NestedPerson("Alice", 30, NestedAddress("123 Main St", "NYC"))

    val serialized = serializer.serialize(person)
    assertEquals(serialized.length, 3)
    assertEquals(serialized(0), "Alice")
    assertEquals(serialized(1), 30)
    // Nested struct should be Array[Any]
    assert(serialized(2).isInstanceOf[Array[?]])
    val addressArr = serialized(2).asInstanceOf[Array[Any]]
    assertEquals(addressArr(0), "123 Main St")
    assertEquals(addressArr(1), "NYC")

    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, person)

  test("serialize/deserialize List of nested case class"):
    val serializer = InlineRowSerializer.derived[Team]
    val team = Team(
      "Engineering",
      List(
        NestedPerson("Alice", 30, NestedAddress("123 Main", "NYC")),
        NestedPerson("Bob", 25, NestedAddress("456 Oak", "LA"))
      )
    )

    val serialized = serializer.serialize(team)
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, team)

  test("serialize/deserialize Optional nested type with Some"):
    val serializer = InlineRowSerializer.derived[EmployeeRecord]
    val record = EmployeeRecord(
      "Alice",
      Some(NestedAddress("123 Home", "NYC")),
      Some(NestedAddress("456 Work", "NYC"))
    )

    val serialized = serializer.serialize(record)
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, record)

  test("serialize/deserialize Optional nested type with None"):
    val serializer = InlineRowSerializer.derived[EmployeeRecord]
    val record = EmployeeRecord("Bob", None, None)

    val serialized = serializer.serialize(record)
    assertEquals(serialized(1), null)
    assertEquals(serialized(2), null)

    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, record)

  test("serialize/deserialize Map with struct values"):
    val serializer = InlineRowSerializer.derived[AddressBook]
    val book = AddressBook(
      Map(
        "home" -> NestedAddress("123 Home", "NYC"),
        "work" -> NestedAddress("456 Work", "LA")
      )
    )

    val serialized = serializer.serialize(book)
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, book)

  test("serialize/deserialize nested List (Matrix)"):
    val serializer = InlineRowSerializer.derived[Matrix]
    val matrix = Matrix(
      List(
        List(1, 2, 3),
        List(4, 5, 6),
        List(7, 8, 9)
      )
    )

    val serialized = serializer.serialize(matrix)
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, matrix)

  test("serialize/deserialize nested Map"):
    val serializer = InlineRowSerializer.derived[NestedMaps]
    val data = NestedMaps(
      Map(
        "group1" -> Map("a" -> 1, "b" -> 2),
        "group2" -> Map("c" -> 3, "d" -> 4)
      )
    )

    val serialized = serializer.serialize(data)
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, data)

  test("roundtrip deeply nested Department"):
    val serializer = InlineRowSerializer.derived[Department]
    val dept = Department(
      "R&D",
      List(
        Team(
          "Backend",
          List(
            NestedPerson("Alice", 30, NestedAddress("123 Main", "NYC"))
          )
        ),
        Team(
          "Frontend",
          List(
            NestedPerson("Bob", 25, NestedAddress("456 Oak", "LA")),
            NestedPerson("Carol", 28, NestedAddress("789 Pine", "SF"))
          )
        )
      )
    )

    val serialized = serializer.serialize(dept)
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, dept)

  test("roundtrip complex Organization"):
    val serializer = InlineRowSerializer.derived[Organization]
    val org = Organization(
      name = "Acme Corp",
      headquarters = NestedAddress("1 HQ Blvd", "NYC"),
      departments = List(
        Department(
          "Engineering",
          List(
            Team(
              "Backend",
              List(
                NestedPerson("Alice", 30, NestedAddress("123 Main", "NYC"))
              )
            )
          )
        )
      ),
      locations = Map(
        "NYC" -> NestedAddress("1 NYC St", "New York"),
        "LA" -> NestedAddress("2 LA Ave", "Los Angeles")
      ),
      parentOrg = Some("MegaCorp")
    )

    val serialized = serializer.serialize(org)
    val deserialized = serializer.deserialize(serialized)
    assertEquals(deserialized, org)
