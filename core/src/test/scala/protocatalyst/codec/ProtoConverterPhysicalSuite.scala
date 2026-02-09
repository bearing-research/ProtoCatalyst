package protocatalyst.codec

import protocatalyst.expr._
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

class ProtoConverterPhysicalSuite extends munit.FunSuite:

  private val simpleSchema = ProtoSchema(
    Vector(
      ProtoStructField("id", ProtoType.IntegerType, nullable = false),
      ProtoStructField("name", ProtoType.StringType, nullable = true)
    )
  )

  private val stats = Statistics(
    rowCount = 1000,
    sizeInBytes = 50000,
    columnStats = Map(
      "id" -> ColumnStatistics(distinctCount = Some(1000), nullCount = Some(0)),
      "name" -> ColumnStatistics(avgLen = Some(20), maxLen = Some(255))
    )
  )

  private val tableScan = ProtoPhysicalPlan.TableScan("users", Some("u"), simpleSchema, stats)

  private val colRef = ProtoExpr.ColumnRef("id", None, ProtoType.IntegerType, false)
  private val litInt = ProtoExpr.Literal(LiteralValue.IntValue(42))

  private def roundTrip(plan: ProtoPhysicalPlan): ProtoPhysicalPlan =
    val proto = ProtoConverter.toProtoPhysicalPlan(plan)
    ProtoConverter.fromProtoPhysicalPlan(proto)

  test("roundtrip TableScan"):
    val result = roundTrip(tableScan)
    val ts = result.asInstanceOf[ProtoPhysicalPlan.TableScan]
    assertEquals(ts.name, "users")
    assertEquals(ts.alias, Some("u"))
    assertEquals(ts.stats.rowCount, 1000L)
    assertEquals(ts.stats.sizeInBytes, 50000L)
    assertEquals(ts.stats.columnStats("id").distinctCount, Some(1000L))
    assertEquals(ts.stats.columnStats("name").avgLen, Some(20L))

  test("roundtrip PhysicalValues"):
    val plan = ProtoPhysicalPlan.PhysicalValues(
      Vector(
        Vector(
          ProtoExpr.Literal(LiteralValue.IntValue(1)),
          ProtoExpr.Literal(LiteralValue.StringValue("a"))
        ),
        Vector(
          ProtoExpr.Literal(LiteralValue.IntValue(2)),
          ProtoExpr.Literal(LiteralValue.StringValue("b"))
        )
      ),
      simpleSchema
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalValues]
    assertEquals(result.rows.size, 2)
    assertEquals(result.schema.fields.size, 2)

  test("roundtrip PhysicalProject"):
    val plan = ProtoPhysicalPlan.PhysicalProject(
      Vector(colRef, ProtoExpr.Alias(litInt, "answer")),
      tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalProject]
    assertEquals(result.projectList.size, 2)

  test("roundtrip PhysicalFilter"):
    val plan = ProtoPhysicalPlan.PhysicalFilter(
      ProtoExpr.Gt(colRef, litInt),
      tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalFilter]

  test("roundtrip PhysicalSort"):
    val plan = ProtoPhysicalPlan.PhysicalSort(
      Vector(SortOrder(colRef, SortDirection.Ascending, NullOrdering.NullsLast)),
      tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalSort]
    assertEquals(result.order.size, 1)

  test("roundtrip PhysicalLimit"):
    val plan = ProtoPhysicalPlan.PhysicalLimit(10, tableScan)
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalLimit]
    assertEquals(result.limit, 10)

  test("roundtrip PhysicalDistinct"):
    val plan = ProtoPhysicalPlan.PhysicalDistinct(tableScan)
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalDistinct]

  test("roundtrip HashJoin"):
    val leftKey = ProtoExpr.ColumnRef("id", Some("l"), ProtoType.IntegerType, false)
    val rightKey = ProtoExpr.ColumnRef("id", Some("r"), ProtoType.IntegerType, false)
    val residual = ProtoExpr.Gt(
      ProtoExpr.ColumnRef("x", Some("l"), ProtoType.IntegerType, false),
      ProtoExpr.Literal(LiteralValue.IntValue(5))
    )
    val plan = ProtoPhysicalPlan.HashJoin(
      left = tableScan,
      right = tableScan,
      joinType = JoinType.Inner,
      leftKeys = Vector(leftKey),
      rightKeys = Vector(rightKey),
      condition = Some(residual),
      buildSide = BuildSide.BuildRight
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.HashJoin]
    assertEquals(result.joinType, JoinType.Inner)
    assertEquals(result.leftKeys.size, 1)
    assertEquals(result.rightKeys.size, 1)
    assert(result.condition.isDefined)
    assertEquals(result.buildSide, BuildSide.BuildRight)

  test("roundtrip SortMergeJoin"):
    val plan = ProtoPhysicalPlan.SortMergeJoin(
      left = tableScan,
      right = tableScan,
      joinType = JoinType.LeftOuter,
      leftKeys = Vector(colRef),
      rightKeys = Vector(colRef),
      condition = None
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.SortMergeJoin]
    assertEquals(result.joinType, JoinType.LeftOuter)
    assert(result.condition.isEmpty)

  test("roundtrip BroadcastHashJoin"):
    val plan = ProtoPhysicalPlan.BroadcastHashJoin(
      left = tableScan,
      right = tableScan,
      joinType = JoinType.RightOuter,
      leftKeys = Vector(colRef),
      rightKeys = Vector(colRef),
      condition = None,
      buildSide = BuildSide.BuildLeft
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.BroadcastHashJoin]
    assertEquals(result.buildSide, BuildSide.BuildLeft)

  test("roundtrip NestedLoopJoin"):
    val plan = ProtoPhysicalPlan.NestedLoopJoin(
      left = tableScan,
      right = tableScan,
      joinType = JoinType.Cross,
      condition = None
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.NestedLoopJoin]
    assertEquals(result.joinType, JoinType.Cross)

  test("roundtrip HashAggregate"):
    val plan = ProtoPhysicalPlan.HashAggregate(
      groupingExprs = Vector(colRef),
      aggregateExprs = Vector(ProtoExpr.Count(colRef, false)),
      child = tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.HashAggregate]
    assertEquals(result.groupingExprs.size, 1)
    assertEquals(result.aggregateExprs.size, 1)

  test("roundtrip SortAggregate"):
    val plan = ProtoPhysicalPlan.SortAggregate(
      groupingExprs = Vector(colRef),
      aggregateExprs = Vector(ProtoExpr.Sum(colRef)),
      child = tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.SortAggregate]

  test("roundtrip Exchange with HashPartitioning"):
    val plan = ProtoPhysicalPlan.Exchange(
      partitioning = Partitioning.HashPartitioning(Vector(colRef), 4),
      child = tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.Exchange]
    val hp = result.partitioning.asInstanceOf[Partitioning.HashPartitioning]
    assertEquals(hp.numPartitions, 4)

  test("roundtrip Exchange with SinglePartition"):
    val plan = ProtoPhysicalPlan.Exchange(
      partitioning = Partitioning.SinglePartition,
      child = tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.Exchange]
    assert(result.partitioning == Partitioning.SinglePartition)

  test("roundtrip Exchange with RoundRobinPartitioning"):
    val plan = ProtoPhysicalPlan.Exchange(
      partitioning = Partitioning.RoundRobinPartitioning(8),
      child = tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.Exchange]
    val rr = result.partitioning.asInstanceOf[Partitioning.RoundRobinPartitioning]
    assertEquals(rr.numPartitions, 8)

  test("roundtrip PhysicalUnion"):
    val plan = ProtoPhysicalPlan.PhysicalUnion(
      Vector(tableScan, tableScan),
      byName = true,
      allowMissingColumns = false
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalUnion]
    assertEquals(result.children.size, 2)
    assertEquals(result.byName, true)

  test("roundtrip PhysicalIntersect"):
    val plan = ProtoPhysicalPlan.PhysicalIntersect(tableScan, tableScan, isAll = true)
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalIntersect]
    assertEquals(result.isAll, true)

  test("roundtrip PhysicalExcept"):
    val plan = ProtoPhysicalPlan.PhysicalExcept(tableScan, tableScan, isAll = false)
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalExcept]
    assertEquals(result.isAll, false)

  test("roundtrip PhysicalWith"):
    val plan = ProtoPhysicalPlan.PhysicalWith(
      cteRelations = Vector(("cte1", tableScan)),
      recursive = false,
      child = tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalWith]
    assertEquals(result.cteRelations.size, 1)
    assertEquals(result.cteRelations.head._1, "cte1")

  test("roundtrip PhysicalWindow"):
    val plan = ProtoPhysicalPlan.PhysicalWindow(
      windowExprs = Vector(colRef),
      partitionSpec = Vector(colRef),
      orderSpec = Vector(SortOrder(colRef, SortDirection.Descending, NullOrdering.NullsFirst)),
      child = tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalWindow]
    assertEquals(result.windowExprs.size, 1)

  test("roundtrip PhysicalGenerate"):
    val plan = ProtoPhysicalPlan.PhysicalGenerate(
      generator = ProtoExpr.Explode(colRef),
      generatorOutput = Vector("col1", "col2"),
      outer = true,
      child = tableScan
    )
    val result = roundTrip(plan).asInstanceOf[ProtoPhysicalPlan.PhysicalGenerate]
    assertEquals(result.generatorOutput, Vector("col1", "col2"))
    assertEquals(result.outer, true)

  test("roundtrip Statistics alone"):
    val proto = ProtoConverter.toProtoStatistics(stats)
    val result = ProtoConverter.fromProtoStatistics(proto)
    assertEquals(result.rowCount, 1000L)
    assertEquals(result.sizeInBytes, 50000L)
    assertEquals(result.columnStats.size, 2)
    assertEquals(result.columnStats("id").distinctCount, Some(1000L))
    assertEquals(result.columnStats("id").nullCount, Some(0L))
    assertEquals(result.columnStats("name").avgLen, Some(20L))
    assertEquals(result.columnStats("name").maxLen, Some(255L))

  test("roundtrip Statistics.unknown"):
    val proto = ProtoConverter.toProtoStatistics(Statistics.unknown)
    val result = ProtoConverter.fromProtoStatistics(proto)
    assertEquals(result.rowCount, -1L)
    assertEquals(result.sizeInBytes, -1L)

  test("roundtrip artifact with physical plan"):
    val artifact = protocatalyst.artifact.CompiledArtifact(
      formatVersion = protocatalyst.artifact.ArtifactVersion(1, 0, 0),
      protocatalystVersion = "0.1.0",
      compiledAt = System.currentTimeMillis(),
      contentHash = 12345L,
      schemaContracts = Vector.empty,
      plan = ProtoLogicalPlan.RelationRef(
        "t",
        None,
        SchemaContract("t", Vector.empty, SchemaFingerprint.compute(Vector.empty))
      ),
      outputSchema = simpleSchema,
      sourceInfo = None,
      physicalPlan = Some(tableScan)
    )
    val proto = ProtoConverter.toProto(artifact)
    val result = ProtoConverter.fromProto(proto)
    assert(result.physicalPlan.isDefined)
    val ts = result.physicalPlan.get.asInstanceOf[ProtoPhysicalPlan.TableScan]
    assertEquals(ts.name, "users")
    assertEquals(ts.stats.rowCount, 1000L)

  test("roundtrip artifact without physical plan"):
    val artifact = protocatalyst.artifact.CompiledArtifact(
      formatVersion = protocatalyst.artifact.ArtifactVersion(1, 0, 0),
      protocatalystVersion = "0.1.0",
      compiledAt = System.currentTimeMillis(),
      contentHash = 12345L,
      schemaContracts = Vector.empty,
      plan = ProtoLogicalPlan.RelationRef(
        "t",
        None,
        SchemaContract("t", Vector.empty, SchemaFingerprint.compute(Vector.empty))
      ),
      outputSchema = simpleSchema,
      sourceInfo = None
    )
    val proto = ProtoConverter.toProto(artifact)
    val result = ProtoConverter.fromProto(proto)
    assert(result.physicalPlan.isEmpty)

  test("roundtrip deeply nested physical plan"):
    val deep = ProtoPhysicalPlan.PhysicalProject(
      Vector(colRef),
      ProtoPhysicalPlan.PhysicalFilter(
        ProtoExpr.Gt(colRef, litInt),
        ProtoPhysicalPlan.HashJoin(
          left = ProtoPhysicalPlan.PhysicalSort(
            Vector(SortOrder(colRef, SortDirection.Ascending, NullOrdering.NullsLast)),
            tableScan
          ),
          right = ProtoPhysicalPlan.HashAggregate(
            Vector(colRef),
            Vector(ProtoExpr.Count(colRef, false)),
            tableScan
          ),
          joinType = JoinType.Inner,
          leftKeys = Vector(colRef),
          rightKeys = Vector(colRef),
          condition = None,
          buildSide = BuildSide.BuildLeft
        )
      )
    )
    val result = roundTrip(deep)
    assert(result.isInstanceOf[ProtoPhysicalPlan.PhysicalProject])
