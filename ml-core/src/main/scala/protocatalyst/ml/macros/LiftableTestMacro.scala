package protocatalyst.ml.macros

import scala.quoted._

import protocatalyst.ml.artifact._
import protocatalyst.ml.expr._
import protocatalyst.ml.graph._
import protocatalyst.ml.types._

/** Test helper macro that constructs ML IR values at compile time and lifts them via ToExpr.
  *
  * Each inline def constructs a value at compile time, lifts it using the ToExpr instance, and
  * splices it into bytecode. Tests compare the result against the expected value at runtime.
  */
object LiftableTestMacro:

  import MLProtoLiftables.given

  // --- TensorDType ---
  inline def liftFloat32: TensorDType = ${ liftFloat32Impl }
  private def liftFloat32Impl(using Quotes): Expr[TensorDType] =
    Expr(TensorDType.Float32)

  // --- Dim ---
  inline def liftStaticDim: Dim = ${ liftStaticDimImpl }
  private def liftStaticDimImpl(using Quotes): Expr[Dim] =
    Expr(Dim.Static(42))

  inline def liftDynamicDim: Dim = ${ liftDynamicDimImpl }
  private def liftDynamicDimImpl(using Quotes): Expr[Dim] =
    Expr(Dim.Dynamic(Some("batch")))

  // --- DataLayout ---
  inline def liftNCHW: DataLayout = ${ liftNCHWImpl }
  private def liftNCHWImpl(using Quotes): Expr[DataLayout] =
    Expr(DataLayout.NCHW)

  // --- TensorType ---
  inline def liftTensorType: TensorType = ${ liftTensorTypeImpl }
  private def liftTensorTypeImpl(using Quotes): Expr[TensorType] =
    Expr(
      TensorType(TensorDType.Float32, Vector(Dim.Static(16), Dim.Static(784)), DataLayout.Default)
    )

  // --- Initializer ---
  inline def liftKaimingInit: Initializer = ${ liftKaimingInitImpl }
  private def liftKaimingInitImpl(using Quotes): Expr[Initializer] =
    Expr(Initializer.Kaiming("fan_in", "relu"))

  inline def liftZerosInit: Initializer = ${ liftZerosInitImpl }
  private def liftZerosInitImpl(using Quotes): Expr[Initializer] =
    Expr(Initializer.Zeros)

  // --- PadMode ---
  inline def liftPadReflect: PadMode = ${ liftPadReflectImpl }
  private def liftPadReflectImpl(using Quotes): Expr[PadMode] =
    Expr(PadMode.Reflect)

  // --- Reduction ---
  inline def liftReductionMean: Reduction = ${ liftReductionMeanImpl }
  private def liftReductionMeanImpl(using Quotes): Expr[Reduction] =
    Expr(Reduction.Mean)

  // --- OpAttribute ---
  inline def liftFloatAttr: OpAttribute = ${ liftFloatAttrImpl }
  private def liftFloatAttrImpl(using Quotes): Expr[OpAttribute] =
    Expr(OpAttribute.FloatAttr(0.5))

  // --- TensorExpr: representative variants ---
  inline def liftInput: TensorExpr = ${ liftInputImpl }
  private def liftInputImpl(using Quotes): Expr[TensorExpr] =
    Expr(TensorExpr.Input("x", TensorType(TensorDType.Float32, Vector(Dim.Static(10)))))

  inline def liftRelu: TensorExpr = ${ liftReluImpl }
  private def liftReluImpl(using Quotes): Expr[TensorExpr] =
    val x = TensorExpr.Input("x", TensorType(TensorDType.Float32, Vector(Dim.Static(10))))
    Expr(TensorExpr.Relu(x))

  inline def liftAdd: TensorExpr = ${ liftAddImpl }
  private def liftAddImpl(using Quotes): Expr[TensorExpr] =
    val a = TensorExpr.Input("a", TensorType(TensorDType.Float32, Vector(Dim.Static(10))))
    val b = TensorExpr.Input("b", TensorType(TensorDType.Float32, Vector(Dim.Static(10))))
    Expr(TensorExpr.Add(a, b))

  inline def liftLinear: TensorExpr = ${ liftLinearImpl }
  private def liftLinearImpl(using Quotes): Expr[TensorExpr] =
    val x = TensorExpr.Input(
      "x",
      TensorType(TensorDType.Float32, Vector(Dim.Static(16), Dim.Static(784)))
    )
    val w = TensorExpr.Parameter(
      "w",
      TensorType(TensorDType.Float32, Vector(Dim.Static(256), Dim.Static(784))),
      None
    )
    val b = TensorExpr.Parameter(
      "b",
      TensorType(TensorDType.Float32, Vector(Dim.Static(256))),
      Some(Initializer.Zeros)
    )
    Expr(TensorExpr.Linear(x, w, Some(b)))

  inline def liftConv: TensorExpr = ${ liftConvImpl }
  private def liftConvImpl(using Quotes): Expr[TensorExpr] =
    val x = TensorExpr.Input(
      "x",
      TensorType(
        TensorDType.Float32,
        Vector(Dim.Static(1), Dim.Static(3), Dim.Static(224), Dim.Static(224))
      )
    )
    val w = TensorExpr.Parameter(
      "w",
      TensorType(
        TensorDType.Float32,
        Vector(Dim.Static(64), Dim.Static(3), Dim.Static(7), Dim.Static(7))
      ),
      None
    )
    Expr(TensorExpr.Conv(x, w, None, Vector(2, 2), Vector(3, 3, 3, 3), Vector(1, 1), 1))

  inline def liftDropout: TensorExpr = ${ liftDropoutImpl }
  private def liftDropoutImpl(using Quotes): Expr[TensorExpr] =
    val x = TensorExpr.Input("x", TensorType(TensorDType.Float32, Vector(Dim.Static(10))))
    Expr(TensorExpr.Dropout(x, 0.5, true))

  inline def liftCrossEntropyLoss: TensorExpr = ${ liftCrossEntropyLossImpl }
  private def liftCrossEntropyLossImpl(using Quotes): Expr[TensorExpr] =
    val pred = TensorExpr.Input(
      "pred",
      TensorType(TensorDType.Float32, Vector(Dim.Static(16), Dim.Static(10)))
    )
    val target = TensorExpr.Input("target", TensorType(TensorDType.Int64, Vector(Dim.Static(16))))
    Expr(TensorExpr.CrossEntropyLoss(pred, target, None, Reduction.Mean))

  inline def liftOpaqueOp: TensorExpr = ${ liftOpaqueOpImpl }
  private def liftOpaqueOpImpl(using Quotes): Expr[TensorExpr] =
    val x = TensorExpr.Input("x", TensorType(TensorDType.Float32, Vector(Dim.Static(10))))
    Expr(
      TensorExpr.OpaqueOp(
        "custom::my_op",
        Vector(x),
        Map("alpha" -> OpAttribute.FloatAttr(0.5), "size" -> OpAttribute.IntAttr(42)),
        TensorType(TensorDType.Float32, Vector(Dim.Static(10)))
      )
    )

  // --- ComputeGraph ---
  inline def liftGraph: ComputeGraph = ${ liftGraphImpl }
  private def liftGraphImpl(using Quotes): Expr[ComputeGraph] =
    val vecF32 = TensorType(TensorDType.Float32, Vector(Dim.Static(10)))
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    Expr(
      ComputeGraph(
        name = "test",
        inputs = Vector(GraphIO("x", vecF32)),
        outputs = Vector(GraphIO("out", vecF32)),
        nodes = Vector(NamedNode("relu", relu, vecF32)),
        opsetVersion = 13
      )
    )

  // --- CompiledMLArtifact ---
  inline def liftArtifact: CompiledMLArtifact = ${ liftArtifactImpl }
  private def liftArtifactImpl(using Quotes): Expr[CompiledMLArtifact] =
    val vecF32 = TensorType(TensorDType.Float32, Vector(Dim.Static(10)))
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val graph = ComputeGraph(
      name = "test",
      inputs = Vector(GraphIO("x", vecF32)),
      outputs = Vector(GraphIO("out", vecF32)),
      nodes = Vector(NamedNode("relu", relu, vecF32))
    )
    Expr(
      CompiledMLArtifact(
        formatVersion = MLArtifactVersion(1, 0, 0),
        protocatalystVersion = "0.1.0-test",
        compiledAt = 1234567890L,
        contentHash = 42L,
        graph = graph,
        sourceInfo = Some(MLSourceInfo("test.scala", 10))
      )
    )
