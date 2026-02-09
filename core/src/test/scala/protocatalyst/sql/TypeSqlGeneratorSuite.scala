package protocatalyst.sql

import protocatalyst.types.{ProtoStructField, ProtoType}

class TypeSqlGeneratorSuite extends munit.FunSuite:
  import TypeSqlGenerator.generate

  test("BooleanType → BOOLEAN"):
    assertEquals(generate(ProtoType.BooleanType), "BOOLEAN")

  test("ByteType → TINYINT"):
    assertEquals(generate(ProtoType.ByteType), "TINYINT")

  test("ShortType → SMALLINT"):
    assertEquals(generate(ProtoType.ShortType), "SMALLINT")

  test("IntegerType → INTEGER"):
    assertEquals(generate(ProtoType.IntegerType), "INTEGER")

  test("LongType → BIGINT"):
    assertEquals(generate(ProtoType.LongType), "BIGINT")

  test("FloatType → REAL"):
    assertEquals(generate(ProtoType.FloatType), "REAL")

  test("DoubleType → DOUBLE"):
    assertEquals(generate(ProtoType.DoubleType), "DOUBLE")

  test("StringType → VARCHAR"):
    assertEquals(generate(ProtoType.StringType), "VARCHAR")

  test("BinaryType → VARBINARY"):
    assertEquals(generate(ProtoType.BinaryType), "VARBINARY")

  test("DateType → DATE"):
    assertEquals(generate(ProtoType.DateType), "DATE")

  test("TimestampType → TIMESTAMP"):
    assertEquals(generate(ProtoType.TimestampType), "TIMESTAMP")

  test("TimestampNTZType → TIMESTAMP"):
    assertEquals(generate(ProtoType.TimestampNTZType), "TIMESTAMP")

  test("CharType(10) → CHAR(10)"):
    assertEquals(generate(ProtoType.CharType(10)), "CHAR(10)")

  test("VarcharType(255) → VARCHAR(255)"):
    assertEquals(generate(ProtoType.VarcharType(255)), "VARCHAR(255)")

  test("DecimalType(10, 2) → DECIMAL(10, 2)"):
    assertEquals(generate(ProtoType.DecimalType(10, 2)), "DECIMAL(10, 2)")

  test("DecimalType(38, 18) → DECIMAL(38, 18)"):
    assertEquals(generate(ProtoType.DecimalType(38, 18)), "DECIMAL(38, 18)")

  test("ArrayType<INTEGER> → ARRAY<INTEGER>"):
    val arrayType = ProtoType.ArrayType(ProtoType.IntegerType, containsNull = true)
    assertEquals(generate(arrayType), "ARRAY<INTEGER>")

  test("ArrayType<VARCHAR> → ARRAY<VARCHAR>"):
    val arrayType = ProtoType.ArrayType(ProtoType.StringType, containsNull = false)
    assertEquals(generate(arrayType), "ARRAY<VARCHAR>")

  test("ArrayType<ArrayType<INTEGER>> → ARRAY<ARRAY<INTEGER>>"):
    val innerArray = ProtoType.ArrayType(ProtoType.IntegerType, containsNull = true)
    val outerArray = ProtoType.ArrayType(innerArray, containsNull = true)
    assertEquals(generate(outerArray), "ARRAY<ARRAY<INTEGER>>")

  test("MapType<VARCHAR, INTEGER> → MAP<VARCHAR, INTEGER>"):
    val mapType = ProtoType.MapType(
      ProtoType.StringType,
      ProtoType.IntegerType,
      valueContainsNull = true
    )
    assertEquals(generate(mapType), "MAP<VARCHAR, INTEGER>")

  test("MapType<INTEGER, ARRAY<VARCHAR>> → MAP<INTEGER, ARRAY<VARCHAR>>"):
    val arrayType = ProtoType.ArrayType(ProtoType.StringType, containsNull = true)
    val mapType = ProtoType.MapType(ProtoType.IntegerType, arrayType, valueContainsNull = false)
    assertEquals(generate(mapType), "MAP<INTEGER, ARRAY<VARCHAR>>")

  test("StructType with single field → STRUCT<name: VARCHAR>"):
    val structType = ProtoType.StructType(
      Vector(ProtoStructField("name", ProtoType.StringType, nullable = true))
    )
    assertEquals(generate(structType), "STRUCT<name: VARCHAR>")

  test("StructType with multiple fields → STRUCT<...>"):
    val structType = ProtoType.StructType(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("name", ProtoType.StringType, nullable = true),
        ProtoStructField("age", ProtoType.IntegerType, nullable = true)
      )
    )
    assertEquals(generate(structType), "STRUCT<id: INTEGER, name: VARCHAR, age: INTEGER>")

  test("StructType with nested struct → STRUCT<...>"):
    val nestedStruct = ProtoType.StructType(
      Vector(
        ProtoStructField("city", ProtoType.StringType, nullable = true),
        ProtoStructField("zipcode", ProtoType.StringType, nullable = true)
      )
    )
    val structType = ProtoType.StructType(
      Vector(
        ProtoStructField("name", ProtoType.StringType, nullable = true),
        ProtoStructField("address", nestedStruct, nullable = true)
      )
    )
    assertEquals(
      generate(structType),
      "STRUCT<name: VARCHAR, address: STRUCT<city: VARCHAR, zipcode: VARCHAR>>"
    )

  // Unsupported types should throw
  test("TimeType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.TimeType(6))
    }

  test("DayTimeIntervalType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.DayTimeIntervalType)
    }

  test("YearMonthIntervalType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.YearMonthIntervalType)
    }

  test("CalendarIntervalType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.CalendarIntervalType)
    }

  test("VariantType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.VariantType)
    }

  test("NullType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.NullType)
    }

  test("UDTType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.UDTType("com.example.MyUDT", ProtoType.BinaryType))
    }

  test("SumType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.SumType("discriminator", Vector.empty))
    }

  test("UnresolvedType throws UnsupportedSqlFeatureException"):
    intercept[UnsupportedSqlFeatureException] {
      generate(ProtoType.UnresolvedType("unknown"))
    }

end TypeSqlGeneratorSuite
