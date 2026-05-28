package protocatalyst.encoder.spark.arrow

/** Iterator that also exposes [[AutoCloseable]] semantics so callers can release native (off-heap)
  * buffers deterministically.
  *
  * Equivalent in shape to Spark's `private[sql] CloseableIterator` — defined here because the
  * Spark version isn't accessible from user code. Use `try-with-resources` (Java) or
  * `scala.util.Using.resource` (Scala) to ensure `close()` runs on all paths.
  */
trait CloseableIteratorLike[A] extends Iterator[A] with AutoCloseable
