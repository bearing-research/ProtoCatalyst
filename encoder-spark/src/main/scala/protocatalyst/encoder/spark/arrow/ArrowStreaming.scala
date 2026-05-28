package protocatalyst.encoder.spark.arrow

import java.io.ByteArrayOutputStream
import java.nio.channels.Channels

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.ipc.ArrowStreamWriter

/** Streaming wrapper that produces Arrow IPC byte-batches from an input iterator.
  *
  * Mirrors Spark Connect's `ArrowSerializer.serialize(...)` (in `sql/connect/common/`). Each
  * yielded `Array[Byte]` is a self-contained Arrow IPC stream: schema message + one record batch
  * + EOS marker. This is the wire format Spark Connect's gRPC layer puts on the wire.
  *
  * Batch rollover:
  *   - On every append, increment row count and (every `batchSizeCheckInterval` rows) check
  *     `sizeInBytes`. Roll over when either `rowCount >= maxRecordsPerBatch` or
  *     `sizeInBytes >= maxBatchSize`.
  *   - On input exhaustion, flush any trailing rows as a final batch.
  *
  * Lifecycle:
  *   - Caller owns the `BufferAllocator` — we never close it.
  *   - The wrapper owns the internal `ArrowRowSerializer` and its `VectorSchemaRoot`; `close()`
  *     releases their buffers.
  *   - `close()` is idempotent and safe to call from a `finally` block even on early termination.
  */
object ArrowStreaming:

  /** Streaming serialize for case class `T`, deriving the per-row writer at macro time. */
  inline def serialize[T](
      input: Iterator[T],
      allocator: BufferAllocator,
      maxRecordsPerBatch: Int,
      maxBatchSize: Long,
      timeZoneId: String,
      largeVarTypes: Boolean,
      batchSizeCheckInterval: Int
  ): CloseableIteratorLike[Array[Byte]] =
    val ser = ArrowRowSerializer.derived[T](allocator, timeZoneId, largeVarTypes)
    serializeWith(ser, input, maxRecordsPerBatch, maxBatchSize, batchSizeCheckInterval)

  /** Spark-default config: 4096 rows/batch, 16 KB/batch, UTC, no LargeUtf8/LargeBinary,
    * check size every 128 rows.
    */
  inline def serialize[T](
      input: Iterator[T],
      allocator: BufferAllocator
  ): CloseableIteratorLike[Array[Byte]] =
    serialize[T](input, allocator, 4096, 16L * 1024, "UTC", false, 128)

  /** Stream over a pre-built serializer. The wrapper takes ownership: closing the returned
    * iterator also closes the serializer.
    */
  def serializeWith[T](
      ser: ArrowRowSerializer[T],
      input: Iterator[T],
      maxRecordsPerBatch: Int,
      maxBatchSize: Long,
      batchSizeCheckInterval: Int
  ): CloseableIteratorLike[Array[Byte]] =
    new BatchedIpcIterator[T](
      ser,
      input,
      maxRecordsPerBatch,
      maxBatchSize,
      batchSizeCheckInterval
    )

  // -------------------------------------------------------------------------
  // Iterator impl: pull-based — `hasNext` materializes the next batch on demand.
  // -------------------------------------------------------------------------

  private final class BatchedIpcIterator[T](
      ser: ArrowRowSerializer[T],
      input: Iterator[T],
      maxRecordsPerBatch: Int,
      maxBatchSize: Long,
      batchSizeCheckInterval: Int
  ) extends CloseableIteratorLike[Array[Byte]]:

    private var sinceCheck: Int = 0
    private var pending: Array[Byte] | Null = null
    private var inputExhausted: Boolean = false
    private var closed: Boolean = false

    private def flushBatch(): Array[Byte] =
      val out = new ByteArrayOutputStream()
      val channel = Channels.newChannel(out)
      val writer = new ArrowStreamWriter(ser.root, /* dictProvider */ null, channel)
      try
        writer.start()
        writer.writeBatch()
        writer.end()
      finally writer.close()
      val bytes = out.toByteArray
      ser.reset()
      sinceCheck = 0
      bytes

    private def prefetch(): Unit =
      if pending != null || closed then return
      while input.hasNext && pending == null do
        val record = input.next()
        ser.append(record)
        sinceCheck += 1
        val rollover =
          ser.rowCount >= maxRecordsPerBatch ||
            (sinceCheck >= batchSizeCheckInterval && ser.sizeInBytes >= maxBatchSize)
        if rollover then pending = flushBatch()
      if pending == null && !inputExhausted then
        inputExhausted = true
        if ser.rowCount > 0 then pending = flushBatch()

    override def hasNext: Boolean =
      prefetch()
      pending != null

    override def next(): Array[Byte] =
      prefetch()
      val b = pending
      if b == null then throw new NoSuchElementException("ArrowStreaming: no more batches")
      pending = null
      b

    override def close(): Unit =
      if !closed then
        closed = true
        pending = null
        ser.close()
