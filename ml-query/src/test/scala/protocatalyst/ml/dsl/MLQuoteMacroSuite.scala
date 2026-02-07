package protocatalyst.ml.dsl

import protocatalyst.ml.dsl.MLQuoteMacro.mlquote
import protocatalyst.ml.dsl.Shape._
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.types._

/** End-to-end tests for the mlquote macro.
  *
  * Each test verifies that the macro:
  *   1. Correctly walks the DSL AST and builds TensorExpr DAG
  *   2. Runs the MLOptimizer at compile time
  *   3. Embeds the optimized graph as a CompiledMLArtifact bytecode constant
  */
class MLQuoteMacroSuite extends munit.FunSuite:

  private def tensorType(dtype: TensorDType, dims: Int*): TensorType =
    TensorType(dtype, dims.map(Dim(_)).toVector)

  private val vecF32 = tensorType(TensorDType.Float32, 10)
  private val matF32_16_784 = tensorType(TensorDType.Float32, 16, 784)
  private val matF32_784_256 = tensorType(TensorDType.Float32, 784, 256)
  private val matF32_256_10 = tensorType(TensorDType.Float32, 256, 10)

  // ============================================================================
  // Basic operations
  // ============================================================================

  test("simple relu"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.relu
    }
    assertEquals(artifact.graph.name, "mlquote")
    assertEquals(artifact.graph.inputs.size, 1)
    assertEquals(artifact.graph.inputs.head.name, "x")

    // The output node should be Relu(Input("x", ...))
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Relu(TensorExpr.Input("x", _)) => () // ok
      case other                                     => fail(s"Expected Relu(Input(x)), got $other")

  test("simple sigmoid"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.sigmoid
    }
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Sigmoid(TensorExpr.Input("x", _)) => ()
      case other => fail(s"Expected Sigmoid(Input(x)), got $other")

  // ============================================================================
  // Arithmetic
  // ============================================================================

  test("add two tensors"):
    val artifact = mlquote {
      val a = Tensor.input[Vec[10], TensorDType.Float32.type]("a", vecF32)
      val b = Tensor.input[Vec[10], TensorDType.Float32.type]("b", vecF32)
      a + b
    }
    assertEquals(artifact.graph.inputs.size, 2)
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Add(TensorExpr.Input("a", _), TensorExpr.Input("b", _)) => ()
      case other => fail(s"Expected Add(Input(a), Input(b)), got $other")

  test("subtract two tensors"):
    val artifact = mlquote {
      val a = Tensor.input[Vec[10], TensorDType.Float32.type]("a", vecF32)
      val b = Tensor.input[Vec[10], TensorDType.Float32.type]("b", vecF32)
      a - b
    }
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Sub(TensorExpr.Input("a", _), TensorExpr.Input("b", _)) => ()
      case other => fail(s"Expected Sub, got $other")

  // ============================================================================
  // MatMul
  // ============================================================================

  test("matmul"):
    val artifact = mlquote {
      val x = Tensor.input[Mat[16, 784], TensorDType.Float32.type]("x", matF32_16_784)
      val w = Tensor.parameter[Mat[784, 256], TensorDType.Float32.type]("w", matF32_784_256)
      x.matmul(w)
    }
    assertEquals(artifact.graph.inputs.size, 2)
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.MatMul(
            TensorExpr.Input("x", _),
            TensorExpr.Parameter("w", _, _),
            false,
            false
          ) =>
        ()
      case other => fail(s"Expected MatMul, got $other")

  // ============================================================================
  // Chained operations (MLP forward pass)
  // ============================================================================

  test("MLP forward pass: matmul → relu → matmul → softmax"):
    val artifact = mlquote {
      val x = Tensor.input[Mat[16, 784], TensorDType.Float32.type]("x", matF32_16_784)
      val w1 = Tensor.parameter[Mat[784, 256], TensorDType.Float32.type]("w1", matF32_784_256)
      val w2 = Tensor.parameter[Mat[256, 10], TensorDType.Float32.type]("w2", matF32_256_10)
      val h = x.matmul(w1)
      val a = h.relu
      val out = a.matmul(w2)
      out.softmax(-1)
    }
    assertEquals(artifact.graph.inputs.size, 3) // x, w1, w2
    // Verify the chain structure
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Softmax(
            TensorExpr.MatMul(
              TensorExpr.Relu(
                TensorExpr.MatMul(TensorExpr.Input("x", _), TensorExpr.Parameter("w1", _, _), _, _)
              ),
              TensorExpr.Parameter("w2", _, _),
              _,
              _
            ),
            -1
          ) =>
        () // ok — full chain verified
      case other => fail(s"Unexpected expression structure: $other")

  // ============================================================================
  // Optimizer integration: Dropout elimination
  // ============================================================================

  test("dropout elimination in inference mode"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      val d = x.dropout(0.5, false)
      d.relu
    }
    // The optimizer should eliminate dropout(training=false)
    // Result: Relu(Input("x", ...)) — dropout removed
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Relu(TensorExpr.Input("x", _)) => () // dropout eliminated!
      case other => fail(s"Expected Relu(Input(x)) after dropout elimination, got $other")

  test("dropout kept in training mode"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      val d = x.dropout(0.5, true)
      d.relu
    }
    // Training dropout should NOT be eliminated
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Relu(TensorExpr.Dropout(TensorExpr.Input("x", _), 0.5, true)) => ()
      case other => fail(s"Expected Relu(Dropout(x, 0.5, true)), got $other")

  // ============================================================================
  // Residual connection
  // ============================================================================

  test("residual connection: relu(x) + x"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.relu + x
    }
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Add(TensorExpr.Relu(TensorExpr.Input("x", _)), TensorExpr.Input("x", _)) =>
        () // residual: relu(x) + x verified structurally
      case other => fail(s"Expected Add(Relu(Input(x)), Input(x)), got $other")

  // ============================================================================
  // Softmax with axis
  // ============================================================================

  test("softmax with explicit axis"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.softmax(-1)
    }
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Softmax(_, -1) => ()
      case other                     => fail(s"Expected Softmax with axis -1, got $other")

  // ============================================================================
  // Activation functions
  // ============================================================================

  test("gelu with approximate"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.gelu(true)
    }
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Gelu(_, true) => ()
      case other                    => fail(s"Expected Gelu(approximate=true), got $other")

  test("leakyRelu with alpha"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.leakyRelu(0.2)
    }
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.LeakyRelu(_, alpha) => assertEquals(alpha, 0.2)
      case other                          => fail(s"Expected LeakyRelu, got $other")

  // ============================================================================
  // Graph metadata
  // ============================================================================

  test("artifact metadata is populated"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.relu
    }
    assertEquals(artifact.formatVersion, protocatalyst.ml.artifact.MLArtifactVersion.current)
    assertEquals(artifact.protocatalystVersion, "0.1.0-SNAPSHOT")
    assert(artifact.compiledAt > 0L)
    assertEquals(artifact.sourceInfo, None)

  // ============================================================================
  // Named nodes from val bindings
  // ============================================================================

  test("val bindings become named nodes"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      val activated = x.relu
      val result = activated.sigmoid
      result
    }
    // Should have named nodes "activated" and "result"
    val nodeNames = artifact.graph.nodes.map(_.name)
    assert(nodeNames.contains("activated"), s"Expected 'activated' node, got: $nodeNames")
    assert(nodeNames.contains("result"), s"Expected 'result' node, got: $nodeNames")

  // ============================================================================
  // Optimizer: Identity elimination
  // ============================================================================

  test("identity transpose elimination"):
    val artifact = mlquote {
      val x = Tensor
        .input[Mat[3, 4], TensorDType.Float32.type]("x", tensorType(TensorDType.Float32, 3, 4))
      val t1 = x.t
      t1.t
    }
    // Transpose([1,0]) followed by Transpose([1,0]) = identity
    // TransposeFusion composes the permutations and eliminates the double transpose
    val outputNode = artifact.graph.nodes.last
    outputNode.expr match
      case TensorExpr.Input("x", _) =>
        () // double transpose eliminated by TransposeFusion
      case other => fail(s"Expected Input(x) after double transpose elimination, got $other")

  // ============================================================================
  // Unary operations
  // ============================================================================

  test("sqrt operation"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.sqrt
    }
    artifact.graph.nodes.last.expr match
      case TensorExpr.Sqrt(TensorExpr.Input("x", _)) => ()
      case other                                     => fail(s"Expected Sqrt(Input(x)), got $other")

  test("neg operation"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.neg
    }
    artifact.graph.nodes.last.expr match
      case TensorExpr.Neg(TensorExpr.Input("x", _)) => ()
      case other                                    => fail(s"Expected Neg(Input(x)), got $other")

  test("exp operation"):
    val artifact = mlquote {
      val x = Tensor.input[Vec[10], TensorDType.Float32.type]("x", vecF32)
      x.exp
    }
    artifact.graph.nodes.last.expr match
      case TensorExpr.Exp(TensorExpr.Input("x", _)) => ()
      case other                                    => fail(s"Expected Exp(Input(x)), got $other")
