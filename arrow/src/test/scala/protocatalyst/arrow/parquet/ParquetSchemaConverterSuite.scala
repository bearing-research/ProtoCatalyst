package protocatalyst.arrow.parquet

import protocatalyst.schema._
import protocatalyst.types._

class ParquetSchemaConverterSuite extends munit.FunSuite:

  private def roundtrip(name: String, dt: ProtoType, nullable: Boolean = true): Unit =
    val schema = ProtoSchema(Vector(ProtoStructField(name, dt, nullable)))
    val parquet = ParquetSchemaConverter.toParquetSchema(schema)
    val back = ParquetSchemaConverter.fromParquetSchema(parquet)
    assertEquals(back.fields.size, 1)
    assertEquals(back.fields(0).name, name)
    assertEquals(back.fields(0).dataType, dt)
    assertEquals(back.fields(0).nullable, nullable)

  test("roundtrip: BooleanType") { roundtrip("flag", ProtoType.BooleanType) }
  test("roundtrip: ByteType") { roundtrip("b", ProtoType.ByteType) }
  test("roundtrip: ShortType") { roundtrip("s", ProtoType.ShortType) }
  test("roundtrip: IntegerType") { roundtrip("i", ProtoType.IntegerType) }
  test("roundtrip: LongType") { roundtrip("l", ProtoType.LongType) }
  test("roundtrip: FloatType") { roundtrip("f", ProtoType.FloatType) }
  test("roundtrip: DoubleType") { roundtrip("d", ProtoType.DoubleType) }
  test("roundtrip: StringType") { roundtrip("name", ProtoType.StringType) }
  test("roundtrip: BinaryType") { roundtrip("data", ProtoType.BinaryType) }
  test("roundtrip: DateType") { roundtrip("dt", ProtoType.DateType) }
  test("roundtrip: TimestampType") { roundtrip("ts", ProtoType.TimestampType) }
  test("roundtrip: TimestampNTZType") { roundtrip("ts_ntz", ProtoType.TimestampNTZType) }

  test("roundtrip: DecimalType small (p<=9)") {
    roundtrip("dec_small", ProtoType.DecimalType(9, 2))
  }

  test("roundtrip: DecimalType medium (p<=18)") {
    roundtrip("dec_medium", ProtoType.DecimalType(18, 6))
  }

  test("roundtrip: DecimalType large (p>18)") {
    roundtrip("dec_large", ProtoType.DecimalType(38, 10))
  }

  test("roundtrip: required (non-nullable)") {
    roundtrip("required_col", ProtoType.IntegerType, nullable = false)
  }

  test("roundtrip: ArrayType") {
    roundtrip("arr", ProtoType.ArrayType(ProtoType.IntegerType, containsNull = true))
  }

  test("roundtrip: ArrayType non-nullable elements") {
    roundtrip("arr_nn", ProtoType.ArrayType(ProtoType.StringType, containsNull = false))
  }

  test("roundtrip: MapType") {
    roundtrip(
      "m",
      ProtoType.MapType(ProtoType.StringType, ProtoType.IntegerType, valueContainsNull = true)
    )
  }

  test("roundtrip: StructType") {
    roundtrip(
      "nested",
      ProtoType.StructType(
        Vector(
          ProtoStructField("x", ProtoType.IntegerType, nullable = false),
          ProtoStructField("y", ProtoType.DoubleType, nullable = true)
        )
      )
    )
  }

  test("roundtrip: multi-field schema") {
    val schema = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntegerType, nullable = false),
        ProtoStructField("name", ProtoType.StringType, nullable = true),
        ProtoStructField("score", ProtoType.DoubleType, nullable = true),
        ProtoStructField("active", ProtoType.BooleanType, nullable = false)
      )
    )
    val parquet = ParquetSchemaConverter.toParquetSchema(schema)
    val back = ParquetSchemaConverter.fromParquetSchema(parquet)
    assertEquals(back.fields.size, 4)
    assertEquals(back.fields(0).dataType, ProtoType.IntegerType)
    assertEquals(back.fields(1).dataType, ProtoType.StringType)
    assertEquals(back.fields(2).dataType, ProtoType.DoubleType)
    assertEquals(back.fields(3).dataType, ProtoType.BooleanType)
  }

  test("unsupported type throws") {
    val schema = ProtoSchema(
      Vector(ProtoStructField("v", ProtoType.VariantType, nullable = true))
    )
    intercept[IllegalArgumentException] {
      ParquetSchemaConverter.toParquetSchema(schema)
    }
  }
