package protocatalyst.encoder.spark

import java.nio.file.{Files, Paths}

import scala.compiletime.testing.typeChecks

import munit.FunSuite
import org.apache.spark.sql.catalyst.encoders.{AgnosticEncoder, ExpressionEncoder}
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.*

import protocatalyst.encoder.spark.AgnosticDerivation.{deriveAgnosticEncoder, given}

/** The §11b "single macro" prototype: `AgnosticDerivation.deriveAgnosticEncoder[T]` derives Spark's
  * `AgnosticEncoder` directly from a `Mirror`, with no `ProtoType`/bridge intermediate.
  *
  * It is validated against the **same** Scala-2.13 goldens (`AgnosticParityFixtures`, generated from
  * Spark's reflective `ScalaReflection.encoderFor`) that `AgnosticEncoderBridgeSpec` checks the
  * two-layer bridge against. Matching those goldens means the single macro is structurally identical
  * to what Spark's reflection produces — i.e. the collapsed upstream form loses nothing. The corpus,
  * canonical dump, and golden files are shared with `AgnosticEncoderBridgeSpec`; the fixtures
  * (`Person`, `Primitives`, …, `Color`, `Shape`) are the top-level case classes defined there.
  */
class AgnosticDerivationSpec extends FunSuite:

  // Canonical, class-name-normalized structural dump — identical to AgnosticEncoderBridgeSpec's, so
  // the two derivations are compared on exactly the same yardstick (and against the same goldens).
  private def canonical(e: AgnosticEncoder[?]): String = e match
    case p: ProductEncoder[?] =>
      "Product[" + p.clsTag.runtimeClass.getSimpleName + "](" +
        p.fields
          .map(f => f.name + ":" + canonical(f.enc) + (if f.nullable then "?" else "!"))
          .mkString(",") + ")"
    case o: OptionEncoder[?]      => "Option[" + canonical(o.elementEncoder) + "]"
    case a: ArrayEncoder[?]       => "Array[" + canonical(a.element) + ",cn=" + a.containsNull + "]"
    case i: IterableEncoder[?, ?] =>
      "Iterable[" + i.clsTag.runtimeClass.getSimpleName + "," + canonical(i.element) +
        ",cn=" + i.containsNull + "]"
    case m: MapEncoder[?, ?, ?] =>
      "Map[" + canonical(m.keyEncoder) + "," + canonical(m.valueEncoder) +
        ",vcn=" + m.valueContainsNull + "]"
    case d: ScalaDecimalEncoder => s"ScalaDecimal(${d.dt.precision},${d.dt.scale})"
    case d: JavaDecimalEncoder  =>
      s"JavaDecimal(${d.dt.precision},${d.dt.scale},len=${d.lenientSerialization})"
    case d: SparkDecimalEncoder => s"SparkDecimal(${d.dt.precision},${d.dt.scale})"
    case e: DateEncoder         => s"Date(len=${e.lenientSerialization})"
    case e: LocalDateEncoder    => s"LocalDate(len=${e.lenientSerialization})"
    case e: TimestampEncoder    => s"Timestamp(len=${e.lenientSerialization})"
    case e: InstantEncoder      => s"Instant(len=${e.lenientSerialization})"
    case c: CharEncoder         => s"Char(${c.length})"
    case v: VarcharEncoder      => s"Varchar(${v.length})"
    case t: TransformingEncoder[?, ?] =>
      s"Transforming[${t.clsTag.runtimeClass.getSimpleName},${canonical(t.transformed)}]"
    case leaf => leaf.getClass.getSimpleName.stripSuffix("$")

  private def loadGolden(name: String): String =
    val path = Paths.get("encoder-spark/src/test/resources/agnostic-parity", s"$name.agnostic")
    if !Files.exists(path) then
      fail(
        s"Missing golden $path — regenerate with " +
          "`sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.AgnosticParityFixtures'`"
      )
    new String(Files.readAllBytes(path)).trim

  /** Assert a (concretely-derived) encoder's canonical dump equals the Spark golden. Not `inline` and
    * the encoder is derived at the call site with a concrete type, so `deriveAgnosticEncoder` always
    * sees a concrete `Mirror`. */
  private def check(actual: AgnosticEncoder[?], golden: String): Unit =
    assertEquals(canonical(actual), loadGolden(golden))

  test("parity: Person (flat)"):
    check(deriveAgnosticEncoder[Person], "Person")
  test("parity: Primitives (7 unboxed)"):
    check(deriveAgnosticEncoder[Primitives], "Primitives")
  test("parity: Boxed (7 boxed)"):
    check(deriveAgnosticEncoder[Boxed], "Boxed")
  test("parity: Scalars (String/Binary/Scala+Java Decimal/BigInt)"):
    check(deriveAgnosticEncoder[Scalars], "Scalars")
  test("parity: Temporal"):
    check(deriveAgnosticEncoder[Temporal], "Temporal")
  test("parity: Opts (Option of leaves)"):
    check(deriveAgnosticEncoder[Opts], "Opts")
  test("parity: Colls (Seq/List/Vector/Set/Array)"):
    check(deriveAgnosticEncoder[Colls], "Colls")
  test("parity: Maps"):
    check(deriveAgnosticEncoder[Maps], "Maps")
  test("parity: Nested (struct of struct)"):
    check(deriveAgnosticEncoder[Nested], "Nested")
  test("parity: Wrapped (Seq[Option[Int]])"):
    check(deriveAgnosticEncoder[Wrapped], "Wrapped")
  test("parity: Deep (Seq/Map/Option of a case class)"):
    check(deriveAgnosticEncoder[Deep], "Deep")
  test("parity: Tuple3 (top-level tuple → Product[Tuple3])"):
    check(deriveAgnosticEncoder[(Int, String, Double)], "Tuple3")
  test("parity: HasTuple (tuple-of-leaves fields)"):
    check(deriveAgnosticEncoder[HasTuple], "HasTuple")
  test("parity: HasTupleCC (tuple whose element is a case class)"):
    check(deriveAgnosticEncoder[HasTupleCC], "HasTupleCC")

  // Scala-3 superset — defined behavior (no Spark oracle), must match the bridge's choices.
  test("Scala 3 enum (simple) → TransformingEncoder over String"):
    assertEquals(canonical(deriveAgnosticEncoder[Color]), "Transforming[Color,StringEncoder]")

  // Leaf types resolve through the `given`s (no Mirror), exactly as they would as a field encoder.
  test("UUID → String-backed TransformingEncoder"):
    assertEquals(canonical(summon[AgnosticEncoder[java.util.UUID]]), "Transforming[UUID,StringEncoder]")
  test("OffsetDateTime → String-backed TransformingEncoder"):
    assertEquals(
      canonical(summon[AgnosticEncoder[java.time.OffsetDateTime]]),
      "Transforming[OffsetDateTime,StringEncoder]"
    )
  test("ZonedDateTime → String-backed TransformingEncoder"):
    assertEquals(
      canonical(summon[AgnosticEncoder[java.time.ZonedDateTime]]),
      "Transforming[ZonedDateTime,StringEncoder]"
    )

  // The upstream-only improvement over the runtime bridge: a data-carrying ADT is rejected at
  // COMPILE time, not at invocation. (The bridge throws IllegalArgumentException at runtime.)
  test("data-carrying Scala 3 ADT → COMPILE error (Spark has no sum-type encoder)"):
    assert(
      !typeChecks("protocatalyst.encoder.spark.AgnosticDerivation.deriveAgnosticEncoder[Shape]"),
      "expected deriveAgnosticEncoder[Shape] to be a compile error"
    )

  // The single macro plugs into Spark's unchanged pipeline: ExpressionEncoder builds from it and the
  // reflection-free schema is Spark-correct (nullability: name nullable, primitives not).
  test("ExpressionEncoder builds from the single-macro AgnosticEncoder; schema is Spark-correct"):
    val enc = ExpressionEncoder(deriveAgnosticEncoder[Person])
    assertEquals(
      enc.schema.fields.map(f => s"${f.name}:${f.dataType.typeName}:${f.nullable}").mkString(","),
      "id:integer:false,name:string:true,active:boolean:false,score:double:false"
    )

end AgnosticDerivationSpec
