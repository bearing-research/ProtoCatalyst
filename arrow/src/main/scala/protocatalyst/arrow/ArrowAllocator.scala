package protocatalyst.arrow

import org.apache.arrow.memory.{BufferAllocator, RootAllocator}
import scala.util.Using

/**
 * Memory allocator management for Arrow operations.
 *
 * Arrow uses off-heap memory for vectors, which requires explicit allocation
 * and deallocation. This object provides utilities for safe allocation management.
 *
 * Usage:
 * {{{
 * // Scoped allocation (recommended)
 * ArrowAllocator.scoped() { allocator =>
 *   val root = VectorSchemaRoot.create(schema, allocator)
 *   // Process...
 *   root.close()
 * }
 *
 * // Manual allocation
 * val allocator = ArrowAllocator.createRoot()
 * try
 *   // Use allocator...
 * finally
 *   allocator.close()
 * }}}
 */
object ArrowAllocator:

  /** Default memory limit: 8GB */
  val DefaultLimit: Long = 8L * 1024 * 1024 * 1024

  /**
   * Create a root allocator with the default memory limit.
   */
  def createRoot(): RootAllocator =
    new RootAllocator(DefaultLimit)

  /**
   * Create a root allocator with a custom memory limit.
   *
   * @param limit Maximum memory in bytes
   */
  def createRoot(limit: Long): RootAllocator =
    new RootAllocator(limit)

  /**
   * Create a child allocator from a parent allocator.
   *
   * Child allocators share the parent's memory limit but allow
   * tracking memory usage separately.
   *
   * @param parent Parent allocator
   * @param name Name for the child allocator (for debugging)
   * @param initialReservation Initial memory reservation (0 = no reservation)
   */
  def createChild(
      parent: BufferAllocator,
      name: String,
      initialReservation: Long = 0
  ): BufferAllocator =
    parent.newChildAllocator(name, initialReservation, parent.getLimit)

  /**
   * Execute a block with a scoped allocator that is automatically closed.
   *
   * This is the recommended pattern for managing Arrow memory.
   */
  def scoped[R](limit: Long = DefaultLimit)(f: BufferAllocator => R): R =
    Using.resource(createRoot(limit))(f)

  /**
   * Execute a block with a child allocator from an existing parent.
   *
   * @param parent Parent allocator
   * @param name Name for the child allocator
   */
  def childScoped[R](
      parent: BufferAllocator,
      name: String
  )(f: BufferAllocator => R): R =
    Using.resource(createChild(parent, name))(f)
