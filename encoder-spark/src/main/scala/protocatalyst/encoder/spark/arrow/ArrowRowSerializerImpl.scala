package protocatalyst.encoder.spark.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{FieldVector, VectorSchemaRoot}
import org.apache.arrow.vector.types.pojo.Schema

/** Runtime implementation of [[ArrowRowSerializer]]. The macro emits a `writeFn` of type
  * `(Array[FieldVector], Int, T) => Unit`; this class pre-caches the field vectors once at
  * construction so the per-row hot path is array indexing + a monomorphic cast + setSafe — no
  * per-call `root.getVector(i)` lookup (Phase C-arrow optimization).
  *
  * `VectorSchemaRoot.allocateNew()` reallocates buffers but keeps the same FieldVector objects,
  * so the cached array stays valid across `reset()`.
  */
final class ArrowRowSerializerImpl[T](
    val schema: Schema,
    val allocator: BufferAllocator,
    writeFn: (Array[FieldVector], Int, T) => Unit
) extends ArrowRowSerializer[T]:

  val root: VectorSchemaRoot = VectorSchemaRoot.create(schema, allocator)

  /** Pre-cached field vectors. Built once; reused across all append() calls. Stays valid across
    * `reset()` because Arrow's allocateNew reuses the FieldVector objects.
    */
  private val vectors: Array[FieldVector] = {
    val list = root.getFieldVectors
    val n = list.size
    val arr = new Array[FieldVector](n)
    var i = 0
    while i < n do
      arr(i) = list.get(i)
      i += 1
    arr
  }

  private var rowIdx: Int = 0
  private var closed: Boolean = false

  override def rowCount: Int = rowIdx

  override def sizeInBytes: Long =
    var total = 0L
    var i = 0
    while i < vectors.length do
      total += vectors(i).getBufferSize.toLong
      i += 1
    total

  override def append(record: T): Unit =
    writeFn(vectors, rowIdx, record)
    rowIdx += 1
    root.setRowCount(rowIdx)

  override def reset(): Unit =
    root.allocateNew()
    rowIdx = 0
    root.setRowCount(0)

  override def close(): Unit =
    if !closed then
      root.close()
      closed = true
