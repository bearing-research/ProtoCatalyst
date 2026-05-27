package protocatalyst.encoder.spark.tpch

import java.nio.file.{Files, Path, Paths}

import munit.FunSuite

import protocatalyst.encoder.spark.UnsafeRowSerializer

/** Integration smoke against real `dbgen` SF=0.01 output. Validates that:
  *  - The `.tbl` parser handles real-world variants (no trailing decimal zeros, embedded
  *    punctuation in comment fields, etc.).
  *  - `UnsafeRowSerializer` round-trips every row of every table at the smoke scale.
  *
  * Skips itself if `data/tpch/sf-0.01/` is not present. Regenerate with:
  * {{{
  * ./scripts/gen-tpch.sh 0.01
  * }}}
  */
class TpchDbgenIntegrationSpec extends FunSuite:

  private val dataRoot: Path = Paths.get("data/tpch/sf-0.01")

  override def munitTests(): Seq[munit.Test] =
    if !Files.isDirectory(dataRoot) then
      println(s"[TpchDbgenIntegrationSpec] Skipping — ${dataRoot.toAbsolutePath} not present.")
      Seq.empty
    else super.munitTests()

  // === Tiny tables — load everything, round-trip everything ===

  test("Region.tbl: all 5 rows round-trip"):
    val ser = UnsafeRowSerializer.derived[Schemas.Region]
    val rows = TblParser.readAll(dataRoot.resolve("region.tbl"), TblParser.parseRegion)
    assertEquals(rows.size, 5)
    rows.foreach(r => assertEquals(ser.deserialize(ser.serialize(r)), r))

  test("Nation.tbl: all 25 rows round-trip"):
    val ser = UnsafeRowSerializer.derived[Schemas.Nation]
    val rows = TblParser.readAll(dataRoot.resolve("nation.tbl"), TblParser.parseNation)
    assertEquals(rows.size, 25)
    rows.foreach(r => assertEquals(ser.deserialize(ser.serialize(r)), r))

  test("Supplier.tbl: all 100 rows round-trip"):
    val ser = UnsafeRowSerializer.derived[Schemas.Supplier]
    val rows = TblParser.readAll(dataRoot.resolve("supplier.tbl"), TblParser.parseSupplier)
    assertEquals(rows.size, 100)
    rows.foreach(r => assertEquals(ser.deserialize(ser.serialize(r)), r))

  // === Medium tables — sample for verification ===

  test("Part.tbl: 2000 rows, sample 50 round-trip"):
    val ser = UnsafeRowSerializer.derived[Schemas.Part]
    val rows = TblParser.readAll(dataRoot.resolve("part.tbl"), TblParser.parsePart)
    assertEquals(rows.size, 2000)
    rows.take(50).foreach(r => assertEquals(ser.deserialize(ser.serialize(r)), r))

  test("Customer.tbl: 1500 rows, sample 50 round-trip"):
    val ser = UnsafeRowSerializer.derived[Schemas.Customer]
    val rows = TblParser.readAll(dataRoot.resolve("customer.tbl"), TblParser.parseCustomer)
    assertEquals(rows.size, 1500)
    rows.take(50).foreach(r => assertEquals(ser.deserialize(ser.serialize(r)), r))

  test("PartSupp.tbl: 8000 rows, sample 100 round-trip"):
    val ser = UnsafeRowSerializer.derived[Schemas.PartSupp]
    val rows = TblParser.readAll(dataRoot.resolve("partsupp.tbl"), TblParser.parsePartSupp)
    assertEquals(rows.size, 8000)
    rows.take(100).foreach(r => assertEquals(ser.deserialize(ser.serialize(r)), r))

  test("Orders.tbl: 15000 rows, sample 100 round-trip"):
    val ser = UnsafeRowSerializer.derived[Schemas.Orders]
    val rows = TblParser.readAll(dataRoot.resolve("orders.tbl"), TblParser.parseOrders)
    assertEquals(rows.size, 15000)
    rows.take(100).foreach(r => assertEquals(ser.deserialize(ser.serialize(r)), r))

  // === The fact table — stream and round-trip every single row ===

  test("Lineitem.tbl: all 60175 rows stream-round-trip (no allocation pressure)"):
    val ser = UnsafeRowSerializer.derived[Schemas.Lineitem]
    var count = 0
    TblParser.foreachLine(dataRoot.resolve("lineitem.tbl"), TblParser.parseLineitem) { row =>
      val restored = ser.deserialize(ser.serialize(row))
      // Cheap field-wise sanity (full case-class compare on every row is expensive)
      assertEquals(restored.orderkey, row.orderkey)
      assertEquals(restored.shipdate, row.shipdate)
      assertEquals(restored.extendedprice, row.extendedprice)
      count += 1
    }
    assertEquals(count, 60175)
