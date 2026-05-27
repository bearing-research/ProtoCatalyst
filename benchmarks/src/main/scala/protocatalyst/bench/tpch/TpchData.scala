package protocatalyst.bench.tpch

import java.nio.file.{Files, Path, Paths}

import protocatalyst.encoder.spark.tpch.{Schemas, TblParser}

/** Shared TPC-H data loader for JMH benchmarks (Scala 3 side).
  *
  * Each table is loaded eagerly into a `Vector` so the inner JMH benchmark loop can index in
  * O(1) without paying I/O cost. Sample size capped at `SAMPLE_LIMIT` to keep heap reasonable
  * across JMH iterations.
  *
  * Expects `data/tpch/sf-<sf>/<table>.tbl` to exist at the project root. Regenerate via
  * `./scripts/gen-tpch.sh <sf>`.
  */
object TpchData:

  /** Hard cap on rows materialized per table per benchmark, to keep memory predictable. JMH
    * benchmarks index modulo this. */
  val SAMPLE_LIMIT: Int = 10_000

  def dataRoot(sf: String): Path = Paths.get(s"data/tpch/sf-$sf")

  def requireExists(sf: String): Path =
    val root = dataRoot(sf)
    if !Files.isDirectory(root) then
      throw new IllegalStateException(
        s"TPC-H data not present at $root. Generate it first:\n" +
          s"  ./scripts/gen-tpch.sh $sf"
      )
    root

  private def loadCapped[T](path: Path, parse: String => T): Vector[T] =
    val rows = scala.collection.mutable.ArrayBuffer.empty[T]
    val reader = Files.newBufferedReader(path)
    try
      var line = reader.readLine()
      while line != null && rows.size < SAMPLE_LIMIT do
        if line.nonEmpty then rows += parse(line)
        line = reader.readLine()
    finally reader.close()
    rows.toVector

  // Convenience loaders, one per table.

  def loadLineitem(sf: String): Vector[Schemas.Lineitem] =
    loadCapped(requireExists(sf).resolve("lineitem.tbl"), TblParser.parseLineitem)

  def loadOrders(sf: String): Vector[Schemas.Orders] =
    loadCapped(requireExists(sf).resolve("orders.tbl"), TblParser.parseOrders)

  def loadCustomer(sf: String): Vector[Schemas.Customer] =
    loadCapped(requireExists(sf).resolve("customer.tbl"), TblParser.parseCustomer)

  def loadPart(sf: String): Vector[Schemas.Part] =
    loadCapped(requireExists(sf).resolve("part.tbl"), TblParser.parsePart)

  def loadSupplier(sf: String): Vector[Schemas.Supplier] =
    loadCapped(requireExists(sf).resolve("supplier.tbl"), TblParser.parseSupplier)

  def loadPartSupp(sf: String): Vector[Schemas.PartSupp] =
    loadCapped(requireExists(sf).resolve("partsupp.tbl"), TblParser.parsePartSupp)

  def loadNation(sf: String): Vector[Schemas.Nation] =
    loadCapped(requireExists(sf).resolve("nation.tbl"), TblParser.parseNation)

  def loadRegion(sf: String): Vector[Schemas.Region] =
    loadCapped(requireExists(sf).resolve("region.tbl"), TblParser.parseRegion)
