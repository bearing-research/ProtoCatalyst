# Spark Catalyst Test Patterns Analysis

This document analyzes Spark Catalyst's testing patterns and compares them with ProtoCatalyst's current test implementation. The goal is to identify discrepancies and recommend improvements.

## Spark Catalyst Testing Patterns

### 1. Test Organization

**Pattern: One Suite Per Rule (or Related Rule Group)**

Spark organizes tests with dedicated test files for each optimizer rule:

```
sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/optimizer/
├── PruneFiltersSuite.scala
├── PropagateEmptyRelationSuite.scala
├── UnwrapCastInBinaryComparisonSuite.scala
├── ReplaceOperatorSuite.scala        # Groups related replace rules
├── AggregateOptimizeSuite.scala      # Groups aggregate-related rules
├── InferFiltersFromConstraintsSuite.scala
├── FoldablePropagationSuite.scala
└── ... (90+ test suite files)
```

**ProtoCatalyst Current State:**
- All optimizer tests in `OptimizerSuite.scala` (2254 lines)
- Additional rules in `AdditionalRulesSuite.scala` (400+ lines)

### 2. Custom RuleExecutor Per Test Suite

**Pattern: Each test suite defines a minimal optimizer with only the rules being tested**

```scala
// Spark Pattern
class PruneFiltersSuite extends PlanTest {
  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("Filter Pushdown and Pruning", FixedPoint(1),
        CombineFilters,
        PruneFilters,
        PushPredicateThroughNonJoin) :: Nil
  }
  // tests...
}
```

This approach:
- Isolates the rules being tested
- Makes tests more predictable
- Reduces test coupling
- Makes debugging easier

**ProtoCatalyst Current State:**
- Tests typically call rules directly: `PruneFilters(input)`
- No mini-optimizer setup for integration testing of rule batches

### 3. PlanTest Trait with comparePlans()

**Pattern: Dedicated test trait with plan comparison utilities**

```scala
// Spark's PlanTest trait
trait PlanTestBase {
  protected def comparePlans(
      plan1: LogicalPlan,
      plan2: LogicalPlan,
      checkAnalysis: Boolean = true): Unit = {
    if (checkAnalysis) {
      SimpleAnalyzer.checkAnalysis(plan1)
      SimpleAnalyzer.checkAnalysis(plan2)
    }
    val normalized1 = normalizePlan(normalizeExprIds(plan1))
    val normalized2 = normalizePlan(normalizeExprIds(plan2))
    if (normalized1 != normalized2) {
      fail(s"""
        |== FAIL: Plans do not match ===
        |${sideBySide(normalized1.treeString, normalized2.treeString).mkString("\n")}
        """.stripMargin)
    }
  }

  protected def compareExpressions(e1: Expression, e2: Expression): Unit = {
    comparePlans(Filter(e1, OneRowRelation()), Filter(e2, OneRowRelation()))
  }
}
```

Key features:
- Normalizes expression IDs before comparison
- Provides side-by-side diff on failure
- Optional analysis validation
- Expression comparison helper

**ProtoCatalyst Current State:**
- Manual pattern matching to verify results
- No normalized comparison
- No side-by-side diff output
- Less informative failure messages

### 4. Fluent DSL for Plan Construction

**Pattern: Readable DSL for building test plans**

```scala
// Spark DSL usage
import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._

val testRelation = LocalRelation($"a".int, $"b".int, $"c".int)

// Build plans fluently
val query = testRelation
  .where($"a" === 1 && $"b" > 10)
  .select($"a", $"b")
  .groupBy($"a")(sum($"b"))
  .orderBy($"a".asc)

// Operators work on expressions
val expr = $"a" + $"b" * 2
val filter = $"a".isNull && ($"b" === 1 || $"c" > 5)
```

DSL features:
- `$"name"` creates UnresolvedAttribute
- `.int`, `.string`, `.boolean` set types
- Operator overloading (`+`, `-`, `===`, `&&`, etc.)
- Plan builders (`.where()`, `.select()`, `.join()`, etc.)
- Implicit conversions for literals

**ProtoCatalyst Current State:**
- Manual constructor calls:
```scala
val input = ProtoLogicalPlan.Filter(
  ProtoExpr.Literal(LiteralValue.BooleanValue(false)),
  table("t")
)
```

### 5. Test Fixture Patterns

**Pattern: LocalRelation with typed attributes**

```scala
// Spark fixtures
val testRelation = LocalRelation($"a".int, $"b".int, $"c".int)
val testRelation1 = LocalRelation.fromExternalRows(Seq($"a".int), data = Seq(Row(1)))
val x = testRelation.subquery("x")  // Adds qualifier
val y = testRelation.subquery("y")
```

**ProtoCatalyst Current State:**
```scala
private def table(name: String): ProtoLogicalPlan =
  ProtoLogicalPlan.RelationRef(name, None, emptyContract(name))

private def col(name: String): ProtoExpr =
  ProtoExpr.ColumnRef(name, None, ProtoType.IntegerType, nullable = true)
```

### 6. Analyze Before Comparison

**Pattern: Plans are analyzed before comparison**

```scala
// Spark pattern
val query = testRelation.where($"a" === 1).analyze  // .analyze resolves attributes
val optimized = Optimize.execute(query.analyze)
val correctAnswer = testRelation.where($"a" === 1).analyze

comparePlans(optimized, correctAnswer)
```

This ensures:
- Attribute references are resolved
- Types are inferred
- Semantic correctness is validated

**ProtoCatalyst Current State:**
- No analysis phase before comparison
- Plans tested as-is (which may be appropriate given compile-time focus)

### 7. Comprehensive Test Coverage

**Pattern: Tests cover multiple scenarios**

```scala
test("basic functionality") { ... }
test("edge case: empty input") { ... }
test("edge case: overflow values") { ... }
test("no-op: rule should not apply") { ... }
test("SPARK-12345: regression test for specific issue") { ... }
test("with configuration X disabled") {
  withSQLConf(SQLConf.SOME_CONFIG.key -> "false") { ... }
}
```

Patterns observed:
- Basic happy path tests
- Edge cases explicitly tested
- No-op scenarios (verify rule doesn't transform when it shouldn't)
- JIRA ticket references for regression tests
- Configuration variation testing

**ProtoCatalyst Current State:**
- Basic functionality mostly covered
- Some edge cases
- Limited no-op testing
- No issue reference convention

### 8. Helper Methods for Repeated Patterns

**Pattern: Extract common test setups into helper methods**

```scala
// Spark pattern
private def testConstraintsAfterJoin(
    x: LogicalPlan,
    y: LogicalPlan,
    expectedLeft: LogicalPlan,
    expectedRight: LogicalPlan,
    joinType: JoinType,
    condition: Option[Expression] = Some("x.a".attr === "y.a".attr)) = {
  val originalQuery = x.join(y, joinType, condition).analyze
  val correctAnswer = expectedLeft.join(expectedRight, joinType, condition).analyze
  val optimized = Optimize.execute(originalQuery)
  comparePlans(optimized, correctAnswer)
}
```

**ProtoCatalyst Current State:**
- Some helpers (`table()`, `col()`)
- Could benefit from more test helpers

---

## Discrepancy Summary

| Aspect | Spark Catalyst | ProtoCatalyst | Priority |
|--------|---------------|---------------|----------|
| Test organization | One suite per rule | All rules in 1-2 files | Medium |
| Plan comparison | `comparePlans()` with normalization | Manual pattern matching | High |
| DSL for construction | Fluent `$"a".int.where(...)` | Manual constructors | Medium |
| Mini-optimizer | Custom per test suite | Direct rule calls | Low |
| Test fixtures | `LocalRelation` with types | `RelationRef` with empty contract | Low |
| Analysis phase | Plans analyzed before test | No analysis | Low (may be intentional) |
| Edge case coverage | Comprehensive | Partial | Medium |
| Helper methods | Many reusable helpers | Few | Medium |
| JIRA references | Standard practice | Not used | Low |
| Config testing | `withSQLConf()` | Not applicable | N/A |

---

## Recommendations

### High Priority

1. **Implement `comparePlans()` helper**
   - Add plan normalization (especially for expression IDs if applicable)
   - Provide tree diff on failure
   - Make test failures more informative

2. **Add expression comparison helper**
   - `compareExpressions(e1, e2)` for expression-level tests

### Medium Priority

3. **Create fluent DSL for plan construction**
   - Extension methods for `ProtoExpr`: `col("a") + col("b")`, `col("a") === lit(1)`
   - Extension methods for `ProtoLogicalPlan`: `plan.where(expr).select(...)`
   - Would significantly improve test readability

4. **Split test suites by rule**
   - Create dedicated test files for complex rules
   - Keep related rules grouped (e.g., all Replace* rules together)

5. **Improve edge case coverage**
   - Add tests for boundary conditions
   - Add no-op tests (rule should not apply)
   - Consider systematic test generation

### Low Priority

6. **Add mini-optimizer pattern for integration tests**
   - Test rule combinations together
   - Verify batch execution order matters

7. **Adopt issue reference convention**
   - Reference GitHub issues in test names when fixing bugs

---

## Example Improved Test Structure

```scala
// Proposed: core/src/test/scala/protocatalyst/optimizer/rules/PruneFiltersSuite.scala

package protocatalyst.optimizer.rules

import protocatalyst.optimizer._
import protocatalyst.plan._
import protocatalyst.testutil._  // New: test utilities

class PruneFiltersSuite extends OptimizerTestSuite:

  // Mini-optimizer for this rule
  val optimize = RuleExecutor(
    Batch("Filters", FixedPoint(1), PruneFilters, CombineFilters)
  )

  val testRelation = LocalRelation.int("a").int("b").int("c")

  test("filter with FALSE becomes Limit 0"):
    val input = testRelation.where(lit(false))
    val expected = testRelation.limit(0)
    comparePlans(optimize(input), expected)

  test("filter with TRUE is removed"):
    val input = testRelation.where(lit(true))
    val expected = testRelation
    comparePlans(optimize(input), expected)

  test("no-op: filter with column condition unchanged"):
    val input = testRelation.where(col("a") > lit(5))
    val optimized = optimize(input)
    // Verify structure is preserved
    assert(optimized.isInstanceOf[Filter])

  test("GH-123: regression for nested false filters"):
    val input = testRelation.where(lit(false)).where(lit(true))
    val expected = testRelation.limit(0)
    comparePlans(optimize(input), expected)
```

---

## Files to Review

Key Spark test files for reference:

| Rule | Spark Test File |
|------|-----------------|
| PruneFilters | `PruneFiltersSuite.scala` |
| PropagateEmptyRelation | `PropagateEmptyRelationSuite.scala` |
| UnwrapCastInBinaryComparison | `UnwrapCastInBinaryComparisonSuite.scala` |
| ReplaceDistinctWithAggregate | `ReplaceOperatorSuite.scala` |
| RemoveLiteralFromGroupExpressions | `AggregateOptimizeSuite.scala` |
| InferFiltersFromConstraints | `InferFiltersFromConstraintsSuite.scala` |
| FoldablePropagation | `FoldablePropagationSuite.scala` |
| PushFoldableIntoBranches | `PushFoldableIntoBranchesSuite.scala` |
| NullDownPropagation | `NullDownPropagationSuite.scala` |
| ReplaceNullWithFalseInPredicate | `ReplaceNullWithFalseInPredicateSuite.scala` |

All located in: `/spark/sql/catalyst/src/test/scala/org/apache/spark/sql/catalyst/optimizer/`
