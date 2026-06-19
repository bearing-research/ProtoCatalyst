package protocatalyst.encoder.spark

import scala.compiletime.*
import scala.deriving.Mirror
import scala.reflect.{ClassTag, classTag}

import org.apache.spark.sql.catalyst.encoders.{AgnosticEncoder, Codec}
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.*
import org.apache.spark.sql.types.{DecimalType, Metadata}

/** Single-pass compile-time derivation of Spark's `AgnosticEncoder[T]`, directly from a Scala 3
  * `Mirror` — the form proposed for upstream Spark in REPORT §11b ("one layer, not two").
  *
  * In this repo the production path is `AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[T])`:
  * a `Mirror` walk that builds the engine-independent `ProtoEncoder`/`ProtoType` IR, then a runtime
  * bridge that lowers it to Spark's `AgnosticEncoder`. The two layers exist only because `ProtoEncoder`
  * also targets non-Spark backends; the bridge's whole job is re-splitting what `ProtoType` normalized
  * away (`BigInt`/`BigDecimal`, `Array`/`Seq`, `UUID`/`String`) and restoring Spark's nullability rule.
  *
  * Inside Spark there is no second backend and no second IR — `AgnosticEncoder` already *is* Spark's
  * reflection-free encoder description. So the upstream `deriveAgnosticEncoder[T]` is a *single* macro
  * that emits `AgnosticEncoder` nodes directly, never discarding the information the bridge has to
  * recover. This object is exactly that single pass — the artifact a `spark-sql-encoder-3` module
  * would ship in place of `ScalaReflection.encoderFor`.
  *
  * It produces structurally identical `AgnosticEncoder`s to the bridge (and therefore to Spark's
  * reflective `encoderFor`); `AgnosticDerivationSpec` asserts that against the same §8 goldens.
  * One upstream-only improvement: a data-carrying ADT is rejected at **compile time**
  * (`scala.compiletime.error`), where the runtime bridge can only throw at invocation.
  *
  * To upstream: this is the file you take. Change only its `package` line to
  * `org.apache.spark.sql.catalyst.encoders` and follow `docs/scala3-encoder/MIGRATION.md`. There are no
  * `protocatalyst.*` dependencies; a build guard (`encoderSpark` `sourceGenerator`) compiles this file
  * under that exact package on every build to keep that true.
  */
object AgnosticDerivation:

  /** Derive `AgnosticEncoder[T]` for a product (case class / tuple) or sum (Scala 3 enum) from its
    * `Mirror` — no `TypeTag`, no reflection. Leaf types resolve through the `given`s below; this is
    * the entry the upstream `ExpressionEncoder.apply[T]()` would call (`encoderFor`'s replacement). */
  inline def deriveAgnosticEncoder[T](using m: Mirror.Of[T]): AgnosticEncoder[T] =
    inline m match
      case p: Mirror.ProductOf[T] => deriveProduct[T](p, summonInline[ClassTag[T]])
      case s: Mirror.SumOf[T]     => deriveSum[T](s, summonInline[ClassTag[T]])

  /** Resolve a leaf `given` if one exists, otherwise derive a product / sum via its `Mirror`. Used for
    * fields and for the `Option`/collection/`Map` `inline given`s' *element* — so a collection of a
    * case class derives that element (which has no summonable given). */
  private inline def summonAgnostic[T]: AgnosticEncoder[T] =
    summonFrom {
      case enc: AgnosticEncoder[T] => enc
      case _: Mirror.ProductOf[T]  => deriveAgnosticEncoder[T]
      case _: Mirror.SumOf[T]      => deriveAgnosticEncoder[T]
      case _ =>
        error(
          "Cannot find or derive an AgnosticEncoder for this type. Supported: primitives (boxed and " +
            "unboxed), String, Array[Byte], Scala/Java BigDecimal & BigInt, temporal " +
            "(LocalDate/Date/Instant/Timestamp/LocalDateTime/Duration/Period), UUID, " +
            "Offset/ZonedDateTime, Option, Seq/List/Vector/Set/Array, Map, case classes, tuples, and " +
            "simple Scala 3 enums."
        )
    }

  // === Product (case class / tuple) ===

  private inline def deriveProduct[T](m: Mirror.ProductOf[T], ct: ClassTag[T]): AgnosticEncoder[T] =
    ProductEncoder(ct, deriveFields[m.MirroredElemTypes, m.MirroredElemLabels], None)

  private inline def deriveFields[Types <: Tuple, Labels <: Tuple]: List[EncoderField] =
    inline (erasedValue[Types], erasedValue[Labels]) match
      case (_: EmptyTuple, _: EmptyTuple) => Nil
      case (_: (t *: ts), _: (l *: ls)) =>
        val child = summonAgnostic[t]
        // Field nullability is Spark's authoritative rule — `EncoderField(name, enc, enc.nullable)`,
        // where `enc.nullable = !isPrimitive` — taken from the lowered child, not a separate flag.
        EncoderField(constValue[l].toString, child, child.nullable, Metadata.empty) ::
          deriveFields[ts, ls]

  // === Sum (Scala 3 enum / sealed trait) ===

  private inline def deriveSum[T](m: Mirror.SumOf[T], ct: ClassTag[T]): AgnosticEncoder[T] =
    inline if allSingletons[m.MirroredElemTypes] then scala3EnumEncoder[T](ct)
    else
      error(
        "Cannot derive an AgnosticEncoder for a data-carrying Scala 3 enum / sealed-trait ADT: " +
          "Spark's AgnosticEncoder model has no sum-type representation. (A Scala-3 capability Spark's " +
          "encoder model would need to grow; rejected here at compile time.)"
      )

  /** True iff every case of the sum type is a singleton (parameterless) — a *simple* enum. */
  private transparent inline def allSingletons[Types <: Tuple]: Boolean =
    inline erasedValue[Types] match
      case _: EmptyTuple => true
      case _: (t *: ts)  => isSingleton[t] && allSingletons[ts]

  private transparent inline def isSingleton[T]: Boolean =
    summonFrom {
      case m: Mirror.ProductOf[T] =>
        inline erasedValue[m.MirroredElemTypes] match
          case _: EmptyTuple => true
          case _             => false
      case _ => true
    }

  /** A simple Scala 3 `enum` round-trips as a `TransformingEncoder` over `StringEncoder` whose codec
    * is the case name via the companion's `valueOf` (Java reflection, run only at ser/deser time — not
    * the `scala.reflect.runtime` this initiative removes). Spark's reflection has no Scala-3-enum
    * encoder, so this DEFINES the behavior (a defined superset, not parity). */
  private def scala3EnumEncoder[T](ct: ClassTag[T]): AgnosticEncoder[T] =
    val enumClass = ct.runtimeClass
    val codecProvider: () => Codec[Any, String] = () =>
      new Codec[Any, String]:
        private lazy val companion = Class.forName(enumClass.getName + "$")
        private lazy val module = companion.getField("MODULE$").get(null)
        private lazy val valueOfM = companion.getMethod("valueOf", classOf[String])
        def encode(in: Any): String = in.toString
        def decode(out: String): Any = valueOfM.invoke(module, out)
    TransformingEncoder(ct.asInstanceOf[ClassTag[Any]], StringEncoder, codecProvider, nullable = true)
      .asInstanceOf[AgnosticEncoder[T]]

  // === Leaf encoders — Scala type → Spark's leaf node ===
  // The boxed/unboxed split is by type (Int vs java.lang.Integer), exactly as Spark distinguishes
  // PrimitiveIntEncoder from BoxedIntEncoder.

  given AgnosticEncoder[Boolean] = PrimitiveBooleanEncoder
  given AgnosticEncoder[Byte] = PrimitiveByteEncoder
  given AgnosticEncoder[Short] = PrimitiveShortEncoder
  given AgnosticEncoder[Int] = PrimitiveIntEncoder
  given AgnosticEncoder[Long] = PrimitiveLongEncoder
  given AgnosticEncoder[Float] = PrimitiveFloatEncoder
  given AgnosticEncoder[Double] = PrimitiveDoubleEncoder

  // Explicit names: a boxed wrapper has the same *simple* name as its primitive (`java.lang.Boolean`
  // vs `scala.Boolean`), so the compiler-synthesized given names would collide. The types are still
  // distinct, so resolution picks the right one per field type.
  given boxedBoolean: AgnosticEncoder[java.lang.Boolean] = BoxedBooleanEncoder
  given boxedByte: AgnosticEncoder[java.lang.Byte] = BoxedByteEncoder
  given boxedShort: AgnosticEncoder[java.lang.Short] = BoxedShortEncoder
  given boxedInt: AgnosticEncoder[java.lang.Integer] = BoxedIntEncoder
  given boxedLong: AgnosticEncoder[java.lang.Long] = BoxedLongEncoder
  given boxedFloat: AgnosticEncoder[java.lang.Float] = BoxedFloatEncoder
  given boxedDouble: AgnosticEncoder[java.lang.Double] = BoxedDoubleEncoder

  given AgnosticEncoder[String] = StringEncoder
  given AgnosticEncoder[Array[Byte]] = BinaryEncoder
  given AgnosticEncoder[java.lang.Void] = NullEncoder

  given scalaDecimal: AgnosticEncoder[BigDecimal] = ScalaDecimalEncoder(DecimalType(38, 18))
  given AgnosticEncoder[BigInt] = ScalaBigIntEncoder
  given javaDecimal: AgnosticEncoder[java.math.BigDecimal] =
    JavaDecimalEncoder(DecimalType(38, 18), lenientSerialization = false)
  given AgnosticEncoder[java.math.BigInteger] = JavaBigIntEncoder

  given AgnosticEncoder[java.time.LocalDate] = LocalDateEncoder(lenientSerialization = false)
  given AgnosticEncoder[java.sql.Date] = DateEncoder(lenientSerialization = false)
  given AgnosticEncoder[java.time.Instant] = InstantEncoder(lenientSerialization = false)
  given AgnosticEncoder[java.sql.Timestamp] = TimestampEncoder(lenientSerialization = false)
  given AgnosticEncoder[java.time.LocalDateTime] = LocalDateTimeEncoder
  given AgnosticEncoder[java.time.Duration] = DayTimeIntervalEncoder
  given AgnosticEncoder[java.time.Period] = YearMonthIntervalEncoder

  // Beyond-Spark extensions (Spark's reflection rejects these outright): lossless String-backed
  // TransformingEncoders, mirroring AgnosticEncoderBridge.
  given AgnosticEncoder[java.util.UUID] =
    stringBacked(classTag[java.util.UUID], _.toString, s => java.util.UUID.fromString(s))
  given AgnosticEncoder[java.time.OffsetDateTime] =
    stringBacked(classTag[java.time.OffsetDateTime], _.toString, s => java.time.OffsetDateTime.parse(s))
  given AgnosticEncoder[java.time.ZonedDateTime] =
    stringBacked(classTag[java.time.ZonedDateTime], _.toString, s => java.time.ZonedDateTime.parse(s))

  private def stringBacked[T](
      ct: ClassTag[T],
      encode0: Any => String,
      decode0: String => Any
  ): AgnosticEncoder[T] =
    val codecProvider: () => Codec[Any, String] = () =>
      new Codec[Any, String]:
        def encode(in: Any): String = encode0(in)
        def decode(out: String): Any = decode0(out)
    TransformingEncoder(ct.asInstanceOf[ClassTag[Any]], StringEncoder, codecProvider, nullable = true)
      .asInstanceOf[AgnosticEncoder[T]]

  // === Option / collections / Map — inline givens resolving the element through `summonAgnostic` ===
  // (so the element may be a case class, which has no summonable given). `containsNull` /
  // `valueContainsNull` follow the element/value encoder's `.nullable` — Spark's rule.

  inline given optionAgnostic[T]: AgnosticEncoder[Option[T]] =
    OptionEncoder(summonAgnostic[T])

  inline given seqAgnostic[T]: AgnosticEncoder[Seq[T]] =
    iterable(summonAgnostic[T], ClassTag(classOf[Seq[?]]))
  inline given listAgnostic[T]: AgnosticEncoder[List[T]] =
    iterable(summonAgnostic[T], ClassTag(classOf[List[?]]))
  inline given vectorAgnostic[T]: AgnosticEncoder[Vector[T]] =
    iterable(summonAgnostic[T], ClassTag(classOf[Vector[?]]))
  inline given setAgnostic[T]: AgnosticEncoder[Set[T]] =
    iterable(summonAgnostic[T], ClassTag(classOf[Set[?]]))

  private def iterable[C, E](e: AgnosticEncoder[E], ct: ClassTag[C]): AgnosticEncoder[C] =
    IterableEncoder(ct, e, e.nullable, lenientSerialization = false)

  inline given arrayAgnostic[T]: AgnosticEncoder[Array[T]] =
    array(summonAgnostic[T])
  private def array[E](e: AgnosticEncoder[E]): AgnosticEncoder[Array[E]] =
    ArrayEncoder(e, e.nullable)

  inline given mapAgnostic[K, V]: AgnosticEncoder[Map[K, V]] =
    mapEnc(summonAgnostic[K], summonAgnostic[V], ClassTag(classOf[Map[?, ?]]))
  private def mapEnc[C, K, V](
      k: AgnosticEncoder[K],
      v: AgnosticEncoder[V],
      ct: ClassTag[C]
  ): AgnosticEncoder[C] = MapEncoder(ct, k, v, v.nullable)
