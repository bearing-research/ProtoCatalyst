package protocatalyst.encoder

import munit.FunSuite

class ProtoRowSuite extends FunSuite:

  // === Creation tests ===

  test("create ProtoRow with varargs"):
    val row = ProtoRow("Alice", 30, true)
    assertEquals(row.length, 3)
    assertEquals(row.get(0), "Alice")
    assertEquals(row.get(1), 30)
    assertEquals(row.get(2), true)

  test("create ProtoRow from Seq"):
    val row = ProtoRow.fromSeq(Seq("Bob", 25, false))
    assertEquals(row.length, 3)
    assertEquals(row.get(0), "Bob")
    assertEquals(row.get(1), 25)
    assertEquals(row.get(2), false)

  test("empty ProtoRow"):
    val row = ProtoRow.empty
    assertEquals(row.length, 0)
    assertEquals(row.toSeq, Seq.empty)

  // === Type-specific getters ===

  test("getBoolean"):
    val row = ProtoRow(true, false)
    assertEquals(row.getBoolean(0), true)
    assertEquals(row.getBoolean(1), false)

  test("getByte"):
    val row = ProtoRow(1.toByte, 127.toByte)
    assertEquals(row.getByte(0), 1.toByte)
    assertEquals(row.getByte(1), 127.toByte)

  test("getShort"):
    val row = ProtoRow(100.toShort, 32000.toShort)
    assertEquals(row.getShort(0), 100.toShort)
    assertEquals(row.getShort(1), 32000.toShort)

  test("getInt"):
    val row = ProtoRow(42, -100)
    assertEquals(row.getInt(0), 42)
    assertEquals(row.getInt(1), -100)

  test("getLong"):
    val row = ProtoRow(9999999999L, -1L)
    assertEquals(row.getLong(0), 9999999999L)
    assertEquals(row.getLong(1), -1L)

  test("getFloat"):
    val row = ProtoRow(3.14f, -0.5f)
    assertEquals(row.getFloat(0), 3.14f)
    assertEquals(row.getFloat(1), -0.5f)

  test("getDouble"):
    val row = ProtoRow(2.718281828, -100.5)
    assertEquals(row.getDouble(0), 2.718281828)
    assertEquals(row.getDouble(1), -100.5)

  test("getString"):
    val row = ProtoRow("hello", "world")
    assertEquals(row.getString(0), "hello")
    assertEquals(row.getString(1), "world")

  test("getString converts non-string to string"):
    val row = ProtoRow(42)
    assertEquals(row.getString(0), "42")

  test("getBinary"):
    val bytes = Array[Byte](1, 2, 3, 4)
    val row = ProtoRow(bytes)
    assertEquals(row.getBinary(0).toSeq, bytes.toSeq)

  test("getDecimal from BigDecimal"):
    val row = ProtoRow(BigDecimal("123.456"))
    assertEquals(row.getDecimal(0), BigDecimal("123.456"))

  test("getDecimal from java.math.BigDecimal"):
    val row = ProtoRow(new java.math.BigDecimal("789.012"))
    assertEquals(row.getDecimal(0), BigDecimal("789.012"))

  test("getDecimal from Int"):
    val row = ProtoRow(42)
    assertEquals(row.getDecimal(0), BigDecimal(42))

  test("getDecimal from Long"):
    val row = ProtoRow(9999999999L)
    assertEquals(row.getDecimal(0), BigDecimal(9999999999L))

  // === Collection getters ===

  test("getSeq"):
    val row = ProtoRow(Seq(1, 2, 3))
    assertEquals(row.getSeq[Int](0), Seq(1, 2, 3))

  test("getMap"):
    val row = ProtoRow(Map("a" -> 1, "b" -> 2))
    assertEquals(row.getMap[String, Int](0), Map("a" -> 1, "b" -> 2))

  test("getStruct from ProtoRow"):
    val inner = ProtoRow("nested", 123)
    val outer = ProtoRow(inner)
    val retrieved = outer.getStruct(0)
    assertEquals(retrieved.getString(0), "nested")
    assertEquals(retrieved.getInt(1), 123)

  test("getStruct from Product"):
    case class Point(x: Int, y: Int)
    val row = ProtoRow(Point(10, 20))
    val struct = row.getStruct(0)
    assertEquals(struct.getInt(0), 10)
    assertEquals(struct.getInt(1), 20)

  // === Null handling ===

  test("isNullAt returns true for null"):
    val row = ProtoRow("test", null, 42)
    assertEquals(row.isNullAt(0), false)
    assertEquals(row.isNullAt(1), true)
    assertEquals(row.isNullAt(2), false)

  test("get returns null for null values"):
    val row = ProtoRow(null)
    assertEquals(row.get(0), null)

  // === Index bounds ===

  test("negative index throws"):
    val row = ProtoRow("test")
    intercept[IndexOutOfBoundsException]:
      row.get(-1)

  test("out of bounds index throws"):
    val row = ProtoRow("test")
    intercept[IndexOutOfBoundsException]:
      row.get(1)

  // === Other methods ===

  test("apply is alias for get"):
    val row = ProtoRow("a", "b", "c")
    assertEquals(row(0), row.get(0))
    assertEquals(row(1), row.get(1))
    assertEquals(row(2), row.get(2))

  test("toSeq returns values"):
    val row = ProtoRow(1, "two", 3.0)
    assertEquals(row.toSeq, Seq(1, "two", 3.0))

  test("copy creates new row"):
    val original = ProtoRow(1, 2, 3)
    val copied = original.copy()
    assertEquals(copied.toSeq, original.toSeq)

  // === GenericProtoRow is a case class ===

  test("GenericProtoRow equality"):
    val row1 = GenericProtoRow(Vector(1, 2, 3))
    val row2 = GenericProtoRow(Vector(1, 2, 3))
    val row3 = GenericProtoRow(Vector(1, 2, 4))
    assertEquals(row1, row2)
    assertNotEquals(row1, row3)
