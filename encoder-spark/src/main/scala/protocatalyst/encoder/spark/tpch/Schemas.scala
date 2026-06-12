package protocatalyst.encoder.spark.tpch

import java.time.LocalDate

/** TPC-H schema case classes (Scala 3 side).
  *
  * Field types map TPC-H spec types to JVM equivalents:
  *  - `BIGINT identifier` → `Long`
  *  - `INTEGER` → `Int`
  *  - `CHAR(n)` / `VARCHAR(n)` → `String`. We use plain `String` rather than
  *    `ProtoEncoder.charEncoder(n)` / `varcharEncoder(n)` because (a) Spark's
  *    `ScalaReflection` would also pick `StringType` for plain `String`, keeping the comparison
  *    apples-to-apples, and (b) per-row encoder cost doesn't depend on the Char/Varchar length
  *    metadata.
  *  - `DECIMAL(15, 2)` → `BigDecimal`. Both Spark's `ScalaDecimalEncoder` and our
  *    `ProtoEncoder[BigDecimal]` default to `DecimalType(38, 18)`. We document this divergence
  *    from the TPC-H spec; using the system-default precision is the only honest apples-to-apples
  *    comparison because that's what real Dataset[T] users get. See
  *    `docs/scala3-encoder/BENCHMARKS.md`.
  *  - `DATE` → `java.time.LocalDate`.
  *
  * Field names match TPC-H column names with the `l_`/`o_`/etc. table-prefixes dropped. One
  * exception: `partType` instead of `type` because the latter is a Scala reserved word.
  *
  * The Scala 2.13 mirror lives at
  * `benchmark-spark/src/main/scala/protocatalyst/benchmark/tpch/Schemas.scala`. Both files must
  * stay structurally identical; the parity tests catch divergence at the byte level.
  */
object Schemas:

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
      partType: String, // SQL: P_TYPE — renamed to avoid Scala `type` reserved word
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

  /** The wide-fact-table workhorse. 16 columns, ~6M rows per SF=1.
    * Exercises every encoder slot type we care about: Long, Int, BigDecimal, String, LocalDate.
    */
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
