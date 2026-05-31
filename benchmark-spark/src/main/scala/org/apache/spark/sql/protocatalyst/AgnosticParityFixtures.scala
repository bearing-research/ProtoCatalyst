// Lives under `org.apache.spark.sql.*` for parity with the other fixture generators; uses only
// the public `ExpressionEncoder` API.
package org.apache.spark.sql.protocatalyst

import java.io.{File, PrintWriter}

import org.apache.spark.sql.catalyst.encoders.{AgnosticEncoder, ExpressionEncoder}
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders._

/** Generates golden `AgnosticEncoder` structural dumps from Spark's reflective
  * `ScalaReflection.encoderFor` (via `ExpressionEncoder[T]().encoder`). The Scala 3 side
  * (`AgnosticEncoderBridgeSpec`) derives the same case class with `ProtoEncoder.derived` + the
  * `AgnosticEncoderBridge` and asserts the canonical dump matches — the structural-parity oracle
  * (design doc D2/D6). `encoderFor[T: TypeTag]` is Scala-2.13-only, so the golden must be produced
  * here, not on the Scala 3 side.
  *
  * The dump uses class **simple** names so it is stable across the 2.13/Scala-3 module boundary
  * (the user case class lives in different packages on each side).
  *
  * Re-run: `sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.AgnosticParityFixtures'`
  */
// Top-level (not nested in the object) so Spark derives them with `outerPointerGetter = None`,
// matching the Scala 3 spec. Structural mirrors of the spec's case classes.
case class Person(id: Int, name: String, active: Boolean, score: Double)

// M1 leaf-coverage corpus.
case class Primitives(b: Boolean, by: Byte, sh: Short, i: Int, l: Long, f: Float, d: Double)
case class Boxed(
    b: java.lang.Boolean,
    by: java.lang.Byte,
    sh: java.lang.Short,
    i: java.lang.Integer,
    l: java.lang.Long,
    f: java.lang.Float,
    d: java.lang.Double)
case class Scalars(
    s: String,
    bin: Array[Byte],
    dec: BigDecimal,
    jdec: java.math.BigDecimal,
    bi: BigInt,
    jbi: java.math.BigInteger)
case class Temporal(
    ld: java.time.LocalDate,
    sd: java.sql.Date,
    inst: java.time.Instant,
    ts: java.sql.Timestamp,
    ldt: java.time.LocalDateTime,
    dur: java.time.Duration,
    per: java.time.Period)

object AgnosticParityFixtures {

  /** Canonical, class-name-normalized structural dump of an AgnosticEncoder. Kept in sync with the
    * identical helper in the Scala 3 spec.
    */
  def canonical(e: AgnosticEncoder[_]): String = e match {
    case p: ProductEncoder[_] =>
      "Product[" + p.clsTag.runtimeClass.getSimpleName + "](" +
        p.fields
          .map(f => f.name + ":" + canonical(f.enc) + (if (f.nullable) "?" else "!"))
          .mkString(",") + ")"
    case o: OptionEncoder[_]       => "Option[" + canonical(o.elementEncoder) + "]"
    case a: ArrayEncoder[_]        => "Array[" + canonical(a.element) + ",cn=" + a.containsNull + "]"
    case i: IterableEncoder[_, _]  =>
      "Iterable[" + i.clsTag.runtimeClass.getSimpleName + "," + canonical(i.element) +
        ",cn=" + i.containsNull + "]"
    case m: MapEncoder[_, _, _] =>
      "Map[" + canonical(m.keyEncoder) + "," + canonical(m.valueEncoder) +
        ",vcn=" + m.valueContainsNull + "]"
    // Parametric leaves: capture precision/scale and lenientSerialization so the dump is rigorous.
    case d: ScalaDecimalEncoder => "ScalaDecimal(" + d.dt.precision + "," + d.dt.scale + ")"
    case d: JavaDecimalEncoder =>
      "JavaDecimal(" + d.dt.precision + "," + d.dt.scale + ",len=" + d.lenientSerialization + ")"
    case d: SparkDecimalEncoder => "SparkDecimal(" + d.dt.precision + "," + d.dt.scale + ")"
    case e: DateEncoder         => "Date(len=" + e.lenientSerialization + ")"
    case e: LocalDateEncoder    => "LocalDate(len=" + e.lenientSerialization + ")"
    case e: TimestampEncoder    => "Timestamp(len=" + e.lenientSerialization + ")"
    case e: InstantEncoder      => "Instant(len=" + e.lenientSerialization + ")"
    case c: CharEncoder         => "Char(" + c.length + ")"
    case v: VarcharEncoder      => "Varchar(" + v.length + ")"
    case leaf                   => leaf.getClass.getSimpleName.stripSuffix("$")
  }

  private def write(dir: File, name: String, dump: String): Unit = {
    dir.mkdirs()
    val f = new File(dir, s"$name.agnostic")
    val w = new PrintWriter(f)
    try w.write(dump)
    finally w.close()
    println(s"Wrote ${f.getPath}: $dump")
  }

  def main(args: Array[String]): Unit = {
    val outDir =
      if (args.nonEmpty) new File(args(0))
      else new File("encoder-spark/src/test/resources/agnostic-parity")
    write(outDir, "Person", canonical(ExpressionEncoder[Person]().encoder))
    write(outDir, "Primitives", canonical(ExpressionEncoder[Primitives]().encoder))
    write(outDir, "Boxed", canonical(ExpressionEncoder[Boxed]().encoder))
    write(outDir, "Scalars", canonical(ExpressionEncoder[Scalars]().encoder))
    write(outDir, "Temporal", canonical(ExpressionEncoder[Temporal]().encoder))
    println("Done.")
  }
}
