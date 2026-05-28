package protocatalyst.encoder.spark.arrow

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

import munit.FunSuite
import org.apache.arrow.memory.RootAllocator
import org.apache.arrow.vector.{IntVector, VarCharVector}
import org.apache.arrow.vector.ipc.ArrowStreamReader

/** Streaming wrapper smoke tests (Phase A5).
  *
  * Verifies batching semantics + IPC framing — each yielded `Array[Byte]` is a self-contained
  * Arrow IPC stream readable by Arrow's stock `ArrowStreamReader`. Byte-level parity against
  * Spark's `ArrowSerializer.serialize` (same input, same config → same bytes) is A7.
  */
class ArrowStreamingSpec extends FunSuite:

  case class Simple(id: Int, name: String)

  // ---------------------------------------------------------------------------
  // IPC roundtrip — write a batch via our streaming wrapper, read it back via
  // Arrow's standard ArrowStreamReader. Verifies the framing is valid IPC.
  // ---------------------------------------------------------------------------

  test("single batch: produces a valid Arrow IPC stream"):
    val alloc = new RootAllocator(Long.MaxValue)
    val rows = Iterator(Simple(1, "alice"), Simple(2, "bob"), Simple(3, "carol"))
    val it = ArrowStreaming.serialize[Simple](rows, alloc)
    try
      val batches = it.toList
      assertEquals(batches.size, 1, "small input should produce a single batch")

      val readAlloc = new RootAllocator(Long.MaxValue)
      try
        val reader = new ArrowStreamReader(new ByteArrayInputStream(batches.head), readAlloc)
        try
          assert(reader.loadNextBatch(), "stream must have one batch")
          val root = reader.getVectorSchemaRoot
          assertEquals(root.getRowCount, 3)
          val id = root.getVector(0).asInstanceOf[IntVector]
          val name = root.getVector(1).asInstanceOf[VarCharVector]
          assertEquals(id.get(0), 1)
          assertEquals(id.get(1), 2)
          assertEquals(id.get(2), 3)
          assertEquals(new String(name.get(0), StandardCharsets.UTF_8), "alice")
          assertEquals(new String(name.get(1), StandardCharsets.UTF_8), "bob")
          assertEquals(new String(name.get(2), StandardCharsets.UTF_8), "carol")
          assert(!reader.loadNextBatch(), "stream should have only one batch")
        finally reader.close()
      finally readAlloc.close()
    finally
      it.close()
      alloc.close()

  // ---------------------------------------------------------------------------
  // Row-count rollover — 250 rows with maxRecordsPerBatch=100 → 3 batches.
  // ---------------------------------------------------------------------------

  test("maxRecordsPerBatch rollover: 250 rows @ 100/batch → 3 batches (100/100/50)"):
    val alloc = new RootAllocator(Long.MaxValue)
    val rows = Iterator.range(0, 250).map(i => Simple(i, s"row-$i"))
    val it = ArrowStreaming.serialize[Simple](
      rows,
      alloc,
      maxRecordsPerBatch = 100,
      maxBatchSize = Long.MaxValue,
      timeZoneId = "UTC",
      largeVarTypes = false,
      batchSizeCheckInterval = 128
    )
    try
      val batches = it.toList
      val counts = batches.map { bytes =>
        val readAlloc = new RootAllocator(Long.MaxValue)
        try
          val r = new ArrowStreamReader(new ByteArrayInputStream(bytes), readAlloc)
          try
            r.loadNextBatch()
            r.getVectorSchemaRoot.getRowCount
          finally r.close()
        finally readAlloc.close()
      }
      assertEquals(counts, List(100, 100, 50))
    finally
      it.close()
      alloc.close()

  // ---------------------------------------------------------------------------
  // Empty input → empty iterator.
  // ---------------------------------------------------------------------------

  test("empty input: yields no batches"):
    val alloc = new RootAllocator(Long.MaxValue)
    val it = ArrowStreaming.serialize[Simple](Iterator.empty, alloc)
    try
      assert(!it.hasNext)
      assertEquals(it.toList, List.empty[Array[Byte]])
    finally
      it.close()
      alloc.close()

  // ---------------------------------------------------------------------------
  // Round-trip values across multiple batches — IDs 0..249 must read back in order.
  // ---------------------------------------------------------------------------

  test("multi-batch values preserve order across batches"):
    val alloc = new RootAllocator(Long.MaxValue)
    val rows = Iterator.range(0, 250).map(i => Simple(i, s"row-$i"))
    val it = ArrowStreaming.serialize[Simple](
      rows,
      alloc,
      maxRecordsPerBatch = 100,
      maxBatchSize = Long.MaxValue,
      timeZoneId = "UTC",
      largeVarTypes = false,
      batchSizeCheckInterval = 128
    )
    try
      val reconstructed = it.flatMap { bytes =>
        val readAlloc = new RootAllocator(Long.MaxValue)
        try
          val r = new ArrowStreamReader(new ByteArrayInputStream(bytes), readAlloc)
          try
            r.loadNextBatch()
            val root = r.getVectorSchemaRoot
            val id = root.getVector(0).asInstanceOf[IntVector]
            val name = root.getVector(1).asInstanceOf[VarCharVector]
            (0 until root.getRowCount).map { i =>
              (id.get(i), new String(name.get(i), StandardCharsets.UTF_8))
            }.toList
          finally r.close()
        finally readAlloc.close()
      }.toList
      assertEquals(reconstructed.size, 250)
      assertEquals(reconstructed.head, (0, "row-0"))
      assertEquals(reconstructed.last, (249, "row-249"))
      assertEquals(reconstructed.map(_._1), (0 until 250).toList)
    finally
      it.close()
      alloc.close()

  // ---------------------------------------------------------------------------
  // close() is idempotent and safe to call without consuming the iterator.
  // ---------------------------------------------------------------------------

  test("close on unconsumed iterator: releases buffers without error"):
    val alloc = new RootAllocator(Long.MaxValue)
    val rows = Iterator.range(0, 1000).map(i => Simple(i, s"x-$i"))
    val it = ArrowStreaming.serialize[Simple](rows, alloc)
    // don't consume, just close
    it.close()
    it.close() // idempotent
    alloc.close() // would throw if buffers leaked
