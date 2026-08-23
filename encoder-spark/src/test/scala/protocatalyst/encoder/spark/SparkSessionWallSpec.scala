package protocatalyst.encoder.spark

import munit.FunSuite
import org.apache.spark.sql.{Encoder, SparkSession}
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import protocatalyst.encoder.spark.AgnosticDerivation.{deriveAgnosticEncoder, given}

/** The **second** Scala-3 execution wall, and its removal: `SparkSession.builder()` itself.
  *
  * `ScalaReflection` (REPORT §3) is the wall the encoder path hits. This one sits upstream of it and
  * hits sooner — before any encoder exists. `org.apache.spark.sql.SparkSession` (sql-api 4.1.2) resolves
  * its implementation companion like this:
  * {{{
  *   private[this] def lookupCompanion(name: String): SparkSessionCompanion = {
  *     val cls = SparkClassUtils.classForName(name)
  *     val mirror = scala.reflect.runtime.currentMirror   // <- cannot initialize on Scala 3
  *     ...
  * }}}
  * On Scala 3 that throws `FatalError: class Array does not have a member apply`
  * (scala/scala3#25896) for both the Classic and Connect candidates. `DEFAULT_COMPANION` wraps the
  * lookup in `Try`, the error is `NonFatal`, so the cause is swallowed and re-thrown as
  * `IllegalStateException("Cannot find a SparkSession implementation on the Classpath.")` — an error
  * message that points at the classpath when the classpath is fine.
  *
  * With the patched `SparkSession` on the classpath (`spark-reflection-patch`, `lookupCompanion`
  * reading the companion's static `MODULE$` field instead of building a runtime mirror), the stock
  * entry point works from Scala 3 and drives a real query end-to-end.
  */
class SparkSessionWallSpec extends FunSuite:

  /** The runtime universe is still broken in this JVM — the patch removes Spark's *dependence* on it,
    * it does not repair `scala.reflect.runtime`. Informational: if a future Scala 3 fixes #25896 this
    * prints instead of failing, since that would be good news rather than a regression. */
  test("the runtime universe that stock lookupCompanion needs is unusable from Scala 3"):
    try
      val u = scala.reflect.runtime.universe
      val m = u.runtimeMirror(getClass.getClassLoader)
      m.classSymbol(classOf[String]).companion
      println(
        "[SparkSessionWallSpec] NOTE: scala.reflect.runtime initialized on this Scala 3 build — " +
          "scala/scala3#25896 may be fixed. The patch is then belt-and-braces, not load-bearing."
      )
    catch
      case t: Throwable =>
        assert(
          scala.util.control.NonFatal(t),
          s"expected a NonFatal failure (that is why Spark's Try swallows it), got $t"
        )
        assert(
          t.getMessage != null && t.getMessage.contains("does not have a member apply"),
          s"expected the #25896 signature, got ${t.getClass.getName}: ${t.getMessage}"
        )

  test("stock SparkSession.builder() yields a working session from a Scala 3 process"):
    val spark = SparkSession
      .builder()
      .master("local[2]")
      .appName("protocatalyst-sparksession-wall")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "4")
      .getOrCreate()
    try
      // Unpatched, construction never gets this far: builder() throws before returning.
      assertEquals(spark.version, "4.1.2")
      assertEquals(spark.getClass.getName, "org.apache.spark.sql.classic.SparkSession")

      // And the session is usable: a typed Dataset query whose encoders come from compile-time
      // derivation (no TypeTag, no reflection) rather than ScalaReflection.encoderFor.
      given Encoder[WallRow] = ExpressionEncoder(deriveAgnosticEncoder[WallRow])
      given Encoder[(String, Long)] = ExpressionEncoder(deriveAgnosticEncoder[(String, Long)])

      val rows = Seq(WallRow(1, "a", 10L), WallRow(2, "b", 20L), WallRow(3, "a", 30L))
      val ds = spark.createDataset(rows)

      // Typed lambda ops: closure cleaning, whole-stage codegen and Spark's own ser/deser, all
      // driven from Scala 3.
      val out = ds.filter(_.amount >= 20L).map(r => (r.name, r.amount)).collect().sortBy(_._2)
      assertEquals(out.toList, List(("b", 20L), ("a", 30L)))
    finally spark.stop()

case class WallRow(id: Int, name: String, amount: Long)
