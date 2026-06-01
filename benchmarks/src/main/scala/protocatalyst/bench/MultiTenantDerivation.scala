package protocatalyst.bench

import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.encoder.spark.AgnosticEncoderBridge
import protocatalyst.encoder.spark.tpch.Schemas.*

/** Multi-tenant derivation experiment — the **compile-time** side.
  *
  * Models a shared-JVM server (Spark Connect / Thrift) where `S` concurrent sessions each service a
  * stream of typed requests, every request deriving an `ExpressionEncoder[T]` via the *real public
  * injection path* — `ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[T]))`,
  * the no-`TypeTag` overload (no `ScalaReflection`, no global lock). Requests cycle over the 8 TPC-H
  * types so no per-type cache could mask the cost.
  *
  * Reports, per session count, aggregate throughput (derivations/sec) and request latency
  * percentiles (p50/p99, microseconds). The lock-free counterpart to
  * `org.apache.spark.sql.protocatalyst.MultiTenantDerivation` (Scala 2.13, reflective). Run:
  * {{{ sbt 'benchmarks/runMain protocatalyst.bench.MultiTenantDerivation' }}}
  */
object MultiTenantDerivation:

  // One thunk per TPC-H type — the exact artifact a server builds per typed request.
  private val requests: Array[() => AnyRef] = Array(
    () => ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Region])),
    () => ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Nation])),
    () => ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Part])),
    () => ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Supplier])),
    () => ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[PartSupp])),
    () => ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Customer])),
    () => ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Orders])),
    () => ExpressionEncoder(AgnosticEncoderBridge.toAgnostic(ProtoEncoder.derived[Lineitem]))
  )

  def main(args: Array[String]): Unit =
    MultiTenantHarness.run("compile-time (bridge, lock-free)", requests)
