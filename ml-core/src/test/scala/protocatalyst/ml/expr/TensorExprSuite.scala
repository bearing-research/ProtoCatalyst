package protocatalyst.ml.expr

import protocatalyst.ml.types._

class TensorExprSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32
  private val i64 = TensorDType.Int64

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val scalarF32 = tensorType(f32)
  private val vecF32 = tensorType(f32, 10)
  private val matF32 = tensorType(f32, 3, 4)
  private val imgF32 = tensorType(f32, 1, 3, 224, 224)

  // ============================================================================
  // Leaf nodes
  // ============================================================================

  test("Input construction"):
    val input = TensorExpr.Input("x", matF32)
    val TensorExpr.Input(name, tt) = input: @unchecked
    assertEquals(name, "x")
    assertEquals(tt, matF32)

  test("Constant construction"):
    val data = TensorData(f32, Vector(2), Array[Byte](0, 0, 0, 0, 0, 0, 0, 0))
    val c = TensorExpr.Constant("bias", tensorType(f32, 2), data)
    val TensorExpr.Constant(name, _, cData) = c: @unchecked
    assertEquals(name, "bias")
    assertEquals(cData.numElements, 2L)

  test("Parameter with initializer"):
    val param = TensorExpr.Parameter("weight", matF32, Some(Initializer.Kaiming("fan_in", "relu")))
    val TensorExpr.Parameter(name, _, init) = param: @unchecked
    assertEquals(name, "weight")
    assert(init.isDefined)

  test("Parameter without initializer"):
    val param = TensorExpr.Parameter("w", matF32, None)
    val TensorExpr.Parameter(_, _, init) = param: @unchecked
    assertEquals(init, None)

  // ============================================================================
  // Linear algebra
  // ============================================================================

  test("MatMul construction"):
    val a = TensorExpr.Input("a", tensorType(f32, 3, 4))
    val b = TensorExpr.Input("b", tensorType(f32, 4, 5))
    val mm = TensorExpr.MatMul(a, b, transA = false, transB = false)
    val TensorExpr.MatMul(left, right, _, _) = mm: @unchecked
    assertEquals(left, a)
    assertEquals(right, b)

  test("Gemm construction"):
    val a = TensorExpr.Input("a", tensorType(f32, 3, 4))
    val b = TensorExpr.Input("b", tensorType(f32, 4, 5))
    val c = TensorExpr.Input("c", tensorType(f32, 3, 5))
    val gemm = TensorExpr.Gemm(a, b, Some(c), 1.0, 1.0, transA = false, transB = false)
    val TensorExpr.Gemm(_, _, gemmC, alpha, _, _, _) = gemm: @unchecked
    assertEquals(alpha, 1.0)
    assert(gemmC.isDefined)

  test("Linear construction"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 784))
    val w = TensorExpr.Parameter("w", tensorType(f32, 256, 784), None)
    val b = TensorExpr.Parameter("b", tensorType(f32, 256), Some(Initializer.Zeros))
    val linear = TensorExpr.Linear(x, w, Some(b))
    val TensorExpr.Linear(_, _, bias) = linear: @unchecked
    assert(bias.isDefined)

  // ============================================================================
  // Activations
  // ============================================================================

  test("activation functions"):
    val x = TensorExpr.Input("x", vecF32)
    val activations = Seq(
      TensorExpr.Relu(x),
      TensorExpr.LeakyRelu(x, 0.01),
      TensorExpr.Sigmoid(x),
      TensorExpr.Tanh(x),
      TensorExpr.Softmax(x, axis = -1),
      TensorExpr.LogSoftmax(x, axis = -1),
      TensorExpr.Gelu(x, approximate = false),
      TensorExpr.Silu(x),
      TensorExpr.Elu(x, 1.0),
      TensorExpr.HardSwish(x)
    )
    assertEquals(activations.size, 10)

  // ============================================================================
  // Element-wise arithmetic
  // ============================================================================

  test("arithmetic operations"):
    val a = TensorExpr.Input("a", vecF32)
    val b = TensorExpr.Input("b", vecF32)
    val ops = Seq(
      TensorExpr.Add(a, b),
      TensorExpr.Sub(a, b),
      TensorExpr.Mul(a, b),
      TensorExpr.Div(a, b),
      TensorExpr.Pow(a, b),
      TensorExpr.Sqrt(a),
      TensorExpr.Neg(a),
      TensorExpr.Abs(a),
      TensorExpr.Exp(a),
      TensorExpr.Log(a),
      TensorExpr.Clip(a, Some(0.0), Some(1.0))
    )
    assertEquals(ops.size, 11)

  // ============================================================================
  // Normalization
  // ============================================================================

  test("BatchNorm construction"):
    val x = TensorExpr.Input("x", imgF32)
    val scale = TensorExpr.Parameter("scale", tensorType(f32, 3), None)
    val bias = TensorExpr.Parameter("bias", tensorType(f32, 3), None)
    val mean = TensorExpr.Parameter("mean", tensorType(f32, 3), None)
    val variance = TensorExpr.Parameter("var", tensorType(f32, 3), None)
    val bn = TensorExpr.BatchNorm(x, scale, bias, mean, variance, 1e-5, 0.1, training = false)
    val TensorExpr.BatchNorm(_, _, _, _, _, eps, _, tr) = bn: @unchecked
    assertEquals(eps, 1e-5)
    assertEquals(tr, false)

  test("LayerNorm construction"):
    val x = TensorExpr.Input("x", tensorType(f32, 16, 512))
    val ln = TensorExpr.LayerNorm(x, Vector(512), None, None, 1e-5)
    val TensorExpr.LayerNorm(_, normShape, _, _, _) = ln: @unchecked
    assertEquals(normShape, Vector(512))

  // ============================================================================
  // Shape manipulation
  // ============================================================================

  test("Reshape construction"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3, 4))
    val r = TensorExpr.Reshape(x, Vector(2, 12))
    val TensorExpr.Reshape(_, shape) = r: @unchecked
    assertEquals(shape, Vector(2, 12))

  test("Transpose construction"):
    val x = TensorExpr.Input("x", tensorType(f32, 2, 3))
    val t = TensorExpr.Transpose(x, Vector(1, 0))
    val TensorExpr.Transpose(_, perm) = t: @unchecked
    assertEquals(perm, Vector(1, 0))

  test("Concat construction"):
    val a = TensorExpr.Input("a", tensorType(f32, 2, 3))
    val b = TensorExpr.Input("b", tensorType(f32, 2, 3))
    val c = TensorExpr.TensorConcat(Vector(a, b), axis = 0)
    val TensorExpr.TensorConcat(inputs, _) = c: @unchecked
    assertEquals(inputs.size, 2)

  // ============================================================================
  // Convolution
  // ============================================================================

  test("Conv construction"):
    val x = TensorExpr.Input("x", imgF32)
    val w = TensorExpr.Parameter("w", tensorType(f32, 64, 3, 3, 3), None)
    val conv = TensorExpr.Conv(
      x,
      w,
      None,
      strides = Vector(1, 1),
      pads = Vector(1, 1, 1, 1),
      dilations = Vector(1, 1),
      group = 1
    )
    val TensorExpr.Conv(_, _, _, _, _, _, grp) = conv: @unchecked
    assertEquals(grp, 1)

  // ============================================================================
  // Recurrent
  // ============================================================================

  test("LSTM construction"):
    val x = TensorExpr.Input("x", tensorType(f32, 32, 10, 128))
    val wih = TensorExpr.Parameter("w_ih", tensorType(f32, 4 * 256, 128), None)
    val whh = TensorExpr.Parameter("w_hh", tensorType(f32, 4 * 256, 256), None)
    val lstm = TensorExpr.LSTM(
      x,
      wih,
      whh,
      None,
      hiddenSize = 256,
      numLayers = 1,
      bidirectional = false,
      dropout = 0.0
    )
    val TensorExpr.LSTM(_, _, _, _, hs, _, _, _) = lstm: @unchecked
    assertEquals(hs, 256)

  // ============================================================================
  // Attention
  // ============================================================================

  test("ScaledDotProductAttention construction"):
    val q = TensorExpr.Input("q", tensorType(f32, 1, 8, 64, 64))
    val k = TensorExpr.Input("k", tensorType(f32, 1, 8, 64, 64))
    val v = TensorExpr.Input("v", tensorType(f32, 1, 8, 64, 64))
    val attn = TensorExpr.ScaledDotProductAttention(q, k, v, None, dropout = 0.0, isCausal = true)
    val TensorExpr.ScaledDotProductAttention(_, _, _, msk, _, causal) = attn: @unchecked
    assert(causal)
    assertEquals(msk, None)

  // ============================================================================
  // Loss functions
  // ============================================================================

  test("loss functions"):
    val pred = TensorExpr.Input("pred", tensorType(f32, 16, 10))
    val target = TensorExpr.Input("target", tensorType(i64, 16))
    val losses = Seq(
      TensorExpr.CrossEntropyLoss(pred, target, None, Reduction.Mean),
      TensorExpr.MSELoss(pred, target, Reduction.Sum),
      TensorExpr.L1Loss(pred, target, Reduction.None)
    )
    assertEquals(losses.size, 3)

  // ============================================================================
  // DAG structure (shared subexpressions)
  // ============================================================================

  test("DAG: same node can be shared by multiple parents"):
    val x = TensorExpr.Input("x", vecF32)
    val relu = TensorExpr.Relu(x)
    val add = TensorExpr.Add(relu, relu) // relu is shared
    val TensorExpr.Add(l, r) = add: @unchecked
    assertEquals(l, r)
    assert(l.eq(r)) // same reference

  test("DAG: residual connection pattern"):
    val x = TensorExpr.Input("x", vecF32)
    val w = TensorExpr.Parameter("w", matF32, None)
    val linear = TensorExpr.Linear(x, w, None)
    val relu = TensorExpr.Relu(linear)
    val residual = TensorExpr.Add(relu, x) // x used twice in the graph
    val TensorExpr.Add(_, rhs) = residual: @unchecked
    assert(rhs.eq(x))

  // ============================================================================
  // OpaqueOp (extensibility)
  // ============================================================================

  test("OpaqueOp for custom operations"):
    val x = TensorExpr.Input("x", vecF32)
    val op = TensorExpr.OpaqueOp(
      "custom::my_op",
      Vector(x),
      Map("alpha" -> OpAttribute.FloatAttr(0.5)),
      vecF32
    )
    val TensorExpr.OpaqueOp(opName, _, attrs, _) = op: @unchecked
    assertEquals(opName, "custom::my_op")
    assertEquals(attrs.size, 1)

  // ============================================================================
  // Enum completeness
  // ============================================================================

  test("all expression categories are constructible"):
    // Verify we can construct at least one from each category
    val x = TensorExpr.Input("x", vecF32)
    val exprs: Seq[TensorExpr] = Seq(
      x, // leaf
      TensorExpr.MatMul(x, x, false, false), // linear algebra
      TensorExpr.Conv(x, x, None, Vector(1), Vector(0), Vector(1), 1), // conv
      TensorExpr.Relu(x), // activation
      TensorExpr.Add(x, x), // arithmetic
      TensorExpr.MaxPool(x, Vector(2), Vector(2), Vector(0)), // pooling
      TensorExpr.LayerNorm(x, Vector(10), None, None, 1e-5), // normalization
      TensorExpr.ReduceSum(x, Vector(0), true), // reduction
      TensorExpr.Reshape(x, Vector(10)), // shape
      TensorExpr.Dropout(x, 0.5, true), // regularization
      TensorExpr.MSELoss(x, x, Reduction.Mean), // loss
      TensorExpr.Equal(x, x), // comparison
      TensorExpr.Cast(x, TensorDType.Float64) // cast
    )
    assertEquals(exprs.size, 13)
