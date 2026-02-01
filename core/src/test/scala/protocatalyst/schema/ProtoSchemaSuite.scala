package protocatalyst.schema

import protocatalyst.types.*

class ProtoSchemaSuite extends munit.FunSuite:

  test("schema fingerprint is stable"):
    val fields = Vector(
      ProtoStructField("id", ProtoType.LongType, nullable = false),
      ProtoStructField("name", ProtoType.StringType, nullable = true)
    )
    val schema1 = ProtoSchema(fields)
    val schema2 = ProtoSchema(fields)

    assertEquals(schema1.fingerprint.toLong, schema2.fingerprint.toLong)

  test("schema fingerprint is order-independent"):
    val fields1 = Vector(
      ProtoStructField("a", ProtoType.IntType, nullable = false),
      ProtoStructField("b", ProtoType.StringType, nullable = true)
    )
    val fields2 = Vector(
      ProtoStructField("b", ProtoType.StringType, nullable = true),
      ProtoStructField("a", ProtoType.IntType, nullable = false)
    )

    val schema1 = ProtoSchema(fields1)
    val schema2 = ProtoSchema(fields2)

    assertEquals(schema1.fingerprint.toLong, schema2.fingerprint.toLong)

  test("different schemas have different fingerprints"):
    val schema1 = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.LongType, nullable = false)
      )
    )
    val schema2 = ProtoSchema(
      Vector(
        ProtoStructField("id", ProtoType.IntType, nullable = false)
      )
    )

    assertNotEquals(schema1.fingerprint.toLong, schema2.fingerprint.toLong)
