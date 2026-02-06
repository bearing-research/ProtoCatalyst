# Compile-Time Query Optimization

This document explains ProtoCatalyst's compile-time query optimization system.

## The Big Idea

**Move safe, deterministic query optimization from runtime to compile time.**

Spark's Catalyst optimizer runs every time a query executes. But many optimizations don't need runtime information:

| Optimization | Runtime Info Needed? | ProtoCatalyst |
|--------------|---------------------|---------------|
| Constant folding (`5 + 5` → `10`) | No | Compile time |
| Boolean simplification (`true AND x` → `x`) | No | Compile time |
| Predicate canonicalization | No | Compile time |
| Filter combining | No | Compile time |
| Cost-based join reordering | Yes (statistics) | Runtime (Spark) |
| Partition pruning | Yes (catalog) | Runtime (Spark) |
| AQE re-optimization | Yes (runtime stats) | Runtime (Spark) |

ProtoCatalyst performs the "always safe" optimizations at Scala compile time, embedding optimized plans directly in bytecode.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                     COMPILE TIME (scalac)                        │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   DSL                           SQL                              │
│   quote { ... }                 SqlMacro.compileOptimized(...)   │
│        │                              │                          │
│        ▼                              ▼                          │
│   QuoteMacro                    SqlParser                        │
│   (AST analysis)                (SQL parsing)                    │
│        │                              │                          │
│        └──────────┬──────────────────┘                          │
│                   ▼                                              │
│           ProtoLogicalPlan                                       │
│           (compile-time IR)                                      │
│                   │                                              │
│                   ▼                                              │
│           Optimizer.optimize()                                   │
│           (48 rules at compile time)                             │
│                   │                                              │
│                   ▼                                              │
│           ToExpr[ProtoLogicalPlan]                               │
│           (lift to Expr for embedding)                           │
│                   │                                              │
│                   ▼                                              │
│           Bytecode constant                                      │
│           (plan + content hash)                                  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        RUNTIME (Spark)                           │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   CompiledQuery.toBytes                                          │
│           │                                                      │
│           ▼                                                      │
│   Protobuf serialization                                         │
│           │                                                      │
│           ▼                                                      │
│   spark-catalyst module                                          │
│   (ProtoLogicalPlan → Spark LogicalPlan)                         │
│           │                                                      │
│           ▼                                                      │
│   Spark Optimizer + AQE                                          │
│   (cost-based, runtime optimizations)                            │
│           │                                                      │
│           ▼                                                      │
│   Execution                                                      │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## Two Entry Points

### 1. SQL Path

```scala
import protocatalyst.sql.SqlMacro

case class User(name: String, age: Int, salary: Double) derives ProtoEncoder

// SQL is parsed AND optimized at compile time
val query = SqlMacro.compileOptimized[User](
  "SELECT name, age FROM users WHERE age > 18 AND true"
)
// At compile time:
//   - SQL parsed
//   - "AND true" folded away
//   - Plan embedded in bytecode
```

### 2. DSL Path (quote macro)

```scala
import protocatalyst.dsl._

case class User(name: String, age: Int, salary: Double) derives ProtoEncoder

// DSL is analyzed and optimized at compile time
val query = QuoteMacro.quote {
  Table[User]("users")
    .filter(_.age > 18)
    .filter(_.salary > 50000.0)  // Combined with previous filter
    .limit(100)
}
// At compile time:
//   - DSL AST analyzed via macros
//   - Filters potentially combined
//   - Plan embedded in bytecode
```

## How the DSL Macro Works

The `QuoteMacro.quote { }` macro:

1. **Receives the DSL expression** as an AST (Abstract Syntax Tree)
2. **Extracts type information** using `ProtoEncoder` at compile time
3. **Pattern matches the AST** to recognize DSL operations
4. **Builds a `ProtoLogicalPlan`** representing the query
5. **Runs the optimizer** (48 rules, all at compile time)
6. **Embeds the result** in bytecode via `ToExpr` instances

### Example AST Transformation

```scala
// User writes:
Table[User]("users").filter(_.age > 18).limit(10)

// Macro sees AST like:
Apply(
  Select(
    Apply(
      Select(
        Apply(TypeApply(Select(_, "apply"), _), List(Literal("users"))),
        "filter"
      ),
      List(/* lambda: _.age > 18 */)
    ),
    "limit"
  ),
  List(Literal(10))
)

// Macro produces:
ProtoLogicalPlan.Limit(
  10,
  ProtoLogicalPlan.Filter(
    ProtoExpr.Gt(
      ProtoExpr.ColumnRef("age", None, ProtoType.IntegerType, false),
      ProtoExpr.Literal(LiteralValue.IntValue(18))
    ),
    ProtoLogicalPlan.RelationRef("users", None, schemaContract)
  )
)
```

## Supported DSL Operations

### Query Operations

| Operation | Example | Status |
|-----------|---------|--------|
| Table reference | `Table[User]("users")` | ✅ |
| `.toQuery` | `table.toQuery` | ✅ |
| `.filter` / `.where` | `.filter(_.age > 18)` | ✅ |
| `.limit` | `.limit(10)` | ✅ |
| `.distinct` | `.distinct` | ✅ |
| `.as` (alias) | `.as("u")` | ✅ |
| `.orderBy` | `.orderBy(_.age.asc)` | ✅ |
| `.union` | `.union(other)` | ✅ |
| `.intersect` | `.intersect(other)` | ✅ |
| `.intersectAll` | `.intersectAll(other)` | ✅ |
| `.except` | `.except(other)` | ✅ |
| `.exceptAll` | `.exceptAll(other)` | ✅ |

### Expression Operations

| Operation | Example | Status |
|-----------|---------|--------|
| Comparison | `>`, `>=`, `<`, `<=` | ✅ |
| Equality | `===`, `=!=` | ✅ |
| Boolean AND | `&&` | ✅ |
| Boolean OR | `\|\|` | ✅ |
| Int literals | `18`, `100` | ✅ |
| String literals | `"Alice"` | ✅ |
| Double literals | `50000.0` | ✅ |
| Boolean literals | `true`, `false` | ✅ |

### Not Yet Implemented

| Operation | Notes |
|-----------|-------|
| `.join` / `.leftJoin` | Complex - requires handling two schemas |
| `.select` via macro | Type inference issues with Dynamic |
| `.groupBy` / aggregations | Requires aggregate function support |
| Arithmetic (`+`, `-`, `*`, `/`) | In expressions |
| Subqueries | Nested query support |

## File Organization

```
query/src/main/scala/protocatalyst/
├── dsl/
│   ├── QuoteMacro.scala      # The quote { } macro implementation
│   ├── Table.scala           # Table[A] entry point
│   ├── Query.scala           # Query[A] builder
│   ├── Expr.scala            # Expression DSL
│   ├── Column.scala          # Column references
│   └── FieldSelector.scala   # Dynamic field access (_.field)
├── query/
│   └── CompiledQuery.scala   # Result type with artifact
└── ...

core/src/main/scala/protocatalyst/
├── macros/
│   └── ProtoLiftables.scala  # ToExpr instances for IR types
├── plan/
│   └── ProtoLogicalPlan.scala # Logical plan IR (19 node types)
├── expr/
│   └── ProtoExpr.scala       # Expression IR (57 expression types)
└── optimizer/
    ├── Optimizer.scala       # 48 optimization rules
    └── rules/                # Individual rule implementations
```

## Testing

```bash
# Run DSL macro tests
sbt "query/testOnly *QuoteMacroSuite"

# Run all query tests
sbt "query/test"

# Currently: 22 QuoteMacroSuite tests, 103 total query tests
```

## Known Limitations

### Type Inference with Dynamic

The `FieldSelector` uses Scala's `Dynamic` trait for `_.field` syntax:

```scala
class FieldSelector[A] extends Dynamic:
  def selectDynamic(fieldName: String): Column[A, Any] = ...
```

This returns `Column[A, Any]`, losing the actual field type. This affects:

- Lambda-style `select` (macro can't infer output type)
- Complex expressions that need type info

**Workaround**: The macro extracts types from the `ProtoEncoder` schema rather than relying on Scala's type inference.

### Static Analysis Only

The macro can only analyze expressions that are statically known at compile time:

```scala
// Works - literal table name
QuoteMacro.quote { Table[User]("users") }

// Won't work - runtime variable
val tableName = "users"
QuoteMacro.quote { Table[User](tableName) }  // Compile error
```

## Next Steps

1. **Joins**: Add `.join`, `.leftJoin`, `.rightJoin` support
2. **Arithmetic**: Add `+`, `-`, `*`, `/` in expressions
3. **Select via macro**: Explore alternative to Dynamic for better type inference
4. **Aggregate functions**: `count`, `sum`, `avg`, etc. for `.groupBy`

## Related Documents

- [DESIGN.md](DESIGN.md) - Overall ProtoCatalyst architecture
- [OPTIMIZER_PLAN.md](OPTIMIZER_PLAN.md) - Optimizer rule details
- [SQL_PARSER.md](SQL_PARSER.md) - SQL path documentation
- [Plan file](../.claude/plans/) - Detailed implementation plan
