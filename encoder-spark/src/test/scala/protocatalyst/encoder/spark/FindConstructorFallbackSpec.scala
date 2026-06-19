package protocatalyst.encoder.spark

import munit.FunSuite
import org.apache.spark.sql.catalyst.ScalaReflection

// A type whose *constructor* the runtime cannot match for the given param list (no public 1-arg
// ctor), but whose companion exposes a matching `apply`. This is exactly the shape that drives
// `ScalaReflection.findConstructor` into its `None` fallback — the branch that, in stock Spark, uses
// `scala.reflect.runtime.universe` (`mirror.staticClass(...).companion` + `universe.TermName("apply")`)
// and therefore crashes when touched from a Scala 3 process (the §3 wall, scala/scala3#25896).
class Widget(val a: Int, val b: Int)
object Widget:
  def apply(a: Int): Widget = new Widget(a, 0)

/** Proves the de-reflected `findConstructor` fallback (Java reflection, no runtime universe).
  *
  * The 2-line down-payment (lazy `universe`, `NameTransformer.encode`) is enough for ordinary case
  * classes — they resolve via `findConstructor`'s Java *primary* path and never reach the fallback.
  * This spec deliberately reaches the fallback to exercise the separate follow-up: with the
  * companion-`apply` lookup reimplemented on `MethodUtils.getMatchingAccessibleMethod` + `MODULE$`,
  * the `None` branch resolves cleanly from this Scala 3 process instead of forcing the universe.
  */
class FindConstructorFallbackSpec extends FunSuite:

  test("findConstructor resolves a companion `apply` via Java reflection (no runtime universe)"):
    // No accessible 1-arg constructor exists -> primary getMatchingAccessibleConstructor returns
    // null -> findConstructor takes the companion-`apply` fallback.
    val builder = ScalaReflection.findConstructor(classOf[Widget], Seq(classOf[Int]))
    assert(builder.isDefined, "expected the companion-`apply` fallback to resolve")
    val w = builder.get(Seq(Integer.valueOf(5)))
    assertEquals(w.a, 5)
    assertEquals(w.b, 0) // supplied by `apply`, proving the companion method (not a ctor) was invoked

  test("findConstructor still prefers a matching constructor over the fallback"):
    // A matching 2-arg constructor exists -> primary path wins; the fallback is not consulted.
    val builder = ScalaReflection.findConstructor(classOf[Widget], Seq(classOf[Int], classOf[Int]))
    assert(builder.isDefined, "expected the constructor primary path to resolve")
    val w = builder.get(Seq(Integer.valueOf(3), Integer.valueOf(4)))
    assertEquals(w.a, 3)
    assertEquals(w.b, 4)
