package protocatalyst.encoder.spark.arrow

import org.apache.arrow.vector.VectorSchemaRoot

/** Runtime implementation of [[ArrowRowDeserializer]]. The macro emits a `readFn` of type
  * `(VectorSchemaRoot, Int) => T`; this class just delegates per-row.
  *
  * A9 note: vectors are resolved via `root.getVector(i)` per-field per-row, same A4 caveat as
  * the writer. Phase C-arrow will hoist these into pre-cached vector references.
  */
final class ArrowRowDeserializerImpl[T](
    readFn: (VectorSchemaRoot, Int) => T
) extends ArrowRowDeserializer[T]:

  override def read(root: VectorSchemaRoot, index: Int): T = readFn(root, index)
