package protocatalyst.benchmark.tpch

import java.nio.file.{Files, Path, Paths}

/** Shared TPC-H data loader, Scala 2.13 mirror. See `protocatalyst.bench.tpch.TpchData` for
 * the Scala 3 version. Both load the same `data/tpch/sf-<sf>/<table>.tbl` files.
 */
object TpchData {

  val SAMPLE_LIMIT: Int = 10000

  def dataRoot(sf: String): Path = Paths.get(s"data/tpch/sf-$sf")

  def requireExists(sf: String): Path = {
    val root = dataRoot(sf)
    if (!Files.isDirectory(root)) {
      throw new IllegalStateException(
        s"TPC-H data not present at $root. Generate it first:\n" +
          s"  ./scripts/gen-tpch.sh $sf"
      )
    }
    root
  }

  def loadLineitem(sf: String): Vector[Schemas.Lineitem] =
    TblParser.readAllCapped(requireExists(sf).resolve("lineitem.tbl"), TblParser.parseLineitem, SAMPLE_LIMIT)
  def loadOrders(sf: String): Vector[Schemas.Orders] =
    TblParser.readAllCapped(requireExists(sf).resolve("orders.tbl"), TblParser.parseOrders, SAMPLE_LIMIT)
  def loadCustomer(sf: String): Vector[Schemas.Customer] =
    TblParser.readAllCapped(requireExists(sf).resolve("customer.tbl"), TblParser.parseCustomer, SAMPLE_LIMIT)
  def loadPart(sf: String): Vector[Schemas.Part] =
    TblParser.readAllCapped(requireExists(sf).resolve("part.tbl"), TblParser.parsePart, SAMPLE_LIMIT)
}
