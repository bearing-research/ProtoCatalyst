package protocatalyst.mock

import protocatalyst.encoder.ProtoEncoder

class UnsafeRowSuite extends munit.FunSuite:

  // ============================================
  // MockUnsafeRow Basic Tests
  // ============================================

  test("MockUnsafeRow stores and retrieves primitives"):
    val writer = UnsafeRowWriter(4)
    writer.write(0, true)
    writer.write(1, 42)
    writer.write(2, 3.14)
    writer.write(3, 9999L)

    val row = writer.getRow()

    assertEquals(row.getBoolean(0), true)
    assertEquals(row.getInt(1), 42)
    assertEquals(row.getDouble(2), 3.14)
    assertEquals(row.getLong(3), 9999L)

  test("MockUnsafeRow stores and retrieves all primitive types"):
    val writer = UnsafeRowWriter(6)
    writer.write(0, 127.toByte)
    writer.write(1, 32767.toShort)
    writer.write(2, Int.MaxValue)
    writer.write(3, Long.MaxValue)
    writer.write(4, 3.14f)
    writer.write(5, 2.718281828)

    val row = writer.getRow()

    assertEquals(row.getByte(0), 127.toByte)
    assertEquals(row.getShort(1), 32767.toShort)
    assertEquals(row.getInt(2), Int.MaxValue)
    assertEquals(row.getLong(3), Long.MaxValue)
    assertEqualsFloat(row.getFloat(4), 3.14f, 0.001f)
    assertEqualsDouble(row.getDouble(5), 2.718281828, 0.0000001)

  test("MockUnsafeRow handles null values"):
    val writer = UnsafeRowWriter(3)
    writer.write(0, 42)
    writer.setNullAt(1)
    writer.write(2, "hello")

    val row = writer.getRow()

    assertEquals(row.isNullAt(0), false)
    assertEquals(row.isNullAt(1), true)
    assertEquals(row.isNullAt(2), false)

    assertEquals(row.getInt(0), 42)
    assertEquals(row.getString(2), "hello")

  test("MockUnsafeRow stores and retrieves strings"):
    val writer = UnsafeRowWriter(3)
    writer.write(0, "hello")
    writer.write(1, "world")
    writer.write(2, "")

    val row = writer.getRow()

    assertEquals(row.getString(0), "hello")
    assertEquals(row.getString(1), "world")
    assertEquals(row.getString(2), "")

  test("MockUnsafeRow handles UTF-8 strings"):
    val writer = UnsafeRowWriter(2)
    writer.write(0, "你好世界")
    writer.write(1, "🎉🎊")

    val row = writer.getRow()

    assertEquals(row.getString(0), "你好世界")
    assertEquals(row.getString(1), "🎉🎊")

  test("MockUnsafeRow stores and retrieves binary data"):
    val writer = UnsafeRowWriter(2)
    val bytes1 = Array[Byte](1, 2, 3, 4, 5)
    val bytes2 = Array[Byte]()
    writer.write(0, bytes1)
    writer.write(1, bytes2)

    val row = writer.getRow()

    assertEquals(row.getBinary(0).toSeq, bytes1.toSeq)
    assertEquals(row.getBinary(1).toSeq, bytes2.toSeq)

  test("MockUnsafeRow copy creates independent row"):
    val writer = UnsafeRowWriter(2)
    writer.write(0, 42)
    writer.write(1, "hello")

    val row = writer.getRow()
    val copy = row.copy()

    assertEquals(row.getInt(0), copy.getInt(0))
    assertEquals(row.getString(1), copy.getString(1))
    assertEquals(row, copy)

  test("MockUnsafeRow equality"):
    val writer1 = UnsafeRowWriter(2)
    writer1.write(0, 42)
    writer1.write(1, "hello")
    val row1 = writer1.getRow()

    val writer2 = UnsafeRowWriter(2)
    writer2.write(0, 42)
    writer2.write(1, "hello")
    val row2 = writer2.getRow()

    assertEquals(row1, row2)
    assertEquals(row1.hashCode(), row2.hashCode())

  // ============================================
  // MockUTF8String Tests
  // ============================================

  test("MockUTF8String from string"):
    val utf8 = MockUTF8String.fromString("hello")
    assertEquals(utf8.toString, "hello")
    assertEquals(utf8.numBytes, 5)

  test("MockUTF8String comparison"):
    val a = MockUTF8String.fromString("abc")
    val b = MockUTF8String.fromString("abd")
    val c = MockUTF8String.fromString("abc")

    assert(a.compareTo(b) < 0)
    assert(b.compareTo(a) > 0)
    assertEquals(a.compareTo(c), 0)
    assertEquals(a, c)

  // ============================================
  // Array Data Tests
  // ============================================

  test("UnsafeRowWriter writes arrays"):
    val writer = UnsafeRowWriter(2)
    writer.writeArray(0, Seq(1, 2, 3), MockDataType.IntegerType)
    writer.writeArray(1, Seq("a", "b"), MockDataType.StringType)

    val row = writer.getRow()
    val intArr = row.getArray(0)
    val strArr = row.getArray(1)

    assertEquals(intArr.size, 3)
    assertEquals(intArr.getInt(0), 1)
    assertEquals(intArr.getInt(1), 2)
    assertEquals(intArr.getInt(2), 3)

    assertEquals(strArr.size, 2)
    assertEquals(strArr.getString(0), "a")
    assertEquals(strArr.getString(1), "b")

  test("UnsafeRowWriter writes arrays with nulls"):
    val writer = UnsafeRowWriter(1)
    writer.writeArray(0, Seq(1, null, 3), MockDataType.IntegerType)

    val row = writer.getRow()
    val arr = row.getArray(0)

    assertEquals(arr.size, 3)
    assertEquals(arr.isNullAt(0), false)
    assertEquals(arr.isNullAt(1), true)
    assertEquals(arr.isNullAt(2), false)
    assertEquals(arr.getInt(0), 1)
    assertEquals(arr.getInt(2), 3)

  // ============================================
  // Map Data Tests
  // ============================================

  test("UnsafeRowWriter writes maps"):
    val writer = UnsafeRowWriter(1)
    writer.writeMap(0, Map("a" -> 1, "b" -> 2), MockDataType.StringType, MockDataType.IntegerType)

    val row = writer.getRow()
    val mapData = row.getMap(0)

    assertEquals(mapData.numElements, 2)
    val resultMap = mapData.toMap(MockDataType.StringType, MockDataType.IntegerType)
    assertEquals(resultMap("a"), 1)
    assertEquals(resultMap("b"), 2)

  // ============================================
  // Nested Struct Tests
  // ============================================

  test("UnsafeRowWriter writes nested structs"):
    // Create inner struct
    val innerWriter = UnsafeRowWriter(2)
    innerWriter.write(0, "inner")
    innerWriter.write(1, 100)
    val innerRow = innerWriter.getRow()

    // Create outer struct with nested inner
    val outerWriter = UnsafeRowWriter(2)
    outerWriter.write(0, "outer")
    outerWriter.write(1, innerRow)

    val row = outerWriter.getRow()

    assertEquals(row.getString(0), "outer")

    val nested = row.getStruct(1, 2)
    assertEquals(nested.getString(0), "inner")
    assertEquals(nested.getInt(1), 100)

  // ============================================
  // UnsafeRowSerializer Derivation Tests
  // ============================================

  case class SimpleUser(name: String, age: Int) derives ProtoEncoder

  test("UnsafeRowSerializer derives for simple case class"):
    val ser = UnsafeRowSerializer.derived[SimpleUser]

    assertEquals(ser.schema.fields.size, 2)
    assertEquals(ser.schema.fields(0).name, "name")
    assertEquals(ser.schema.fields(1).name, "age")

  test("UnsafeRowSerializer roundtrip for simple case class"):
    val ser = UnsafeRowSerializer.derived[SimpleUser]
    val user = SimpleUser("Alice", 30)

    val row = ser.serialize(user)
    val back = ser.deserialize(row)

    assertEquals(back.name, user.name)
    assertEquals(back.age, user.age)

  case class AllPrimitives(
      b: Boolean,
      i: Int,
      l: Long,
      d: Double,
      s: String
  ) derives ProtoEncoder

  test("UnsafeRowSerializer roundtrip for all primitives"):
    val ser = UnsafeRowSerializer.derived[AllPrimitives]
    val original = AllPrimitives(true, 42, 9999L, 3.14, "hello")

    val row = ser.serialize(original)
    val back = ser.deserialize(row)

    assertEquals(back.b, original.b)
    assertEquals(back.i, original.i)
    assertEquals(back.l, original.l)
    assertEquals(back.d, original.d)
    assertEquals(back.s, original.s)

  case class WithOptional(name: String, age: Option[Int]) derives ProtoEncoder

  test("UnsafeRowSerializer handles Option[Some]"):
    val ser = UnsafeRowSerializer.derived[WithOptional]
    val original = WithOptional("Alice", Some(30))

    val row = ser.serialize(original)
    val back = ser.deserialize(row)

    assertEquals(back.name, original.name)
    assertEquals(back.age, Some(30))

  test("UnsafeRowSerializer handles Option[None]"):
    val ser = UnsafeRowSerializer.derived[WithOptional]
    val original = WithOptional("Bob", None)

    val row = ser.serialize(original)
    val back = ser.deserialize(row)

    assertEquals(back.name, original.name)
    assertEquals(back.age, None)

  case class Address(city: String, zip: String) derives ProtoEncoder
  case class PersonWithAddress(name: String, city: String, zip: String) derives ProtoEncoder

  test("UnsafeRowSerializer handles flat structs with multiple fields"):
    // Note: Nested case class deserialization returns Product, not the original type.
    // This is expected behavior for UnsafeRow - nested structs are accessed via getStruct().
    // For full case class reconstruction, use RowSerializer with proper type encoders.
    val ser = UnsafeRowSerializer.derived[PersonWithAddress]
    val original = PersonWithAddress("Alice", "NYC", "10001")

    val row = ser.serialize(original)
    val back = ser.deserialize(row)

    assertEquals(back.name, original.name)
    assertEquals(back.city, original.city)
    assertEquals(back.zip, original.zip)

  case class WithArray(name: String, scores: Seq[Int]) derives ProtoEncoder

  test("UnsafeRowSerializer handles arrays"):
    val ser = UnsafeRowSerializer.derived[WithArray]
    val original = WithArray("Alice", Seq(90, 85, 95))

    val row = ser.serialize(original)
    val back = ser.deserialize(row)

    assertEquals(back.name, original.name)
    assertEquals(back.scores, Seq(90, 85, 95))

  case class WithMap(name: String, props: Map[String, Int]) derives ProtoEncoder

  test("UnsafeRowSerializer handles maps"):
    val ser = UnsafeRowSerializer.derived[WithMap]
    val original = WithMap("Alice", Map("a" -> 1, "b" -> 2))

    val row = ser.serialize(original)
    val back = ser.deserialize(row)

    assertEquals(back.name, original.name)
    assertEquals(back.props, Map("a" -> 1, "b" -> 2))

  // ============================================
  // Large Row Tests (8-byte alignment verification)
  // ============================================

  test("MockUnsafeRow handles many fields (null bitmap alignment)"):
    // Test with >64 fields to verify multi-word null bitmap
    val numFields = 100
    val writer = UnsafeRowWriter(numFields)

    (0 until numFields).foreach { i =>
      if i % 2 == 0 then writer.write(i, i)
      else writer.setNullAt(i)
    }

    val row = writer.getRow()

    (0 until numFields).foreach { i =>
      if i % 2 == 0 then
        assertEquals(row.isNullAt(i), false)
        assertEquals(row.getInt(i), i)
      else assertEquals(row.isNullAt(i), true)
    }

  test("MockUnsafeRow memory layout is 8-byte aligned"):
    val writer = UnsafeRowWriter(3)
    writer.write(0, "a") // 1 byte content, should be padded
    writer.write(1, "bb") // 2 byte content, should be padded
    writer.write(2, "ccc") // 3 byte content, should be padded

    val row = writer.getRow()

    // Verify data integrity through 8-byte alignment
    assertEquals(row.getString(0), "a")
    assertEquals(row.getString(1), "bb")
    assertEquals(row.getString(2), "ccc")

    // Size should be aligned: nullBitmap(8) + fixedWidth(24) + variable(8*3 = 24 with padding)
    // Each string: "a"=1 byte -> 8 bytes, "bb"=2 bytes -> 8 bytes, "ccc"=3 bytes -> 8 bytes
    assert(row.sizeInBytes % 8 == 0, s"Size ${row.sizeInBytes} is not 8-byte aligned")

  // ============================================
  // Edge Cases
  // ============================================

  test("MockUnsafeRow handles empty string"):
    val writer = UnsafeRowWriter(1)
    writer.write(0, "")
    val row = writer.getRow()
    assertEquals(row.getString(0), "")

  test("MockUnsafeRow throws on invalid ordinal"):
    val writer = UnsafeRowWriter(2)
    writer.write(0, 1)
    writer.write(1, 2)
    val row = writer.getRow()

    intercept[IndexOutOfBoundsException] {
      row.getInt(2)
    }
    intercept[IndexOutOfBoundsException] {
      row.getInt(-1)
    }

  test("MockUnsafeRow throws on null access for primitives"):
    val writer = UnsafeRowWriter(1)
    writer.setNullAt(0)
    val row = writer.getRow()

    intercept[NullPointerException] {
      row.getInt(0)
    }

  test("UnsafeRowWriter requires all fields to be written"):
    val writer = UnsafeRowWriter(2)
    writer.write(0, 42)
    // Field 1 not written

    intercept[IllegalStateException] {
      writer.getRow()
    }

  // ============================================
  // Performance Characteristics (informational)
  // ============================================

  test("MockUnsafeRow is compact (informational)"):
    val writer = UnsafeRowWriter(3)
    writer.write(0, 42)
    writer.write(1, 3.14)
    writer.write(2, "hello")

    val row = writer.getRow()

    // null bitmap: 8 bytes (for 3 fields)
    // fixed region: 24 bytes (3 * 8)
    // variable region: 8 bytes (5 bytes + 3 padding for "hello")
    // Total: 40 bytes
    assertEquals(row.sizeInBytes, 40)
