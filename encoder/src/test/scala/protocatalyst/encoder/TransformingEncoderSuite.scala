package protocatalyst.encoder

import protocatalyst.types.ProtoType
import protocatalyst.encoder.codec.{
  JavaSerializationCodec,
  KryoSerializationCodec,
  ForySerializationCodec
}
import munit.FunSuite

// Test types must be top-level to be Serializable
// (inner classes capture a reference to the outer class)
case class TestSimpleData(x: Int, y: String)
case class TestNestedData(label: String, data: TestSimpleData)

class TransformingEncoderSuite extends FunSuite:

  // === Java codec tests ===

  test("TransformingEncoder.java creates encoder with BinaryType"):
    val enc = TransformingEncoder.java[TestSimpleData]
    assertEquals(enc.catalystType, ProtoType.BinaryType)
    assertEquals(enc.nullable, true)

  test("TransformingEncoder.java roundtrip"):
    val enc = TransformingEncoder.java[TestSimpleData]
    val original = TestSimpleData(42, "hello")
    val bytes = enc.encode(original)
    val restored = enc.decode(bytes)
    assertEquals(restored, original)

  test("TransformingEncoder.java handles null"):
    val enc = TransformingEncoder.java[TestSimpleData]
    val bytes = enc.encode(null.asInstanceOf[TestSimpleData])
    assertEquals(bytes, null)
    val restored = enc.decode(null)
    assertEquals(restored, null)

  test("TransformingEncoder.java nested case class"):
    val enc = TransformingEncoder.java[TestNestedData]
    val original = TestNestedData("test", TestSimpleData(1, "nested"))
    val bytes = enc.encode(original)
    val restored = enc.decode(bytes)
    assertEquals(restored, original)

  // === Kryo codec tests ===

  test("TransformingEncoder.kryo creates encoder with BinaryType"):
    val enc = TransformingEncoder.kryo[TestSimpleData]
    assertEquals(enc.catalystType, ProtoType.BinaryType)
    assertEquals(enc.nullable, true)

  test("TransformingEncoder.kryo roundtrip"):
    val enc = TransformingEncoder.kryo[TestSimpleData]
    val original = TestSimpleData(100, "world")
    val bytes = enc.encode(original)
    val restored = enc.decode(bytes)
    // Compare by value since Kryo creates new instances
    assertEquals(restored.x, original.x)
    assertEquals(restored.y, original.y)

  test("TransformingEncoder.kryo handles null"):
    val enc = TransformingEncoder.kryo[TestSimpleData]
    assertEquals(enc.encode(null.asInstanceOf[TestSimpleData]), null)
    assertEquals(enc.decode(null), null)

  test("TransformingEncoder.kryo with custom configuration"):
    import com.esotericsoftware.kryo.Kryo
    val enc = TransformingEncoder.kryo[TestSimpleData] { kryo =>
      kryo.register(classOf[TestSimpleData])
    }
    val original = TestSimpleData(999, "configured")
    val bytes = enc.encode(original)
    val restored = enc.decode(bytes)
    assertEquals(restored.x, original.x)
    assertEquals(restored.y, original.y)

  // === Fory codec tests ===

  test("TransformingEncoder.fory creates encoder with BinaryType"):
    val enc = TransformingEncoder.fory[TestSimpleData]
    assertEquals(enc.catalystType, ProtoType.BinaryType)
    assertEquals(enc.nullable, true)

  test("TransformingEncoder.fory roundtrip"):
    val enc = TransformingEncoder.fory[TestSimpleData]
    val original = TestSimpleData(200, "fory")
    val bytes = enc.encode(original)
    val restored = enc.decode(bytes)
    // Compare by value since Fory creates new instances
    assertEquals(restored.x, original.x)
    assertEquals(restored.y, original.y)

  test("TransformingEncoder.fory handles null"):
    val enc = TransformingEncoder.fory[TestSimpleData]
    assertEquals(enc.encode(null.asInstanceOf[TestSimpleData]), null)
    assertEquals(enc.decode(null), null)

  test("TransformingEncoder.fory nested case class"):
    val enc = TransformingEncoder.fory[TestNestedData]
    val original = TestNestedData("fory-test", TestSimpleData(7, "deep"))
    val bytes = enc.encode(original)
    val restored = enc.decode(bytes)
    assertEquals(restored.label, original.label)
    assertEquals(restored.data.x, original.data.x)
    assertEquals(restored.data.y, original.data.y)

  // === Default codec tests ===

  test("TransformingEncoder.default uses Java codec"):
    val enc = TransformingEncoder.default[TestSimpleData]
    assertEquals(enc.catalystType, ProtoType.BinaryType)
    val original = TestSimpleData(1, "default")
    val bytes = enc.encode(original)
    val restored = enc.decode(bytes)
    assertEquals(restored, original)

  // === Factory pattern tests ===

  test("TransformingEncoder.apply accepts custom codec provider"):
    val enc = TransformingEncoder[TestSimpleData](JavaSerializationCodec)
    val original = TestSimpleData(5, "custom")
    val bytes = enc.encode(original)
    val restored = enc.decode(bytes)
    assertEquals(restored, original)

  test("TransformingEncoder has empty schema"):
    val enc = TransformingEncoder.java[TestSimpleData]
    assertEquals(enc.schema.fields, Vector.empty)

  test("TransformingEncoder ClassTag is captured"):
    val enc = TransformingEncoder.java[TestSimpleData]
    assertEquals(enc.clsTag.runtimeClass, classOf[TestSimpleData])

  // === ProtoEncoder compatibility tests ===

  test("TransformingEncoder is a ProtoEncoder"):
    val enc: ProtoEncoder[TestSimpleData] = TransformingEncoder.java[TestSimpleData]
    assertEquals(enc.catalystType, ProtoType.BinaryType)
    assertEquals(enc.nullable, true)

  test("TransformingEncoder can be used as given encoder"):
    given ProtoEncoder[TestSimpleData] = TransformingEncoder.fory[TestSimpleData]
    val enc = summon[ProtoEncoder[TestSimpleData]]
    assertEquals(enc.catalystType, ProtoType.BinaryType)

  // === Size comparison (informal) ===

  test("Kryo produces smaller output than Java for complex objects"):
    val data = TestNestedData("comparison", TestSimpleData(12345, "a longer string value"))
    val javaEnc = TransformingEncoder.java[TestNestedData]
    val kryoEnc = TransformingEncoder.kryo[TestNestedData]

    val javaBytes = javaEnc.encode(data)
    val kryoBytes = kryoEnc.encode(data)

    // Kryo should generally produce smaller output
    assert(
      kryoBytes.length <= javaBytes.length,
      s"Expected Kryo (${kryoBytes.length}) <= Java (${javaBytes.length})"
    )
