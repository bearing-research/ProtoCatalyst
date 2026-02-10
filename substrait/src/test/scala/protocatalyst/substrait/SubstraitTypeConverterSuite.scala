package protocatalyst.substrait

import protocatalyst.types.{ProtoStructField, ProtoType}

class SubstraitTypeConverterSuite extends munit.FunSuite:
  import SubstraitTypeConverter.toSubstrait

  // ========== Primitive Types ==========

  test("BooleanType"):
    val substraitType = toSubstrait(ProtoType.BooleanType)
    assert(substraitType != null)

  test("ByteType"):
    val substraitType = toSubstrait(ProtoType.ByteType)
    assert(substraitType != null)

  test("ShortType"):
    val substraitType = toSubstrait(ProtoType.ShortType)
    assert(substraitType != null)

  test("IntegerType"):
    val substraitType = toSubstrait(ProtoType.IntegerType)
    assert(substraitType != null)

  test("LongType"):
    val substraitType = toSubstrait(ProtoType.LongType)
    assert(substraitType != null)

  test("FloatType"):
    val substraitType = toSubstrait(ProtoType.FloatType)
    assert(substraitType != null)

  test("DoubleType"):
    val substraitType = toSubstrait(ProtoType.DoubleType)
    assert(substraitType != null)

  // ========== String and Binary ==========

  test("StringType"):
    val substraitType = toSubstrait(ProtoType.StringType)
    assert(substraitType != null)

  test("BinaryType"):
    val substraitType = toSubstrait(ProtoType.BinaryType)
    assert(substraitType != null)

  // ========== Decimal ==========

  test("DecimalType"):
    val substraitType = toSubstrait(ProtoType.DecimalType(10, 2))
    assert(substraitType != null)

  // ========== Date and Time ==========

  test("DateType"):
    val substraitType = toSubstrait(ProtoType.DateType)
    assert(substraitType != null)

  test("TimestampType"):
    val substraitType = toSubstrait(ProtoType.TimestampType)
    assert(substraitType != null)

  test("TimeType"):
    val substraitType = toSubstrait(ProtoType.TimeType(6))
    assert(substraitType != null)

  // ========== Complex Types ==========

  test("ArrayType"):
    val substraitType = toSubstrait(ProtoType.ArrayType(ProtoType.IntegerType, containsNull = true))
    assert(substraitType != null)

  test("MapType"):
    val substraitType = toSubstrait(
      ProtoType.MapType(ProtoType.StringType, ProtoType.IntegerType, valueContainsNull = true)
    )
    assert(substraitType != null)

  test("StructType"):
    val protoType = ProtoType.StructType(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("name", ProtoType.StringType, nullable = true)
      )
    )
    val substraitType = toSubstrait(protoType)
    assert(substraitType != null)

  // ========== Nested Complex Types ==========

  test("ArrayType of StructType"):
    val protoType = ProtoType.ArrayType(
      ProtoType.StructType(
        Vector(
          ProtoStructField("x", ProtoType.IntegerType, nullable = false),
          ProtoStructField("y", ProtoType.IntegerType, nullable = false)
        )
      ),
      containsNull = true
    )
    val substraitType = toSubstrait(protoType)
    assert(substraitType != null)

  // ========== Unsupported Types ==========

  test("NullType throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      toSubstrait(ProtoType.NullType)
    }

  test("CalendarIntervalType throws exception"):
    intercept[UnsupportedSubstraitFeatureException] {
      toSubstrait(ProtoType.CalendarIntervalType)
    }

end SubstraitTypeConverterSuite
