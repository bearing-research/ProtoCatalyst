// Lives under `org.apache.spark.sql.*` so it can call the package-private `ArrowUtils`.
package org.apache.spark.sql.protocatalyst

import java.io.{File, FileOutputStream}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, Period}
import java.sql.{Date => SqlDate, Timestamp => SqlTimestamp}

import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.ArrowUtils

import protocatalyst.benchmark.tpch.Schemas._

/** Generates Arrow Schema parity fixtures from Spark's `ArrowUtils.toArrowSchema` (which is what
  * `ArrowSerializer[T]` uses internally).
  *
  * For each test class, we compute Spark's authoritative Arrow Schema and serialize it as an IPC
  * message. The Scala 3 parity test (`ArrowSchemaParitySpec` in encoder-spark) compares those
  * bytes against `ArrowSchemaBuilders.schemaFor[T]` to confirm byte-identical output for the same
  * input case class.
  *
  * Re-run when fixture types, Spark version, or Arrow version changes:
  * {{{
  * sbt 'benchmarkSpark/runMain protocatalyst.benchmark.ArrowSchemaParityFixtures'
  * }}}
  *
  * Case-class shapes here MUST structurally match the Scala 3 mirror in
  * `encoder-spark/src/test/scala/.../arrow/ArrowSchemaParitySpec.scala`. The TPC-H schemas are
  * imported from `protocatalyst.benchmark.tpch.Schemas` (the Scala 2.13 copy), which is
  * structurally identical to `protocatalyst.encoder.spark.tpch.Schemas`.
  */
object ArrowSchemaParityFixtures {

  // ---------------------------------------------------------------------------
  // Per-variant case classes. Mirror the Scala 3 spec field-for-field.
  // ---------------------------------------------------------------------------

  case class Simple(id: Int, name: String)
  case class AllPrimitives(
      b: Boolean,
      i8: Byte,
      i16: Short,
      i32: Int,
      i64: Long,
      f32: Float,
      f64: Double)
  case class Strings(s: String, b: Array[Byte])
  case class Decimals(s: BigDecimal, j: java.math.BigDecimal)
  // NOTE: `LocalTime` (TimeType) is rejected by Spark 4.1.2's `SerializerBuildHelper.createSerializer`
  // (`[UNSUPPORTED_TIME_TYPE]`) even though `LocalTimeEncoder` exists in the AgnosticEncoder
  // hierarchy. Our macro encoder supports LocalTime as an extension; there is no Spark counterpart
  // to compare against, so it's excluded from the parity surface.
  case class Temporal(
      d: LocalDate,
      sd: SqlDate,
      i: Instant,
      st: SqlTimestamp,
      ldt: LocalDateTime,
      dur: Duration,
      per: Period)
  case class Optional(oi: Option[Int], os: Option[String], od: Option[LocalDate])

  // ---------------------------------------------------------------------------
  // Schema build helpers.
  // ---------------------------------------------------------------------------

  /** Given a `StructType` (from `ExpressionEncoder[T]().schema`), produce the bytes Spark's
    * `ArrowSerializer[T]` would emit for the IPC-stream's schema message.
    */
  private def schemaBytes(struct: StructType, timeZoneId: String, largeVarTypes: Boolean): Array[Byte] = {
    val schema = ArrowUtils.toArrowSchema(
      struct,
      timeZoneId,
      errorOnDuplicatedFieldNames = true,
      largeVarTypes = largeVarTypes)
    schema.serializeAsMessage()
  }

  private def writeFixture(dir: File, name: String, data: Array[Byte]): Unit = {
    dir.mkdirs()
    val f = new File(dir, s"$name.arrow-schema")
    val out = new FileOutputStream(f)
    try out.write(data)
    finally out.close()
    println(f"Wrote ${f.getPath} (${data.length}%4dB, ${f.length()}B on disk)")
  }

  private def emit[T: ExpressionEncoder](dir: File, name: String, largeVarTypes: Boolean = false): Unit = {
    val struct = implicitly[ExpressionEncoder[T]].schema
    writeFixture(dir, name, schemaBytes(struct, "UTC", largeVarTypes))
  }

  // ---------------------------------------------------------------------------
  // Main: generate the fixture set.
  // ---------------------------------------------------------------------------

  def main(args: Array[String]): Unit = {
    val outDir =
      if (args.nonEmpty) new File(args(0))
      else new File("encoder-spark/src/test/resources/arrow-schema-parity")
    println(s"Output dir: ${outDir.getAbsolutePath}")

    // Per-variant fixtures. We need explicit ExpressionEncoder typeclass instances because
    // `encoderFor[T]` would otherwise pick them up via TypeTag; making them explicit makes the
    // generator's failure mode loud (compile error) if a class shape drifts.
    implicit val encSimple: ExpressionEncoder[Simple] = ExpressionEncoder[Simple]()
    implicit val encPrim: ExpressionEncoder[AllPrimitives] = ExpressionEncoder[AllPrimitives]()
    implicit val encStrings: ExpressionEncoder[Strings] = ExpressionEncoder[Strings]()
    implicit val encDecimals: ExpressionEncoder[Decimals] = ExpressionEncoder[Decimals]()
    implicit val encTemporal: ExpressionEncoder[Temporal] = ExpressionEncoder[Temporal]()
    implicit val encOptional: ExpressionEncoder[Optional] = ExpressionEncoder[Optional]()

    emit[Simple](outDir, "Simple")
    emit[AllPrimitives](outDir, "AllPrimitives")
    emit[Strings](outDir, "Strings")
    emit[Strings](outDir, "Strings-largeVarTypes", largeVarTypes = true)
    emit[Decimals](outDir, "Decimals")
    emit[Temporal](outDir, "Temporal")
    emit[Optional](outDir, "Optional")

    // TPC-H tables.
    implicit val encRegion: ExpressionEncoder[Region] = ExpressionEncoder[Region]()
    implicit val encNation: ExpressionEncoder[Nation] = ExpressionEncoder[Nation]()
    implicit val encPart: ExpressionEncoder[Part] = ExpressionEncoder[Part]()
    implicit val encSupplier: ExpressionEncoder[Supplier] = ExpressionEncoder[Supplier]()
    implicit val encPartSupp: ExpressionEncoder[PartSupp] = ExpressionEncoder[PartSupp]()
    implicit val encCustomer: ExpressionEncoder[Customer] = ExpressionEncoder[Customer]()
    implicit val encOrders: ExpressionEncoder[Orders] = ExpressionEncoder[Orders]()
    implicit val encLineitem: ExpressionEncoder[Lineitem] = ExpressionEncoder[Lineitem]()

    emit[Region](outDir, "tpch-Region")
    emit[Nation](outDir, "tpch-Nation")
    emit[Part](outDir, "tpch-Part")
    emit[Supplier](outDir, "tpch-Supplier")
    emit[PartSupp](outDir, "tpch-PartSupp")
    emit[Customer](outDir, "tpch-Customer")
    emit[Orders](outDir, "tpch-Orders")
    emit[Lineitem](outDir, "tpch-Lineitem")

    println("Done.")
  }
}
