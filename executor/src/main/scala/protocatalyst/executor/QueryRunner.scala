package protocatalyst.executor

import protocatalyst.arrow.ArrowAllocator
import protocatalyst.artifact.CompiledArtifact
import protocatalyst.codec.ArtifactCodec
import protocatalyst.executor.exec._
import protocatalyst.executor.physical.PhysicalPlanExecutor
import protocatalyst.plan._

/** Executes ProtoCatalyst compiled artifacts against in-memory Arrow batches.
  *
  * This is the standalone execution engine — no Spark, no external dependencies. Mirrors the
  * SparkQueryRunner API but returns Arrow-columnar Batch results instead of DataFrames.
  *
  * All execution goes through the physical plan pipeline: logical plan → PhysicalPlanner →
  * PhysicalPlanExecutor. If the artifact already contains a pre-planned physical plan, it is used
  * directly.
  *
  * {{{
  * val catalog = Catalog()
  * catalog.registerTable("users", usersBatch)
  * val result = QueryRunner.execute(artifactBytes, catalog)
  * val rows = QueryRunner.collect(result)
  * }}}
  */
object QueryRunner:

  /** Configuration for query execution. */
  case class ExecutionConfig(
      validateSchema: Boolean = false,
      memoryLimit: Long = ArrowAllocator.DefaultLimit,
      batchSize: Int = 65536,
      plannerConfig: PlannerConfig = PlannerConfig()
  )

  val DefaultConfig: ExecutionConfig = ExecutionConfig()

  /** Execute a compiled artifact and return the result as a Batch.
    *
    * @param artifactBytes
    *   PCAT-format artifact bytes (4-byte magic + format byte + payload)
    * @param catalog
    *   In-memory table catalog
    * @param config
    *   Execution configuration
    * @return
    *   Batch with query results
    * @throws ExecutionException
    *   if parsing, validation, or execution fails
    */
  def execute(
      artifactBytes: Array[Byte],
      catalog: Catalog,
      config: ExecutionConfig = DefaultConfig
  ): Batch =
    val artifact = ArtifactCodec.deserializeWithHeader(artifactBytes) match
      case Right(a)  => a
      case Left(err) => throw ExecutionException(s"Failed to deserialize artifact: $err")
    executeArtifact(artifact, catalog, config)

  /** Execute from a CompiledArtifact directly (no deserialization). */
  def executeArtifact(
      artifact: CompiledArtifact,
      catalog: Catalog,
      config: ExecutionConfig = DefaultConfig
  ): Batch =
    // Validate schema contracts if enabled
    if config.validateSchema then validateSchemaContracts(artifact, catalog)

    val allocator = ArrowAllocator.createRoot(config.memoryLimit)

    // Use pre-planned physical plan from artifact if available, otherwise plan on the fly
    val physicalPlan = artifact.physicalPlan.getOrElse {
      val planner = PhysicalPlanner(catalog.statsProvider, config.plannerConfig)
      planner.plan(artifact.plan)
    }

    val executor = PhysicalPlanExecutor(catalog, allocator)
    executor.execute(physicalPlan)

  /** Validate schema contracts against the catalog. */
  private def validateSchemaContracts(artifact: CompiledArtifact, catalog: Catalog): Unit =
    for contract <- artifact.schemaContracts do
      catalog.getTable(contract.relationName) match
        case None =>
          throw ExecutionException(
            s"Schema validation failed: table '${contract.relationName}' not found in catalog"
          )
        case Some(batch) =>
          for field <- contract.requiredFields do
            batch.schema(field.name) match
              case None =>
                throw ExecutionException(
                  s"Schema validation failed: field '${field.name}' not found in table '${contract.relationName}'"
                )
              case Some(f) =>
                if f.dataType != field.expectedType then
                  throw ExecutionException(
                    s"Schema validation failed: field '${field.name}' in table '${contract.relationName}' " +
                      s"has type ${f.dataType} but expected ${field.expectedType}"
                  )

  /** Collect a Batch result into a Vector of Maps for easy inspection. */
  def collect(batch: Batch): Vector[Map[String, Any]] =
    val fields = batch.schema.fields
    (0 until batch.rowCount).map { row =>
      fields.zipWithIndex.map { (field, col) =>
        field.name -> Batch.getValue(batch.root.getVector(col), row)
      }.toMap
    }.toVector
