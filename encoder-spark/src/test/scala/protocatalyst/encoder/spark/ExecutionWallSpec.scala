package protocatalyst.encoder.spark

import java.time.{OffsetDateTime, ZonedDateTime}
import java.util.UUID

import scala.deriving.Mirror

import munit.FunSuite
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.encoder.spark.AgnosticDerivation.{deriveAgnosticEncoder, given}

// Beyond-Spark extension types (Spark's own reflection has no encoder for these); the bridge adds
// them as String-backed TransformingEncoders, so a successful round-trip is their self-consistency
// oracle.
case class HasExtensions(id: UUID, when: OffsetDateTime, zoned: ZonedDateTime)

/** End-to-end execution from a **Scala 3 process** against Spark's real encoder runtime.
  *
  * Without the patch (see `spark-reflection-patch`), every case here crashes the moment Spark builds
  * the serializer: `Invoke.encodedFunctionName` -> `ScalaReflection.encodeFieldNameToIdentifier`
  * forces `ScalaReflection`'s `<clinit>`, whose eager `val universe = scala.reflect.runtime.universe`
  * cannot initialize on the Scala 3 stdlib (`FatalError: class Array does not have a member apply`).
  * That is the Scala-3 execution wall (docs/scala3-encoder/REPORT.md §3, REFLECTION_REPLACEMENT.md §2.1).
  *
  * With the 2-line patch on the test classpath (lazy `universe`; `encodeFieldNameToIdentifier` via
  * `NameTransformer.encode`), the wall is gone and Spark's *unmodified* serializer/deserializer run.
  *
  * This is the strongest evidence in the initiative: the encoder description we derive at compile
  * time (no `TypeTag`, no reflection) drives Spark's actual codegen ser/deser and round-trips real
  * values — turning the structural-parity oracle (`AgnosticEncoderBridgeSpec`) into *observed*
  * behavioral parity. The same corpus runs through **both** the repo's two-layer path (`roundTrip`) and
  * the single-file drop-in `AgnosticDerivation.deriveAgnosticEncoder` (`roundTripDropIn`) — so the exact
  * artifact a Spark maintainer would take is proven end-to-end, not validated only by proxy.
  */
class ExecutionWallSpec extends FunSuite:

  /** Build Spark's runtime ser/deser from *our* compile-time-derived encoder and round-trip `value`.
    *
    * `ExpressionEncoder(agnostic).resolveAndBind()` then `createSerializer()` (T → `InternalRow`) /
    * `createDeserializer()` (`InternalRow` → T) is **exactly how Spark uses an encoder** — it is the
    * path `Dataset[T]` drives internally. The two helpers differ only in where the `AgnosticEncoder`
    * comes from. */
  private inline def roundTrip[T](value: T)(using Mirror.Of[T]): T =
    val agnostic = AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[T]) // the repo's two-layer path
    val enc      = ExpressionEncoder(agnostic).resolveAndBind()
    val row      = enc.createSerializer()(value)
    enc.createDeserializer()(row)

  /** Same round-trip, but the `AgnosticEncoder` is the **single-file drop-in** —
    * `AgnosticDerivation.deriveAgnosticEncoder[T]`, no bridge, no `ProtoEncoder`/`ProtoType`. This
    * exercises the *exact artifact upstream takes* (the file with only its package line changed) through
    * Spark's real ser/deser, not by proxy through the bridge. */
  private inline def roundTripDropIn[T](value: T)(using Mirror.Of[T]): T =
    val agnostic = deriveAgnosticEncoder[T]
    val enc      = ExpressionEncoder(agnostic).resolveAndBind()
    val row      = enc.createSerializer()(value)
    enc.createDeserializer()(row)

  test("flat product round-trips through Spark's real ser/deser"):
    val p = Person(7, "alice", active = true, score = 1.5)
    assertEquals(roundTrip(p), p)

  test("all primitive widths round-trip"):
    val p = Primitives(b = true, by = 1, sh = 2, i = 3, l = 4L, f = 5.0f, d = 6.0)
    assertEquals(roundTrip(p), p)

  test("boxed (java.lang) nullable scalars round-trip"):
    val b = Boxed(
      java.lang.Boolean.TRUE,
      java.lang.Byte.valueOf(1.toByte),
      java.lang.Short.valueOf(2.toShort),
      java.lang.Integer.valueOf(3),
      java.lang.Long.valueOf(4L),
      java.lang.Float.valueOf(5.0f),
      java.lang.Double.valueOf(6.0)
    )
    assertEquals(roundTrip(b), b)

  test("options (Some and None) round-trip"):
    val full  = Opts(Some(1), Some("y"), Some(java.time.LocalDate.of(2026, 5, 31)))
    val empty = Opts(None, None, None)
    assertEquals(roundTrip(full), full)
    assertEquals(roundTrip(empty), empty)

  test("nested product round-trips"):
    val n = Nested(1, Address("Main St", 90210))
    assertEquals(roundTrip(n), n)

  test("maps round-trip"):
    val m = Maps(Map("a" -> 1, "b" -> 2), Map(1 -> "x"))
    assertEquals(roundTrip(m), m)

  test("collection/map/option of case class round-trips"):
    val d = Deep(
      id = 9,
      tags = Seq(Address("A", 1), Address("B", 2)),
      lookup = Map("home" -> Address("C", 3)),
      maybe = Some(Address("D", 4))
    )
    assertEquals(roundTrip(d), d)

  test("tuple fields round-trip"):
    val t = HasTuple(3, ("k", 5), (7L, true, 2.5))
    assertEquals(roundTrip(t), t)

  test("tuple whose element is a case class round-trips"):
    val t = HasTupleCC(3, ("k", Address("Elm", 11)))
    assertEquals(roundTrip(t), t)

  test("beyond-Spark extension types (UUID, OffsetDateTime, ZonedDateTime) round-trip"):
    val v = HasExtensions(
      UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
      OffsetDateTime.parse("2026-05-31T10:15:30+01:00"),
      ZonedDateTime.parse("2026-05-31T10:15:30+01:00[Europe/Paris]")
    )
    assertEquals(roundTrip(v), v)

  test("collections (incl. Array) round-trip with structural equality"):
    val c = Colls(Seq(1, 2), List("a", "b"), Vector(3L), Set(4, 5), Array(6.0, 7.0))
    val back = roundTrip(c)
    assertEquals(back.ints, c.ints)
    assertEquals(back.strs, c.strs)
    assertEquals(back.vec, c.vec)
    assertEquals(back.set, c.set)
    assertEquals(back.arr.toSeq, c.arr.toSeq)

  // === The same corpus, driven by the single-file drop-in `deriveAgnosticEncoder[T]` directly (no ===
  // === bridge): the exact file a Spark maintainer takes, proven end-to-end through Spark's codegen. ===

  test("DROP-IN: flat product round-trips through Spark's real ser/deser"):
    val p = Person(7, "alice", active = true, score = 1.5)
    assertEquals(roundTripDropIn(p), p)

  test("DROP-IN: all primitive widths round-trip"):
    val p = Primitives(b = true, by = 1, sh = 2, i = 3, l = 4L, f = 5.0f, d = 6.0)
    assertEquals(roundTripDropIn(p), p)

  test("DROP-IN: boxed (java.lang) nullable scalars round-trip"):
    val b = Boxed(
      java.lang.Boolean.TRUE,
      java.lang.Byte.valueOf(1.toByte),
      java.lang.Short.valueOf(2.toShort),
      java.lang.Integer.valueOf(3),
      java.lang.Long.valueOf(4L),
      java.lang.Float.valueOf(5.0f),
      java.lang.Double.valueOf(6.0)
    )
    assertEquals(roundTripDropIn(b), b)

  test("DROP-IN: options (Some and None) round-trip"):
    val full  = Opts(Some(1), Some("y"), Some(java.time.LocalDate.of(2026, 5, 31)))
    val empty = Opts(None, None, None)
    assertEquals(roundTripDropIn(full), full)
    assertEquals(roundTripDropIn(empty), empty)

  test("DROP-IN: nested product round-trips"):
    val n = Nested(1, Address("Main St", 90210))
    assertEquals(roundTripDropIn(n), n)

  test("DROP-IN: maps round-trip"):
    val m = Maps(Map("a" -> 1, "b" -> 2), Map(1 -> "x"))
    assertEquals(roundTripDropIn(m), m)

  test("DROP-IN: collection/map/option of case class round-trips"):
    val d = Deep(9, Seq(Address("A", 1), Address("B", 2)), Map("home" -> Address("C", 3)), Some(Address("D", 4)))
    assertEquals(roundTripDropIn(d), d)

  test("DROP-IN: tuple whose element is a case class round-trips"):
    val t = HasTupleCC(3, ("k", Address("Elm", 11)))
    assertEquals(roundTripDropIn(t), t)

  test("DROP-IN: beyond-Spark extension types (UUID, OffsetDateTime, ZonedDateTime) round-trip"):
    val v = HasExtensions(
      UUID.fromString("550e8400-e29b-41d4-a716-446655440000"),
      OffsetDateTime.parse("2026-05-31T10:15:30+01:00"),
      ZonedDateTime.parse("2026-05-31T10:15:30+01:00[Europe/Paris]")
    )
    assertEquals(roundTripDropIn(v), v)

  test("DROP-IN: collections (incl. Array) round-trip with structural equality"):
    val c = Colls(Seq(1, 2), List("a", "b"), Vector(3L), Set(4, 5), Array(6.0, 7.0))
    val back = roundTripDropIn(c)
    assertEquals(back.ints, c.ints)
    assertEquals(back.strs, c.strs)
    assertEquals(back.vec, c.vec)
    assertEquals(back.set, c.set)
    assertEquals(back.arr.toSeq, c.arr.toSeq)
