package protocatalyst.encoder.spark

import java.io.InputStream
import java.time.{Instant, LocalDate}
import java.util.Arrays

import munit.FunSuite
import org.apache.spark.sql.catalyst.expressions.UnsafeRow

/** Byte-level parity between our `UnsafeRowSerializer` and Spark's `ExpressionEncoder`.
  *
  * Spark's whole-stage codegen path can't run from a Scala 3 module — `ScalaReflection.<clinit>`
  * forces Scala 2's `runtime.universe`, which trips on the Scala 3 `Array` companion. So the
  * Spark side is run from the Scala 2.13 `benchmark-spark` module via
  * `UnsafeRowParityFixtures.main`, which writes UnsafeRow byte fixtures into
  * `encoder-spark/src/test/resources/parity/<name>.bin`. This suite loads those fixtures and asserts
  * our serializer produces byte-identical output for the same logical values.
  *
  * Test inputs must match `UnsafeRowParityFixtures.main` exactly. Regenerate fixtures with:
  * {{{
  * sbt 'benchmarkSpark/runMain protocatalyst.benchmark.UnsafeRowParityFixtures'
  * }}}
  */
class UnsafeRowParitySpec extends FunSuite:

  private def readFixture(name: String): Array[Byte] =
    val resource = s"/parity/$name.bin"
    val stream: InputStream = getClass.getResourceAsStream(resource)
    require(stream != null, s"Missing fixture: $resource. Regenerate via benchmarkSpark/runMain.")
    try stream.readAllBytes()
    finally stream.close()

  private def unsafeRowBytes(row: UnsafeRow): Array[Byte] =
    val out = new Array[Byte](row.getSizeInBytes)
    System.arraycopy(row.getBytes, 0, out, 0, row.getSizeInBytes)
    out

  private def hex(b: Array[Byte]): String = b.map(x => f"$x%02x").mkString(" ")

  private def assertParity(label: String, expected: Array[Byte], actual: UnsafeRow): Unit =
    val actualBytes = unsafeRowBytes(actual)
    if !Arrays.equals(expected, actualBytes) then
      fail(
        s"""[$label] UnsafeRow bytes differ.
           |  Spark  (${expected.length}B): ${hex(expected)}
           |  Ours   (${actualBytes.length}B): ${hex(actualBytes)}""".stripMargin
      )

  // === Test shapes must structurally match UnsafeRowParityFixtures.scala ===

  case class Simple(id: Int, name: String)
  case class WithDecimal(orderkey: Long, quantity: BigDecimal)
  case class WithTemporal(shipdate: LocalDate, ts: Instant)
  case class WithOption(id: Int, label: Option[String])

  test("parity: Simple(42, \"alice\")"):
    val ours = UnsafeRowSerializer.derived[Simple].serialize(Simple(42, "alice"))
    assertParity("Simple", readFixture("Simple"), ours)

  test("parity: WithDecimal(1234L, 17.5...)"):
    val ours = UnsafeRowSerializer
      .derived[WithDecimal]
      .serialize(WithDecimal(1234L, BigDecimal("17.500000000000000000")))
    assertParity("WithDecimal", readFixture("WithDecimal"), ours)

  test("parity: WithTemporal(2026-05-26, 2026-05-26T15:00:00Z)"):
    val ours = UnsafeRowSerializer
      .derived[WithTemporal]
      .serialize(WithTemporal(LocalDate.of(2026, 5, 26), Instant.parse("2026-05-26T15:00:00Z")))
    assertParity("WithTemporal", readFixture("WithTemporal"), ours)

  test("parity: WithOption(7, Some(\"seven\"))"):
    val ours = UnsafeRowSerializer.derived[WithOption].serialize(WithOption(7, Some("seven")))
    assertParity("WithOption_Some", readFixture("WithOption_Some"), ours)

  test("parity: WithOption(7, None) — null bitmask"):
    val ours = UnsafeRowSerializer.derived[WithOption].serialize(WithOption(7, None))
    assertParity("WithOption_None", readFixture("WithOption_None"), ours)
