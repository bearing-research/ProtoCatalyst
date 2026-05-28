package protocatalyst.encoder.spark.arrow

import org.apache.arrow.vector.VectorSchemaRoot

/** Per-row Arrow deserializer for case class `T`, deriving its reader at `scalac` time.
  *
  * Mirror of [[ArrowRowSerializer]]: given an Arrow [[VectorSchemaRoot]] and a row index,
  * materialize a `T`. Dispatch is fully monomorphic — the macro emits a single `new T(...)`
  * constructor call whose arguments are typed `vec.get(idx)` reads, jsoniter-scala style. No
  * `Mirror.fromProduct`, no `Array[Any]`, no boxing for primitive fields.
  *
  * Phase A9 scope mirrors the writer (A4 + A8): primitives, String, Array[Byte] (with
  * largeVarTypes dispatch), Scala/Java BigDecimal, LocalDate/jsql.Date, Instant/jsql.Timestamp,
  * LocalDateTime, LocalTime, Duration/Period, and `Option[X]` over any of those.
  */
trait ArrowRowDeserializer[T]:

  /** Read row `index` from `root` into a `T`. The caller owns `root`'s lifecycle. */
  def read(root: VectorSchemaRoot, index: Int): T

object ArrowRowDeserializer:

  /** Derive an [[ArrowRowDeserializer]] for case class `T`. */
  inline def derived[T](
      inline timeZoneId: String,
      inline largeVarTypes: Boolean
  ): ArrowRowDeserializer[T] =
    ${ ArrowRowDeserializerMacro.derivedImpl[T]('timeZoneId, 'largeVarTypes) }

  /** Spark-default config: UTC, no LargeUtf8/LargeBinary. */
  inline def derived[T]: ArrowRowDeserializer[T] = derived[T]("UTC", false)
