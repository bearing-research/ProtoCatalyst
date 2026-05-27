package protocatalyst.benchmark.tpch

import java.io.File
import java.nio.file.{Files, Paths}

import org.apache.spark.sql.SparkSession

/** Converts TPC-H `.tbl` files to Parquet for the end-to-end query benchmarks.
 *
 * `.tbl` is a pipe-delimited text format that Spark CAN read but slowly (full parse on every
 * scan). Parquet is the realistic Spark storage format for analytics — column-oriented,
 * compressed, indexed. End-to-end TPC-H reports use Parquet by convention.
 *
 * Reads each TPC-H table from `data/tpch/sf-<sf>/<table>.tbl`, writes to
 * `data/tpch/sf-<sf>-parquet/<table>.parquet/` (Spark writes Parquet as a directory of part
 * files). Runs as a one-shot tool:
 *
 * {{{
 *   sbt 'benchmarkSpark/runMain protocatalyst.benchmark.tpch.TpchParquetConverter 1'
 * }}}
 */
object TpchParquetConverter {

  private val tables = Seq(
    "lineitem", "orders", "customer", "part", "supplier", "partsupp", "nation", "region"
  )

  def main(args: Array[String]): Unit = {
    val sf = if (args.nonEmpty) args(0) else "1"
    val srcRoot = Paths.get(s"data/tpch/sf-$sf")
    val dstRoot = Paths.get(s"data/tpch/sf-$sf-parquet")

    if (!Files.isDirectory(srcRoot)) {
      System.err.println(s"Source not found: $srcRoot")
      System.err.println(s"Generate it first:  ./scripts/gen-tpch.sh $sf")
      sys.exit(1)
    }

    val spark = SparkSession.builder()
      .appName("TpchParquetConverter")
      .master("local[*]")
      .config("spark.sql.shuffle.partitions", "8")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.driver.host", "127.0.0.1")
      // The REPL artifact class server isn't reachable when running under sbt; in local mode
      // user classes are already on the classpath, so this is safe to disable.
      .config("spark.repl.class.outputDir", "")
      .getOrCreate()

    try {
      import spark.implicits._

      Files.createDirectories(dstRoot)

      println(s"==> Converting SF=$sf .tbl → Parquet")
      println(s"    Source: ${srcRoot.toAbsolutePath}")
      println(s"    Dest:   ${dstRoot.toAbsolutePath}")

      // Each table: read .tbl with the right parser, build Dataset[T], write Parquet.
      // Using as[T] gives us schema-correct Parquet directly, no manual schema declaration.

      writeOne(spark, srcRoot.resolve("region.tbl").toString,
        dstRoot.resolve("region.parquet").toString,
        spark.sparkContext.textFile(srcRoot.resolve("region.tbl").toString)
          .map(TblParser.parseRegion).toDS()
      )
      writeOne(spark, srcRoot.resolve("nation.tbl").toString,
        dstRoot.resolve("nation.parquet").toString,
        spark.sparkContext.textFile(srcRoot.resolve("nation.tbl").toString)
          .map(TblParser.parseNation).toDS()
      )
      writeOne(spark, srcRoot.resolve("supplier.tbl").toString,
        dstRoot.resolve("supplier.parquet").toString,
        spark.sparkContext.textFile(srcRoot.resolve("supplier.tbl").toString)
          .map(TblParser.parseSupplier).toDS()
      )
      writeOne(spark, srcRoot.resolve("part.tbl").toString,
        dstRoot.resolve("part.parquet").toString,
        spark.sparkContext.textFile(srcRoot.resolve("part.tbl").toString)
          .map(TblParser.parsePart).toDS()
      )
      writeOne(spark, srcRoot.resolve("partsupp.tbl").toString,
        dstRoot.resolve("partsupp.parquet").toString,
        spark.sparkContext.textFile(srcRoot.resolve("partsupp.tbl").toString)
          .map(TblParser.parsePartSupp).toDS()
      )
      writeOne(spark, srcRoot.resolve("customer.tbl").toString,
        dstRoot.resolve("customer.parquet").toString,
        spark.sparkContext.textFile(srcRoot.resolve("customer.tbl").toString)
          .map(TblParser.parseCustomer).toDS()
      )
      writeOne(spark, srcRoot.resolve("orders.tbl").toString,
        dstRoot.resolve("orders.parquet").toString,
        spark.sparkContext.textFile(srcRoot.resolve("orders.tbl").toString)
          .map(TblParser.parseOrders).toDS()
      )
      writeOne(spark, srcRoot.resolve("lineitem.tbl").toString,
        dstRoot.resolve("lineitem.parquet").toString,
        spark.sparkContext.textFile(srcRoot.resolve("lineitem.tbl").toString)
          .map(TblParser.parseLineitem).toDS()
      )

      println("==> Done.")
      tables.foreach { t =>
        val d = new File(dstRoot.resolve(s"$t.parquet").toString)
        if (d.exists()) {
          val size = sizeOf(d)
          println(f"    $t%-10s  $size%10s")
        }
      }
    } finally spark.stop()
  }

  private def writeOne[T](spark: SparkSession, src: String, dst: String, ds: org.apache.spark.sql.Dataset[T]): Unit = {
    if (new File(dst).exists()) {
      println(s"    skip (exists): $dst")
      return
    }
    println(s"    writing: $dst")
    ds.write.mode("overwrite").parquet(dst)
  }

  private def bytesOf(f: File): Long =
    if (f.isDirectory) {
      val files = f.listFiles()
      if (files == null) 0L else files.iterator.map(bytesOf).sum
    } else f.length()

  private def formatSize(b: Long): String =
    if (b > (1L << 30)) f"${b.toDouble / (1L << 30)}%.1fG"
    else if (b > (1L << 20)) f"${b.toDouble / (1L << 20)}%.1fM"
    else if (b > (1L << 10)) f"${b.toDouble / (1L << 10)}%.1fK"
    else s"${b}B"

  private def sizeOf(f: File): String = formatSize(bytesOf(f))
}
