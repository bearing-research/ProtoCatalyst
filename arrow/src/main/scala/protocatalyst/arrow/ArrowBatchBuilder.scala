package protocatalyst.arrow

import java.io.Closeable

import scala.deriving.Mirror
import scala.util.Using

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._
import org.apache.arrow.vector.types.pojo.Schema

import protocatalyst.encoder.ProtoEncoder

/** Builder for creating Arrow RecordBatches from sequences of case classes.
  *
  * Manages Arrow memory allocation and batch lifecycle. Uses InlineArrowWriter for compile-time
  * specialized serialization.
  *
  * Usage:
  * {{{
  * case class Person(name: String, age: Int) derives ProtoEncoder
  *
  * // Option 1: Scoped (recommended)
  * ArrowBatchBuilder.scoped(persons) { root =>
  *   // Process root...
  * }
  *
  * // Option 2: Manual resource management
  * val builder = ArrowBatchBuilder.create[Person]
  * try
  *   val root = builder.build(persons)
  *   // Process root...
  *   root.close()
  * finally
  *   builder.close()
  * }}}
  */
trait ArrowBatchBuilder[T] extends Closeable:
  /** Build a VectorSchemaRoot from a sequence of values */
  def build(values: Seq[T]): VectorSchemaRoot

  /** Get the Arrow schema */
  def schema: Schema

  /** Get the allocator */
  def allocator: BufferAllocator

  /** Release all resources */
  def close(): Unit

object ArrowBatchBuilder:

  /** Default batch size for streaming writes */
  val DefaultBatchSize: Int = 65536

  /** Create a new ArrowBatchBuilder with a dedicated allocator. The allocator will be closed when
    * the builder is closed.
    */
  inline def create[T](using m: Mirror.ProductOf[T], enc: ProtoEncoder[T]): ArrowBatchBuilder[T] =
    val rootAllocator = ArrowAllocator.createRoot()
    val writer = InlineArrowWriter.derived[T]
    ArrowBatchBuilderImpl[T](writer, rootAllocator, ownsAllocator = true)

  /** Create a new ArrowBatchBuilder with a provided allocator. The allocator will NOT be closed
    * when the builder is closed.
    */
  inline def createWithAllocator[T](
      parentAllocator: BufferAllocator
  )(using m: Mirror.ProductOf[T], enc: ProtoEncoder[T]): ArrowBatchBuilder[T] =
    val writer = InlineArrowWriter.derived[T]
    ArrowBatchBuilderImpl[T](writer, parentAllocator, ownsAllocator = false)

  /** Create a batch in a scoped context, automatically managing resources.
    *
    * This is the recommended way to use ArrowBatchBuilder for simple use cases.
    */
  inline def scoped[T, R](
      values: Seq[T]
  )(
      f: VectorSchemaRoot => R
  )(using m: Mirror.ProductOf[T], enc: ProtoEncoder[T]): R =
    Using.resource(create[T]) { builder =>
      Using.resource(builder.build(values))(f)
    }

/** Implementation of ArrowBatchBuilder */
class ArrowBatchBuilderImpl[T](
    writer: InlineArrowWriter[T],
    val allocator: BufferAllocator,
    ownsAllocator: Boolean
) extends ArrowBatchBuilder[T]:

  def schema: Schema = writer.schema

  def build(values: Seq[T]): VectorSchemaRoot =
    val root = VectorSchemaRoot.create(schema, allocator)
    try
      writer.write(values, root)
      root
    catch
      case e: Exception =>
        root.close()
        throw e

  def close(): Unit =
    if ownsAllocator then allocator.close()
