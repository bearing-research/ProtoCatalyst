package protocatalyst.benchmark.tpch

import java.io.BufferedReader
import java.nio.file.{Files, Path}
import java.time.LocalDate

import scala.collection.mutable.ArrayBuffer

import protocatalyst.benchmark.tpch.Schemas._

/** Pipe-delimited `.tbl` parser, Scala 2.13 mirror of
 * `encoder-spark/.../tpch/TblParser.scala`. The format is identical; both files must stay
 * structurally aligned. Used by the Scala-2.13 side of the JMH encoder benchmark.
 */
object TblParser {

  private final val DELIMITER = '|'

  private def splitFields(line: String, expected: Int): Array[String] = {
    val out = new Array[String](expected)
    var i = 0
    var start = 0
    var col = 0
    while (i < line.length && col < expected) {
      if (line.charAt(i) == DELIMITER) {
        out(col) = line.substring(start, i)
        col += 1
        start = i + 1
      }
      i += 1
    }
    if (col < expected) {
      throw new IllegalArgumentException(
        s"Expected $expected columns, got $col, in line: ${line.take(120)}…"
      )
    }
    out
  }

  def parseRegion(line: String): Region = {
    val f = splitFields(line, 3)
    Region(f(0).toLong, f(1), f(2))
  }

  def parseNation(line: String): Nation = {
    val f = splitFields(line, 4)
    Nation(f(0).toLong, f(1), f(2).toLong, f(3))
  }

  def parsePart(line: String): Part = {
    val f = splitFields(line, 9)
    Part(f(0).toLong, f(1), f(2), f(3), f(4), f(5).toInt, f(6), BigDecimal(f(7)), f(8))
  }

  def parseSupplier(line: String): Supplier = {
    val f = splitFields(line, 7)
    Supplier(f(0).toLong, f(1), f(2), f(3).toLong, f(4), BigDecimal(f(5)), f(6))
  }

  def parsePartSupp(line: String): PartSupp = {
    val f = splitFields(line, 5)
    PartSupp(f(0).toLong, f(1).toLong, f(2).toInt, BigDecimal(f(3)), f(4))
  }

  def parseCustomer(line: String): Customer = {
    val f = splitFields(line, 8)
    Customer(f(0).toLong, f(1), f(2), f(3).toLong, f(4), BigDecimal(f(5)), f(6), f(7))
  }

  def parseOrders(line: String): Orders = {
    val f = splitFields(line, 9)
    Orders(
      f(0).toLong, f(1).toLong, f(2), BigDecimal(f(3)), LocalDate.parse(f(4)),
      f(5), f(6), f(7).toInt, f(8)
    )
  }

  def parseLineitem(line: String): Lineitem = {
    val f = splitFields(line, 16)
    Lineitem(
      f(0).toLong, f(1).toLong, f(2).toLong, f(3).toInt,
      BigDecimal(f(4)), BigDecimal(f(5)), BigDecimal(f(6)), BigDecimal(f(7)),
      f(8), f(9),
      LocalDate.parse(f(10)), LocalDate.parse(f(11)), LocalDate.parse(f(12)),
      f(13), f(14), f(15)
    )
  }

  // === Bulk loaders ===

  def readAllCapped[T](path: Path, parse: String => T, limit: Int): Vector[T] = {
    val rows = ArrayBuffer.empty[T]
    val reader = Files.newBufferedReader(path)
    try {
      var line = reader.readLine()
      while (line != null && rows.size < limit) {
        if (line.nonEmpty) rows += parse(line)
        line = reader.readLine()
      }
    } finally reader.close()
    rows.toVector
  }
}
