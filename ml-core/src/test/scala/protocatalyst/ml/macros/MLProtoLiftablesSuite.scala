package protocatalyst.ml.macros

import protocatalyst.ml.artifact._
import protocatalyst.ml.expr._
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

/** Tests for MLProtoLiftables — verifies ToExpr instances roundtrip through macro expansion.
  *
  * Each test calls an inline macro that constructs a value at compile time, lifts it via ToExpr,
  * and splices it into bytecode. At runtime we compare the result to the expected value.
  */
class MLProtoLiftablesSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32
  private val i64 = TensorDType.Int64

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val vecF32 = tensorType(f32, 10)

  // ============================================================================
  // Simple enums
  // ============================================================================

  test("TensorDType lifts correctly"):
    assertEquals(LiftableTestMacro.liftFloat32, TensorDType.Float32)

  test("DataLayout lifts correctly"):
    assertEquals(LiftableTestMacro.liftNCHW, DataLayout.NCHW)

  test("PadMode lifts correctly"):
    assertEquals(LiftableTestMacro.liftPadReflect, PadMode.Reflect)

  test("Reduction lifts correctly"):
    assertEquals(LiftableTestMacro.liftReductionMean, Reduction.Mean)

  // ============================================================================
  // Parameterized enums
  // ============================================================================

  test("Dim.Static lifts correctly"):
    assertEquals(LiftableTestMacro.liftStaticDim, Dim.Static(42))

  test("Dim.Dynamic lifts correctly"):
    assertEquals(LiftableTestMacro.liftDynamicDim, Dim.Dynamic(Some("batch")))

  test("Initializer.Kaiming lifts correctly"):
    assertEquals(LiftableTestMacro.liftKaimingInit, Initializer.Kaiming("fan_in", "relu"))

  test("Initializer.Zeros lifts correctly"):
    assertEquals(LiftableTestMacro.liftZerosInit, Initializer.Zeros)

  test("OpAttribute.FloatAttr lifts correctly"):
    assertEquals(LiftableTestMacro.liftFloatAttr, OpAttribute.FloatAttr(0.5))

  // ============================================================================
  // Case classes
  // ============================================================================

  test("TensorType lifts correctly"):
    assertEquals(LiftableTestMacro.liftTensorType, tensorType(f32, 16, 784))

  // ============================================================================
  // TensorExpr variants
  // ============================================================================

  test("TensorExpr.Input lifts correctly"):
    assertEquals(LiftableTestMacro.liftInput, TensorExpr.Input("x", vecF32))

  test("TensorExpr.Relu lifts correctly"):
    val x = TensorExpr.Input("x", vecF32)
    assertEquals(LiftableTestMacro.liftRelu, TensorExpr.Relu(x))

  test("TensorExpr.Add lifts correctly"):
    val a = TensorExpr.Input("a", vecF32)
    val b = TensorExpr.Input("b", vecF32)
    assertEquals(LiftableTestMacro.liftAdd, TensorExpr.Add(a, b))

  test("TensorExpr.Linear lifts correctly"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 256), Some(Initializer.Zeros))
    assertEquals(LiftableTestMacro.liftLinear, TensorExpr.Linear(x, w, Some(b)))

  test("TensorExpr.Conv lifts correctly"):
    val x = TensorExpr.Input("x", tensorType(f32, 1, 3, 224, 224))
    val w = TensorExpr.Parameter("w", tensorType(f32, 64, 3, 7, 7), None)
    assertEquals(
      LiftableTestMacro.liftConv,
      TensorExpr.Conv(x, w, None, Vector(2, 2), Vector(3, 3, 3, 3), Vector(1, 1), 1)
    )

  test("TensorExpr.Dropout lifts correctly"):
    val x = TensorExpr.Input("x", vecF32)
    assertEquals(LiftableTestMacro.liftDropout, TensorExpr.Dropout(x, 0.5, true))

  test("TensorExpr.CrossEntropyLoss lifts correctly"):
    val pred = TensorExpr.Input("pred", tensorType(f32, 16, 10))
    val target = TensorExpr.Input("target", tensorType(i64, 16))
    assertEquals(
      LiftableTestMacro.liftCrossEntropyLoss,
      TensorExpr.CrossEntropyLoss(pred, target, None, Reduction.Mean)
    )

  test("TensorExpr.OpaqueOp lifts correctly"):
    val x = TensorExpr.Input("x", vecF32)
    assertEquals(
      LiftableTestMacro.liftOpaqueOp,
      TensorExpr.OpaqueOp(
        "custom::my_op",
        Vector(x),
        Map("alpha" -> OpAttribute.FloatAttr(0.5), "size" -> OpAttribute.IntAttr(42)),
        vecF32
      )
    )

  // ============================================================================
  // Graph types
  // ============================================================================

  test("ComputeGraph lifts correctly"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val expected = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(NamedNode("relu", relu, vecF32)),
      opsetVersion = 13
    )
    assertEquals(LiftableTestMacro.liftGraph, expected)

  // ============================================================================
  // Artifact types
  // ============================================================================

  test("CompiledMLArtifact lifts correctly"):
    val result = LiftableTestMacro.liftArtifact
    assertEquals(result.formatVersion, MLArtifactVersion(1, 0, 0))
    assertEquals(result.protocatalystVersion, "0.1.0-test")
    assertEquals(result.compiledAt, 1234567890L)
    assertEquals(result.contentHash, 42L)
    assertEquals(result.graph.name, "test")
    assertEquals(result.sourceInfo, Some(MLSourceInfo("test.scala", 10)))
