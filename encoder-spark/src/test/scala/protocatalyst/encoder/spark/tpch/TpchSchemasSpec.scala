package protocatalyst.encoder.spark.tpch

import java.time.LocalDate

import munit.FunSuite

import protocatalyst.encoder.spark.UnsafeRowSerializer
import protocatalyst.encoder.spark.tpch.Schemas.*

/** Verifies the 8 TPC-H case classes are end-to-end functional with our serializer:
  *  - The `.tbl` parser produces correct values for each table shape.
  *  - `UnsafeRowSerializer.derived[T]` compiles + serializes + round-trips for each.
  *
  * Test `.tbl` lines are drawn from real `dbgen` SF=1 output (first row of each table). This
  * exercises every encoder slot type used by TPC-H (Long, Int, BigDecimal, String, LocalDate)
  * across realistic field counts (3 → 16 columns).
  */
class TpchSchemasSpec extends FunSuite:

  private inline def roundtrip[T](ser: UnsafeRowSerializer[T], value: T): T =
    ser.deserialize(ser.serialize(value))

  // === Region (3 cols) — narrowest table ===

  test("Region: parse + roundtrip"):
    val line = "0|AFRICA|lar deposits. blithely final packages cajole.|"
    val parsed = TblParser.parseRegion(line)
    assertEquals(parsed.regionkey, 0L)
    assertEquals(parsed.name, "AFRICA")

    val ser = UnsafeRowSerializer.derived[Region]
    assertEquals(roundtrip(ser, parsed), parsed)

  // === Nation (4 cols) ===

  test("Nation: parse + roundtrip"):
    val line = "0|ALGERIA|0| haggle. carefully final deposits detect slyly agai|"
    val parsed = TblParser.parseNation(line)
    assertEquals(parsed.nationkey, 0L)
    assertEquals(parsed.name, "ALGERIA")
    assertEquals(parsed.regionkey, 0L)

    val ser = UnsafeRowSerializer.derived[Nation]
    assertEquals(roundtrip(ser, parsed), parsed)

  // === Part (9 cols) — first with BigDecimal + Int ===

  test("Part: parse + roundtrip"):
    val line = "1|goldenrod lavender spring chocolate lace|Manufacturer#1|Brand#13|" +
      "PROMO BURNISHED COPPER|7|JUMBO PKG|901.00|ly. slyly ironi|"
    val parsed = TblParser.parsePart(line)
    assertEquals(parsed.partkey, 1L)
    assertEquals(parsed.partType, "PROMO BURNISHED COPPER")
    assertEquals(parsed.size, 7)
    assertEquals(parsed.retailprice, BigDecimal("901.00"))

    val ser = UnsafeRowSerializer.derived[Part]
    assertEquals(roundtrip(ser, parsed), parsed)

  // === Supplier (7 cols) ===

  test("Supplier: parse + roundtrip"):
    val line = "1|Supplier#000000001| N kD4on9OM Ipw3,gf0JBoQDd7tgrzrddZ|17|" +
      "27-918-335-1736|5755.94|each slyly above the careful|"
    val parsed = TblParser.parseSupplier(line)
    assertEquals(parsed.suppkey, 1L)
    assertEquals(parsed.nationkey, 17L)
    assertEquals(parsed.acctbal, BigDecimal("5755.94"))

    val ser = UnsafeRowSerializer.derived[Supplier]
    assertEquals(roundtrip(ser, parsed), parsed)

  // === PartSupp (5 cols) ===

  test("PartSupp: parse + roundtrip"):
    val line = "1|2|3325|771.64|carefully pending foxes|"
    val parsed = TblParser.parsePartSupp(line)
    assertEquals(parsed.partkey, 1L)
    assertEquals(parsed.suppkey, 2L)
    assertEquals(parsed.availqty, 3325)

    val ser = UnsafeRowSerializer.derived[PartSupp]
    assertEquals(roundtrip(ser, parsed), parsed)

  // === Customer (8 cols) ===

  test("Customer: parse + roundtrip"):
    val line = "1|Customer#000000001|IVhzIApeRb ot,c,E|15|25-989-741-2988|" +
      "711.56|BUILDING|to the even, regular platelets|"
    val parsed = TblParser.parseCustomer(line)
    assertEquals(parsed.custkey, 1L)
    assertEquals(parsed.mktsegment, "BUILDING")
    assertEquals(parsed.acctbal, BigDecimal("711.56"))

    val ser = UnsafeRowSerializer.derived[Customer]
    assertEquals(roundtrip(ser, parsed), parsed)

  // === Orders (9 cols) — first with LocalDate ===

  test("Orders: parse + roundtrip"):
    val line = "1|36901|O|173665.47|1996-01-02|5-LOW|Clerk#000000951|0|" +
      "nstructions sleep furiously among |"
    val parsed = TblParser.parseOrders(line)
    assertEquals(parsed.orderkey, 1L)
    assertEquals(parsed.orderdate, LocalDate.of(1996, 1, 2))
    assertEquals(parsed.totalprice, BigDecimal("173665.47"))

    val ser = UnsafeRowSerializer.derived[Orders]
    assertEquals(roundtrip(ser, parsed), parsed)

  // === Lineitem (16 cols) — the workhorse ===

  test("Lineitem: parse + roundtrip"):
    val line = "1|155190|7706|1|17.00|21168.23|0.04|0.02|N|O|" +
      "1996-03-13|1996-02-12|1996-03-22|DELIVER IN PERSON|TRUCK|" +
      "egular courts above the|"
    val parsed = TblParser.parseLineitem(line)
    assertEquals(parsed.orderkey, 1L)
    assertEquals(parsed.linenumber, 1)
    assertEquals(parsed.extendedprice, BigDecimal("21168.23"))
    assertEquals(parsed.shipdate, LocalDate.of(1996, 3, 13))
    assertEquals(parsed.shipmode, "TRUCK")

    val ser = UnsafeRowSerializer.derived[Lineitem]
    assertEquals(roundtrip(ser, parsed), parsed)

  test("Lineitem: serialize produces 16-field UnsafeRow"):
    val ser = UnsafeRowSerializer.derived[Lineitem]
    val row = ser.serialize(
      Lineitem(
        1L, 155190L, 7706L, 1, BigDecimal("17.00"), BigDecimal("21168.23"),
        BigDecimal("0.04"), BigDecimal("0.02"), "N", "O",
        LocalDate.of(1996, 3, 13), LocalDate.of(1996, 2, 12), LocalDate.of(1996, 3, 22),
        "DELIVER IN PERSON", "TRUCK", "egular courts above the"
      )
    )
    assertEquals(row.numFields, 16)
    // Decimal slots use offset+size encoding for variable-width. Just verify they're non-null.
    (0 until 16).foreach(i => assert(!row.isNullAt(i), s"field $i unexpectedly null"))
