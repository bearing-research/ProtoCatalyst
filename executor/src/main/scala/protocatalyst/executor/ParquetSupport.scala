package protocatalyst.executor

import org.apache.arrow.memory.BufferAllocator

import protocatalyst.arrow.parquet.ParquetIO
import protocatalyst.executor.exec.Batch

/** Batch-aware Parquet utilities and Catalog extensions. */
object ParquetSupport:

  /** Read a Parquet file into a Batch. */
  def readBatch(path: String, allocator: BufferAllocator): Batch =
    val (root, schema) = ParquetIO.read(path, allocator)
    Batch.fromRoot(root, schema)

  /** Write a Batch to a Parquet file. */
  def writeBatch(
      batch: Batch,
      path: String,
      config: ParquetIO.WriteConfig = ParquetIO.WriteConfig()
  ): Unit =
    ParquetIO.write(batch.root, batch.schema, path, config)

  extension (catalog: Catalog)

    /** Register a Parquet file as a named table.
      *
      * Reads the file into a Batch, infers schema from Parquet metadata, extracts statistics from
      * the footer, and registers both in the catalog.
      */
    def registerParquetTable(name: String, path: String, allocator: BufferAllocator): Unit =
      val batch = readBatch(path, allocator)
      val stats = ParquetIO.readStatistics(path)
      catalog.registerTable(name, batch)
      catalog.registerStatistics(name, stats)
