package protocatalyst.ml.dsl

import scala.quoted._

import protocatalyst.ml.artifact._
import protocatalyst.ml.expr.TensorExpr
import protocatalyst.ml.graph._
import protocatalyst.ml.macros.MLProtoLiftables.given
import protocatalyst.ml.optimizer.MLOptimizer
import protocatalyst.ml.types._

/** Compile-time quotation macro for ML tensor expressions.
  *
  * Captures Tensor DSL expressions at compile time, transforms them to a TensorExpr DAG, optimizes
  * using MLOptimizer, and embeds the result as a CompiledMLArtifact bytecode constant.
  */
object MLQuoteMacro:

  /** Quote a tensor expression, optimize at compile time, and embed as a CompiledMLArtifact. */
  inline def mlquote[S <: Tuple, D <: TensorDType](
      inline body: Tensor[S, D]
  ): CompiledMLArtifact =
    ${ mlquoteImpl[S, D]('body) }

  private def mlquoteImpl[S <: Tuple: Type, D <: TensorDType: Type](
      bodyExpr: Expr[Tensor[S, D]]
  )(using Quotes): Expr[CompiledMLArtifact] =
    import quotes.reflect.*

    // Mutable state for collecting graph structure
    val graphInputs = collection.mutable.LinkedHashMap[String, TensorType]()
    val graphNodes = collection.mutable.ArrayBuffer[(String, TensorExpr, TensorType)]()

    type Info = (TensorExpr, TensorType)
    type Bindings = Map[String, Info]

    // ==================================================================
    // Type extraction from Scala type parameters
    // ==================================================================

    def extractDims(tpr: TypeRepr): Either[String, Vector[Int]] =
      val d = tpr.dealias
      d match
        case AppliedType(_, List(head, tail)) =>
          head.dealias match
            case ConstantType(IntConstant(n)) =>
              extractDims(tail).map(n +: _)
            case _ =>
              Left(s"Shape dimension must be a literal Int type, got: ${head.show}")
        case t if t =:= TypeRepr.of[EmptyTuple] =>
          Right(Vector.empty)
        case _ =>
          Left(s"Cannot extract shape from type: ${d.show}")

    val dtypeNames: Map[String, TensorDType] = Map(
      "Float16" -> TensorDType.Float16,
      "Float32" -> TensorDType.Float32,
      "Float64" -> TensorDType.Float64,
      "BFloat16" -> TensorDType.BFloat16,
      "Int8" -> TensorDType.Int8,
      "Int16" -> TensorDType.Int16,
      "Int32" -> TensorDType.Int32,
      "Int64" -> TensorDType.Int64,
      "UInt8" -> TensorDType.UInt8,
      "Bool" -> TensorDType.Bool,
      "Complex64" -> TensorDType.Complex64,
      "Complex128" -> TensorDType.Complex128
    )

    def dtypeBySymbolOwner(sym: Symbol): Option[TensorDType] =
      if sym.owner.fullName.contains("TensorDType") then dtypeNames.get(sym.name)
      else None

    def extractDType(tpr: TypeRepr): Either[String, TensorDType] =
      val d = tpr.dealias
      val candidates: Seq[(TypeRepr, TensorDType)] = Seq(
        TypeRepr.of[TensorDType.Float16.type] -> TensorDType.Float16,
        TypeRepr.of[TensorDType.Float32.type] -> TensorDType.Float32,
        TypeRepr.of[TensorDType.Float64.type] -> TensorDType.Float64,
        TypeRepr.of[TensorDType.BFloat16.type] -> TensorDType.BFloat16,
        TypeRepr.of[TensorDType.Int8.type] -> TensorDType.Int8,
        TypeRepr.of[TensorDType.Int16.type] -> TensorDType.Int16,
        TypeRepr.of[TensorDType.Int32.type] -> TensorDType.Int32,
        TypeRepr.of[TensorDType.Int64.type] -> TensorDType.Int64,
        TypeRepr.of[TensorDType.UInt8.type] -> TensorDType.UInt8,
        TypeRepr.of[TensorDType.Bool.type] -> TensorDType.Bool,
        TypeRepr.of[TensorDType.Complex64.type] -> TensorDType.Complex64,
        TypeRepr.of[TensorDType.Complex128.type] -> TensorDType.Complex128
      )
      // Direct subtype check (handles TensorDType.Float32.type directly)
      candidates
        .find((tr, _) => d <:< tr)
        .map(_._2)
        .orElse {
          // TermRef fallback: resolve val aliases like `val f32 = TensorDType.Float32`
          d match
            case tr @ TermRef(_, _) if tr.widen <:< TypeRepr.of[TensorDType] =>
              val sym = tr.termSymbol
              // If the symbol itself is a TensorDType enum value, match by name
              dtypeBySymbolOwner(sym)
                .orElse {
                  // For val aliases, check if the widened singleton type matches
                  val w = tr.widenTermRefByName
                  if w != d then candidates.find((cand, _) => w <:< cand).map(_._2)
                  else None
                }
            case _ => None
        } match
        case Some(dt) => Right(dt)
        case None     => Left(s"Cannot extract TensorDType from type: ${d.show}")

    def makeTensorType(shapeTpr: TypeRepr, dtypeTpr: TypeRepr): Either[String, TensorType] =
      for
        dims <- extractDims(shapeTpr)
        dtype <- extractDType(dtypeTpr)
      yield TensorType(dtype, dims.map(Dim(_)))

    // ==================================================================
    // Literal extraction helpers
    // ==================================================================

    def extractStringLit(term: Term): Either[String, String] = term match
      case Literal(StringConstant(s)) => Right(s)
      case Inlined(_, _, inner)       => extractStringLit(inner)
      case Typed(inner, _)            => extractStringLit(inner)
      case _                          => Left(s"Expected string literal, got: ${term.show}")

    def extractIntLit(term: Term): Either[String, Int] = term match
      case Literal(IntConstant(n)) => Right(n)
      case NamedArg(_, inner)      => extractIntLit(inner)
      case Inlined(_, _, inner)    => extractIntLit(inner)
      case Typed(inner, _)         => extractIntLit(inner)
      case _                       => Left(s"Expected int literal, got: ${term.show}")

    def extractDoubleLit(term: Term): Either[String, Double] = term match
      case Literal(DoubleConstant(d)) => Right(d)
      case Literal(IntConstant(n))    => Right(n.toDouble)
      case Literal(FloatConstant(f))  => Right(f.toDouble)
      case NamedArg(_, inner)         => extractDoubleLit(inner)
      case Inlined(_, _, inner)       => extractDoubleLit(inner)
      case Typed(inner, _)            => extractDoubleLit(inner)
      case _                          => Left(s"Expected double literal, got: ${term.show}")

    def extractBoolLit(term: Term): Either[String, Boolean] = term match
      case Literal(BooleanConstant(b)) => Right(b)
      case NamedArg(_, inner)          => extractBoolLit(inner)
      case Inlined(_, _, inner)        => extractBoolLit(inner)
      case Typed(inner, _)             => extractBoolLit(inner)
      case _                           => Left(s"Expected boolean literal, got: ${term.show}")

    def extractIntVector(term: Term): Either[String, Vector[Int]] = term match
      case Apply(TypeApply(Select(_, "apply"), _), args) => traverseInts(args)
      case Apply(Select(_, "apply"), args)               => traverseInts(args)
      case Inlined(_, _, inner)                          => extractIntVector(inner)
      case Typed(inner, _)                               => extractIntVector(inner)
      case _ => Left(s"Expected Vector[Int] literal, got: ${term.show}")

    def traverseInts(args: List[Term]): Either[String, Vector[Int]] =
      args.foldLeft(Right(Vector.empty[Int]): Either[String, Vector[Int]]) {
        case (Right(acc), arg) => extractIntLit(arg).map(acc :+ _)
        case (left, _)         => left
      }

    def extractReduction(term: Term): Either[String, Reduction] = term match
      case Select(_, "Mean")    => Right(Reduction.Mean)
      case Select(_, "Sum")     => Right(Reduction.Sum)
      case Select(_, "None")    => Right(Reduction.None)
      case Ident("Mean")        => Right(Reduction.Mean)
      case Ident("Sum")         => Right(Reduction.Sum)
      case Inlined(_, _, inner) => extractReduction(inner)
      case Typed(inner, _)      => extractReduction(inner)
      case _                    => Left(s"Expected Reduction value, got: ${term.show}")

    // ==================================================================
    // Initializer extraction (best-effort)
    // ==================================================================

    def extractOptInitializer(args: List[Term]): Option[Initializer] =
      args.lift(2).flatMap { term =>
        val unwrapped = unwrapAll(term)
        unwrapped match
          case Ident("None") | Select(_, "None") => None
          case Apply(_, List(inner))             => extractInitValue(inner).toOption
          case _                                 => None
      }

    def extractInitValue(term: Term): Either[String, Initializer] =
      val t = unwrapAll(term)
      t match
        case Select(_, "Zeros") | Ident("Zeros") => Right(Initializer.Zeros)
        case Select(_, "Ones") | Ident("Ones")   => Right(Initializer.Ones)
        case Apply(Select(_, "Xavier"), List(g)) => extractDoubleLit(g).map(Initializer.Xavier(_))
        case Apply(Select(_, "Kaiming"), List(m, nl)) =>
          for mode <- extractStringLit(m); nonlin <- extractStringLit(nl)
          yield Initializer.Kaiming(mode, nonlin)
        case Apply(Select(_, "Normal"), List(m, s)) =>
          for mean <- extractDoubleLit(m); std <- extractDoubleLit(s)
          yield Initializer.Normal(mean, std)
        case Apply(Select(_, "Uniform"), List(lo, hi)) =>
          for l <- extractDoubleLit(lo); h <- extractDoubleLit(hi)
          yield Initializer.Uniform(l, h)
        case _ => Left(s"Unknown Initializer: ${t.show}")

    // ==================================================================
    // Helper utilities
    // ==================================================================

    def selectName(term: Term): String = term match
      case Select(_, name) => name
      case Ident(name)     => name
      case _               => ""

    def unwrapAll(term: Term): Term = term match
      case Inlined(_, _, inner) => unwrapAll(inner)
      case Typed(inner, _)      => unwrapAll(inner)
      case NamedArg(_, inner)   => unwrapAll(inner)
      case other                => other

    def computeMatMulType(left: TensorType, right: TensorType): TensorType =
      if left.shape.size >= 2 && right.shape.size >= 2 then
        left.copy(shape = left.shape.init :+ right.shape.last)
      else left

    def computeTranspose2DType(tt: TensorType): TensorType =
      if tt.shape.size == 2 then tt.copy(shape = tt.shape.reverse) else tt

    def extractOptTensorArg(term: Term, bindings: Bindings): Option[TensorExpr] =
      val t = unwrapAll(term)
      t match
        case Ident("None") | Select(_, "None") => None
        case Apply(_, List(inner))             => extract(inner, bindings).toOption.map(_._1)
        case _                                 => extract(t, bindings).toOption.map(_._1)

    def extractOptDouble(term: Term): Option[Double] =
      val t = unwrapAll(term)
      t match
        case Ident("None") | Select(_, "None") => None
        case Apply(_, List(inner))             => extractDoubleLit(inner).toOption
        case other                             => extractDoubleLit(other).toOption

    // ==================================================================
    // Extraction helpers
    // ==================================================================

    def extractUnary(
        child: Term,
        bindings: Bindings,
        op: TensorExpr => TensorExpr
    ): Either[String, Info] =
      extract(child, bindings).map { (e, tt) => (op(e), tt) }

    def extractBinOp(
        left: Term,
        right: Term,
        bindings: Bindings,
        op: (TensorExpr, TensorExpr) => TensorExpr
    ): Either[String, Info] =
      for
        (le, lt) <- extract(left, bindings)
        (re, _) <- extract(right, bindings)
      yield (op(le, re), lt)

    def extractCrossEntropyLoss(args: List[Term], bindings: Bindings): Either[String, Info] =
      for
        (ie, it) <- extract(args(0), bindings)
        (te, _) <- extract(args(1), bindings)
      yield
        val reduction =
          args.lift(2).flatMap(a => extractReduction(a).toOption).getOrElse(Reduction.Mean)
        val scalarTt = TensorType(it.dtype, Vector.empty)
        (TensorExpr.CrossEntropyLoss(ie, te, None, reduction), scalarTt)

    def extractMSELoss(args: List[Term], bindings: Bindings): Either[String, Info] =
      for
        (ie, it) <- extract(args(0), bindings)
        (te, _) <- extract(args(1), bindings)
      yield
        val reduction =
          args.lift(2).flatMap(a => extractReduction(a).toOption).getOrElse(Reduction.Mean)
        val scalarTt = TensorType(it.dtype, Vector.empty)
        (TensorExpr.MSELoss(ie, te, reduction), scalarTt)

    // ==================================================================
    // Block processing
    // ==================================================================

    def processBlock(stats: List[Statement], expr: Term, bindings: Bindings): Either[String, Info] =
      stats
        .foldLeft(Right(bindings): Either[String, Bindings]) {
          case (Left(err), _)                                       => Left(err)
          case (Right(currentBindings), ValDef(name, _, Some(rhs))) =>
            extract(rhs, currentBindings) match
              case Left(err)                      => Left(err)
              case Right(info @ (tensorExpr, tt)) =>
                tensorExpr match
                  case _: TensorExpr.Input     => ()
                  case _: TensorExpr.Parameter => ()
                  case _: TensorExpr.Constant  => ()
                  case _                       => graphNodes += ((name, tensorExpr, tt))
                Right(currentBindings + (name -> info))
          case (_, stat) =>
            Left(s"Unsupported statement in mlquote block: ${stat.show}")
        }
        .flatMap(finalBindings => extract(expr, finalBindings))

    // ==================================================================
    // Main AST walker
    // ==================================================================

    def extract(term: Term, bindings: Bindings): Either[String, Info] =
      term match
        // --- Structural wrappers ---
        case Inlined(_, _, inner) => extract(inner, bindings)
        case Typed(inner, _)      => extract(inner, bindings)
        case Block(stats, expr)   => processBlock(stats, expr, bindings)

        // --- Val reference ---
        case Ident(name) if bindings.contains(name) => Right(bindings(name))

        // --- Tensor.input[S, D](name, tt) ---
        case Apply(TypeApply(sel, typeArgs), args)
            if selectName(sel) == "input" && typeArgs.size >= 2 && args.nonEmpty =>
          for
            name <- extractStringLit(args.head)
            tt <- makeTensorType(typeArgs(0).tpe, typeArgs(1).tpe)
          yield
            val expr = TensorExpr.Input(name, tt)
            graphInputs(name) = tt
            (expr, tt)

        // --- Tensor.parameter[S, D](name, tt, init?) ---
        case Apply(TypeApply(sel, typeArgs), args)
            if selectName(sel) == "parameter" && typeArgs.size >= 2 && args.nonEmpty =>
          for
            name <- extractStringLit(args.head)
            tt <- makeTensorType(typeArgs(0).tpe, typeArgs(1).tpe)
          yield
            val init = extractOptInitializer(args)
            val expr = TensorExpr.Parameter(name, tt, init)
            graphInputs(name) = tt
            (expr, tt)

        // --- Shape-preserving unary ops (no args) ---
        case Select(child, "relu")      => extractUnary(child, bindings, TensorExpr.Relu(_))
        case Select(child, "sigmoid")   => extractUnary(child, bindings, TensorExpr.Sigmoid(_))
        case Select(child, "tanh")      => extractUnary(child, bindings, TensorExpr.Tanh(_))
        case Select(child, "silu")      => extractUnary(child, bindings, TensorExpr.Silu(_))
        case Select(child, "hardSwish") => extractUnary(child, bindings, TensorExpr.HardSwish(_))
        case Select(child, "sqrt")      => extractUnary(child, bindings, TensorExpr.Sqrt(_))
        case Select(child, "neg")       => extractUnary(child, bindings, TensorExpr.Neg(_))
        case Select(child, "abs")       => extractUnary(child, bindings, TensorExpr.Abs(_))
        case Select(child, "exp")       => extractUnary(child, bindings, TensorExpr.Exp(_))
        case Select(child, "log")       => extractUnary(child, bindings, TensorExpr.Log(_))

        // --- tensor.matmul[S2](other) ---
        case Apply(TypeApply(Select(child, "matmul"), _), List(other)) =>
          for
            (le, lt) <- extract(child, bindings)
            (re, rt) <- extract(other, bindings)
          yield (TensorExpr.MatMul(le, re, false, false), computeMatMulType(lt, rt))

        case Apply(Select(child, "matmul"), List(other)) =>
          for
            (le, lt) <- extract(child, bindings)
            (re, rt) <- extract(other, bindings)
          yield (TensorExpr.MatMul(le, re, false, false), computeMatMulType(lt, rt))

        // --- tensor.t (transpose 2D, has using evidence param) ---
        case Apply(Select(child, "t"), _) =>
          extract(child, bindings).map { (e, tt) =>
            (TensorExpr.Transpose(e, Vector(1, 0)), computeTranspose2DType(tt))
          }

        // --- Binary arithmetic: +, -, *, / ---
        case Apply(Select(child, "+" | "$plus"), List(other)) =>
          extractBinOp(child, other, bindings, TensorExpr.Add(_, _))
        case Apply(Select(child, "-" | "$minus"), List(other)) =>
          extractBinOp(child, other, bindings, TensorExpr.Sub(_, _))
        case Apply(Select(child, "*" | "$times"), List(other)) =>
          extractBinOp(child, other, bindings, TensorExpr.Mul(_, _))
        case Apply(Select(child, "/" | "$div"), List(other)) =>
          extractBinOp(child, other, bindings, TensorExpr.Div(_, _))

        // --- Unary ops with arguments ---
        case Apply(Select(child, "softmax"), args) =>
          for
            (e, tt) <- extract(child, bindings)
            axis <- extractIntLit(unwrapAll(args.headOption.getOrElse(Literal(IntConstant(-1)))))
          yield (TensorExpr.Softmax(e, axis), tt)

        case Apply(Select(child, "logSoftmax"), args) =>
          for
            (e, tt) <- extract(child, bindings)
            axis <- extractIntLit(unwrapAll(args.headOption.getOrElse(Literal(IntConstant(-1)))))
          yield (TensorExpr.LogSoftmax(e, axis), tt)

        case Apply(Select(child, "gelu"), args) =>
          for
            (e, tt) <- extract(child, bindings)
            approx <- extractBoolLit(
              unwrapAll(args.headOption.getOrElse(Literal(BooleanConstant(false))))
            )
          yield (TensorExpr.Gelu(e, approx), tt)

        case Apply(Select(child, "leakyRelu"), args) =>
          for
            (e, tt) <- extract(child, bindings)
            alpha <- extractDoubleLit(
              unwrapAll(args.headOption.getOrElse(Literal(DoubleConstant(0.01))))
            )
          yield (TensorExpr.LeakyRelu(e, alpha), tt)

        case Apply(Select(child, "elu"), args) =>
          for
            (e, tt) <- extract(child, bindings)
            alpha <- extractDoubleLit(
              unwrapAll(args.headOption.getOrElse(Literal(DoubleConstant(1.0))))
            )
          yield (TensorExpr.Elu(e, alpha), tt)

        case Apply(Select(child, "dropout"), args) if args.size >= 2 =>
          for
            (e, tt) <- extract(child, bindings)
            ratio <- extractDoubleLit(unwrapAll(args(0)))
            training <- extractBoolLit(unwrapAll(args(1)))
          yield (TensorExpr.Dropout(e, ratio, training), tt)

        case Apply(Select(child, "flatten"), args) =>
          for
            (e, tt) <- extract(child, bindings)
            axis <- extractIntLit(unwrapAll(args.headOption.getOrElse(Literal(IntConstant(1)))))
          yield (TensorExpr.Flatten(e, axis), tt)

        case Apply(Select(child, "layerNorm"), args) if args.nonEmpty =>
          for
            (e, tt) <- extract(child, bindings)
            normShape <- extractIntVector(unwrapAll(args(0)))
            eps <- args.lift(1).map(a => extractDoubleLit(unwrapAll(a))).getOrElse(Right(1e-5))
          yield (TensorExpr.LayerNorm(e, normShape, None, None, eps), tt)

        // --- tensor.reshape[S2](shape) ---
        case Apply(TypeApply(Select(child, "reshape"), _), List(shapeExpr)) =>
          for
            (e, tt) <- extract(child, bindings)
            shape <- extractIntVector(shapeExpr)
          yield (TensorExpr.Reshape(e, shape), tt.copy(shape = shape.map(Dim(_))))

        case Apply(Select(child, "reshape"), List(shapeExpr)) =>
          for
            (e, tt) <- extract(child, bindings)
            shape <- extractIntVector(shapeExpr)
          yield (TensorExpr.Reshape(e, shape), tt.copy(shape = shape.map(Dim(_))))

        // --- tensor.linear(weight, bias?) ---
        case Apply(Select(child, "linear"), args) if args.nonEmpty =>
          for
            (ie, it) <- extract(child, bindings)
            (we, _) <- extract(args(0), bindings)
          yield
            val bias = args.lift(1).flatMap(a => extractOptTensorArg(a, bindings))
            (TensorExpr.Linear(ie, we, bias), it)

        // --- tensor.clip(min, max) ---
        case Apply(Select(child, "clip"), args) =>
          for (e, tt) <- extract(child, bindings)
          yield
            val min = args.headOption.flatMap(extractOptDouble)
            val max = args.lift(1).flatMap(extractOptDouble)
            (TensorExpr.Clip(e, min, max), tt)

        // --- Free function: crossEntropyLoss ---
        case Apply(TypeApply(sel, _), args)
            if selectName(sel) == "crossEntropyLoss" && args.size >= 2 =>
          extractCrossEntropyLoss(args, bindings)
        case Apply(sel, args) if selectName(sel) == "crossEntropyLoss" && args.size >= 2 =>
          extractCrossEntropyLoss(args, bindings)

        // --- Free function: mseLoss ---
        case Apply(TypeApply(sel, _), args) if selectName(sel) == "mseLoss" && args.size >= 2 =>
          extractMSELoss(args, bindings)
        case Apply(sel, args) if selectName(sel) == "mseLoss" && args.size >= 2 =>
          extractMSELoss(args, bindings)

        case other =>
          Left(s"Unsupported expression in mlquote: ${other.show}")

    // ==================================================================
    // Run extraction and build artifact
    // ==================================================================

    extract(bodyExpr.asTerm, Map.empty) match
      case Left(err) =>
        report.errorAndAbort(s"mlquote: $err")
      case Right((expr, tt)) =>
        // Add final expression as output node if not already recorded
        expr match
          case _: TensorExpr.Input | _: TensorExpr.Parameter | _: TensorExpr.Constant => ()
          case _                                                                      =>
            if !graphNodes.exists((_, e, _) => e eq expr) then graphNodes += (("output", expr, tt))

        val graph = ComputeGraph(
          name = "mlquote",
          inputs = graphInputs.toVector.map((n, t) => GraphIO(n, t)),
          outputs = Vector(GraphIO("output", tt)),
          nodes = graphNodes.toVector.map((n, e, t) => NamedNode(n, e, t))
        )

        val optimized = MLOptimizer.optimize(graph)

        '{
          CompiledMLArtifact(
            formatVersion = MLArtifactVersion.current,
            protocatalystVersion = "0.1.0-SNAPSHOT",
            compiledAt = System.currentTimeMillis(),
            contentHash = ${ Expr(optimized.hashCode().toLong) },
            graph = ${ Expr(optimized) },
            sourceInfo = None
          )
        }
