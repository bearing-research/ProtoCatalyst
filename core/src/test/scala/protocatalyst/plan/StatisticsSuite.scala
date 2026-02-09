package protocatalyst.plan

class StatisticsSuite extends munit.FunSuite:

  test("Statistics.unknown has negative sentinel values"):
    assertEquals(Statistics.unknown.rowCount, -1L)
    assertEquals(Statistics.unknown.sizeInBytes, -1L)
    assert(Statistics.unknown.columnStats.isEmpty)

  test("afterFilter reduces row count by selectivity"):
    val input = Statistics(rowCount = 1000, sizeInBytes = 10000)
    val result = Statistics.afterFilter(input, selectivity = 0.5)
    assertEquals(result.rowCount, 500L)
    assertEquals(result.sizeInBytes, 5000L)

  test("afterFilter with default selectivity (0.33)"):
    val input = Statistics(rowCount = 900, sizeInBytes = 9000)
    val result = Statistics.afterFilter(input)
    assertEquals(result.rowCount, 297L)

  test("afterFilter preserves unknown statistics"):
    val result = Statistics.afterFilter(Statistics.unknown)
    assertEquals(result.rowCount, -1L)

  test("afterFilter result is at least 1 row"):
    val input = Statistics(rowCount = 1, sizeInBytes = 100)
    val result = Statistics.afterFilter(input, selectivity = 0.01)
    assert(result.rowCount >= 1)

  test("afterJoin inner reduces via max(left, right)"):
    val left = Statistics(rowCount = 100, sizeInBytes = 1000)
    val right = Statistics(rowCount = 50, sizeInBytes = 500)
    val result = Statistics.afterJoin(left, right, JoinType.Inner)
    // inner: left * right / max(left, right) = 100 * 50 / 100 = 50
    assertEquals(result.rowCount, 50L)

  test("afterJoin cross is left * right"):
    val left = Statistics(rowCount = 10, sizeInBytes = 100)
    val right = Statistics(rowCount = 20, sizeInBytes = 200)
    val result = Statistics.afterJoin(left, right, JoinType.Cross)
    assertEquals(result.rowCount, 200L)

  test("afterJoin left semi preserves left row count"):
    val left = Statistics(rowCount = 100, sizeInBytes = 1000)
    val right = Statistics(rowCount = 50, sizeInBytes = 500)
    val result = Statistics.afterJoin(left, right, JoinType.LeftSemi)
    assertEquals(result.rowCount, 100L)

  test("afterJoin full outer is left + right"):
    val left = Statistics(rowCount = 100, sizeInBytes = 1000)
    val right = Statistics(rowCount = 50, sizeInBytes = 500)
    val result = Statistics.afterJoin(left, right, JoinType.FullOuter)
    assertEquals(result.rowCount, 150L)

  test("afterJoin with unknown stats returns unknown"):
    val result = Statistics.afterJoin(Statistics.unknown, Statistics(10, 100), JoinType.Inner)
    assertEquals(result.rowCount, -1L)

  test("afterAggregate with zero group keys returns 1 row"):
    val input = Statistics(rowCount = 1000, sizeInBytes = 10000)
    val result = Statistics.afterAggregate(input, numGroupKeys = 0)
    assertEquals(result.rowCount, 1L)

  test("afterAggregate with group keys reduces rows"):
    val input = Statistics(rowCount = 1000, sizeInBytes = 10000)
    val result = Statistics.afterAggregate(input, numGroupKeys = 3)
    assert(result.rowCount > 0)
    assert(result.rowCount <= input.rowCount)

  test("afterAggregate preserves unknown statistics"):
    val result = Statistics.afterAggregate(Statistics.unknown, numGroupKeys = 2)
    assertEquals(result.rowCount, -1L)

  test("afterProject adjusts size proportionally"):
    val input = Statistics(rowCount = 100, sizeInBytes = 1000)
    val result = Statistics.afterProject(input, numOutputCols = 5, numInputCols = 10)
    assertEquals(result.rowCount, 100L)
    assertEquals(result.sizeInBytes, 500L)

  test("afterProject with same columns preserves size"):
    val input = Statistics(rowCount = 100, sizeInBytes = 1000)
    val result = Statistics.afterProject(input, numOutputCols = 10, numInputCols = 10)
    assertEquals(result.rowCount, 100L)
    assertEquals(result.sizeInBytes, 1000L)

  test("ColumnStatistics defaults to all None"):
    val cs = ColumnStatistics()
    assert(cs.distinctCount.isEmpty)
    assert(cs.nullCount.isEmpty)
    assert(cs.avgLen.isEmpty)
    assert(cs.maxLen.isEmpty)

  test("ColumnStatistics with values"):
    val cs = ColumnStatistics(
      distinctCount = Some(100),
      nullCount = Some(5),
      avgLen = Some(8),
      maxLen = Some(255)
    )
    assertEquals(cs.distinctCount, Some(100L))
    assertEquals(cs.nullCount, Some(5L))
    assertEquals(cs.avgLen, Some(8L))
    assertEquals(cs.maxLen, Some(255L))

  test("Statistics with column stats"):
    val stats = Statistics(
      rowCount = 1000,
      sizeInBytes = 50000,
      columnStats = Map(
        "id" -> ColumnStatistics(distinctCount = Some(1000)),
        "name" -> ColumnStatistics(avgLen = Some(20), maxLen = Some(100))
      )
    )
    assertEquals(stats.columnStats.size, 2)
    assertEquals(stats.columnStats("id").distinctCount, Some(1000L))
    assertEquals(stats.columnStats("name").avgLen, Some(20L))

  test("Cost addition"):
    val a = Cost(1.0, 2.0, 3.0)
    val b = Cost(4.0, 5.0, 6.0)
    val sum = a + b
    assertEquals(sum.cpu, 5.0)
    assertEquals(sum.io, 7.0)
    assertEquals(sum.memory, 9.0)

  test("Cost total with default weights"):
    val c = Cost(10.0, 20.0, 30.0)
    // total = 10*1 + 20*1 + 30*0.5 = 45
    assertEquals(c.total(), 45.0)

  test("Cost.zero"):
    assertEquals(Cost.zero.cpu, 0.0)
    assertEquals(Cost.zero.io, 0.0)
    assertEquals(Cost.zero.memory, 0.0)

  test("CostEstimator.estimate for PhysicalFilter"):
    import ProtoPhysicalPlan._
    val dummyChild = PhysicalValues(Vector.empty, protocatalyst.schema.ProtoSchema(Vector.empty))
    val filter = PhysicalFilter(
      condition =
        protocatalyst.expr.ProtoExpr.Literal(protocatalyst.types.LiteralValue.BooleanValue(true)),
      child = dummyChild
    )
    val stats = Statistics(rowCount = 1000, sizeInBytes = 10000)
    val cost = CostEstimator.estimate(filter, stats)
    assertEquals(cost.cpu, 1000.0)
    assertEquals(cost.io, 0.0)
    assertEquals(cost.memory, 0.0)
