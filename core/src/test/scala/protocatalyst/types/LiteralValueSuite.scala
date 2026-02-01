package protocatalyst.types

class LiteralValueSuite extends munit.FunSuite:

  // === LiteralValue.typeOf Tests ===

  test("typeOf BooleanValue returns BooleanType"):
    val lit = LiteralValue.BooleanValue(true)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.BooleanType)

  test("typeOf ByteValue returns ByteType"):
    val lit = LiteralValue.ByteValue(42.toByte)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.ByteType)

  test("typeOf ShortValue returns ShortType"):
    val lit = LiteralValue.ShortValue(1000.toShort)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.ShortType)

  test("typeOf IntValue returns IntType"):
    val lit = LiteralValue.IntValue(123456)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.IntType)

  test("typeOf LongValue returns LongType"):
    val lit = LiteralValue.LongValue(9876543210L)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.LongType)

  test("typeOf FloatValue returns FloatType"):
    val lit = LiteralValue.FloatValue(3.14f)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.FloatType)

  test("typeOf DoubleValue returns DoubleType"):
    val lit = LiteralValue.DoubleValue(2.718281828)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.DoubleType)

  test("typeOf StringValue returns StringType"):
    val lit = LiteralValue.StringValue("hello")
    assertEquals(LiteralValue.typeOf(lit), ProtoType.StringType)

  test("typeOf BinaryValue returns BinaryType"):
    val lit = LiteralValue.BinaryValue(Array[Byte](1, 2, 3))
    assertEquals(LiteralValue.typeOf(lit), ProtoType.BinaryType)

  test("typeOf DecimalValue returns DecimalType(38, 18)"):
    val lit = LiteralValue.DecimalValue(BigDecimal("123.456"))
    LiteralValue.typeOf(lit) match
      case ProtoType.DecimalType(p, s) =>
        assertEquals(p, 38)
        assertEquals(s, 18)
      case other => fail(s"Expected DecimalType, got $other")

  test("typeOf DateValue returns DateType"):
    val lit = LiteralValue.DateValue(19000)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.DateType)

  test("typeOf TimestampValue returns TimestampType"):
    val lit = LiteralValue.TimestampValue(1700000000000000L)
    assertEquals(LiteralValue.typeOf(lit), ProtoType.TimestampType)

  test("typeOf NullValue returns the specified type"):
    val types = List(
      ProtoType.StringType,
      ProtoType.IntType,
      ProtoType.ArrayType(ProtoType.IntType, containsNull = true),
      ProtoType.MapType(ProtoType.StringType, ProtoType.IntType, valueContainsNull = true)
    )
    for t <- types do
      val lit = LiteralValue.NullValue(t)
      assertEquals(LiteralValue.typeOf(lit), t)

  // === LiteralValue Construction Tests ===

  test("BooleanValue stores value correctly"):
    val trueVal = LiteralValue.BooleanValue(true)
    val falseVal = LiteralValue.BooleanValue(false)
    trueVal match
      case LiteralValue.BooleanValue(v) => assertEquals(v, true)
      case _                            => fail("Expected BooleanValue")
    falseVal match
      case LiteralValue.BooleanValue(v) => assertEquals(v, false)
      case _                            => fail("Expected BooleanValue")

  test("ByteValue boundary values"):
    val minVal = LiteralValue.ByteValue(Byte.MinValue)
    val maxVal = LiteralValue.ByteValue(Byte.MaxValue)
    val zeroVal = LiteralValue.ByteValue(0)
    minVal match
      case LiteralValue.ByteValue(v) => assertEquals(v, Byte.MinValue)
      case _                         => fail("Expected ByteValue")
    maxVal match
      case LiteralValue.ByteValue(v) => assertEquals(v, Byte.MaxValue)
      case _                         => fail("Expected ByteValue")
    zeroVal match
      case LiteralValue.ByteValue(v) => assertEquals(v, 0.toByte)
      case _                         => fail("Expected ByteValue")

  test("ShortValue boundary values"):
    val minVal = LiteralValue.ShortValue(Short.MinValue)
    val maxVal = LiteralValue.ShortValue(Short.MaxValue)
    minVal match
      case LiteralValue.ShortValue(v) => assertEquals(v, Short.MinValue)
      case _                          => fail("Expected ShortValue")
    maxVal match
      case LiteralValue.ShortValue(v) => assertEquals(v, Short.MaxValue)
      case _                          => fail("Expected ShortValue")

  test("IntValue boundary values"):
    val minVal = LiteralValue.IntValue(Int.MinValue)
    val maxVal = LiteralValue.IntValue(Int.MaxValue)
    minVal match
      case LiteralValue.IntValue(v) => assertEquals(v, Int.MinValue)
      case _                        => fail("Expected IntValue")
    maxVal match
      case LiteralValue.IntValue(v) => assertEquals(v, Int.MaxValue)
      case _                        => fail("Expected IntValue")

  test("LongValue boundary values"):
    val minVal = LiteralValue.LongValue(Long.MinValue)
    val maxVal = LiteralValue.LongValue(Long.MaxValue)
    minVal match
      case LiteralValue.LongValue(v) => assertEquals(v, Long.MinValue)
      case _                         => fail("Expected LongValue")
    maxVal match
      case LiteralValue.LongValue(v) => assertEquals(v, Long.MaxValue)
      case _                         => fail("Expected LongValue")

  test("FloatValue special values"):
    val posInf = LiteralValue.FloatValue(Float.PositiveInfinity)
    val negInf = LiteralValue.FloatValue(Float.NegativeInfinity)
    val nan = LiteralValue.FloatValue(Float.NaN)
    LiteralValue.FloatValue(Float.MinValue)
    LiteralValue.FloatValue(Float.MaxValue)

    posInf match
      case LiteralValue.FloatValue(v) => assert(v.isPosInfinity)
      case _                          => fail("Expected FloatValue")
    negInf match
      case LiteralValue.FloatValue(v) => assert(v.isNegInfinity)
      case _                          => fail("Expected FloatValue")
    nan match
      case LiteralValue.FloatValue(v) => assert(v.isNaN)
      case _                          => fail("Expected FloatValue")

  test("DoubleValue special values"):
    val posInf = LiteralValue.DoubleValue(Double.PositiveInfinity)
    val negInf = LiteralValue.DoubleValue(Double.NegativeInfinity)
    val nan = LiteralValue.DoubleValue(Double.NaN)
    LiteralValue.DoubleValue(Double.MinValue)
    LiteralValue.DoubleValue(Double.MaxValue)

    posInf match
      case LiteralValue.DoubleValue(v) => assert(v.isPosInfinity)
      case _                           => fail("Expected DoubleValue")
    negInf match
      case LiteralValue.DoubleValue(v) => assert(v.isNegInfinity)
      case _                           => fail("Expected DoubleValue")
    nan match
      case LiteralValue.DoubleValue(v) => assert(v.isNaN)
      case _                           => fail("Expected DoubleValue")

  test("StringValue with various content"):
    val empty = LiteralValue.StringValue("")
    val unicode = LiteralValue.StringValue("hello 世界 🌍")
    val newlines = LiteralValue.StringValue("line1\nline2\r\n")
    val withNull = LiteralValue.StringValue("with\u0000null")

    empty match
      case LiteralValue.StringValue(v) => assertEquals(v, "")
      case _                           => fail("Expected StringValue")
    unicode match
      case LiteralValue.StringValue(v) => assertEquals(v, "hello 世界 🌍")
      case _                           => fail("Expected StringValue")
    newlines match
      case LiteralValue.StringValue(v) => assertEquals(v, "line1\nline2\r\n")
      case _                           => fail("Expected StringValue")
    withNull match
      case LiteralValue.StringValue(v) => assert(v.contains('\u0000'))
      case _                           => fail("Expected StringValue")

  test("BinaryValue with various content"):
    val empty = LiteralValue.BinaryValue(Array.emptyByteArray)
    val single = LiteralValue.BinaryValue(Array[Byte](42))
    val full = LiteralValue.BinaryValue((0 until 256).map(_.toByte).toArray)

    empty match
      case LiteralValue.BinaryValue(v) => assertEquals(v.length, 0)
      case _                           => fail("Expected BinaryValue")
    single match
      case LiteralValue.BinaryValue(v) =>
        assertEquals(v.length, 1)
        assertEquals(v(0), 42.toByte)
      case _ => fail("Expected BinaryValue")
    full match
      case LiteralValue.BinaryValue(v) =>
        assertEquals(v.length, 256)
      case _ => fail("Expected BinaryValue")

  test("DecimalValue with various precision"):
    val small = LiteralValue.DecimalValue(BigDecimal("0.001"))
    LiteralValue.DecimalValue(BigDecimal("999999999999999999.999999999999999999"))
    val negative = LiteralValue.DecimalValue(BigDecimal("-123.456"))
    val zero = LiteralValue.DecimalValue(BigDecimal("0"))

    small match
      case LiteralValue.DecimalValue(v) => assertEquals(v, BigDecimal("0.001"))
      case _                            => fail("Expected DecimalValue")
    negative match
      case LiteralValue.DecimalValue(v) => assertEquals(v, BigDecimal("-123.456"))
      case _                            => fail("Expected DecimalValue")
    zero match
      case LiteralValue.DecimalValue(v) => assertEquals(v, BigDecimal("0"))
      case _                            => fail("Expected DecimalValue")

  test("DateValue edge cases"):
    val epoch = LiteralValue.DateValue(0)
    val positive = LiteralValue.DateValue(19000) // 2022-01-13
    val negative = LiteralValue.DateValue(-10000) // before epoch

    epoch match
      case LiteralValue.DateValue(v) => assertEquals(v, 0)
      case _                         => fail("Expected DateValue")
    positive match
      case LiteralValue.DateValue(v) => assertEquals(v, 19000)
      case _                         => fail("Expected DateValue")
    negative match
      case LiteralValue.DateValue(v) => assertEquals(v, -10000)
      case _                         => fail("Expected DateValue")

  test("TimestampValue edge cases"):
    val epoch = LiteralValue.TimestampValue(0L)
    val positive = LiteralValue.TimestampValue(1700000000000000L) // 2023-11-14
    val negative = LiteralValue.TimestampValue(-1000000000000000L) // before epoch

    epoch match
      case LiteralValue.TimestampValue(v) => assertEquals(v, 0L)
      case _                              => fail("Expected TimestampValue")
    positive match
      case LiteralValue.TimestampValue(v) => assertEquals(v, 1700000000000000L)
      case _                              => fail("Expected TimestampValue")
    negative match
      case LiteralValue.TimestampValue(v) => assertEquals(v, -1000000000000000L)
      case _                              => fail("Expected TimestampValue")

  // === Equality Tests ===

  test("LiteralValue equality for primitives"):
    val int1 = LiteralValue.IntValue(42)
    val int2 = LiteralValue.IntValue(42)
    val int3 = LiteralValue.IntValue(43)

    assertEquals(int1, int2)
    assertNotEquals(int1, int3)

  test("LiteralValue equality for strings"):
    val str1 = LiteralValue.StringValue("hello")
    val str2 = LiteralValue.StringValue("hello")
    val str3 = LiteralValue.StringValue("world")

    assertEquals(str1, str2)
    assertNotEquals(str1, str3)

  test("LiteralValue equality for decimals"):
    val dec1 = LiteralValue.DecimalValue(BigDecimal("123.456"))
    val dec2 = LiteralValue.DecimalValue(BigDecimal("123.456"))
    val dec3 = LiteralValue.DecimalValue(BigDecimal("123.457"))

    assertEquals(dec1, dec2)
    assertNotEquals(dec1, dec3)

  test("LiteralValue equality for null values"):
    val null1 = LiteralValue.NullValue(ProtoType.StringType)
    val null2 = LiteralValue.NullValue(ProtoType.StringType)
    val null3 = LiteralValue.NullValue(ProtoType.IntType)

    assertEquals(null1, null2)
    assertNotEquals(null1, null3)

  // === Type Discrimination Tests ===

  test("all LiteralValue variants are distinct"):
    val values: List[LiteralValue] = List(
      LiteralValue.BooleanValue(true),
      LiteralValue.ByteValue(1),
      LiteralValue.ShortValue(1),
      LiteralValue.IntValue(1),
      LiteralValue.LongValue(1),
      LiteralValue.FloatValue(1.0f),
      LiteralValue.DoubleValue(1.0),
      LiteralValue.StringValue("1"),
      LiteralValue.BinaryValue(Array[Byte](1)),
      LiteralValue.DecimalValue(BigDecimal("1")),
      LiteralValue.DateValue(1),
      LiteralValue.TimestampValue(1),
      LiteralValue.NullValue(ProtoType.IntType)
    )

    // Each value should have a different type
    values.map(LiteralValue.typeOf)
    // Note: Some share the same type (IntValue, ShortValue, etc. when value is 1)
    // But the literal values themselves are different enum cases
    for i <- values.indices; j <- values.indices if i != j do
      assertNotEquals(values(i), values(j), s"values($i) should not equal values($j)")
