# ProtoCatalyst Compile-Time Optimizer Plan

## Executive Summary

ProtoCatalyst aims to implement a **compile-time Catalyst** - moving all static, structural optimizations from runtime to compile time. Cost-based optimizations that require runtime statistics remain at runtime via Spark's CBO.

```
┌─────────────────────────────────────────────────────────────────┐
│                      COMPILE TIME                                │
│                  (ProtoCatalyst Optimizer)                       │
├─────────────────────────────────────────────────────────────────┤
│  SQL/DSL → Parse → Analyze → Optimize → ProtoLogicalPlan        │
│                                              ↓                   │
│                                    Optimized Artifact            │
└─────────────────────────────────────────────────────────────────┘
                                       ↓
┌─────────────────────────────────────────────────────────────────┐
│                        RUNTIME                                   │
│                    (Spark CBO, optional)                         │
├─────────────────────────────────────────────────────────────────┤
│  Load Artifact → Convert to LogicalPlan → CBO → Execute         │
│                  (already structurally optimized)                │
└─────────────────────────────────────────────────────────────────┘
```

---

## 1. Architecture

### 1.1 Rule Execution Framework

Based on Spark's `RuleExecutor` pattern:

```scala
package protocatalyst.optimizer

trait Rule[T] {
  val ruleName: String
  def apply(plan: T): T
}

sealed trait Strategy
case object Once extends Strategy                    // Run once, idempotent
case class FixedPoint(maxIterations: Int) extends Strategy  // Until convergence

case class Batch(name: String, strategy: Strategy, rules: Seq[Rule[ProtoLogicalPlan]])

abstract class RuleExecutor {
  def batches: Seq[Batch]

  def execute(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    batches.foldLeft(plan) { (currentPlan, batch) =>
      batch.strategy match {
        case Once =>
          batch.rules.foldLeft(currentPlan)((p, r) => r(p))
        case FixedPoint(max) =>
          var plan = currentPlan
          var iteration = 0
          var changed = true
          while (changed && iteration < max) {
            val newPlan = batch.rules.foldLeft(plan)((p, r) => r(p))
            changed = newPlan != plan
            plan = newPlan
            iteration += 1
          }
          plan
      }
    }
  }
}
```

### 1.2 Module Structure

```
protocatalyst/
├── optimizer/
│   ├── RuleExecutor.scala           # Batch execution framework
│   ├── Rule.scala                   # Base rule trait
│   ├── Optimizer.scala              # Main optimizer with batches
│   │
│   ├── rules/
│   │   ├── expressions/             # Expression-level optimizations
│   │   │   ├── ConstantFolding.scala
│   │   │   ├── BooleanSimplification.scala
│   │   │   ├── NullPropagation.scala
│   │   │   ├── ConstantPropagation.scala
│   │   │   ├── SimplifyCasts.scala
│   │   │   ├── SimplifyConditionals.scala
│   │   │   ├── OptimizeIn.scala
│   │   │   └── LikeSimplification.scala
│   │   │
│   │   ├── operators/               # Plan-level optimizations
│   │   │   ├── ColumnPruning.scala
│   │   │   ├── PushDownPredicates.scala
│   │   │   ├── LimitPushdown.scala
│   │   │   ├── CollapseProject.scala
│   │   │   ├── CollapseFilters.scala
│   │   │   ├── CombineUnions.scala
│   │   │   ├── EliminateDistinct.scala
│   │   │   ├── EliminateSorts.scala
│   │   │   ├── EliminateLimits.scala
│   │   │   └── RemoveRedundantOperators.scala
│   │   │
│   │   └── subqueries/              # Subquery optimizations
│   │       ├── RewriteCorrelatedSubquery.scala
│   │       └── InlineCTE.scala
│   │
│   └── utils/
│       ├── PredicateHelper.scala    # Predicate manipulation utilities
│       ├── AttributeSet.scala       # Attribute reference tracking
│       └── ExpressionCanonicalizer.scala
```

---

## 2. Optimization Rules Catalog

> **Note**: This catalog is aligned with Spark Catalyst's `Optimizer.scala` and `expressions.scala`.
> Rules marked with ✅ are compile-time safe. Rules marked with ⚠️ require runtime info.

### 2.1 Expression-Level Rules (Compile-Time Safe)

#### ConstantFolding ✅
**Source**: `expressions.scala:49-130` (Spark)

Evaluates expressions with constant inputs at compile time.

| Before | After |
|--------|-------|
| `5 + 10` | `15` |
| `'hello' \|\| ' world'` | `'hello world'` |
| `LENGTH('abc')` | `3` |
| `UPPER('hello')` | `'HELLO'` |
| `1 > 0` | `true` |
| `CAST('123' AS INT)` | `123` |

```scala
object ConstantFolding extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    plan.transformExpressionsDown {
      case expr if expr.foldable && !expr.isInstanceOf[Literal] =>
        Literal(expr.eval(), expr.dataType)
    }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### BooleanSimplification ✅
**Source**: `expressions.scala:359-588` (Spark)

Simplifies boolean expressions using logical identities.

| Before | After |
|--------|-------|
| `TRUE AND x` | `x` |
| `FALSE AND x` | `FALSE` |
| `TRUE OR x` | `TRUE` |
| `FALSE OR x` | `x` |
| `x AND x` | `x` |
| `x OR x` | `x` |
| `NOT NOT x` | `x` |
| `NOT (a AND b)` | `(NOT a) OR (NOT b)` (when safe) |
| `x AND NOT x` | `FALSE` (when x not nullable) |
| `x OR NOT x` | `TRUE` (when x not nullable) |

```scala
object BooleanSimplification extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    plan.transformExpressionsUp {
      // Identity rules
      case And(Literal(true, _), right) => right
      case And(left, Literal(true, _)) => left
      case And(Literal(false, _), _) => Literal.False
      case And(_, Literal(false, _)) => Literal.False

      case Or(Literal(true, _), _) => Literal.True
      case Or(_, Literal(true, _)) => Literal.True
      case Or(Literal(false, _), right) => right
      case Or(left, Literal(false, _)) => left

      // Double negation
      case Not(Not(child)) => child

      // Idempotence
      case And(left, right) if left == right => left
      case Or(left, right) if left == right => left

      // Complement (only when not nullable)
      case And(left, Not(right)) if left == right && !left.nullable => Literal.False
      case Or(left, Not(right)) if left == right && !left.nullable => Literal.True
    }
}
```

**Dependencies**: Nullability tracking
**Deterministic**: Yes

---

#### NullPropagation
**Source**: `expressions.scala:860-929` (Spark)

Propagates null knowledge through expressions.

| Before | After |
|--------|-------|
| `IsNull(non_nullable_expr)` | `FALSE` |
| `IsNotNull(non_nullable_expr)` | `TRUE` |
| `null_intolerant_func(NULL)` | `NULL` |
| `COALESCE(NULL, x, NULL, y)` | `COALESCE(x, y)` |
| `COALESCE(x, non_null, y)` | `COALESCE(x, non_null)` |
| `COUNT(NULL)` | `0` |
| `IF(NULL, x, y)` | `y` |

```scala
object NullPropagation extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    plan.transformExpressionsUp {
      // IsNull/IsNotNull on non-nullable
      case IsNull(child) if !child.nullable => Literal.False
      case IsNotNull(child) if !child.nullable => Literal.True

      // Null-intolerant with null input
      case e: NullIntolerant if e.children.exists(_.isInstanceOf[Literal.Null]) =>
        Literal.Null(e.dataType)

      // Coalesce simplification
      case Coalesce(children) =>
        val simplified = children.filterNot(_.isInstanceOf[Literal.Null])
        val untilFirstNonNullable = simplified.takeWhile(_.nullable) match {
          case init if init.length < simplified.length => init :+ simplified(init.length)
          case all => all
        }
        if (untilFirstNonNullable.length == 1) untilFirstNonNullable.head
        else Coalesce(untilFirstNonNullable)
    }
}
```

**Dependencies**: Nullability tracking in expressions
**Deterministic**: Yes

---

#### ConstantPropagation
**Source**: `expressions.scala:134-234` (Spark)

Replaces attributes with known constant values.

| Before | After |
|--------|-------|
| `WHERE id = 5 AND amount > id + 10` | `WHERE id = 5 AND amount > 15` |
| `WHERE status = 'active' AND UPPER(status) = 'ACTIVE'` | `WHERE status = 'active' AND TRUE` |

```scala
object ConstantPropagation extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transform {
      case Filter(condition, child) =>
        val constraints = extractEqualityConstraints(condition)
        val newCondition = propagateConstants(condition, constraints)
        Filter(newCondition, child)
    }
  }

  private def extractEqualityConstraints(expr: ProtoExpr): Map[Attribute, Literal] = {
    expr.collect {
      case Eq(attr: AttributeReference, lit: Literal) => (attr, lit)
      case Eq(lit: Literal, attr: AttributeReference) => (attr, lit)
    }.toMap
  }

  private def propagateConstants(expr: ProtoExpr, constraints: Map[Attribute, Literal]): ProtoExpr = {
    expr.transformUp {
      case attr: AttributeReference if constraints.contains(attr) => constraints(attr)
    }
  }
}
```

**Dependencies**: ConstantFolding (runs after)
**Deterministic**: Yes

---

#### SimplifyCasts
**Source**: `expressions.scala:1200-1280` (Spark)

Removes unnecessary cast operations.

| Before | After |
|--------|-------|
| `CAST(int_col AS INT)` | `int_col` |
| `CAST(CAST(x AS BIGINT) AS BIGINT)` | `CAST(x AS BIGINT)` |
| `CAST(string_lit AS STRING)` | `string_lit` |

```scala
object SimplifyCasts extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    plan.transformExpressionsUp {
      // Cast to same type
      case Cast(child, dataType, _) if child.dataType == dataType => child

      // Nested casts to same type
      case Cast(Cast(child, dt1, _), dt2, _) if dt1 == dt2 => Cast(child, dt1)

      // Cast literal to its own type
      case Cast(lit @ Literal(_, dt), targetType, _) if dt == targetType => lit
    }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### SimplifyConditionals
**Source**: `expressions.scala:1000-1100` (Spark)

Simplifies CASE/WHEN and IF expressions.

| Before | After |
|--------|-------|
| `CASE WHEN TRUE THEN x ELSE y END` | `x` |
| `CASE WHEN FALSE THEN x ELSE y END` | `y` |
| `IF(TRUE, x, y)` | `x` |
| `IF(FALSE, x, y)` | `y` |
| `CASE WHEN cond THEN x ELSE x END` | `x` |
| `COALESCE(x, x, x)` | `COALESCE(x)` → `x` |

```scala
object SimplifyConditionals extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    plan.transformExpressionsUp {
      // IF with constant condition
      case If(Literal(true, _), trueValue, _) => trueValue
      case If(Literal(false, _), _, falseValue) => falseValue

      // IF with same branches
      case If(_, trueValue, falseValue) if trueValue == falseValue => trueValue

      // CASE WHEN with constant true
      case CaseWhen(Seq((Literal(true, _), value)), _) => value

      // CASE WHEN with all branches returning same value
      case CaseWhen(branches, elseValue)
        if branches.map(_._2).distinct.size == 1 &&
           elseValue.forall(_ == branches.head._2) =>
        branches.head._2
    }
}
```

**Dependencies**: ConstantFolding
**Deterministic**: Yes

---

#### OptimizeIn
**Source**: `expressions.scala:319-349` (Spark)

Optimizes IN clause expressions.

| Before | After |
|--------|-------|
| `x IN ()` | `FALSE` |
| `x IN (1)` | `x = 1` |
| `x IN (1, 2, 1, 3, 2)` | `x IN (1, 2, 3)` |
| `x IN (1, 2, ..., 100)` | `InSet(x, HashSet(...))` |
| `NULL IN (1, 2, 3)` | `NULL` |

```scala
object OptimizeIn extends Rule[ProtoLogicalPlan] {
  val InSetThreshold = 10  // Convert to hash set above this size

  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    plan.transformExpressionsUp {
      // Empty IN is always false
      case In(_, Seq()) => Literal.False

      // Single element IN becomes equality
      case In(value, Seq(single)) => Eq(value, single)

      // Deduplicate IN list
      case In(value, list) =>
        val deduped = list.distinct
        if (deduped.length >= InSetThreshold && deduped.forall(_.foldable))
          InSet(value, deduped.map(_.eval()).toSet)
        else
          In(value, deduped)

      // NULL IN (...) is NULL
      case In(Literal.Null(_), _) => Literal.Null(BooleanType)
    }
}
```

**Dependencies**: ConstantFolding
**Deterministic**: Yes

---

#### LikeSimplification
**Source**: `expressions.scala:500-550` (Spark)

Converts LIKE patterns to simpler operations when possible.

| Before | After |
|--------|-------|
| `x LIKE 'abc'` | `x = 'abc'` |
| `x LIKE 'abc%'` | `StartsWith(x, 'abc')` |
| `x LIKE '%abc'` | `EndsWith(x, 'abc')` |
| `x LIKE '%abc%'` | `Contains(x, 'abc')` |

```scala
object LikeSimplification extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    plan.transformExpressionsUp {
      case Like(left, Literal(pattern: String, _), _) =>
        pattern match {
          case p if !p.contains('%') && !p.contains('_') => Eq(left, Literal(p))
          case p if p.endsWith("%") && !p.dropRight(1).exists(isWildcard) =>
            StartsWith(left, Literal(p.dropRight(1)))
          case p if p.startsWith("%") && !p.drop(1).exists(isWildcard) =>
            EndsWith(left, Literal(p.drop(1)))
          case p if p.startsWith("%") && p.endsWith("%") &&
                    !p.drop(1).dropRight(1).exists(isWildcard) =>
            Contains(left, Literal(p.drop(1).dropRight(1)))
          case _ => Like(left, Literal(pattern))
        }
    }

  private def isWildcard(c: Char): Boolean = c == '%' || c == '_'
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### ReorderAssociativeOperator ✅
**Source**: `expressions.scala:239-317` (Spark)

Reorders associative operations to fold constants together.

| Before | After |
|--------|-------|
| `(x + 1) + 2` | `x + 3` |
| `(x * 2) * 3` | `x * 6` |
| `(s \|\| 'a') \|\| 'b'` | `s \|\| 'ab'` |

**Dependencies**: ConstantFolding
**Deterministic**: Yes

---

#### FoldablePropagation ✅
**Source**: `expressions.scala:978-1113` (Spark)

Propagates foldable (constant) expressions through the plan.

| Before | After |
|--------|-------|
| `Project(a, b) where a := 1` | Replace references to `a` with `1` |

**Dependencies**: ConstantFolding
**Deterministic**: Yes

---

#### NullDownPropagation ✅
**Source**: `expressions.scala:936-976` (Spark)

Propagates null checks downward through null-intolerant expressions.

| Before | After |
|--------|-------|
| `IsNull(a + b)` | `IsNull(a) OR IsNull(b)` |
| `IsNotNull(a + b)` | `IsNotNull(a) AND IsNotNull(b)` |

**Dependencies**: NullPropagation
**Deterministic**: Yes

---

#### SimplifyBinaryComparison ✅
**Source**: `expressions.scala` (Spark)

Simplifies binary comparisons with known outcomes.

| Before | After |
|--------|-------|
| `x = x` (non-nullable) | `TRUE` |
| `x < x` | `FALSE` |
| `x >= x` (non-nullable) | `TRUE` |

**Dependencies**: Nullability tracking
**Deterministic**: Yes

---

#### PushFoldableIntoBranches ✅
**Source**: `expressions.scala:675-763` (Spark)

Pushes foldable expressions into conditional branches.

| Before | After |
|--------|-------|
| `IF(cond, 1, 2) + 10` | `IF(cond, 11, 12)` |
| `CASE WHEN c THEN a ELSE b END + 5` | `CASE WHEN c THEN a+5 ELSE b+5 END` |

**Dependencies**: ConstantFolding
**Deterministic**: Yes

---

#### ReplaceNullWithFalseInPredicate ✅
**Source**: `ReplaceNullWithFalseInPredicate.scala:53` (Spark)

Replaces null literals with false in predicates where null behaves like false.

| Before | After |
|--------|-------|
| `WHERE NULL` | `WHERE FALSE` |
| `WHERE x AND NULL` | `WHERE x AND FALSE` |

**Dependencies**: None
**Deterministic**: Yes

---

#### PruneFilters ✅
**Source**: `Optimizer.scala:2001-2036` (Spark)

Removes always-true/false filters based on constraints.

| Before | After |
|--------|-------|
| `Filter(TRUE, child)` | `child` |
| `Filter(FALSE, child)` | `EmptyRelation` |

**Dependencies**: ConstantFolding
**Deterministic**: Yes

---

#### SimplifyCaseConversionExpressions ✅
**Source**: `expressions.scala:1145-1159` (Spark)

Simplifies nested UPPER/LOWER calls.

| Before | After |
|--------|-------|
| `UPPER(UPPER(x))` | `UPPER(x)` |
| `LOWER(LOWER(x))` | `LOWER(x)` |

**Dependencies**: None
**Deterministic**: Yes

---

#### SimplifyDateTimeConversions ✅
**Source**: `expressions.scala:1161-1202` (Spark)

Removes redundant date/time conversions.

**Dependencies**: None
**Deterministic**: Yes

---

#### CombineConcats ✅
**Source**: `expressions.scala:1204+` (Spark)

Combines adjacent concat operations.

| Before | After |
|--------|-------|
| `CONCAT(CONCAT(a, b), c)` | `CONCAT(a, b, c)` |

**Dependencies**: None
**Deterministic**: Yes

---

#### UnwrapCastInBinaryComparison ✅
**Source**: `UnwrapCastInBinaryComparison.scala:102` (Spark)

Optimizes comparisons by removing unnecessary casts.

| Before | After |
|--------|-------|
| `CAST(int_col AS BIGINT) = 5L` | `int_col = 5` |

**Dependencies**: Type system
**Deterministic**: Yes

---

### 2.2 Operator-Level Rules (Compile-Time Safe)

#### ColumnPruning ✅
**Source**: `Optimizer.scala:1051-1204` (Spark)

Removes unused columns from intermediate operators.

```
Before:
  Project [a]
    Project [a, b, c, d]
      Scan [a, b, c, d, e, f]

After:
  Project [a]
    Project [a]
      Scan [a]
```

```scala
object ColumnPruning extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      // Prune Project
      case Project(projectList, child) =>
        val referenced = projectList.flatMap(_.references).toSet
        val prunedChild = pruneColumns(child, referenced)
        Project(projectList, prunedChild)

      // Prune through Filter
      case Filter(condition, child) =>
        val referenced = condition.references
        val prunedChild = pruneColumns(child, referenced ++ child.outputSet)
        Filter(condition, prunedChild)

      // Prune through Join
      case Join(left, right, joinType, condition) =>
        val condRefs = condition.map(_.references).getOrElse(Set.empty)
        val leftPruned = pruneColumns(left, condRefs ++ left.outputSet)
        val rightPruned = pruneColumns(right, condRefs ++ right.outputSet)
        Join(leftPruned, rightPruned, joinType, condition)
    }
  }

  private def pruneColumns(plan: ProtoLogicalPlan, required: Set[Attribute]): ProtoLogicalPlan = {
    plan match {
      case Project(projectList, child) =>
        val needed = projectList.filter(e => required.contains(e.toAttribute))
        if (needed.length < projectList.length) Project(needed, child)
        else plan
      case _ => plan
    }
  }
}
```

**Dependencies**: Attribute reference tracking
**Deterministic**: Yes

---

#### PushDownPredicates
**Source**: `Optimizer.scala:2038-2234` (Spark)

Moves filter conditions closer to data sources.

```
Before:
  Filter [a > 5 AND b < 10]
    Join [a = c]
      Scan left [a, b]
      Scan right [c, d]

After:
  Join [a = c]
    Filter [a > 5]
      Scan left [a, b]
    Filter [b < 10]  -- Wait, this belongs to left too!
      ...
```

```scala
object PushDownPredicates extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transform {
      // Push through Project (if deterministic)
      case Filter(condition, Project(projectList, child))
        if projectList.forall(_.deterministic) =>
        val newCondition = rewriteCondition(condition, projectList)
        Project(projectList, Filter(newCondition, child))

      // Combine adjacent filters
      case Filter(condition1, Filter(condition2, child)) =>
        Filter(And(condition1, condition2), child)

      // Push to join sides
      case Filter(condition, Join(left, right, Inner, joinCond)) =>
        val (leftFilters, rightFilters, remaining) = splitConjunctivePredicates(
          condition, left.outputSet, right.outputSet
        )
        val newLeft = if (leftFilters.nonEmpty) Filter(leftFilters.reduce(And), left) else left
        val newRight = if (rightFilters.nonEmpty) Filter(rightFilters.reduce(And), right) else right
        val newJoin = Join(newLeft, newRight, Inner, joinCond)
        if (remaining.nonEmpty) Filter(remaining.reduce(And), newJoin) else newJoin
    }
  }

  private def splitConjunctivePredicates(
    condition: ProtoExpr,
    leftOutput: Set[Attribute],
    rightOutput: Set[Attribute]
  ): (Seq[ProtoExpr], Seq[ProtoExpr], Seq[ProtoExpr]) = {
    val predicates = splitAnd(condition)
    val leftOnly = predicates.filter(_.references.subsetOf(leftOutput))
    val rightOnly = predicates.filter(_.references.subsetOf(rightOutput))
    val remaining = predicates.filterNot(p => leftOnly.contains(p) || rightOnly.contains(p))
    (leftOnly, rightOnly, remaining)
  }
}
```

**Dependencies**: Determinism tracking
**Deterministic**: Yes

---

#### LimitPushdown
**Source**: `Optimizer.scala:873-972` (Spark)

Moves LIMIT operations closer to data sources.

```
Before:
  Limit 10
    Union
      Scan A
      Scan B

After:
  Limit 10
    Union
      Limit 10
        Scan A
      Limit 10
        Scan B
```

```scala
object LimitPushdown extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transform {
      // Push through Union
      case Limit(n, Union(children, byName, allowMissing)) =>
        Limit(n, Union(children.map(Limit(n, _)), byName, allowMissing))

      // Push through Project (safe, doesn't change cardinality)
      case Limit(n, Project(projectList, child)) =>
        Project(projectList, Limit(n, child))

      // Merge with Offset
      case Limit(limit, Offset(offset, child)) =>
        Offset(offset, Limit(limit + offset, child))

      // Push through Left Outer Join (to left side only)
      case Limit(n, Join(left, right, LeftOuter, cond)) =>
        Join(Limit(n, left), right, LeftOuter, cond)
    }
  }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### CollapseProject
**Source**: `Optimizer.scala:750-800` (Spark)

Merges consecutive Project operators.

```
Before:
  Project [a, b]
    Project [a, b, c]
      Project [a, b, c, d]
        Scan

After:
  Project [a, b]
    Scan
```

```scala
object CollapseProject extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      case Project(projectList1, Project(projectList2, child))
        if projectList2.forall(_.deterministic) =>
        val substituted = projectList1.map(substituteAlias(_, projectList2))
        Project(substituted, child)
    }
  }

  private def substituteAlias(expr: ProtoExpr, aliases: Seq[NamedExpression]): ProtoExpr = {
    val aliasMap = aliases.collect { case Alias(child, name) => name -> child }.toMap
    expr.transformUp {
      case attr: AttributeReference if aliasMap.contains(attr.name) => aliasMap(attr.name)
    }
  }
}
```

**Dependencies**: Determinism tracking
**Deterministic**: Yes

---

#### CollapseFilters
**Source**: `Optimizer.scala:2000-2030` (Spark)

Merges consecutive Filter operators.

```
Before:
  Filter [a > 5]
    Filter [b < 10]
      Filter [c = 'x']
        Scan

After:
  Filter [a > 5 AND b < 10 AND c = 'x']
    Scan
```

```scala
object CollapseFilters extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      case Filter(condition1, Filter(condition2, child)) =>
        Filter(And(Seq(condition2, condition1)), child)
    }
  }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### CombineUnions
**Source**: `Optimizer.scala:600-650` (Spark)

Flattens nested Union operators.

```
Before:
  Union
    Union
      Scan A
      Scan B
    Scan C

After:
  Union
    Scan A
    Scan B
    Scan C
```

```scala
object CombineUnions extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      case Union(children, byName, allowMissing) =>
        val flattened = children.flatMap {
          case Union(innerChildren, bn, am) if bn == byName && am == allowMissing =>
            innerChildren
          case other =>
            Seq(other)
        }
        Union(flattened, byName, allowMissing)
    }
  }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### EliminateDistinct
**Source**: `Optimizer.scala:520-560` (Spark)

Removes redundant DISTINCT operations.

| Before | After | Reason |
|--------|-------|--------|
| `DISTINCT(GROUP BY key)` | `GROUP BY key` | GROUP BY already distinct on keys |
| `DISTINCT(DISTINCT(...))` | `DISTINCT(...)` | Idempotent |
| `DISTINCT(Limit 1)` | `Limit 1` | Single row is distinct |

```scala
object EliminateDistinct extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      // Distinct after Aggregate is redundant if grouping produces unique rows
      case Distinct(agg @ Aggregate(groupingExprs, _, _))
        if groupingExprs.nonEmpty =>
        agg

      // Double distinct
      case Distinct(Distinct(child)) => Distinct(child)

      // Distinct on single row
      case Distinct(Limit(1, child)) => Limit(1, child)
    }
  }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### EliminateSorts
**Source**: `Optimizer.scala:950-1000` (Spark)

Removes unnecessary Sort operations.

| Before | After | Reason |
|--------|-------|--------|
| `Sort(Limit(1, x))` | `Limit(1, x)` | Single row is sorted |
| `Sort(Sort(x))` | `Sort(x)` | Outer sort dominates |
| `Sort in subquery` | Remove | Subquery order doesn't matter |

```scala
object EliminateSorts extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      // Sort on single row
      case Sort(_, _, Limit(1, child)) => Limit(1, child)

      // Nested sorts (outer dominates)
      case Sort(order1, global1, Sort(_, _, child)) =>
        Sort(order1, global1, child)

      // Sort in subquery (if result order doesn't matter)
      case Project(projectList, Sort(_, _, child)) =>
        Project(projectList, child)
    }
  }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### EliminateLimits
**Source**: `Optimizer.scala:900-940` (Spark)

Simplifies LIMIT operations.

| Before | After |
|--------|-------|
| `LIMIT 10 (LIMIT 5 x)` | `LIMIT 5 x` |
| `LIMIT 5 (LIMIT 10 x)` | `LIMIT 5 x` |
| `LIMIT 0` | `EmptyRelation` |

```scala
object EliminateLimits extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      // Nested limits - take minimum
      case Limit(n1, Limit(n2, child)) => Limit(math.min(n1, n2), child)

      // Zero limit
      case Limit(0, _) => LocalRelation(Seq.empty)
    }
  }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### RemoveRedundantAliases
**Source**: `Optimizer.scala:1300-1350` (Spark)

Removes aliases that don't change the name.

```
Before:
  Project [a AS a, b AS b, c AS c]
    Scan

After:
  Project [a, b, c]
    Scan
```

```scala
object RemoveRedundantAliases extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformExpressionsUp {
      case Alias(child: AttributeReference, name) if child.name == name => child
    }
  }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

#### RemoveNoopOperators
**Source**: `Optimizer.scala:1400-1450` (Spark)

Removes operators that have no effect.

| Before | After |
|--------|-------|
| `Project(child.output, child)` | `child` |
| `Filter(TRUE, child)` | `child` |
| `Sort(empty_order, child)` | `child` |

```scala
object RemoveNoopOperators extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      // Identity project
      case Project(projectList, child) if projectList == child.output => child

      // Always-true filter
      case Filter(Literal(true, BooleanType), child) => child

      // Empty sort
      case Sort(Seq(), _, child) => child

      // Union of single child
      case Union(Seq(child), _, _) => child
    }
  }
}
```

**Dependencies**: None
**Deterministic**: Yes

---

### 2.3 Subquery Rules (Compile-Time Safe)

#### InlineCTE
**Source**: `Optimizer.scala:200-280` (Spark)

Inlines Common Table Expressions when beneficial.

```sql
-- Before
WITH t AS (SELECT * FROM users WHERE active)
SELECT * FROM t WHERE age > 25

-- After (if CTE used once)
SELECT * FROM users WHERE active AND age > 25
```

```scala
object InlineCTE extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transform {
      case With(cteRelations, child) =>
        val refCounts = countReferences(child, cteRelations.map(_.name).toSet)

        // Inline CTEs referenced only once
        val (toInline, toKeep) = cteRelations.partition { cte =>
          refCounts.getOrElse(cte.name, 0) <= 1 && isSafeToInline(cte.plan)
        }

        val inlined = toInline.foldLeft(child) { (plan, cte) =>
          plan.transform {
            case CTERelationRef(name, _) if name == cte.name => cte.plan
          }
        }

        if (toKeep.isEmpty) inlined else With(toKeep, inlined)
    }
  }

  private def isSafeToInline(plan: ProtoLogicalPlan): Boolean = {
    // Safe if deterministic and not too large
    plan.deterministic && plan.stats.sizeInBytes < InlineThreshold
  }
}
```

**Dependencies**: Reference counting
**Deterministic**: Yes

---

#### RewriteCorrelatedSubquery
**Source**: `Optimizer.scala:2300-2500` (Spark)

Decorrelates correlated subqueries when possible.

```sql
-- Before (correlated)
SELECT * FROM orders o
WHERE o.amount > (SELECT AVG(amount) FROM orders WHERE customer_id = o.customer_id)

-- After (decorrelated)
SELECT o.* FROM orders o
JOIN (
  SELECT customer_id, AVG(amount) as avg_amount
  FROM orders
  GROUP BY customer_id
) agg ON o.customer_id = agg.customer_id
WHERE o.amount > agg.avg_amount
```

```scala
object RewriteCorrelatedSubquery extends Rule[ProtoLogicalPlan] {
  def apply(plan: ProtoLogicalPlan): ProtoLogicalPlan = {
    plan.transformUp {
      case Filter(condition, child) =>
        val (correlated, uncorrelated) = extractCorrelatedPredicates(condition)
        if (correlated.isEmpty) plan
        else {
          // Decorrelate: convert to join
          val decorrelated = decorrelate(correlated, child)
          val newCondition = uncorrelated.reduceOption(And).getOrElse(Literal.True)
          if (newCondition == Literal.True) decorrelated
          else Filter(newCondition, decorrelated)
        }
    }
  }
}
```

**Dependencies**: Subquery analysis
**Deterministic**: Yes

---

## 3. Optimization Batches

> **Alignment Note**: This batch structure is modeled after Spark Catalyst's `Optimizer.defaultBatches`.
> Catalyst runs ~28 batches; we implement the compile-time safe subset.

### 3.1 Catalyst's Actual Batch Order (for reference)

```
Spark Catalyst Optimizer Batches:
 1. Finish Analysis (FixedPoint(1))           - FinishAnalysis rules
 2. Rewrite With expression (FixedPoint)      - CTE rewriting
 3. Eliminate Distinct (Once)                 - EliminateDistinct
 4. Inline CTE (Once)                         - InlineCTE
 5. Union (FixedPoint)                        - CombineUnions, RemoveNoopUnion
 6. LocalRelation early (FixedPoint)          - ConvertToLocalRelation, PropagateEmptyRelation
 7. Pullup Correlated Expressions (Once)      - Subquery correlation
 8. Subquery (FixedPoint(1))                  - OptimizeSubqueries
 9. Replace Operators (FixedPoint)            - Rewrite EXCEPT/INTERSECT to joins
10. Aggregate (FixedPoint)                    - Group expression optimization
11. Operator Optimization (FixedPoint) ← ~60 rules, run TWICE (before/after filter inference)
12. Infer Filters (Once)                      - InferFiltersFromConstraints
13. Clean Up Temporary CTE Info (Once)
14. Pre CBO Rules (Once)                      - Prepare for cost-based optimization
15. Early Filter and Projection Push-Down (Once)
16. Update CTE Relation Stats (Once)
17. Join Reorder (FixedPoint(1))              - ⚠️ COST-BASED - skip for compile-time
18. Eliminate Sorts (Once)                    - EliminateSorts, RemoveRedundantSorts
19. Decimal Optimizations (FixedPoint)
20. Distinct Aggregate Rewrite (Once)
21. Object Expressions Optimization (FixedPoint)
22. LocalRelation (FixedPoint)
23. Optimize One Row Plan (FixedPoint)
24. Check Cartesian Products (Once)
25. RewriteSubquery (Once)
26. NormalizeFloatingNumbers (Once)
27. ReplaceUpdateFieldsExpression (Once)
```

### 3.2 ProtoCatalyst Batch Organization (Compile-Time Subset)

```scala
object Optimizer extends RuleExecutor {

  // The core optimization rules - same as Catalyst's operatorOptimizationRuleSet
  // but excluding runtime-dependent rules
  val operatorOptimizationRules = Seq(
    // ─── Operator Push Down ───
    PushProjectionThroughUnion,
    PushProjectionThroughLimitAndOffset,
    ReorderJoin,              // Heuristic reordering (not cost-based)
    EliminateOuterJoin,
    PushDownPredicates,
    LimitPushDown,
    ColumnPruning,

    // ─── Operator Combine ───
    CollapseProject,
    EliminateOffsets,
    EliminateLimits,
    CombineUnions,
    CombineFilters,

    // ─── Constant Folding & Strength Reduction ───
    NullPropagation,
    NullDownPropagation,
    ConstantPropagation,
    FoldablePropagation,
    OptimizeIn,
    ConstantFolding,
    ReorderAssociativeOperator,
    LikeSimplification,
    BooleanSimplification,
    SimplifyConditionals,
    PushFoldableIntoBranches,
    SimplifyBinaryComparison,
    ReplaceNullWithFalseInPredicate,
    PruneFilters,
    SimplifyCasts,
    SimplifyCaseConversionExpressions,
    CombineConcats,
    RemoveRedundantAliases,
    UnwrapCastInBinaryComparison,
    RemoveNoopOperators
  )

  override def batches: Seq[Batch] = Seq(
    // ═══════════════════════════════════════════════════════════════
    // BATCH 1: Eliminate Distinct (Once)
    // ═══════════════════════════════════════════════════════════════
    Batch("Eliminate Distinct", Once,
      EliminateDistinct
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 2: Inline CTE (Once)
    // ═══════════════════════════════════════════════════════════════
    Batch("Inline CTE", Once,
      InlineCTE
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 3: Union (FixedPoint)
    // ═══════════════════════════════════════════════════════════════
    Batch("Union", FixedPoint(10),
      RemoveNoopOperators,
      CombineUnions
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 4: Propagate Empty Relation (FixedPoint)
    // ═══════════════════════════════════════════════════════════════
    Batch("Propagate Empty Relation", FixedPoint(10),
      PropagateEmptyRelation
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 5: Subquery (Once)
    // ═══════════════════════════════════════════════════════════════
    Batch("Subquery", Once,
      RewriteCorrelatedScalarSubquery
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 6: Replace Operators (FixedPoint)
    // ═══════════════════════════════════════════════════════════════
    Batch("Replace Operators", FixedPoint(10),
      ReplaceDistinctWithAggregate,
      ReplaceExceptWithFilter,
      ReplaceIntersectWithSemiJoin
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 7: Aggregate (FixedPoint)
    // ═══════════════════════════════════════════════════════════════
    Batch("Aggregate", FixedPoint(10),
      RemoveLiteralFromGroupExpressions,
      RemoveRepetitionFromGroupExpressions
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 8: Operator Optimization - First Pass (FixedPoint)
    // This is the CORE optimization batch with ~30 rules
    // ═══════════════════════════════════════════════════════════════
    Batch("Operator Optimization", FixedPoint(100),
      operatorOptimizationRules: _*
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 9: Infer Filters (Once)
    // Derives additional filters from constraints
    // ═══════════════════════════════════════════════════════════════
    Batch("Infer Filters", Once,
      InferFiltersFromConstraints
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 10: Operator Optimization - Second Pass (FixedPoint)
    // Run again after filter inference
    // ═══════════════════════════════════════════════════════════════
    Batch("Operator Optimization after Inferring Filters", FixedPoint(100),
      operatorOptimizationRules: _*
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 11: Eliminate Sorts (Once)
    // ═══════════════════════════════════════════════════════════════
    Batch("Eliminate Sorts", Once,
      EliminateSorts,
      RemoveRedundantSorts
    ),

    // ═══════════════════════════════════════════════════════════════
    // BATCH 12: Final Cleanup (Once)
    // ═══════════════════════════════════════════════════════════════
    Batch("Final Cleanup", Once,
      RemoveNoopOperators,
      CollapseProject,
      ConstantFolding
    )
  )
}
```

### 3.2 Rule Dependencies

```
                    ┌─────────────────┐
                    │ Canonicalization │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ ConstantFolding  │
                    └────────┬────────┘
                             │
         ┌───────────────────┼───────────────────┐
         │                   │                   │
         ▼                   ▼                   ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│ BooleanSimplify │ │ SimplifyCondit. │ │ SimplifyCasts   │
└────────┬────────┘ └────────┬────────┘ └────────┬────────┘
         │                   │                   │
         └───────────────────┼───────────────────┘
                             │
                    ┌────────▼────────┐
                    │ ConstantPropag. │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ PredicatePushdn │
                    └────────┬────────┘
                             │
                    ┌────────▼────────┐
                    │ ColumnPruning   │
                    └─────────────────┘
```

---

## 4. What Stays at Runtime

### 4.1 Cost-Based Optimizations (Require Statistics)

These optimizations need runtime data statistics and remain in Spark's CBO:

| Optimization | Why Runtime | What It Needs |
|--------------|-------------|---------------|
| **Join Reordering** | Optimal order depends on table sizes | Row counts, cardinality |
| **Broadcast vs Shuffle Join** | Depends on actual data size | Table size in bytes |
| **Skew Join Handling** | Needs to detect runtime skew | Partition statistics |
| **Dynamic Partition Pruning** | Filter values known at runtime | Runtime filter results |

### 4.2 Adaptive Query Execution (AQE)

Spark's AQE adjusts plans based on runtime observations:

- Coalesce shuffle partitions based on actual data size
- Switch join strategies mid-execution
- Handle data skew dynamically
- Optimize shuffle partition count

### 4.3 Physical Planning

Physical operator selection happens at runtime:

- Scan strategies (columnar vs row)
- Exchange (shuffle) placement
- Codegen decisions
- Memory management

---

## 5. Implementation Phases

### Phase 1: Framework + Core Rules
**Priority: High**

1. Implement `RuleExecutor` framework
2. Implement expression transformation utilities
3. Core rules:
   - ConstantFolding
   - BooleanSimplification
   - CollapseProject
   - CollapseFilters

### Phase 2: Predicate Optimization
**Priority: High**

1. NullPropagation
2. ConstantPropagation
3. OptimizeIn
4. SimplifyConditionals
5. LikeSimplification

### Phase 3: Operator Pushdown
**Priority: High**

1. PushDownPredicates (non-join)
2. LimitPushdown
3. ColumnPruning
4. Push predicates through joins

### Phase 4: Cleanup Rules
**Priority: Medium**

1. EliminateDistinct
2. EliminateSorts
3. EliminateLimits
4. RemoveNoopOperators
5. RemoveRedundantAliases
6. CombineUnions

### Phase 5: Subquery Optimization
**Priority: Medium**

1. InlineCTE
2. RewriteCorrelatedSubquery (basic cases)

### Phase 6: Advanced Rules
**Priority: Low**

1. ReorderAssociativeOperator
2. Advanced join predicate pushdown
3. Complex subquery decorrelation

---

## 6. Testing Strategy

### 6.1 Unit Tests

Each rule needs:
- Positive cases (rule applies)
- Negative cases (rule doesn't apply)
- Edge cases (nulls, empty, boundaries)
- Idempotence test (applying twice = applying once)

### 6.2 Integration Tests

End-to-end optimization tests:
```scala
test("complex query optimization") {
  val input = """
    SELECT a, b
    FROM (
      SELECT a, b, c, d
      FROM t
      WHERE x > 5
    )
    WHERE x > 5 AND y < 10
  """

  val optimized = Optimizer.optimize(parse(input))

  // Should have:
  // - Merged filters
  // - Pruned columns c, d
  // - Removed duplicate predicate x > 5
}
```

### 6.3 Parity Tests

Compare with Spark's optimizer output:
```scala
test("parity with Spark optimizer") {
  val sql = "SELECT * FROM t WHERE a = 1 AND b = 2"

  val protoOptimized = ProtoCatalystOptimizer.optimize(parse(sql))
  val sparkOptimized = spark.sessionState.optimizer.execute(sparkParse(sql))

  assert(semanticallyEqual(protoOptimized, sparkOptimized))
}
```

---

## 7. Metrics and Success Criteria

### 7.1 Correctness Metrics

| Metric | Target |
|--------|--------|
| Rule unit test coverage | > 95% |
| Parity test pass rate | 100% |
| No regressions in existing tests | 100% |

### 7.2 Performance Metrics

| Metric | Target |
|--------|--------|
| Optimization time (typical query) | < 10ms |
| Plan size reduction | > 20% on average |
| Compile-time overhead | < 500ms total |

### 7.3 Quality Metrics

| Metric | Target |
|--------|--------|
| Rules idempotent | 100% |
| Rules deterministic | 100% |
| Clear optimization trace | Always available |

---

## 8. References

### Spark Catalyst Source Files

| File | Content |
|------|---------|
| `Optimizer.scala` | Main optimizer, batches, operator rules |
| `expressions.scala` | Expression optimization rules |
| `RuleExecutor.scala` | Batch execution framework |
| `joins.scala` | Join-specific optimizations |
| `subquery.scala` | Subquery decorrelation |
| `Rule.scala` | Base rule interface |

All at: `/spark/sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/optimizer/`

### Design Documents

- [DESIGN.md](./DESIGN.md) - Overall ProtoCatalyst architecture
- [SPARK_CATALYST_REFERENCE.md](./SPARK_CATALYST_REFERENCE.md) - Catalyst reference
- [SQL_PARSER.md](./SQL_PARSER.md) - SQL parsing implementation

---

## Appendix A: Rule Quick Reference

### Compile-Time Safe Rules (✅)

| Rule | Category | Source File | Line |
|------|----------|-------------|------|
| **Expression Rules** |
| ConstantFolding | Expression | expressions.scala | 49 |
| ConstantPropagation | Expression | expressions.scala | 134 |
| ReorderAssociativeOperator | Expression | expressions.scala | 239 |
| OptimizeIn | Expression | expressions.scala | 319 |
| BooleanSimplification | Expression | expressions.scala | 359 |
| SimplifyConditionals | Expression | expressions.scala | 590 |
| PushFoldableIntoBranches | Expression | expressions.scala | 675 |
| LikeSimplification | Expression | expressions.scala | 765 |
| NullPropagation | Expression | expressions.scala | 860 |
| NullDownPropagation | Expression | expressions.scala | 936 |
| FoldablePropagation | Expression | expressions.scala | 978 |
| SimplifyCasts | Expression | expressions.scala | 1115 |
| SimplifyCaseConversionExpressions | Expression | expressions.scala | 1145 |
| SimplifyDateTimeConversions | Expression | expressions.scala | 1161 |
| CombineConcats | Expression | expressions.scala | 1204 |
| UnwrapCastInBinaryComparison | Expression | UnwrapCastInBinaryComparison.scala | 102 |
| ReplaceNullWithFalseInPredicate | Expression | ReplaceNullWithFalseInPredicate.scala | 53 |
| **Operator Rules** |
| EliminateDistinct | Operator | Optimizer.scala | 550 |
| RemoveRedundantAliases | Operator | Optimizer.scala | 604 |
| RemoveNoopOperators | Operator | Optimizer.scala | 790 |
| LimitPushDown | Operator | Optimizer.scala | 873 |
| PushProjectionThroughUnion | Operator | Optimizer.scala | 983 |
| ColumnPruning | Operator | Optimizer.scala | 1051 |
| CollapseProject | Operator | Optimizer.scala | 1206 |
| CombineUnions | Operator | Optimizer.scala | 1812 |
| CombineFilters | Operator | Optimizer.scala | 1885 |
| EliminateSorts | Operator | Optimizer.scala | 1921 |
| PruneFilters | Operator | Optimizer.scala | 2001 |
| PushDownPredicates | Operator | Optimizer.scala | 2038 |
| PushPredicateThroughNonJoin | Operator | Optimizer.scala | 2054 |
| PushPredicateThroughJoin | Operator | Optimizer.scala | 2246 |
| EliminateLimits | Operator | Optimizer.scala | 2371 |
| EliminateOffsets | Operator | Optimizer.scala | 2407 |
| ReplaceDistinctWithAggregate | Operator | Optimizer.scala | 2542 |
| RemoveLiteralFromGroupExpressions | Operator | Optimizer.scala | 2765 |
| RemoveRepetitionFromGroupExpressions | Operator | Optimizer.scala | 2843 |
| **Join Rules** |
| ReorderJoin (heuristic) | Join | joins.scala | 45 |
| EliminateOuterJoin | Join | joins.scala | 158 |
| **Subquery Rules** |
| InlineCTE | Subquery | InlineCTE.scala | - |
| RewriteCorrelatedScalarSubquery | Subquery | subquery.scala | 693 |
| RewritePredicateSubquery | Subquery | subquery.scala | 56 |

### Runtime-Only Rules (⚠️ - NOT for compile-time)

| Rule | Reason | Source File |
|------|--------|-------------|
| CostBasedJoinReorder | Requires table statistics | CostBasedJoinReorder.scala |
| InjectRuntimeFilter | Runtime filter values | InjectRuntimeFilter.scala |
| OptimizeSubqueries | May need runtime stats | Optimizer.scala |
| UpdateCTERelationStats | Runtime statistics | Optimizer.scala |
| CheckCartesianProducts | Runtime validation | Optimizer.scala |
| PropagateEmptyRelation | May need runtime eval | Optimizer.scala |
