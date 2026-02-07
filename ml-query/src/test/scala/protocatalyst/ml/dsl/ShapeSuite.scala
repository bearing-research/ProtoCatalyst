package protocatalyst.ml.dsl

import protocatalyst.ml.dsl.Shape._

/** Tests for compile-time shape match types.
  *
  * These tests verify that shape computations work correctly by summoning type equality evidence.
  * If a match type doesn't reduce correctly, the test won't compile.
  */
class ShapeSuite extends munit.FunSuite:

  // ============================================================================
  // Shape aliases
  // ============================================================================

  test("Scalar is EmptyTuple"):
    summon[Scalar =:= EmptyTuple]

  test("Vec[10] is 10 *: EmptyTuple"):
    summon[Vec[10] =:= (10 *: EmptyTuple)]

  test("Mat[3, 4] is 3 *: 4 *: EmptyTuple"):
    summon[Mat[3, 4] =:= (3 *: 4 *: EmptyTuple)]

  test("Tensor3D[2, 3, 4] is 2 *: 3 *: 4 *: EmptyTuple"):
    summon[Tensor3D[2, 3, 4] =:= (2 *: 3 *: 4 *: EmptyTuple)]

  test("Tensor4D[1, 3, 224, 224]"):
    summon[Tensor4D[1, 3, 224, 224] =:= (1 *: 3 *: 224 *: 224 *: EmptyTuple)]

  // ============================================================================
  // MatMulShape
  // ============================================================================

  test("MatMulShape: (3, 4) x (4, 5) = (3, 5)"):
    summon[MatMulShape[Mat[3, 4], Mat[4, 5]] =:= Mat[3, 5]]

  test("MatMulShape: (16, 784) x (784, 256) = (16, 256)"):
    summon[MatMulShape[Mat[16, 784], Mat[784, 256]] =:= Mat[16, 256]]

  test("MatMulShape: (1, 1) x (1, 1) = (1, 1)"):
    summon[MatMulShape[Mat[1, 1], Mat[1, 1]] =:= Mat[1, 1]]

  // Note: MatMulShape[Mat[3, 4], Mat[7, 5]] would not compile (4 != 7)

  // ============================================================================
  // BroadcastShape
  // ============================================================================

  test("BroadcastShape: same shapes"):
    summon[BroadcastShape[Vec[10], Vec[10]] =:= Vec[10]]

  test("BroadcastShape: scalar broadcast"):
    summon[BroadcastShape[EmptyTuple, Vec[10]] =:= Vec[10]]
    summon[BroadcastShape[Vec[10], EmptyTuple] =:= Vec[10]]

  test("BroadcastShape: dim 1 broadcasts"):
    summon[BroadcastShape[Vec[1], Vec[10]] =:= Vec[10]]
    summon[BroadcastShape[Vec[10], Vec[1]] =:= Vec[10]]

  test("BroadcastShape: 2D same shapes"):
    summon[BroadcastShape[Mat[3, 4], Mat[3, 4]] =:= Mat[3, 4]]

  test("BroadcastShape: 2D with broadcast dim"):
    summon[BroadcastShape[Mat[3, 1], Mat[3, 4]] =:= Mat[3, 4]]
    summon[BroadcastShape[Mat[1, 4], Mat[3, 4]] =:= Mat[3, 4]]

  // Note: BroadcastShape[Vec[3], Vec[5]] would not compile (3 != 5, neither is 1)

  // ============================================================================
  // BroadcastDim
  // ============================================================================

  test("BroadcastDim: 1 broadcasts to N"):
    summon[BroadcastDim[1, 10] =:= 10]
    summon[BroadcastDim[10, 1] =:= 10]

  test("BroadcastDim: same dimension"):
    summon[BroadcastDim[5, 5] =:= 5]
    summon[BroadcastDim[1, 1] =:= 1]

  // Note: BroadcastDim[3, 5] would not compile

  // ============================================================================
  // Transpose2D
  // ============================================================================

  test("Transpose2D: (3, 4) → (4, 3)"):
    summon[Transpose2D[Mat[3, 4]] =:= Mat[4, 3]]

  test("Transpose2D: (784, 256) → (256, 784)"):
    summon[Transpose2D[Mat[784, 256]] =:= Mat[256, 784]]

  test("Transpose2D: (1, 1) → (1, 1)"):
    summon[Transpose2D[Mat[1, 1]] =:= Mat[1, 1]]

  // ============================================================================
  // Head, Tail, Last
  // ============================================================================

  test("Head of Vec[10] is 10"):
    summon[Head[Vec[10]] =:= 10]

  test("Head of Mat[3, 4] is 3"):
    summon[Head[Mat[3, 4]] =:= 3]

  test("Tail of Mat[3, 4] is Vec[4]"):
    summon[Tail[Mat[3, 4]] =:= Vec[4]]

  test("Last of Vec[10] is 10"):
    summon[Last[Vec[10]] =:= 10]

  test("Last of Mat[3, 4] is 4"):
    summon[Last[Mat[3, 4]] =:= 4]

  test("Last of Tensor3D[2, 3, 4] is 4"):
    summon[Last[Tensor3D[2, 3, 4]] =:= 4]

  // ============================================================================
  // Rank
  // ============================================================================

  test("Rank of Scalar is 0"):
    summon[Rank[Scalar] =:= 0]

  test("Rank of Vec[10] is 1"):
    summon[Rank[Vec[10]] =:= 1]

  test("Rank of Mat[3, 4] is 2"):
    summon[Rank[Mat[3, 4]] =:= 2]

  test("Rank of Tensor3D[2, 3, 4] is 3"):
    summon[Rank[Tensor3D[2, 3, 4]] =:= 3]

  test("Rank of Tensor4D[1, 3, 224, 224] is 4"):
    summon[Rank[Tensor4D[1, 3, 224, 224]] =:= 4]
