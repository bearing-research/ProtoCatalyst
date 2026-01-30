# Spark Integration Plan

## Executive Summary

**Goal**: ProtoCatalyst provides Scala 3 encoder infrastructure that will be **integrated into Apache Spark** to enable Spark's migration to Scala 3. Users will continue using normal Spark APIs - ProtoCatalyst is an internal implementation detail.

### The Problem

Spark's current encoder system relies heavily on Scala 2-specific features:
- **Runtime reflection** (`scala.reflect.runtime.universe`)
- **Scala 2 macros** (`scala.reflect.macros`)
- **TypeTags** (deprecated in Scala 3)

These don't exist in Scala 3, blocking Spark's migration.

### The Solution

ProtoCatalyst provides:
1. **Scala 3 Mirror-based encoder derivation** - replaces TypeTags and runtime reflection
2. **Compile-time schema generation** - replaces runtime schema inference
3. **Compatible IR** - maps directly to Spark's Catalyst expressions and plans

### Integration Vision

```
┌─────────────────────────────────────────────────────────────────┐
│                     Apache Spark (Scala 3)                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   User Code                                                      │
│   ─────────                                                      │
│   case class User(name: String, age: Int)                       │
│   val df = spark.createDataFrame(users)  // Just normal Spark!  │
│   df.filter($"age" > 18).select($"name")                        │
│                                                                  │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   Spark SQL Internal (uses ProtoCatalyst)                       │
│   ────────────────────────────────────────                      │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ ProtoEncoder[User]     → StructType(name, age)          │   │
│   │ (Scala 3 mirrors)        (Catalyst schema)              │   │
│   └─────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼                                   │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ Catalyst Expressions   ← ProtoExpr                      │   │
│   │ Catalyst LogicalPlan   ← ProtoLogicalPlan               │   │
│   └─────────────────────────────────────────────────────────┘   │
│                              │                                   │
│                              ▼                                   │
│   ┌─────────────────────────────────────────────────────────┐   │
│   │ Physical Planning → Execution → Results                 │   │
│   └─────────────────────────────────────────────────────────┘   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

## What Spark Needs from ProtoCatalyst

### Current Spark Encoder Architecture (Scala 2)

```scala
// Current Spark API (Scala 2.13)
import org.apache.spark.sql.Encoder
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder

case class User(name: String, age: Int)

// This uses TypeTags + runtime reflection (Scala 2 only!)
implicit val userEncoder: Encoder[User] = Encoders.product[User]

// Under the hood:
// 1. TypeTag[User] captures type info at compile time (Scala 2 macros)
// 2. Runtime reflection inspects case class fields
// 3. Schema is built dynamically
// 4. Serializer/deserializer generated via code-gen
```

### What ProtoCatalyst Provides (Scala 3)

```scala
// ProtoCatalyst approach (Scala 3)
import protocatalyst.encoder.ProtoEncoder

case class User(name: String, age: Int) derives ProtoEncoder

// Under the hood:
// 1. Scala 3 Mirror captures type info at compile time
// 2. Schema generated at compile time (no runtime reflection)
// 3. ProtoSchema maps 1:1 to Spark StructType
// 4. Can generate InternalRow serializers
```

### Integration Requirements

| Spark Component | Current (Scala 2) | Replacement (ProtoCatalyst) | Status |
|-----------------|-------------------|----------------------------|--------|
| `Encoder[T]` | TypeTag + reflection | `ProtoEncoder[T]` with mirrors | ✅ Done |
| `StructType` | Runtime schema inference | `ProtoSchema` → `StructType` | ✅ Done |
| `Expression` | Catalyst expressions | `ProtoExpr` → `Expression` | ✅ Done (mock) |
| `LogicalPlan` | Catalyst plans | `ProtoLogicalPlan` → `LogicalPlan` | ✅ Done (mock) |
| `InternalRow` | Row serialization | `RowSerializer[T]` derivation | ✅ Done |
| `UnsafeRow` | Optimized row format | `UnsafeRowSerializer[T]` derivation | ✅ Done |

## Current ProtoCatalyst State

### What's Complete ✅

| Component | Status | Spark Equivalent |
|-----------|--------|------------------|
| ProtoType (type system) | 100% | `DataType` |
| ProtoSchema | 100% | `StructType` |
| ProtoExpr (expressions) | 95% | `Expression` (50+ types) |
| ProtoLogicalPlan | 95% | `LogicalPlan` (14 nodes) |
| ProtoEncoder derivation | 100% | `Encoder[T]` derivation |
| RowSerializer derivation | 100% | `InternalRow` serialization |
| UnsafeRowSerializer | 100% | `UnsafeRow` binary format |
| Expression Mapping | 100% | `Expression` (bidirectional via mock) |
| Plan Mapping | 100% | `LogicalPlan` (bidirectional via mock) |
| Schema fingerprinting | 100% | (New capability) |
| SQL Parser | 100% | `SparkSqlParser` |

### What's Missing for Spark Integration ❌

| Component | Priority | Description |
|-----------|----------|-------------|
| **ExpressionEncoder Bridge** | P0 | Implement `Encoder[T]` using `ProtoEncoder[T]` |
| **Real Spark Integration** | P0 | Replace mock types with real Spark types when available |
| **Resolver Integration** | P1 | Integrate with Spark's `Resolver` |
| **Codegen Support** | P2 | Generate efficient Java bytecode |

## Row Serialization ✅

Row serialization is now implemented via `RowSerializer[T]` derivation using Scala 3 mirrors.

### Implementation

```scala
// encoder/src/main/scala/protocatalyst/encoder/RowSerializer.scala
trait RowSerializer[T]:
  def schema: Vector[FieldSchema]
  def serialize(value: T)(using conv: InternalTypeConverter): Array[Any]
  def deserialize(row: Array[Any])(using conv: InternalTypeConverter): T

object RowSerializer:
  inline def derived[T](using m: Mirror.ProductOf[T]): RowSerializer[T] = ...
```

### Key Features

1. **Compile-time derivation** using Scala 3 mirrors - no runtime reflection
2. **InternalTypeConverter abstraction** - separates type conversion from serialization logic
3. **Option handling** - automatically unwraps `Option[T]` to `T | null`
4. **Nested struct support** - recursively serializes nested case classes
5. **Collection support** - handles `List`, `Vector`, `Map`, etc.

### Type Conversion

The `InternalTypeConverter` trait abstracts Spark-specific type conversions:

```scala
trait InternalTypeConverter:
  def toInternal(value: Any, dataType: ProtoType): Any   // String → UTF8String
  def fromInternal(value: Any, dataType: ProtoType): Any // UTF8String → String
```

- **Default converter**: Pass-through (for testing without Spark)
- **MockInternalTypeConverter**: Simulates Spark's UTF8String, ArrayData, etc.
- **SparkInternalTypeConverter**: (Future) Real Spark type conversions

### Usage

```scala
case class User(name: String, age: Int)
val serializer = RowSerializer.derived[User]

// Serialize
val user = User("Alice", 30)
val row: Array[Any] = serializer.serialize(user)
// With Spark converter: Array(UTF8String("Alice"), 30)

// Deserialize
val deserialized: User = serializer.deserialize(row)
// User("Alice", 30)
```

## Expression and Plan Mapping ✅

Bidirectional converters between ProtoCatalyst IR and mock Spark types are implemented. These can be easily adapted to real Spark types when Spark Scala 3 becomes available.

### Expression Converter

```scala
// mock-runtime/src/main/scala/protocatalyst/mock/ExpressionConverter.scala
object ExpressionConverter:
  def toMock(expr: ProtoExpr): MockExpression = ...   // ProtoExpr → MockExpression
  def fromMock(expr: MockExpression): ProtoExpr = ... // MockExpression → ProtoExpr
```

**Supported expressions (35+ types):**
- Literals (all types)
- Column references and bound references
- Comparisons (Eq, NotEq, Lt, LtEq, Gt, GtEq)
- Logical (And, Or, Not)
- Null handling (IsNull, IsNotNull, Coalesce)
- Arithmetic (Add, Subtract, Multiply, Divide)
- String (Concat, Upper, Lower, Substring, Like)
- Aggregates (Count, Sum, Avg, Min, Max)
- Control flow (CaseWhen, If, In)
- Cast and Alias
- Subqueries (ScalarSubquery, Exists, InSubquery)
- Window functions (RowNumber, Rank, DenseRank, Lead, Lag, etc.)

### Plan Converter

```scala
// mock-runtime/src/main/scala/protocatalyst/mock/PlanConverter.scala
object PlanConverter:
  def toMock(plan: ProtoLogicalPlan): MockLogicalPlan = ...
  def fromMock(plan: MockLogicalPlan): ProtoLogicalPlan = ...
```

**Supported plan nodes (14 types):**
- RelationRef → LogicalRelation
- Project, Filter, Aggregate
- Sort, Limit, Distinct
- Join (all types)
- Union, Intersect, Except
- SubqueryAlias, Window
- With (CTE)

### Mock Types

Mock types mirror Spark's type hierarchy for testing without Spark dependency:
- `MockExpression` → `org.apache.spark.sql.catalyst.expressions.Expression`
- `MockLogicalPlan` → `org.apache.spark.sql.catalyst.plans.logical.LogicalPlan`
- `MockDataType` → `org.apache.spark.sql.types.DataType`

## UnsafeRow Support ✅

`UnsafeRow` is Spark's optimized binary row format for CPU cache efficiency and zero-copy serialization.

### Implementation

```scala
// mock-runtime/src/main/scala/protocatalyst/mock/UnsafeRowSerializer.scala
trait UnsafeRowSerializer[T]:
  def schema: MockDataType.StructType
  def serialize(value: T): MockUnsafeRow
  def deserialize(row: MockUnsafeRow): T

object UnsafeRowSerializer:
  inline def derived[T](using m: Mirror.ProductOf[T]): UnsafeRowSerializer[T] = ...
```

### Key Components

1. **MockUnsafeRow** - Binary row format with:
   - 8-byte aligned memory layout
   - Null bitmap (1 bit per field, rounded to 8-byte boundary)
   - Fixed-width region (8 bytes per field)
   - Variable-width region for strings/arrays/nested structs

2. **UnsafeRowWriter** - Constructs UnsafeRow from values:
   - Handles all primitive types (boolean, int, long, double, etc.)
   - Variable-length data (strings, binary, arrays, maps)
   - Nested struct support

3. **UnsafeRowSerializer** - Compile-time derivation:
   - Uses Scala 3 mirrors (no runtime reflection)
   - Full roundtrip serialization/deserialization
   - Support for Option fields, arrays, maps

### Usage

```scala
case class User(name: String, age: Int) derives ProtoEncoder

val ser = UnsafeRowSerializer.derived[User]
val user = User("Alice", 30)

// Serialize to binary format
val row: MockUnsafeRow = ser.serialize(user)
// row.sizeInBytes = 40 (null bitmap + fixed + variable)

// Deserialize back
val back: User = ser.deserialize(row)
// User("Alice", 30)
```

### Memory Layout Example

For `User(name: String, age: Int)`:
```
[Null Bitmap: 8 bytes][name offset+len: 8 bytes][age: 8 bytes][name bytes: 8 bytes]
Total: 32 bytes (8-byte aligned)
```

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────┐
│                         Compile Time                                 │
├─────────────────────────────────────────────────────────────────────┤
│  SQL String ──► SQL Parser ──► AST ──► AstToProtoTransform          │
│                                              │                       │
│  Query DSL ──────────────────────────────────┤                       │
│                                              ▼                       │
│                                      ProtoLogicalPlan               │
│                                      ProtoExpr                       │
│                                              │                       │
│                                              ▼                       │
│  case class T ──► ProtoEncoder ──► ProtoSchema ──► CompiledArtifact │
│                                                           │          │
│                                                    serialize         │
│                                                           ▼          │
│                                                     [bytes]          │
└─────────────────────────────────────────────────────────────────────┘
                                    │
                            deserialize
                                    ▼
┌─────────────────────────────────────────────────────────────────────┐
│                          Runtime (Spark)                             │
├─────────────────────────────────────────────────────────────────────┤
│  CompiledArtifact                                                    │
│        │                                                             │
│        ▼                                                             │
│  ┌──────────────────┐    ┌───────────────────┐                      │
│  │ ExprConverter    │    │ PlanConverter     │                      │
│  │ ProtoExpr →      │    │ ProtoLogicalPlan →│                      │
│  │ Spark Expression │    │ Spark LogicalPlan │                      │
│  └────────┬─────────┘    └─────────┬─────────┘                      │
│           │                        │                                 │
│           └────────────┬───────────┘                                 │
│                        ▼                                             │
│              ┌──────────────────┐                                    │
│              │ QueryBinder      │                                    │
│              │ Resolve attrs    │                                    │
│              │ Validate schemas │                                    │
│              └────────┬─────────┘                                    │
│                       │                                              │
│         ┌─────────────┴─────────────┐                               │
│         ▼                           ▼                                │
│  ┌─────────────────┐    ┌────────────────────────┐                  │
│  │ Normal Path     │    │ Fast Path (Bypass)     │                  │
│  │ → Analyzer      │    │ → Skip Analyzer        │                  │
│  │ → Optimizer     │    │ → Optimizer (optional) │                  │
│  │ → Planner       │    │ → Planner              │                  │
│  └────────┬────────┘    └────────────┬───────────┘                  │
│           │                          │                               │
│           └──────────┬───────────────┘                               │
│                      ▼                                               │
│              SparkPlan (Physical)                                    │
│                      │                                               │
│                      ▼                                               │
│              RDD[InternalRow]                                        │
│                      │                                               │
│                      ▼                                               │
│                 DataFrame                                            │
└─────────────────────────────────────────────────────────────────────┘
```

## Implementation Phases

### Phase 1: InternalRow Serialization (P0 - Core Blocker)

**Goal**: Generate `InternalRow` serializers/deserializers using Scala 3 mirrors

#### 1.1 Row Serializer Derivation

```scala
// encoder/src/main/scala/protocatalyst/encoder/RowSerializer.scala
trait RowSerializer[T]:
  def serialize(value: T): InternalRow
  def deserialize(row: InternalRow): T

object RowSerializer:
  // Derive using Scala 3 mirrors
  inline def derived[T](using m: Mirror.ProductOf[T]): RowSerializer[T] =
    new RowSerializer[T]:
      def serialize(value: T): InternalRow =
        val values = Tuple.fromProductTyped(value).toArray
        new GenericInternalRow(values.map(convertToInternal))

      def deserialize(row: InternalRow): T =
        val values = (0 until row.numFields).map(i => row.get(i, ???))
        m.fromProduct(Tuple.fromArray(values.toArray))
```

#### 1.2 Type-Specific Converters

```scala
// Handle Spark's internal types
object InternalTypeConverter:
  def toInternal(value: Any, dataType: ProtoType): Any = dataType match
    case ProtoType.StringType => UTF8String.fromString(value.asInstanceOf[String])
    case ProtoType.IntType => value  // Int is same
    case ProtoType.LongType => value  // Long is same
    case ProtoType.ArrayType(elem, _) =>
      new GenericArrayData(value.asInstanceOf[Seq[_]].map(toInternal(_, elem)).toArray)
    case ProtoType.StructType(fields) =>
      // Recursive for nested structs
      val struct = value.asInstanceOf[Product]
      new GenericInternalRow(fields.zip(struct.productIterator.toSeq).map {
        case (f, v) => toInternal(v, f.dataType)
      }.toArray)
    // ... other types

  def fromInternal(value: Any, dataType: ProtoType): Any = dataType match
    case ProtoType.StringType => value.asInstanceOf[UTF8String].toString
    case ProtoType.IntType => value
    // ... inverse conversions
```

### Phase 2: ExpressionEncoder Bridge (P0 - Spark API Compatibility)

**Goal**: Implement Spark's `Encoder[T]` interface using `ProtoEncoder[T]`

#### 2.1 Encoder Implementation

```scala
// This would live in Spark's codebase, using ProtoCatalyst
package org.apache.spark.sql.catalyst.encoders

import protocatalyst.encoder.{ProtoEncoder, RowSerializer}

class ProtoBasedEncoder[T](
  proto: ProtoEncoder[T],
  serializer: RowSerializer[T]
) extends Encoder[T]:

  override val schema: StructType =
    SchemaConverter.toSparkSchema(proto.schema)

  override val clsTag: ClassTag[T] = proto.classTag

  // Serialization using ProtoCatalyst
  def toRow(value: T): InternalRow = serializer.serialize(value)
  def fromRow(row: InternalRow): T = serializer.deserialize(row)
```

#### 2.2 Implicit Encoder Derivation

```scala
// In Spark's Encoders object
object Encoders:
  // New Scala 3 API using ProtoCatalyst
  inline def derived[T](using m: Mirror.ProductOf[T]): Encoder[T] =
    val proto = ProtoEncoder.derived[T]
    val serializer = RowSerializer.derived[T]
    new ProtoBasedEncoder(proto, serializer)

  // Replaces the old Scala 2 API:
  // def product[T: TypeTag]: Encoder[T] = ...  // Uses runtime reflection
```

### Phase 3: Expression/Plan Mapping (P1 - Full Integration)

**Goal**: Bidirectional conversion between ProtoCatalyst IR and Spark Catalyst

#### 3.1 Expression Converter

```scala
// Bidirectional: ProtoExpr ↔ Catalyst Expression
object ExpressionBridge:
  def toSpark(expr: ProtoExpr): Expression = expr match
    case ProtoExpr.Literal(LiteralValue.IntValue(v)) =>
      Literal(v, IntegerType)
    case ProtoExpr.ColumnRef(name, qual, _, _) =>
      UnresolvedAttribute(qual.map(_ + "." + name).getOrElse(name))
    case ProtoExpr.Eq(l, r) =>
      EqualTo(toSpark(l), toSpark(r))
    // ... 50+ cases

  def fromSpark(expr: Expression): ProtoExpr = expr match
    case Literal(v: Int, IntegerType) =>
      ProtoExpr.Literal(LiteralValue.IntValue(v))
    case EqualTo(l, r) =>
      ProtoExpr.Eq(fromSpark(l), fromSpark(r))
    // ... inverse
```

#### 3.2 Plan Converter

```scala
object PlanBridge:
  def toSpark(plan: ProtoLogicalPlan): LogicalPlan = plan match
    case ProtoLogicalPlan.Project(exprs, child) =>
      Project(exprs.map(ExpressionBridge.toSpark), toSpark(child))
    case ProtoLogicalPlan.Filter(cond, child) =>
      Filter(ExpressionBridge.toSpark(cond), toSpark(child))
    // ... 14 plan types
```

**Expression coverage needed**:
- Literals (all types) ✓
- Column references ✓
- Comparisons (Eq, NotEq, Lt, LtEq, Gt, GtEq) ✓
- Logical (And, Or, Not) ✓
- Arithmetic (Add, Subtract, Multiply, Divide, Mod) ✓
- Null handling (IsNull, IsNotNull, Coalesce) ✓
- String (Concat, Upper, Lower, Substring, Like) ✓
- Aggregates (Count, Sum, Avg, Min, Max) ✓
- Control flow (CaseWhen, If, In) ✓
- Cast ✓
- Window functions ✓
- Subqueries (ScalarSubquery, Exists, InSubquery) ✓

#### 1.2 Plan Converter

Convert ProtoLogicalPlan to Spark's LogicalPlan:

```scala
// spark/src/main/scala/protocatalyst/spark/PlanConverter.scala
object PlanConverter:
  def convert(
    plan: ProtoLogicalPlan,
    catalog: SessionCatalog,
    resolver: AttributeResolver
  ): LogicalPlan =
    plan match
      case ProtoLogicalPlan.RelationRef(name, alias, schema) =>
        val table = catalog.lookupRelation(TableIdentifier(name))
        alias.map(SubqueryAlias(_, table)).getOrElse(table)

      case ProtoLogicalPlan.Project(exprs, child) =>
        val childPlan = convert(child, catalog, resolver)
        val sparkExprs = exprs.map(ExprConverter.convert(_, resolver))
        Project(sparkExprs.map(_.asInstanceOf[NamedExpression]), childPlan)

      case ProtoLogicalPlan.Filter(cond, child) =>
        val childPlan = convert(child, catalog, resolver)
        val sparkCond = ExprConverter.convert(cond, resolver)
        Filter(sparkCond, childPlan)

      case ProtoLogicalPlan.Join(left, right, joinType, condition) =>
        val leftPlan = convert(left, catalog, resolver)
        val rightPlan = convert(right, catalog, resolver)
        val sparkJoinType = convertJoinType(joinType)
        val sparkCond = condition.map(ExprConverter.convert(_, resolver))
        Join(leftPlan, rightPlan, sparkJoinType, sparkCond, JoinHint.NONE)
      // ... other plan nodes
```

**Plan nodes needed**:
- RelationRef → UnresolvedRelation / LogicalRelation ✓
- Project → Project ✓
- Filter → Filter ✓
- Aggregate → Aggregate ✓
- Sort → Sort ✓
- Limit → Limit / GlobalLimit ✓
- Distinct → Distinct ✓
- Join → Join ✓
- Union → Union ✓
- Intersect → Intersect ✓
- Except → Except ✓
- SubqueryAlias → SubqueryAlias ✓
- Window → Window ✓
- With → WithCTE ✓
- Values → LocalRelation ✓

#### 1.3 Query Binder

Bind attributes at runtime:

```scala
// spark/src/main/scala/protocatalyst/spark/QueryBinder.scala
object QueryBinder:
  def bind(
    compiled: CompiledArtifact,
    spark: SparkSession
  ): Either[BindingError, LogicalPlan] =
    // 1. Validate schemas match registered tables
    val validation = validateSchemas(compiled.requiredSchemas, spark)

    // 2. Convert plan
    val plan = PlanConverter.convert(
      compiled.plan,
      spark.sessionState.catalog,
      AttributeResolver(spark)
    )

    // 3. Run through analyzer to resolve remaining attributes
    val analyzed = spark.sessionState.analyzer.execute(plan)

    Right(analyzed)
```

#### 1.4 DataFrame Bridge

Simple entry point:

```scala
// spark/src/main/scala/protocatalyst/spark/package.scala
extension (spark: SparkSession)
  def executeCompiled[A](compiled: CompiledQuery[A]): DataFrame =
    QueryBinder.bind(compiled.artifact, spark) match
      case Right(plan) =>
        Dataset.ofRows(spark, plan)
      case Left(err) =>
        throw new AnalysisException(err.message)

// Usage:
val query = CompiledQuery.sql[User]("SELECT name FROM users WHERE age > 18")
val df = spark.executeCompiled(query)
```

### Phase 2: Performance Optimization (P1 - For production perf benefits)

**Goal**: Bypass Spark's analyzer for pre-compiled queries

#### 2.1 Pre-Analyzed Plan

Mark plans as already analyzed:

```scala
// Custom logical plan node that skips analysis
case class PreAnalyzedPlan(
  boundPlan: LogicalPlan,
  compiledAt: Long,
  contentHash: String
) extends LogicalPlan {
  override def output: Seq[Attribute] = boundPlan.output
  override def children: Seq[LogicalPlan] = boundPlan :: Nil
  // Mark as resolved to skip analysis rules
  override lazy val resolved: Boolean = true
}
```

#### 2.2 Session Extension

Register custom rules:

```scala
// spark/src/main/scala/protocatalyst/spark/ProtoCatalystExtension.scala
class ProtoCatalystExtension extends (SparkSessionExtensions => Unit):
  override def apply(ext: SparkSessionExtensions): Unit =
    // Resolution rule: handle PreAnalyzedPlan
    ext.injectResolutionRule { session =>
      new Rule[LogicalPlan] {
        override def apply(plan: LogicalPlan): LogicalPlan = plan match
          case p: PreAnalyzedPlan => p.boundPlan  // Already resolved
          case other => other
      }
    }

    // Optional: Custom optimizer rules
    ext.injectOptimizerRule { session =>
      ProtoCatalystOptimizer(session)
    }
```

#### 2.3 Configuration

Enable via Spark config:

```scala
spark.sql.extensions = "protocatalyst.spark.ProtoCatalystExtension"
```

### Phase 3: Advanced Features (P2)

#### 3.1 UDF Resolution

Resolve OpaqueCall to registered UDFs:

```scala
case ProtoExpr.OpaqueCall(name, args, returnType) =>
  val sparkArgs = args.map(convert(_, resolver))
  val funcId = FunctionIdentifier(name)

  if session.sessionState.catalog.functionExists(funcId) then
    val func = session.sessionState.catalog.lookupFunction(funcId, sparkArgs)
    func
  else
    throw new AnalysisException(s"Unknown function: $name")
```

#### 3.2 Schema Evolution Handling

Handle minor schema differences:

```scala
def validateSchemas(
  required: Map[String, SchemaContract],
  spark: SparkSession
): Either[SchemaValidationError, Unit] =
  required.foreach { (tableName, contract) =>
    val actual = spark.table(tableName).schema
    contract.validationMode match
      case ValidationMode.Exact => exactMatch(actual, contract.schema)
      case ValidationMode.Compatible => compatibleMatch(actual, contract.schema)
      case ValidationMode.Relaxed => relaxedMatch(actual, contract.schema)
  }
```

#### 3.3 Metrics & Observability

Track compile-time vs runtime benefits:

```scala
case class ExecutionMetrics(
  compileTimeMs: Long,
  bindTimeMs: Long,
  analyzerSkipped: Boolean,
  optimizerTimeMs: Long,
  executionTimeMs: Long
)
```

## File Structure

```
spark/src/main/scala/protocatalyst/spark/
├── ExprConverter.scala        # ProtoExpr → Spark Expression
├── PlanConverter.scala        # ProtoLogicalPlan → Spark LogicalPlan
├── TypeConverter.scala        # ProtoType → Spark DataType (exists)
├── QueryBinder.scala          # Runtime attribute binding
├── PreAnalyzedPlan.scala      # Analyzer bypass node
├── ProtoCatalystExtension.scala # SparkSessionExtensions
├── SchemaValidator.scala      # Runtime schema validation
└── package.scala              # Extension methods, DataFrame bridge

spark/src/test/scala/protocatalyst/spark/
├── ExprConverterSuite.scala
├── PlanConverterSuite.scala
├── QueryBinderSuite.scala
├── IntegrationSuite.scala     # Full E2E tests
└── PerformanceSuite.scala     # Benchmark vs native Spark
```

## Integration Strategy: Into Spark

ProtoCatalyst is designed to be **merged into Spark**, not used as an external library.

### Target Spark Module Structure

```
spark/
├── sql/
│   ├── catalyst/
│   │   ├── src/main/scala/org/apache/spark/sql/catalyst/
│   │   │   ├── encoders/
│   │   │   │   ├── ExpressionEncoder.scala     # Existing (Scala 2)
│   │   │   │   ├── ProtoBasedEncoder.scala     # NEW: Uses ProtoCatalyst
│   │   │   │   └── RowSerializer.scala         # NEW: From ProtoCatalyst
│   │   │   ├── expressions/
│   │   │   │   └── ... (existing Catalyst expressions)
│   │   │   └── proto/                          # NEW: ProtoCatalyst IR
│   │   │       ├── ProtoType.scala
│   │   │       ├── ProtoExpr.scala
│   │   │       ├── ProtoLogicalPlan.scala
│   │   │       └── ProtoEncoder.scala
│   │   └── ...
│   └── core/
│       └── src/main/scala/org/apache/spark/sql/
│           └── Encoders.scala                  # Update to use ProtoEncoder
```

### Migration Path

**Phase 1: Add ProtoCatalyst alongside existing encoders**
```scala
// Both APIs available during migration
object Encoders:
  // Old API (Scala 2, deprecated)
  @deprecated("Use derived instead", "4.0")
  def product[T: TypeTag]: Encoder[T] = ExpressionEncoder()

  // New API (Scala 3, uses ProtoCatalyst)
  inline def derived[T](using Mirror.ProductOf[T]): Encoder[T] =
    ProtoBasedEncoder.derived[T]
```

**Phase 2: Migrate internal usages**
- Update DataFrame operations to use new encoder
- Ensure backward compatibility for existing code

**Phase 3: Remove Scala 2 encoder infrastructure**
- Remove TypeTag dependencies
- Remove runtime reflection
- Spark is now Scala 3!

### Current ProtoCatalyst Development

While developing outside Spark, we structure for easy integration:

```
protocatalyst/                    → Eventually merged into spark/sql/catalyst/proto/
├── core/                         → ProtoType, ProtoExpr, ProtoLogicalPlan, ProtoSchema
├── encoder/                      → ProtoEncoder derivation, RowSerializer
├── query/                        → Query DSL (optional, for typed queries)
├── sql-parser/                   → SQL parsing (could complement SparkSqlParser)
└── mock-runtime/                 → Testing without full Spark
```

## Testing Strategy

### Unit Tests
- ExprConverter: Each expression type in isolation
- PlanConverter: Each plan node type
- TypeConverter: All type mappings

### Integration Tests
- Full query roundtrip: compile → bind → execute
- Schema validation scenarios
- Error handling paths

### Performance Tests
- Compare analyzer time: native vs pre-compiled
- Measure end-to-end latency
- Memory footprint analysis

## Estimated Effort

| Task | Days | Dependencies |
|------|------|--------------|
| ExprConverter (all expressions) | 8 | - |
| PlanConverter (all plan nodes) | 6 | ExprConverter |
| TypeConverter enhancements | 2 | - |
| QueryBinder | 4 | ExprConverter, PlanConverter |
| DataFrame bridge | 2 | QueryBinder |
| Unit tests | 5 | All converters |
| **Phase 1 Total** | **27 days** | |
| PreAnalyzedPlan | 3 | Phase 1 |
| ProtoCatalystExtension | 4 | PreAnalyzedPlan |
| Integration tests | 4 | Extension |
| **Phase 2 Total** | **11 days** | |
| UDF resolution | 4 | Phase 1 |
| Schema evolution | 3 | Phase 1 |
| Metrics | 2 | Phase 2 |
| **Phase 3 Total** | **9 days** | |

**Total: ~47 days** (with parallelization: ~4-6 weeks with 2 developers)

## Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| Spark 4.0 Scala 3 not available | Blocker | Use Spark 3.x with cross-compile or wait |
| Expression semantics mismatch | High | Extensive testing against Spark behavior |
| Analyzer bypass breaks optimizer | Medium | Thorough integration testing; fallback path |
| Schema drift in production | Medium | Strict validation + clear error messages |

## Next Steps

1. **Verify Spark 4.0 availability** - Check if Scala 3 artifacts are published
2. **Start with Phase 1.1** - ExprConverter is the foundation
3. **Parallel test development** - Write tests alongside implementation
4. **Document semantics** - Capture Spark-specific behaviors as we discover them
