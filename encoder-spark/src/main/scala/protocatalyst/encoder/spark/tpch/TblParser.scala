package protocatalyst.encoder.spark.tpch

import java.io.BufferedReader
import java.nio.file.{Files, Path}
import java.time.LocalDate

import scala.collection.mutable.ArrayBuffer

import protocatalyst.encoder.spark.tpch.Schemas.*

/** Parser for TPC-H `.tbl` files (the format emitted by the `dbgen` tool).
  *
  * `.tbl` is pipe-delimited, one row per line, with a trailing `|` on every line. Column order
  * matches the TPC-H spec. Decimals are unquoted, dates are `yyyy-MM-dd`. No header row.
  *
  * Used by `B.1` to load dbgen output into our case classes for encoder benchmarks. Parquet read
  * via Spark is the alternative path for end-to-end benchmarks.
  */
object TblParser:

  private inline val DELIMITER = '|'

  /** Split a `.tbl` line into raw column strings. Faster than `String.split('|')` because we know
    * lines end with a trailing `|` and we don't need the empty trailing token.
    */
  private def splitFields(line: String, expected: Int): Array[String] =
    val out = new Array[String](expected)
    var i = 0
    var start = 0
    var col = 0
    while i < line.length && col < expected do
      if line.charAt(i) == DELIMITER then
        out(col) = line.substring(start, i)
        col += 1
        start = i + 1
      i += 1
    if col < expected then
      throw new IllegalArgumentException(
        s"Expected $expected columns, got $col, in line: ${line.take(120)}…"
      )
    out

  // === Per-table parsers ===

  def parseRegion(line: String): Region =
    val f = splitFields(line, 3)
    Region(f(0).toLong, f(1), f(2))

  def parseNation(line: String): Nation =
    val f = splitFields(line, 4)
    Nation(f(0).toLong, f(1), f(2).toLong, f(3))

  def parsePart(line: String): Part =
    val f = splitFields(line, 9)
    Part(
      f(0).toLong,
      f(1),
      f(2),
      f(3),
      f(4),
      f(5).toInt,
      f(6),
      BigDecimal(f(7)),
      f(8)
    )

  def parseSupplier(line: String): Supplier =
    val f = splitFields(line, 7)
    Supplier(
      f(0).toLong,
      f(1),
      f(2),
      f(3).toLong,
      f(4),
      BigDecimal(f(5)),
      f(6)
    )

  def parsePartSupp(line: String): PartSupp =
    val f = splitFields(line, 5)
    PartSupp(
      f(0).toLong,
      f(1).toLong,
      f(2).toInt,
      BigDecimal(f(3)),
      f(4)
    )

  def parseCustomer(line: String): Customer =
    val f = splitFields(line, 8)
    Customer(
      f(0).toLong,
      f(1),
      f(2),
      f(3).toLong,
      f(4),
      BigDecimal(f(5)),
      f(6),
      f(7)
    )

  def parseOrders(line: String): Orders =
    val f = splitFields(line, 9)
    Orders(
      f(0).toLong,
      f(1).toLong,
      f(2),
      BigDecimal(f(3)),
      LocalDate.parse(f(4)),
      f(5),
      f(6),
      f(7).toInt,
      f(8)
    )

  def parseLineitem(line: String): Lineitem =
    val f = splitFields(line, 16)
    Lineitem(
      f(0).toLong,
      f(1).toLong,
      f(2).toLong,
      f(3).toInt,
      BigDecimal(f(4)),
      BigDecimal(f(5)),
      BigDecimal(f(6)),
      BigDecimal(f(7)),
      f(8),
      f(9),
      LocalDate.parse(f(10)),
      LocalDate.parse(f(11)),
      LocalDate.parse(f(12)),
      f(13),
      f(14),
      f(15)
    )

  // === Bulk loaders ===

  /** Read an entire `.tbl` file into memory. Returns parsed rows in file order. */
  def readAll[T](path: Path, parse: String => T): Vector[T] =
    val out = ArrayBuffer.empty[T]
    val reader = Files.newBufferedReader(path)
    try
      var line = reader.readLine()
      while line != null do
        if line.nonEmpty then out += parse(line)
        line = reader.readLine()
    finally reader.close()
    out.toVector

  /** Stream `.tbl` lines through `parse` without materializing the full collection. Useful for
    * JMH benchmarks at SF≥1 where loading 6M+ rows into a Vector blows the heap.
    */
  def foreachLine[T](path: Path, parse: String => T)(handler: T => Unit): Unit =
    val reader = Files.newBufferedReader(path)
    try foreachLineWithReader(reader, parse, handler)
    finally reader.close()

  private def foreachLineWithReader[T](
      reader: BufferedReader,
      parse: String => T,
      handler: T => Unit
  ): Unit =
    var line = reader.readLine()
    while line != null do
      if line.nonEmpty then handler(parse(line))
      line = reader.readLine()
