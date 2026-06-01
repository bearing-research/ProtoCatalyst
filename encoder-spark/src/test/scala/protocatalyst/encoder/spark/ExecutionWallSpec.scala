package protocatalyst.encoder.spark

import scala.deriving.Mirror

import munit.FunSuite
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import protocatalyst.encoder.ProtoEncoder

/** End-to-end execution from a **Scala 3 process** against Spark's real encoder runtime.
  *
  * Without the patch (see `spark-reflection-patch`), every case here crashes the moment Spark builds
  * the serializer: `Invoke.encodedFunctionName` -> `ScalaReflection.encodeFieldNameToIdentifier`
  * forces `ScalaReflection`'s `<clinit>`, whose eager `val universe = scala.reflect.runtime.universe`
  * cannot initialize on the Scala 3 stdlib (`FatalError: class Array does not have a member apply`).
  * That is the Scala-3 execution wall (docs/REPORT.md §3, REFLECTION_REPLACEMENT.md §2.1).
  *
  * With the 2-line patch on the test classpath (lazy `universe`; `encodeFieldNameToIdentifier` via
  * `NameTransformer.encode`), the wall is gone and Spark's *unmodified* serializer/deserializer run.
  *
  * This is the strongest evidence in the initiative: the encoder description we derive at compile
  * time (no `TypeTag`, no reflection) drives Spark's actual codegen ser/deser and round-trips real
  * values — turning the structural-parity oracle (`AgnosticEncoderBridgeSpec`) into *observed*
  * behavioral parity.
  */
class ExecutionWallSpec extends FunSuite:

  /** Build Spark's runtime ser/deser from *our* compile-time-derived encoder and round-trip `value`. */
  private inline def roundTrip[T](value: T)(using Mirror.Of[T]): T =
    val agnostic = AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[T])
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

  test("collections (incl. Array) round-trip with structural equality"):
    val c = Colls(Seq(1, 2), List("a", "b"), Vector(3L), Set(4, 5), Array(6.0, 7.0))
    val back = roundTrip(c)
    assertEquals(back.ints, c.ints)
    assertEquals(back.strs, c.strs)
    assertEquals(back.vec, c.vec)
    assertEquals(back.set, c.set)
    assertEquals(back.arr.toSeq, c.arr.toSeq)
