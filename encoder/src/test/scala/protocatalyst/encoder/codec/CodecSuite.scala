package protocatalyst.encoder.codec

import munit.FunSuite

// Test types must be top-level to be Serializable
case class CodecTestPoint(x: Int, y: Int)
case class CodecTestPerson(name: String, age: Int, active: Boolean)
case class CodecTestNested(label: String, point: CodecTestPoint)

class CodecSuite extends FunSuite:

  // Test Java serialization codec
  test("Java codec: roundtrip String"):
    val codec = JavaSerializationCodec()
    val data = "hello world"
    val bytes = codec.encode(data)
    val result = codec.decode(bytes)
    assertEquals(result, data)

  test("Java codec: roundtrip case class"):
    val codec = JavaSerializationCodec()
    val data = CodecTestPoint(1, 2)
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestPoint]
    assertEquals(result, data)

  test("Java codec: roundtrip complex case class"):
    val codec = JavaSerializationCodec()
    val data = CodecTestPerson("Alice", 30, true)
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestPerson]
    assertEquals(result, data)

  test("Java codec: roundtrip nested case class"):
    val codec = JavaSerializationCodec()
    val data = CodecTestNested("origin", CodecTestPoint(0, 0))
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestNested]
    assertEquals(result, data)

  test("Java codec: null handling - encode"):
    val codec = JavaSerializationCodec()
    val bytes = codec.encode(null)
    assertEquals(bytes, null)

  test("Java codec: null handling - decode"):
    val codec = JavaSerializationCodec()
    val result = codec.decode(null)
    assertEquals(result, null)

  test("Java codec: roundtrip primitives"):
    val codec = JavaSerializationCodec()
    assertEquals(codec.decode(codec.encode(42: java.lang.Integer)), 42)
    assertEquals(codec.decode(codec.encode(3.14: java.lang.Double)), 3.14)
    assertEquals(codec.decode(codec.encode(true: java.lang.Boolean)), true)

  test("Java codec: roundtrip collections"):
    val codec = JavaSerializationCodec()
    val list = java.util.Arrays.asList(1, 2, 3)
    val bytes = codec.encode(list)
    val result = codec.decode(bytes)
    assertEquals(result, list)

  // Test Kryo codec
  test("Kryo codec: roundtrip String"):
    val codec = KryoSerializationCodec()
    val data = "hello world"
    val bytes = codec.encode(data)
    val result = codec.decode(bytes)
    assertEquals(result, data)

  test("Kryo codec: roundtrip case class"):
    val codec = KryoSerializationCodec()
    val data = CodecTestPoint(1, 2)
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestPoint]
    assertEquals(result.x, data.x)
    assertEquals(result.y, data.y)

  test("Kryo codec: roundtrip complex case class"):
    val codec = KryoSerializationCodec()
    val data = CodecTestPerson("Bob", 25, false)
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestPerson]
    assertEquals(result.name, data.name)
    assertEquals(result.age, data.age)
    assertEquals(result.active, data.active)

  test("Kryo codec: roundtrip nested case class"):
    val codec = KryoSerializationCodec()
    val data = CodecTestNested("center", CodecTestPoint(5, 5))
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestNested]
    assertEquals(result.label, data.label)
    assertEquals(result.point.x, data.point.x)

  test("Kryo codec: null handling"):
    val codec = KryoSerializationCodec()
    assertEquals(codec.encode(null), null)
    assertEquals(codec.decode(null), null)

  test("Kryo codec: custom configuration"):
    val codec = KryoCodecImpl.withConfig { kryo =>
      kryo.register(classOf[CodecTestPoint])
    }
    val data = CodecTestPoint(10, 20)
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestPoint]
    assertEquals(result.x, data.x)
    assertEquals(result.y, data.y)

  // Test Fory codec
  test("Fory codec: roundtrip String"):
    val codec = ForySerializationCodec()
    val data = "hello world"
    val bytes = codec.encode(data)
    val result = codec.decode(bytes)
    assertEquals(result, data)

  test("Fory codec: roundtrip case class"):
    val codec = ForySerializationCodec()
    val data = CodecTestPoint(1, 2)
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestPoint]
    assertEquals(result.x, data.x)
    assertEquals(result.y, data.y)

  test("Fory codec: roundtrip complex case class"):
    val codec = ForySerializationCodec()
    val data = CodecTestPerson("Charlie", 35, true)
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestPerson]
    assertEquals(result.name, data.name)
    assertEquals(result.age, data.age)
    assertEquals(result.active, data.active)

  test("Fory codec: roundtrip nested case class"):
    val codec = ForySerializationCodec()
    val data = CodecTestNested("corner", CodecTestPoint(-1, -1))
    val bytes = codec.encode(data)
    val result = codec.decode(bytes).asInstanceOf[CodecTestNested]
    assertEquals(result.label, data.label)
    assertEquals(result.point.x, data.point.x)

  test("Fory codec: null handling"):
    val codec = ForySerializationCodec()
    assertEquals(codec.encode(null), null)
    assertEquals(codec.decode(null), null)

  // Codec factory pattern tests
  test("JavaSerializationCodec factory creates new instances"):
    val codec1 = JavaSerializationCodec()
    val codec2 = JavaSerializationCodec()
    assert(codec1 ne codec2)

  test("KryoSerializationCodec factory creates new instances"):
    val codec1 = KryoSerializationCodec()
    val codec2 = KryoSerializationCodec()
    assert(codec1 ne codec2)

  test("ForySerializationCodec factory returns codec instance"):
    val codec1 = ForySerializationCodec()
    ForySerializationCodec()
    assert(codec1.isInstanceOf[ForyCodecImpl])
