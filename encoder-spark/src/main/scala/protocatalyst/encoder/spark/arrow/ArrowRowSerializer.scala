package protocatalyst.encoder.spark.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.VectorSchemaRoot
import org.apache.arrow.vector.types.pojo.Schema

/** Per-row Arrow serializer for case class `T`, deriving its Arrow Schema and writer at
  * `scalac` time.
  *
  * Mirrors the Spark Connect `ArrowSerializer[T]` surface (per-record `append`, batch-level
  * `rowCount` / `reset` / `close`) but emits the per-row writer entirely from a Scala 3 macro.
  * No runtime reflection, no AgnosticEncoder dispatch.
  *
  * Phase A4 scope: primitives (Boolean, Byte, Short, Int, Long, Float, Double) + String. Other
  * AgnosticEncoder variants come in A8.
  *
  * Lifecycle:
  *   - Caller owns the [[BufferAllocator]] — we never close it.
  *   - The serializer owns the [[VectorSchemaRoot]]; `close()` releases its buffers.
  *   - `reset()` reuses the same root and zeros it for the next batch.
  */
trait ArrowRowSerializer[T] extends AutoCloseable:

  /** The Arrow schema, derived at macro time. Stable across `reset()`. */
  def schema: Schema

  /** Caller-provided allocator. Not closed by this serializer. */
  def allocator: BufferAllocator

  /** The vector container. Vectors inside are pre-resolved and reused across rows. */
  def root: VectorSchemaRoot

  /** Append one record. Grows underlying vector buffers as needed (uses Arrow's `setSafe` API). */
  def append(record: T): Unit

  /** Rows written since the last `reset()`. */
  def rowCount: Int

  /** Total bytes currently held in the underlying vector buffers. Sum across all FieldVectors;
    * matches the granularity used for batch-size rollover. Cheap — no allocation.
    */
  def sizeInBytes: Long

  /** Discard buffer contents and rewind `rowCount` to 0. Re-uses vector objects. */
  def reset(): Unit

  /** Release vector buffers. Idempotent. */
  override def close(): Unit

object ArrowRowSerializer:

  /** Derive an [[ArrowRowSerializer]] for case class `T`. */
  inline def derived[T](
      inline allocator: BufferAllocator,
      inline timeZoneId: String,
      inline largeVarTypes: Boolean
  ): ArrowRowSerializer[T] =
    ${ ArrowRowSerializerMacro.derivedImpl[T]('allocator, 'timeZoneId, 'largeVarTypes) }

  /** Spark-default config: UTC timezone, no LargeUtf8/LargeBinary. */
  inline def derived[T](inline allocator: BufferAllocator): ArrowRowSerializer[T] =
    derived[T](allocator, "UTC", false)
