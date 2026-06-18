package protocatalyst.truffle.backend.bench

import java.io.FileOutputStream
import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters.*

import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.dictionary.DictionaryProvider.MapDictionaryProvider
import org.apache.arrow.vector.ipc.ArrowFileWriter
import org.apache.arrow.vector.util.VectorSchemaRootAppender

import protocatalyst.arrow.parquet.ParquetIO

/** One-time JVM converter: TPC-H parquet → a single-record-batch Arrow IPC file per table, under
  * `data/tpch/sf-$sf-arrow/`. The cold-start driver (`TpchNativeDriver`, also the native-image
  * entrypoint) reads these instead of parquet — parquet-mr + Hadoop are infeasible to native-image
  * (heavy reflection); the Arrow IPC reader lives in `arrow-vector` and is far lighter.
  *
  * Run: `sbt 'truffleBackend/runMain protocatalyst.truffle.backend.bench.TpchArrowConvert --sf 0.01'`
  */
object TpchArrowConvert:

  private val Tables = Seq("lineitem", "orders", "customer", "supplier", "nation", "region", "part", "partsupp")

  def main(args: Array[String]): Unit =
    val sf = argOr(args, "--sf", "0.01")
    val src = locate(s"sf-$sf-parquet")
    val outDir = Paths.get(src).getParent.resolve(s"sf-$sf-arrow")
    Files.createDirectories(outDir)
    val allocator = new RootAllocator()
    for t <- Tables if Files.isDirectory(Paths.get(s"$src/$t.parquet")) do
      val parts = Files.list(Paths.get(s"$src/$t.parquet")).iterator.asScala
        .map(_.toString).filter(_.endsWith(".parquet")).toVector.sorted
      val (root, _) = ParquetIO.read(parts.head, allocator)
      parts.tail.foreach { p =>
        val (r, _) = ParquetIO.read(p, allocator)
        VectorSchemaRootAppender.append(root, r); r.close()
      }
      writeIpc(root, outDir.resolve(s"$t.arrow").toString)
      println(s"# $t -> ${outDir.resolve(s"$t.arrow")} (${root.getRowCount} rows)")
      root.close()
    allocator.close()
    println(s"# done: $outDir")

  private def writeIpc(root: VectorSchemaRoot, path: String): Unit =
    val out = new FileOutputStream(path)
    val writer = new ArrowFileWriter(root, new MapDictionaryProvider(), out.getChannel)
    try
      writer.start()
      writer.writeBatch()
      writer.end()
    finally
      writer.close()
      out.close()

  private def locate(rel: String): String =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .take(6)
      .map(_.resolve(Paths.get("data", "tpch", rel)))
      .find(p => Files.isDirectory(p.resolve("lineitem.parquet")))
      .map(_.toString)
      .getOrElse(sys.error(s"parquet dir not found: data/tpch/$rel"))

  private def argOr(args: Array[String], flag: String, default: String): String =
    val i = args.indexOf(flag); if i >= 0 && i + 1 < args.length then args(i + 1) else default
