package protocatalyst.benchmark

import java.io.{File, FileOutputStream}
import java.time.{Instant, LocalDate}

import scala.reflect.classTag

import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders._
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.types.Metadata

/** Generates "golden" UnsafeRow byte fixtures from Spark's `ExpressionEncoder`, used by the
  * Scala 3 parity test in `encoder-spark`.
  *
  * Spark's whole-stage codegen path can't run from a Scala 3 module — `ScalaReflection.<clinit>`
  * trips on the Scala 3 `Array` companion. So we run Spark here (where Scala 2.13's
  * `runtime.universe` works natively), write the resulting UnsafeRow bytes to disk, and the
  * Scala 3 test asserts byte-equality against the fixtures.
  *
  * Re-run when fixture values or Spark version changes:
  * {{{
  * sbt 'benchmarkSpark/runMain protocatalyst.benchmark.UnsafeRowParityFixtures'
  * }}}
  *
  * Shapes must structurally match the Scala 3 case classes in
  * `encoder-spark/src/test/scala/.../UnsafeRowParitySpec.scala`.
  */
object UnsafeRowParityFixtures {

  case class Simple(id: Int, name: String)
  case class WithDecimal(orderkey: Long, quantity: BigDecimal)
  case class WithTemporal(shipdate: LocalDate, ts: Instant)
  case class WithOption(id: Int, label: Option[String])

  private def bytesOf(row: UnsafeRow): Array[Byte] = {
    val out = new Array[Byte](row.getSizeInBytes)
    System.arraycopy(row.getBytes, 0, out, 0, row.getSizeInBytes)
    out
  }

  private def writeFixture(dir: File, name: String, data: Array[Byte]): Unit = {
    dir.mkdirs()
    val f = new File(dir, s"$name.bin")
    val out = new FileOutputStream(f)
    try out.write(data)
    finally out.close()
    println(f"Wrote ${f.getPath} (${data.length}%4dB)")
  }

  def main(args: Array[String]): Unit = {
    val outDir =
      if (args.nonEmpty) new File(args(0))
      else new File("encoder-spark/src/test/resources/parity")

    println(s"Output dir: ${outDir.getAbsolutePath}")

    // === Simple(Int, String) ===
    val simpleEnc = ProductEncoder[Simple](
      classTag[Simple],
      Seq(
        EncoderField("id", PrimitiveIntEncoder, nullable = false, Metadata.empty),
        EncoderField("name", StringEncoder, nullable = true, Metadata.empty)
      ),
      outerPointerGetter = None
    )
    val simpleSer = ExpressionEncoder(simpleEnc).createSerializer()
    writeFixture(outDir, "Simple", bytesOf(simpleSer(Simple(42, "alice")).asInstanceOf[UnsafeRow]))

    // === WithDecimal(Long, BigDecimal) ===
    val decEnc = ProductEncoder[WithDecimal](
      classTag[WithDecimal],
      Seq(
        EncoderField("orderkey", PrimitiveLongEncoder, nullable = false, Metadata.empty),
        EncoderField("quantity", DEFAULT_SCALA_DECIMAL_ENCODER, nullable = true, Metadata.empty)
      ),
      outerPointerGetter = None
    )
    val decSer = ExpressionEncoder(decEnc).createSerializer()
    writeFixture(
      outDir,
      "WithDecimal",
      bytesOf(
        decSer(WithDecimal(1234L, BigDecimal("17.500000000000000000"))).asInstanceOf[UnsafeRow]
      )
    )

    // === WithTemporal(LocalDate, Instant) ===
    val tempEnc = ProductEncoder[WithTemporal](
      classTag[WithTemporal],
      Seq(
        EncoderField(
          "shipdate",
          STRICT_LOCAL_DATE_ENCODER,
          nullable = true,
          Metadata.empty
        ),
        EncoderField("ts", STRICT_INSTANT_ENCODER, nullable = true, Metadata.empty)
      ),
      outerPointerGetter = None
    )
    val tempSer = ExpressionEncoder(tempEnc).createSerializer()
    writeFixture(
      outDir,
      "WithTemporal",
      bytesOf(
        tempSer(
          WithTemporal(LocalDate.of(2026, 5, 26), Instant.parse("2026-05-26T15:00:00Z"))
        ).asInstanceOf[UnsafeRow]
      )
    )

    // === WithOption(Int, Option[String]) — Some ===
    val optEnc = ProductEncoder[WithOption](
      classTag[WithOption],
      Seq(
        EncoderField("id", PrimitiveIntEncoder, nullable = false, Metadata.empty),
        EncoderField(
          "label",
          OptionEncoder(StringEncoder),
          nullable = true,
          Metadata.empty
        )
      ),
      outerPointerGetter = None
    )
    val optSer = ExpressionEncoder(optEnc).createSerializer()
    writeFixture(
      outDir,
      "WithOption_Some",
      bytesOf(optSer(WithOption(7, Some("seven"))).asInstanceOf[UnsafeRow])
    )
    writeFixture(
      outDir,
      "WithOption_None",
      bytesOf(optSer(WithOption(7, None)).asInstanceOf[UnsafeRow])
    )

    // === WithTime(Int, LocalTime) — DEFERRED ===
    // Spark 4.1.2 has LocalTimeEncoder + TimeType, but SerializerBuildHelper still throws
    // UNSUPPORTED_TIME_TYPE when actually building a serializer. Re-enable once Spark closes
    // SPARK-46934-followup (or whichever ticket lights this up).

    println("Done.")
  }
}
