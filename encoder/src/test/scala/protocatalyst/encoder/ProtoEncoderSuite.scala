package protocatalyst.encoder

import protocatalyst.types.*

class ProtoEncoderSuite extends munit.FunSuite:

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
