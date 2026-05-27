package protocatalyst.benchmark.tpch

import java.time.LocalDate

/** TPC-H schema case classes (Scala 2.13 side).
 *
 * Structural mirror of `encoder-spark/.../tpch/Schemas.scala`. Both files must stay
 * field-for-field identical so the JMH benchmarks compare the same shape. See that file for the
 * TPC-H ↔ JVM type mapping rationale and the BigDecimal-precision divergence note.
 *
 * The Scala 3 side is the authoritative copy when in doubt — when the encoder benchmark report
 * cites "Lineitem has 16 columns of these types," that's what readers will look at.
 */
object Schemas {

  case class Region(
      regionkey: Long,
      name: String,
      comment: String
  )

  case class Nation(
      nationkey: Long,
      name: String,
      regionkey: Long,
      comment: String
  )

  case class Part(
      partkey: Long,
      name: String,
      mfgr: String,
      brand: String,
      partType: String, // SQL: P_TYPE
      size: Int,
      container: String,
      retailprice: BigDecimal,
      comment: String
  )

  case class Supplier(
      suppkey: Long,
      name: String,
      address: String,
      nationkey: Long,
      phone: String,
      acctbal: BigDecimal,
      comment: String
  )

  case class PartSupp(
      partkey: Long,
      suppkey: Long,
      availqty: Int,
      supplycost: BigDecimal,
      comment: String
  )

  case class Customer(
      custkey: Long,
      name: String,
      address: String,
      nationkey: Long,
      phone: String,
      acctbal: BigDecimal,
      mktsegment: String,
      comment: String
  )

  case class Orders(
      orderkey: Long,
      custkey: Long,
      orderstatus: String,
      totalprice: BigDecimal,
      orderdate: LocalDate,
      orderpriority: String,
      clerk: String,
      shippriority: Int,
      comment: String
  )

  case class Lineitem(
      orderkey: Long,
      partkey: Long,
      suppkey: Long,
      linenumber: Int,
      quantity: BigDecimal,
      extendedprice: BigDecimal,
      discount: BigDecimal,
      tax: BigDecimal,
      returnflag: String,
      linestatus: String,
      shipdate: LocalDate,
      commitdate: LocalDate,
      receiptdate: LocalDate,
      shipinstruct: String,
      shipmode: String,
      comment: String
  )
}
