package protocatalyst.ml.dsl

import protocatalyst.ml.dsl.Shape._
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.types._

/** Tests for the Tensor[S, D] typed DSL wrapper. */
class TensorSuite extends munit.FunSuite:

  private val f32 = TensorDType.Float32

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val vecF32 = tensorType(f32, 10)
  private val matF32_16_784 = tensorType(f32, 16, 784)
  private val matF32_256_784 = tensorType(f32, 256, 784)
  private val matF32_10_256 = tensorType(f32, 10, 256)

  // ============================================================================
  // Constructors
  // ============================================================================

  test("Tensor.input creates Input expr"):
    val x = Tensor.input[Mat[16, 784], f32.type]("x", matF32_16_784)
    x.expr match
      case TensorExpr.Input(name, tt) =>
        assertEquals(name, "x")
        assertEquals(tt, matF32_16_784)
      case _ => fail("Expected Input")

  test("Tensor.parameter creates Parameter expr"):
    val w = Tensor.parameter[Mat[256, 784], f32.type](
      "w",
      matF32_256_784,
      Some(Initializer.Kaiming("fan_in", "relu"))
    )
    w.expr match
      case TensorExpr.Parameter(name, _, init) =>
        assertEquals(name, "w")
        assert(init.isDefined)
      case _ => fail("Expected Parameter")

  // ============================================================================
  // MatMul
  // ============================================================================

  test("matmul produces MatMul expr"):
    val a = Tensor.input[Mat[3, 4], f32.type]("a", tensorType(f32, 3, 4))
    val b = Tensor.input[Mat[4, 5], f32.type]("b", tensorType(f32, 4, 5))
    val c: Tensor[Mat[3, 5], f32.type] = a.matmul(b) // compile-time shape check
    c.expr match
      case TensorExpr.MatMul(_, _, false, false) => () // ok
      case _                                     => fail("Expected MatMul")

  // ============================================================================
  // Activations
  // ============================================================================

  test("relu preserves shape"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.relu
    y.expr match
      case TensorExpr.Relu(_) => () // ok
      case _                  => fail("Expected Relu")

  test("sigmoid preserves shape"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.sigmoid
    y.expr match
      case TensorExpr.Sigmoid(_) => () // ok
      case _                     => fail("Expected Sigmoid")

  test("gelu preserves shape"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.gelu(approximate = true)
    y.expr match
      case TensorExpr.Gelu(_, true) => () // ok
      case _                        => fail("Expected Gelu(approximate=true)")

  test("leakyRelu with alpha"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.leakyRelu(0.2)
    y.expr match
      case TensorExpr.LeakyRelu(_, alpha) => assertEquals(alpha, 0.2)
      case _                              => fail("Expected LeakyRelu")

  // ============================================================================
  // Element-wise arithmetic (same shape)
  // ============================================================================

  test("add same shape"):
    val a = Tensor.input[Vec[10], f32.type]("a", vecF32)
    val b = Tensor.input[Vec[10], f32.type]("b", vecF32)
    val c: Tensor[Vec[10], f32.type] = a + b
    c.expr match
      case TensorExpr.Add(_, _) => () // ok
      case _                    => fail("Expected Add")

  test("sub produces Sub expr"):
    val a = Tensor.input[Vec[10], f32.type]("a", vecF32)
    val b = Tensor.input[Vec[10], f32.type]("b", vecF32)
    val c: Tensor[Vec[10], f32.type] = a - b
    c.expr match
      case TensorExpr.Sub(_, _) => () // ok
      case _                    => fail("Expected Sub")

  test("mul produces Mul expr"):
    val a = Tensor.input[Vec[10], f32.type]("a", vecF32)
    val b = Tensor.input[Vec[10], f32.type]("b", vecF32)
    val c: Tensor[Vec[10], f32.type] = a * b
    c.expr match
      case TensorExpr.Mul(_, _) => () // ok
      case _                    => fail("Expected Mul")

  test("div produces Div expr"):
    val a = Tensor.input[Vec[10], f32.type]("a", vecF32)
    val b = Tensor.input[Vec[10], f32.type]("b", vecF32)
    val c: Tensor[Vec[10], f32.type] = a / b
    c.expr match
      case TensorExpr.Div(_, _) => () // ok
      case _                    => fail("Expected Div")

  // ============================================================================
  // Unary operations
  // ============================================================================

  test("sqrt preserves shape"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.sqrt
    y.expr match
      case TensorExpr.Sqrt(_) => ()
      case _                  => fail("Expected Sqrt")

  test("neg preserves shape"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.neg
    y.expr match
      case TensorExpr.Neg(_) => ()
      case _                 => fail("Expected Neg")

  test("exp preserves shape"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.exp
    y.expr match
      case TensorExpr.Exp(_) => ()
      case _                 => fail("Expected Exp")

  // ============================================================================
  // Shape manipulation
  // ============================================================================

  test("transpose 2D"):
    val x = Tensor.input[Mat[3, 4], f32.type]("x", tensorType(f32, 3, 4))
    val y: Tensor[Mat[4, 3], f32.type] = x.t
    y.expr match
      case TensorExpr.Transpose(_, Vector(1, 0)) => ()
      case _                                     => fail("Expected Transpose with perm [1, 0]")

  test("reshape to new shape"):
    val x = Tensor.input[Mat[2, 6], f32.type]("x", tensorType(f32, 2, 6))
    val y: Tensor[Tensor3D[2, 2, 3], f32.type] = x.reshape[Tensor3D[2, 2, 3]](Vector(2, 2, 3))
    y.expr match
      case TensorExpr.Reshape(_, Vector(2, 2, 3)) => ()
      case _                                      => fail("Expected Reshape")

  test("flatten"):
    val x = Tensor.input[Tensor3D[2, 3, 4], f32.type]("x", tensorType(f32, 2, 3, 4))
    val y = x.flatten(1)
    y.expr match
      case TensorExpr.Flatten(_, 1) => ()
      case _                        => fail("Expected Flatten with axis 1")

  // ============================================================================
  // Normalization
  // ============================================================================

  test("layerNorm preserves shape"):
    val x = Tensor.input[Mat[16, 512], f32.type]("x", tensorType(f32, 16, 512))
    val y: Tensor[Mat[16, 512], f32.type] = x.layerNorm(Vector(512))
    y.expr match
      case TensorExpr.LayerNorm(_, Vector(512), None, None, eps) =>
        assertEquals(eps, 1e-5)
      case _ => fail("Expected LayerNorm")

  // ============================================================================
  // Regularization
  // ============================================================================

  test("dropout preserves shape"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.dropout(0.5, training = true)
    y.expr match
      case TensorExpr.Dropout(_, 0.5, true) => ()
      case _                                => fail("Expected Dropout(0.5, true)")

  // ============================================================================
  // Softmax
  // ============================================================================

  test("softmax preserves shape"):
    val x = Tensor.input[Mat[16, 10], f32.type]("x", tensorType(f32, 16, 10))
    val y: Tensor[Mat[16, 10], f32.type] = x.softmax(axis = -1)
    y.expr match
      case TensorExpr.Softmax(_, -1) => ()
      case _                         => fail("Expected Softmax with axis -1")

  // ============================================================================
  // Loss functions
  // ============================================================================

  test("crossEntropyLoss produces scalar"):
    val pred = Tensor.input[Mat[16, 10], f32.type]("pred", tensorType(f32, 16, 10))
    val target =
      Tensor.input[Vec[16], TensorDType.Int64.type]("target", tensorType(TensorDType.Int64, 16))
    val loss: Tensor[Scalar, f32.type] = crossEntropyLoss(pred, target)
    loss.expr match
      case TensorExpr.CrossEntropyLoss(_, _, None, Reduction.Mean) => ()
      case _ => fail("Expected CrossEntropyLoss with Mean reduction")

  test("mseLoss produces scalar"):
    val pred = Tensor.input[Vec[10], f32.type]("pred", vecF32)
    val target = Tensor.input[Vec[10], f32.type]("target", vecF32)
    val loss: Tensor[Scalar, f32.type] = mseLoss(pred, target, Reduction.Sum)
    loss.expr match
      case TensorExpr.MSELoss(_, _, Reduction.Sum) => ()
      case _                                       => fail("Expected MSELoss with Sum reduction")

  // ============================================================================
  // Composability: chained operations
  // ============================================================================

  test("MLP forward pass: matmul + relu + matmul"):
    val x = Tensor.input[Mat[16, 784], f32.type]("x", matF32_16_784)
    val w1 = Tensor.parameter[Mat[784, 256], f32.type]("w1", tensorType(f32, 784, 256))
    val w2 = Tensor.parameter[Mat[256, 10], f32.type]("w2", matF32_10_256)

    // Shape types flow through:
    val h: Tensor[Mat[16, 256], f32.type] = x.matmul(w1) // (16,784) x (784,256) = (16,256)
    val a: Tensor[Mat[16, 256], f32.type] = h.relu // shape preserved
    val out: Tensor[Mat[16, 10], f32.type] = a.matmul(w2) // (16,256) x (256,10) = (16,10)
    val logits = out.softmax(axis = -1) // shape preserved

    // Verify the expression tree structure
    logits.expr match
      case TensorExpr.Softmax(
            TensorExpr.MatMul(TensorExpr.Relu(TensorExpr.MatMul(_, _, _, _)), _, _, _),
            -1
          ) =>
        () // ok
      case other => fail(s"Unexpected expression structure: $other")

  test("Residual connection pattern"):
    val x = Tensor.input[Vec[10], f32.type]("x", vecF32)
    val y: Tensor[Vec[10], f32.type] = x.relu + x // residual: relu(x) + x
    y.expr match
      case TensorExpr.Add(TensorExpr.Relu(inner), original) =>
        assert(inner.eq(original)) // shared reference
      case _ => fail("Expected Add(Relu(x), x)")
