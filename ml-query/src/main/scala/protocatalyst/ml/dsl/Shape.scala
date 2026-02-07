package protocatalyst.ml.dsl

/** Compile-time shape types for tensor operations.
  *
  * Uses Scala 3 literal types and match types for static shape checking. When shapes are
  * incompatible, the match type does not reduce, causing a compile error at the call site.
  *
  * Example:
  * {{{
  * // Compiles: inner dimensions match (4 == 4)
  * summon[MatMulShape[Mat[3, 4], Mat[4, 5]] =:= Mat[3, 5]]
  *
  * // Does not compile: inner dimensions mismatch (4 != 7)
  * summon[MatMulShape[Mat[3, 4], Mat[7, 5]] =:= Mat[3, 5]] // error!
  * }}}
  */
object Shape:

  // ============================================================================
  // Shape aliases
  // ============================================================================

  /** Scalar (rank 0) */
  type Scalar = EmptyTuple

  /** Vector (rank 1) */
  type Vec[N <: Int] = N *: EmptyTuple

  /** Matrix (rank 2) */
  type Mat[M <: Int, N <: Int] = M *: N *: EmptyTuple

  /** 3D tensor */
  type Tensor3D[A <: Int, B <: Int, C <: Int] = A *: B *: C *: EmptyTuple

  /** 4D tensor (e.g. NCHW images) */
  type Tensor4D[A <: Int, B <: Int, C <: Int, D <: Int] = A *: B *: C *: D *: EmptyTuple

  // ============================================================================
  // Type-level equality check
  // ============================================================================

  /** Returns R if A and B are the same type, otherwise does not reduce (compile error). */
  type IfSame[A, B, R] = B match
    case A => R

  // ============================================================================
  // MatMul shape checking
  // ============================================================================

  /** MatMul: (M, K) x (K, N) → (M, N). Fails to compile if inner dimensions don't match.
    *
    * Uses IfSame to check k1 == k2 without duplicate pattern variables.
    */
  type MatMulShape[L <: Tuple, R <: Tuple] = (L, R) match
    case (m *: k1 *: EmptyTuple, k2 *: n *: EmptyTuple) =>
      IfSame[k1, k2, m *: n *: EmptyTuple]

  // ============================================================================
  // Broadcast shape checking
  // ============================================================================

  /** Element-wise broadcast: aligns shapes pairwise (same-rank broadcasting).
    *
    * Note: This only reduces with concrete types, not abstract type parameters. For use in match
    * type positions and call-site type checking, not method body type inference.
    */
  type BroadcastShape[L <: Tuple, R <: Tuple] = (L, R) match
    case (EmptyTuple, r)      => r
    case (l, EmptyTuple)      => l
    case (lh *: lt, rh *: rt) => BroadcastDim[lh, rh] *: BroadcastShape[lt, rt]

  /** Broadcast a single dimension: 1→N, N→N, else compile error. */
  type BroadcastDim[A <: Int, B <: Int] <: Int = A match
    case 1 => B
    case _ => BroadcastDimNonOne[A, B]

  /** Helper: A is not 1, check if B is 1 or B equals A. */
  type BroadcastDimNonOne[A <: Int, B <: Int] <: Int = B match
    case 1 => A
    case A => A

  // ============================================================================
  // Transpose shapes
  // ============================================================================

  /** Transpose 2D: (M, N) → (N, M) */
  type Transpose2D[S <: Tuple] = S match
    case m *: n *: EmptyTuple => n *: m *: EmptyTuple

  // ============================================================================
  // Shape accessors
  // ============================================================================

  /** Head dimension of a shape (first element). */
  type Head[S <: Tuple] = S match
    case h *: _ => h

  /** Tail of a shape (all dimensions except first). */
  type Tail[S <: Tuple] <: Tuple = S match
    case _ *: t => t

  /** Last dimension of a shape. */
  type Last[S <: Tuple] = S match
    case h *: EmptyTuple => h
    case _ *: t          => Last[t]

  /** Rank (number of dimensions) of a shape. */
  type Rank[S <: Tuple] <: Int = S match
    case EmptyTuple => 0
    case _ *: t     => compiletime.ops.int.S[Rank[t]]
