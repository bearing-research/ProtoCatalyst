package protocatalyst.optimizer

import protocatalyst.expr._
import protocatalyst.optimizer.rules._
import protocatalyst.plan.NullOrdering.{NullsFirst, NullsLast}
import protocatalyst.plan.SortDirection.{Ascending, Descending}
import protocatalyst.plan._
import protocatalyst.schema._
import protocatalyst.types._

/** Tests for additional optimizer rules implemented in Phase 6+.
  */
class AdditionalRulesSuite extends munit.FunSuite:

  // Helper to create a simple table reference
  private def table(name: String): ProtoLogicalPlan =
    ProtoLogicalPlan.RelationRef(name, None, emptyContract(name))

  private def emptyContract(name: String) = SchemaContract(
    name,
    Vector.empty,
    SchemaFingerprint.fromLong(0L)
  )

  private def col(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.IntegerType, nullable = true)

  private def colLong(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.LongType, nullable = true)

  private def colShort(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.ShortType, nullable = true)

  private def colDouble(name: String): ProtoExpr =
    ProtoExpr.ColumnRef(name, None, ProtoType.DoubleType, nullable = true)

  // ========================================================
  // PruneFilters Tests
  // ========================================================

  test("PruneFilters: filter with FALSE condition becomes Limit 0"):
    val input = ProtoLogicalPlan.Filter(
      ProtoExpr.Literal(LiteralValue.BooleanValue(false)),
      table("t")
    )
    val result = PruneFilters(input)
    result match
      case ProtoLogicalPlan.Limit(0, _) => ()
      case _                            => fail(s"Expected Limit(0, _), got $result")

  test("PruneFilters: filter with NULL boolean condition becomes Limit 0"):
    val input = ProtoLogicalPlan.Filter(
      ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType)),
      table("t")
    )
    val result = PruneFilters(input)
    result match
      case ProtoLogicalPlan.Limit(0, _) => ()
      case _                            => fail(s"Expected Limit(0, _), got $result")

  test("PruneFilters: filter with TRUE condition remains unchanged"):
    val input = ProtoLogicalPlan.Filter(
      ProtoExpr.Literal(LiteralValue.BooleanValue(true)),
      table("t")
    )
    val result = PruneFilters(input)
    assertEquals(result, input)

  test("PruneFilters: filter with column condition remains unchanged"):
    val input = ProtoLogicalPlan.Filter(col("a"), table("t"))
    val result = PruneFilters(input)
    assertEquals(result, input)

  // ========================================================
  // ReplaceNullWithFalseInPredicate Tests
  // ========================================================

  test("ReplaceNullWithFalseInPredicate: null in filter condition becomes false"):
    val input = ProtoLogicalPlan.Filter(
      ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType)),
      table("t")
    )
    val result = ReplaceNullWithFalseInPredicate(input)
    result match
      case ProtoLogicalPlan.Filter(ProtoExpr.Literal(LiteralValue.BooleanValue(false)), _) => ()
      case _ => fail(s"Expected Filter with false, got $result")

  test("ReplaceNullWithFalseInPredicate: null in AND children becomes false"):
    val input = ProtoLogicalPlan.Filter(
      ProtoExpr.And(
        Vector(col("a"), ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.IntegerType)))
      ),
      table("t")
    )
    val result = ReplaceNullWithFalseInPredicate(input)
    result match
      case ProtoLogicalPlan.Filter(ProtoExpr.And(children), _) =>
        assert(
          children.exists {
            case ProtoExpr.Literal(LiteralValue.BooleanValue(false)) => true
            case _                                                   => false
          },
          s"Expected AND with false, got $result"
        )
      case _ => fail(s"Expected Filter with AND, got $result")

  test("ReplaceNullWithFalseInPredicate: null in join condition becomes false"):
    val input = ProtoLogicalPlan.Join(
      table("t1"),
      table("t2"),
      JoinType.Inner,
      Some(ProtoExpr.Literal(LiteralValue.NullValue(ProtoType.BooleanType)))
    )
    val result = ReplaceNullWithFalseInPredicate(input)
    result match
      case ProtoLogicalPlan.Join(
            _,
            _,
            _,
            Some(ProtoExpr.Literal(LiteralValue.BooleanValue(false)))
          ) =>
        ()
      case _ => fail(s"Expected Join with false condition, got $result")

  // ========================================================
  // NullDownPropagation Tests
  // ========================================================

  test("NullDownPropagation: IsNull of Add propagates"):
    // IsNull(a + b) should become IsNull(a) OR IsNull(b)
    val input = ProtoExpr.IsNull(ProtoExpr.Add(col("a"), col("b")))
    val result = NullDownPropagation.propagateNullDown(input)
    result match
      case ProtoExpr.Or(Vector(ProtoExpr.IsNull(_), ProtoExpr.IsNull(_))) => ()
      case _ => fail(s"Expected Or(IsNull, IsNull), got $result")

  test("NullDownPropagation: IsNull of Subtract propagates"):
    val input = ProtoExpr.IsNull(ProtoExpr.Subtract(col("a"), col("b")))
    val result = NullDownPropagation.propagateNullDown(input)
    result match
      case ProtoExpr.Or(Vector(ProtoExpr.IsNull(_), ProtoExpr.IsNull(_))) => ()
      case _ => fail(s"Expected Or(IsNull, IsNull), got $result")

  test("NullDownPropagation: IsNotNull of Multiply propagates"):
    // IsNotNull(a * b) should become IsNotNull(a) AND IsNotNull(b)
    val input = ProtoExpr.IsNotNull(ProtoExpr.Multiply(col("a"), col("b")))
    val result = NullDownPropagation.propagateNullDown(input)
    result match
      case ProtoExpr.And(Vector(ProtoExpr.IsNotNull(_), ProtoExpr.IsNotNull(_))) => ()
      case _ => fail(s"Expected And(IsNotNull, IsNotNull), got $result")

  test("NullDownPropagation: IsNull of simple column is unchanged"):
    val input = ProtoExpr.IsNull(col("a"))
    val result = NullDownPropagation.propagateNullDown(input)
    assertEquals(result, input)

  // ========================================================
  // FoldablePropagation Tests
  // ========================================================

  test("FoldablePropagation: constant alias propagates to parent project"):
    // Project([x, y+1], Project([a AS x, 5 AS y], t))
    // The reference to y+1 should become 5+1 = 6 (after constant folding)
    val innerProj = ProtoLogicalPlan.Project(
      Vector(
        ProtoExpr.Alias(col("a"), "x"),
        ProtoExpr.Alias(ProtoExpr.lit(5), "y")
      ),
      table("t")
    )
    val outerProj = ProtoLogicalPlan.Project(
      Vector(
        col("x"),
        ProtoExpr.Add(
          ProtoExpr.ColumnRef("y", None, ProtoType.IntegerType, nullable = false),
          ProtoExpr.lit(1)
        )
      ),
      innerProj
    )
    val result = FoldablePropagation(outerProj)
    // The result should have the constant 5 substituted for y
    result match
      case ProtoLogicalPlan.Project(
            Vector(_, ProtoExpr.Add(ProtoExpr.Literal(LiteralValue.IntValue(5)), _)),
            _
          ) =>
        ()
      case _ => fail(s"Expected propagated constant, got $result")

  // ========================================================
  // PushFoldableIntoBranches Tests
  // ========================================================

  test("PushFoldableIntoBranches: push addition into IF branches"):
    // IF(cond, 1, 2) + 10 -> IF(cond, 11, 12)
    val input = ProtoExpr.Add(
      ProtoExpr.If(col("cond"), ProtoExpr.lit(1), ProtoExpr.lit(2)),
      ProtoExpr.lit(10)
    )
    val result = PushFoldableIntoBranches.pushFoldableIntoBranches(input)
    result match
      case ProtoExpr.If(_, ProtoExpr.Add(_, _), ProtoExpr.Add(_, _)) => ()
      case _ => fail(s"Expected IF with Add in branches, got $result")

  test("PushFoldableIntoBranches: push multiplication into CaseWhen branches"):
    // CaseWhen((cond1, 2), (cond2, 3), else 4) * 5 -> CaseWhen((cond1, 2*5), (cond2, 3*5), else 4*5)
    val input = ProtoExpr.Multiply(
      ProtoExpr.CaseWhen(
        Vector((col("cond1"), ProtoExpr.lit(2)), (col("cond2"), ProtoExpr.lit(3))),
        Some(ProtoExpr.lit(4))
      ),
      ProtoExpr.lit(5)
    )
    val result = PushFoldableIntoBranches.pushFoldableIntoBranches(input)
    result match
      case cw @ ProtoExpr.CaseWhen(branches, Some(ProtoExpr.Multiply(_, _))) =>
        assert(
          branches.forall { case (_, v) =>
            v.isInstanceOf[ProtoExpr.Multiply]
          },
          s"All branches should have Multiply, got $cw"
        )
      case _ => fail(s"Expected CaseWhen with Multiply in branches, got $result")

  // ========================================================
  // UnwrapCastInBinaryComparison Tests
  // ========================================================

  test("UnwrapCastInBinaryComparison: unwrap cast from INT to BIGINT in equality"):
    // CAST(int_col AS BIGINT) = 5L -> int_col = 5
    val intCol = ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, nullable = true)
    val castExpr = ProtoExpr.Cast(intCol, ProtoType.LongType)
    val input = ProtoLogicalPlan.Filter(
      ProtoExpr.Eq(castExpr, ProtoExpr.Literal(LiteralValue.LongValue(5L))),
      table("t")
    )
    val result = UnwrapCastInBinaryComparison(input)
    result match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Eq(col, ProtoExpr.Literal(LiteralValue.IntValue(5))),
            _
          ) =>
        assertEquals(col, intCol)
      case _ => fail(s"Expected unwrapped comparison, got $result")

  test("UnwrapCastInBinaryComparison: don't unwrap when value overflows"):
    // CAST(byte_col AS LONG) = 1000L should NOT be unwrapped (1000 > Byte.MaxValue)
    val byteCol = ProtoExpr.ColumnRef("b", None, ProtoType.ByteType, nullable = true)
    val castExpr = ProtoExpr.Cast(byteCol, ProtoType.LongType)
    val cond = ProtoExpr.Eq(castExpr, ProtoExpr.Literal(LiteralValue.LongValue(1000L)))
    val input = ProtoLogicalPlan.Filter(cond, table("t"))
    val result = UnwrapCastInBinaryComparison(input)
    result match
      case ProtoLogicalPlan.Filter(ProtoExpr.Eq(ProtoExpr.Cast(_, _), _), _) => ()
      case _ => fail(s"Expected cast to remain, got $result")

  test("UnwrapCastInBinaryComparison: unwrap in greater-than comparison"):
    // CAST(short_col AS INT) > 100 -> short_col > 100S
    val shortCol = ProtoExpr.ColumnRef("s", None, ProtoType.ShortType, nullable = true)
    val castExpr = ProtoExpr.Cast(shortCol, ProtoType.IntegerType)
    val input = ProtoLogicalPlan.Filter(
      ProtoExpr.Gt(castExpr, ProtoExpr.Literal(LiteralValue.IntValue(100))),
      table("t")
    )
    val result = UnwrapCastInBinaryComparison(input)
    result match
      case ProtoLogicalPlan.Filter(
            ProtoExpr.Gt(col, ProtoExpr.Literal(LiteralValue.ShortValue(100))),
            _
          ) =>
        assertEquals(col, shortCol)
      case _ => fail(s"Expected unwrapped comparison, got $result")

  // ========================================================
  // PropagateEmptyRelation Tests
  // ========================================================

  test("PropagateEmptyRelation: filter on empty remains empty"):
    val emptyRel = ProtoLogicalPlan.Limit(0, table("t"))
    val input = ProtoLogicalPlan.Filter(col("a"), emptyRel)
    val result = PropagateEmptyRelation(input)
    assertEquals(result, emptyRel)

  test("PropagateEmptyRelation: inner join with empty left is empty"):
    val emptyRel = ProtoLogicalPlan.Limit(0, table("t1"))
    val input = ProtoLogicalPlan.Join(emptyRel, table("t2"), JoinType.Inner, None)
    val result = PropagateEmptyRelation(input)
    assertEquals(result, emptyRel)

  test("PropagateEmptyRelation: inner join with empty right is empty"):
    val emptyRel = ProtoLogicalPlan.Limit(0, table("t2"))
    val input = ProtoLogicalPlan.Join(table("t1"), emptyRel, JoinType.Inner, None)
    val result = PropagateEmptyRelation(input)
    assertEquals(result, emptyRel)

  test("PropagateEmptyRelation: left anti join with empty right returns left"):
    val emptyRel = ProtoLogicalPlan.Limit(0, table("t2"))
    val leftTable = table("t1")
    val input = ProtoLogicalPlan.Join(leftTable, emptyRel, JoinType.LeftAnti, None)
    val result = PropagateEmptyRelation(input)
    assertEquals(result, leftTable)

  test("PropagateEmptyRelation: union removes empty members"):
    val emptyRel = ProtoLogicalPlan.Limit(0, table("t1"))
    val nonEmptyRel = table("t2")
    val input = ProtoLogicalPlan.Union(Vector(emptyRel, nonEmptyRel), false, false)
    val result = PropagateEmptyRelation(input)
    assertEquals(result, nonEmptyRel)

  // ========================================================
  // RemoveRedundantSorts Tests
  // ========================================================

  test("RemoveRedundantSorts: remove duplicate consecutive sorts"):
    val sortOrder = Vector(SortOrder(col("a"), Ascending, NullsFirst))
    val input =
      ProtoLogicalPlan.Sort(sortOrder, false, ProtoLogicalPlan.Sort(sortOrder, false, table("t")))
    val result = RemoveRedundantSorts(input)
    result match
      case ProtoLogicalPlan.Sort(_, _, ProtoLogicalPlan.RelationRef(_, _, _)) => ()
      case _ => fail(s"Expected single sort, got $result")

  test("RemoveRedundantSorts: outer sort dominates even with different inner order"):
    // When sorts are nested, the outer sort determines final order, making inner sort redundant
    val sortOrder1 = Vector(SortOrder(col("a"), Ascending, NullsFirst))
    val sortOrder2 = Vector(SortOrder(col("b"), Descending, NullsLast))
    val input =
      ProtoLogicalPlan.Sort(sortOrder1, false, ProtoLogicalPlan.Sort(sortOrder2, false, table("t")))
    val result = RemoveRedundantSorts(input)
    // Inner sort should be removed since outer sort dominates
    result match
      case ProtoLogicalPlan.Sort(order, _, ProtoLogicalPlan.RelationRef(_, _, _)) =>
        assertEquals(order, sortOrder1)
      case _ => fail(s"Expected single sort with outer order, got $result")

  // ========================================================
  // RemoveLiteralFromGroupExpressions Tests
  // ========================================================

  test("RemoveLiteralFromGroupExpressions: remove constant from group by"):
    val input = ProtoLogicalPlan.Aggregate(
      Vector(col("a"), ProtoExpr.lit(1), col("b")),
      Vector(col("a"), col("b")),
      table("t")
    )
    val result = RemoveLiteralFromGroupExpressions(input)
    result match
      case ProtoLogicalPlan.Aggregate(grouping, _, _) =>
        assert(!grouping.exists(isLiteral), s"Grouping should not contain literals: $grouping")
      case _ => fail(s"Expected Aggregate, got $result")

  test("RemoveLiteralFromGroupExpressions: keep non-literal group by"):
    val input = ProtoLogicalPlan.Aggregate(
      Vector(col("a"), col("b")),
      Vector(col("a"), col("b")),
      table("t")
    )
    val result = RemoveLiteralFromGroupExpressions(input)
    assertEquals(result, input)

  private def isLiteral(e: ProtoExpr): Boolean = e match
    case ProtoExpr.Literal(_) => true
    case _                    => false

  // ========================================================
  // RemoveRepetitionFromGroupExpressions Tests
  // ========================================================

  test("RemoveRepetitionFromGroupExpressions: remove duplicate group by keys"):
    val input = ProtoLogicalPlan.Aggregate(
      Vector(col("a"), col("b"), col("a")),
      Vector(col("a"), col("b")),
      table("t")
    )
    val result = RemoveRepetitionFromGroupExpressions(input)
    result match
      case ProtoLogicalPlan.Aggregate(grouping, _, _) =>
        assertEquals(grouping.size, 2)
      case _ => fail(s"Expected Aggregate, got $result")

  test("RemoveRepetitionFromGroupExpressions: keep unique group by keys"):
    val input = ProtoLogicalPlan.Aggregate(
      Vector(col("a"), col("b")),
      Vector(col("a"), col("b")),
      table("t")
    )
    val result = RemoveRepetitionFromGroupExpressions(input)
    assertEquals(result, input)

  // ========================================================
  // ReplaceDistinctWithAggregate Tests
  // ========================================================

  test("ReplaceDistinctWithAggregate: convert distinct to aggregate"):
    val proj = ProtoLogicalPlan.Project(Vector(col("a"), col("b")), table("t"))
    val input = ProtoLogicalPlan.Distinct(proj)
    val result = ReplaceDistinctWithAggregate(input)
    result match
      case ProtoLogicalPlan.Aggregate(grouping, aggExprs, _) =>
        assertEquals(grouping.size, 2)
        assertEquals(aggExprs.size, 2)
      case _ => fail(s"Expected Aggregate, got $result")

  // ========================================================
  // InferFiltersFromConstraints Tests
  // ========================================================

  test("InferFiltersFromConstraints: infer filter from equality join"):
    // SELECT * FROM t1 JOIN t2 ON t1.a = t2.a WHERE t1.a = 5
    // Should infer: t2.a = 5
    val t1 = table("t1")
    val t2 = table("t2")
    val joinCond = ProtoExpr.Eq(
      ProtoExpr.ColumnRef("a", Some("t1"), ProtoType.IntegerType, true),
      ProtoExpr.ColumnRef("a", Some("t2"), ProtoType.IntegerType, true)
    )
    val joined = ProtoLogicalPlan.Join(t1, t2, JoinType.Inner, Some(joinCond))
    val filtered = ProtoLogicalPlan.Filter(
      ProtoExpr.Eq(
        ProtoExpr.ColumnRef("a", Some("t1"), ProtoType.IntegerType, true),
        ProtoExpr.lit(5)
      ),
      joined
    )
    val result = InferFiltersFromConstraints(filtered)
    // Should have additional filter inferred
    result match
      case ProtoLogicalPlan.Filter(_, ProtoLogicalPlan.Join(_, _, _, _)) => ()
      case _ => fail(s"Expected Filter over Join, got $result")

  // ========================================================
  // ReplaceExceptWithFilter Tests
  // ========================================================

  test("ReplaceExceptWithFilter: convert EXCEPT to left anti join"):
    val proj1 = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, true)),
      table("t1")
    )
    val proj2 = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, true)),
      table("t2")
    )
    val input = ProtoLogicalPlan.Except(proj1, proj2, false)
    val result = ReplaceExceptWithFilter(input)
    result match
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.Join(_, _, JoinType.LeftAnti, _)) => ()
      case _ => fail(s"Expected Distinct(LeftAntiJoin), got $result")

  test("ReplaceExceptWithFilter: EXCEPT ALL is not converted"):
    val proj1 = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, true)),
      table("t1")
    )
    val proj2 = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, true)),
      table("t2")
    )
    val input = ProtoLogicalPlan.Except(proj1, proj2, true) // isAll = true
    val result = ReplaceExceptWithFilter(input)
    assertEquals(result, input) // Should remain unchanged

  // ========================================================
  // ReplaceIntersectWithSemiJoin Tests
  // ========================================================

  test("ReplaceIntersectWithSemiJoin: convert INTERSECT to left semi join"):
    val proj1 = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, true)),
      table("t1")
    )
    val proj2 = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, true)),
      table("t2")
    )
    val input = ProtoLogicalPlan.Intersect(proj1, proj2, false)
    val result = ReplaceIntersectWithSemiJoin(input)
    result match
      case ProtoLogicalPlan.Distinct(ProtoLogicalPlan.Join(_, _, JoinType.LeftSemi, _)) => ()
      case _ => fail(s"Expected Distinct(LeftSemiJoin), got $result")

  test("ReplaceIntersectWithSemiJoin: INTERSECT ALL is not converted"):
    val proj1 = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, true)),
      table("t1")
    )
    val proj2 = ProtoLogicalPlan.Project(
      Vector(ProtoExpr.ColumnRef("a", None, ProtoType.IntegerType, true)),
      table("t2")
    )
    val input = ProtoLogicalPlan.Intersect(proj1, proj2, true) // isAll = true
    val result = ReplaceIntersectWithSemiJoin(input)
    assertEquals(result, input) // Should remain unchanged
