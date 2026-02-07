package protocatalyst.ml.types

class TensorTypeSuite extends munit.FunSuite:

  // ============================================================================
  // TensorDType
  // ============================================================================

  test("TensorDType has all expected variants"):
    val allTypes = TensorDType.values
    assertEquals(allTypes.length, 12)
    assert(allTypes.contains(TensorDType.Float32))
    assert(allTypes.contains(TensorDType.Float64))
    assert(allTypes.contains(TensorDType.Float16))
    assert(allTypes.contains(TensorDType.BFloat16))
    assert(allTypes.contains(TensorDType.Int8))
    assert(allTypes.contains(TensorDType.Int16))
    assert(allTypes.contains(TensorDType.Int32))
    assert(allTypes.contains(TensorDType.Int64))
    assert(allTypes.contains(TensorDType.UInt8))
    assert(allTypes.contains(TensorDType.Bool))
    assert(allTypes.contains(TensorDType.Complex64))
    assert(allTypes.contains(TensorDType.Complex128))

  // ============================================================================
  // Dim
  // ============================================================================

  test("Dim.Static stores size"):
    val d = Dim.Static(32)
    assertEquals(d, Dim(32))

  test("Dim.Dynamic with name"):
    val d = Dim.dynamic("batch")
    assertEquals(d, Dim.Dynamic(Some("batch")))

  test("Dim.Dynamic without name"):
    val d = Dim.dynamic
    assertEquals(d, Dim.Dynamic(None))

  // ============================================================================
  // TensorType
  // ============================================================================

  test("scalar tensor (rank 0)"):
    val t = TensorType(TensorDType.Float32, Vector.empty)
    assertEquals(t.rank, 0)
    assert(t.isFullyStatic)
    assertEquals(t.staticShape, Some(Vector.empty))

  test("vector tensor (rank 1)"):
    val t = TensorType(TensorDType.Float32, Vector(Dim(10)))
    assertEquals(t.rank, 1)
    assert(t.isFullyStatic)
    assertEquals(t.staticShape, Some(Vector(10)))

  test("matrix tensor (rank 2)"):
    val t = TensorType(TensorDType.Float64, Vector(Dim(3), Dim(4)))
    assertEquals(t.rank, 2)
    assert(t.isFullyStatic)
    assertEquals(t.staticShape, Some(Vector(3, 4)))

  test("4D tensor with NCHW layout"):
    val t = TensorType(
      TensorDType.Float32,
      Vector(Dim(1), Dim(3), Dim(224), Dim(224)),
      DataLayout.NCHW
    )
    assertEquals(t.rank, 4)
    assertEquals(t.layout, DataLayout.NCHW)
    assertEquals(t.staticShape, Some(Vector(1, 3, 224, 224)))

  test("tensor with dynamic dimension"):
    val t = TensorType(
      TensorDType.Float32,
      Vector(Dim.dynamic("batch"), Dim(3), Dim(224), Dim(224))
    )
    assertEquals(t.rank, 4)
    assert(!t.isFullyStatic)
    assertEquals(t.staticShape, None)

  test("default layout is Default"):
    val t = TensorType(TensorDType.Float32, Vector(Dim(10)))
    assertEquals(t.layout, DataLayout.Default)

  // ============================================================================
  // TensorData
  // ============================================================================

  test("TensorData stores raw bytes"):
    val data = TensorData(TensorDType.Float32, Vector(2, 3), Array[Byte](1, 2, 3, 4))
    assertEquals(data.dtype, TensorDType.Float32)
    assertEquals(data.shape, Vector(2, 3))
    assertEquals(data.numElements, 6L)

  test("TensorData equality compares rawBytes deeply"):
    val d1 = TensorData(TensorDType.Float32, Vector(2), Array[Byte](1, 2, 3, 4))
    val d2 = TensorData(TensorDType.Float32, Vector(2), Array[Byte](1, 2, 3, 4))
    val d3 = TensorData(TensorDType.Float32, Vector(2), Array[Byte](5, 6, 7, 8))
    assertEquals(d1, d2)
    assertNotEquals(d1, d3)

  test("TensorData hashCode is consistent with equals"):
    val d1 = TensorData(TensorDType.Int32, Vector(4), Array[Byte](0, 0, 0, 1))
    val d2 = TensorData(TensorDType.Int32, Vector(4), Array[Byte](0, 0, 0, 1))
    assertEquals(d1.hashCode(), d2.hashCode())

  // ============================================================================
  // Supporting enums
  // ============================================================================

  test("Initializer variants"):
    val inits: Seq[Initializer] = Seq(
      Initializer.Zeros,
      Initializer.Ones,
      Initializer.Xavier(1.0),
      Initializer.Kaiming("fan_in", "relu"),
      Initializer.Normal(0.0, 0.02),
      Initializer.Uniform(-0.1, 0.1)
    )
    assertEquals(inits.size, 6)

  test("PadMode variants"):
    assertEquals(PadMode.values.length, 3)

  test("Reduction variants"):
    assertEquals(Reduction.values.length, 3)

  test("OpAttribute variants"):
    val attrs: Seq[OpAttribute] = Seq(
      OpAttribute.IntAttr(42),
      OpAttribute.FloatAttr(3.14),
      OpAttribute.StringAttr("test"),
      OpAttribute.IntsAttr(Vector(1L, 2L, 3L)),
      OpAttribute.FloatsAttr(Vector(1.0, 2.0)),
      OpAttribute.TensorAttr(TensorData(TensorDType.Float32, Vector(1), Array[Byte](0, 0, 0, 0)))
    )
    assertEquals(attrs.size, 6)
