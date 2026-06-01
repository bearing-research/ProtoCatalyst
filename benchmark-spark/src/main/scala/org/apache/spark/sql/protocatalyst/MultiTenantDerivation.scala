package org.apache.spark.sql.protocatalyst

import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import protocatalyst.benchmark.tpch.Schemas._

/** Multi-tenant derivation experiment — the **reflective** side. Models a shared-JVM server where
  * `S` concurrent sessions each derive an `ExpressionEncoder[T]` via the real public API
  * `ExpressionEncoder[T: TypeTag]()` → `ScalaReflection.encoderFor` → the global `ScalaSubtypeLock`.
  * Requests cycle over the 8 TPC-H types. Run:
  * {{{ sbt 'benchmarkSpark/runMain org.apache.spark.sql.protocatalyst.MultiTenantDerivation' }}} */
object MultiTenantDerivation {
  private val requests: Array[() => AnyRef] = Array(
    () => ExpressionEncoder[Region](),
    () => ExpressionEncoder[Nation](),
    () => ExpressionEncoder[Part](),
    () => ExpressionEncoder[Supplier](),
    () => ExpressionEncoder[PartSupp](),
    () => ExpressionEncoder[Customer](),
    () => ExpressionEncoder[Orders](),
    () => ExpressionEncoder[Lineitem]()
  )
  def main(args: Array[String]): Unit =
    MultiTenantHarness.run("reflective (ScalaReflection.encoderFor, global lock)", requests)
}
