# ProtoCatalyst Design Document

## Executive Summary

ProtoCatalyst is a Scala 3 query compiler that validates, optimizes, and serializes query plans at compile time. It provides:

1. **Encoder Derivation**: Compile-time derivation of Spark Encoders without runtime reflection
2. **Compiled Query Artifacts**: Pre-analyzed, optimized query plans serialized as protobuf
3. **Engine-Independent IR**: A rich intermediate representation (`ProtoLogicalPlan`, `ProtoExpr`, `ProtoType`) that can target multiple execution backends
4. **ML Compute Graphs**: Tensor operation IR with autograd and ONNX export

ProtoCatalyst is a query compiler, not a query engine. It sits above execution runtimes (Spark today, DataFusion/Velox in the future) the way LLVM sits above CPU architectures. See [ROADMAP.md](../../ROADMAP.md) for the full vision.

---

## 1. Shared Architecture

### 1.1 Design Principles

```
┌──────────────────────────────────────────────────────────────┐
│                    Compile Time (Scala 3)                     │
│                                                              │
│  ┌──────────┐  ┌───────────┐  ┌──────────────────────────┐  │
│  │ProtoType │  │ProtoSchema│  │ ProtoExpr /              │  │
│  │(26 types)│──│(fingerpr.)│──│ ProtoLogicalPlan (20)    │  │
│  └──────────┘  └───────────┘  └──────────────────────────┘  │
│       │              │               │                       │
│       │         ┌────┘               │                       │
│       ▼         ▼                    ▼                       │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  Optimizer (48 rules)                                │   │
│  │  Constant folding, predicate pushdown, cast simpl.   │   │
│  └──────────────────────┬───────────────────────────────┘   │
│                         ▼                                    │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  CompiledArtifact (protobuf / JSON)                  │   │
│  │  Plan + SchemaContracts + OutputSchema + SourceInfo   │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌──────────────────────────────────────────────────────┐   │
│  │  ComputeGraph / TensorExpr (ML)                      │   │
│  │  Autograd, shape inference, ONNX export              │   │
│  └──────────────────────────────────────────────────────┘   │
└──────────────────────────────┬───────────────────────────────┘
                               │ (artifact bytes)
                               ▼
┌──────────────────────────────────────────────────────────────┐
│                    Backend Lowerings                          │
│                                                              │
│  ┌──────────────┐  ┌───────────────┐  ┌──────────────────┐  │
│  │ Spark        │  │ DataFusion    │  │ Velox / other    │  │
│  │ (exists)     │  │ (future)      │  │ (future)         │  │
│  └──────────────┘  └───────────────┘  └──────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 1.2 Shared Type Model: ProtoType

```scala
package protocatalyst.types

/** Compile-time representation of Spark DataTypes.
  * 26 type variants covering all Spark DataTypes plus ADT support.
  * Nullability is tracked per-field on ProtoStructField, not on types.
  */
enum ProtoType extends Serializable:
  // Primitives
  case BooleanType, ByteType, ShortType, IntegerType, LongType
  case FloatType, DoubleType, StringType, BinaryType
  case DateType, TimestampType, TimestampNTZType

  // Intervals and temporal
  case DayTimeIntervalType          // java.time.Duration
  case YearMonthIntervalType        // java.time.Period
  case TimeType(precision: Int)     // java.time.LocalTime
  case CalendarIntervalType         // months + days + microseconds

  // String with length constraints
  case CharType(length: Int)        // Fixed-length
  case VarcharType(length: Int)     // Variable-length with max

  // Numeric with precision
  case DecimalType(precision: Int, scale: Int)

  // Complex types
  case ArrayType(elementType: ProtoType, containsNull: Boolean)
  case MapType(keyType: ProtoType, valueType: ProtoType, valueContainsNull: Boolean)
  case StructType(fields: Vector[ProtoStructField])

  // Special
  case VariantType                  // Semi-structured data (JSON-like)
  case NullType                     // Null-only columns (java.lang.Void)
  case UDTType(udtClassName: String, sqlType: ProtoType)
  case UnresolvedType(hint: String) // Can't resolve at compile time
  case SumType(discriminatorField: String, variants: Vector[SumVariant])  // Sealed traits/ADTs

case class ProtoStructField(
    name: String,
    dataType: ProtoType,
    nullable: Boolean,
    metadata: Map[String, String] = Map.empty
) extends Serializable

/** Variant descriptor for sum types (sealed traits/ADTs) */
case class SumVariant(
    name: String,
    ordinal: Int,
    dataType: Option[ProtoType]  // None for case objects (no data)
) extends Serializable

/** Type-safe literal values — eliminates Any from ProtoExpr.Literal */
enum LiteralValue extends Serializable:
  case BooleanValue(value: Boolean)
  case ByteValue(value: Byte)
  case ShortValue(value: Short)
  case IntValue(value: Int)
  case LongValue(value: Long)
  case FloatValue(value: Float)
  case DoubleValue(value: Double)
  case StringValue(value: String)
  case BinaryValue(value: immutable.ArraySeq[Byte])  // Immutable byte sequence
  case DecimalValue(value: BigDecimal)
  case DateValue(epochDays: Int)
  case TimestampValue(epochMicros: Long)
  case TimeValue(microsSinceMidnight: Long)
  case CalendarIntervalValue(months: Int, days: Int, microseconds: Long)
  case NullValue(dataType: ProtoType)

object LiteralValue:
  /** Infer ProtoType from the literal value.
    * DecimalValue infers precision/scale from the BigDecimal, matching Spark's behavior.
    */
  def typeOf(lit: LiteralValue): ProtoType = lit match
    case BooleanValue(_)  => ProtoType.BooleanType
    case IntValue(_)      => ProtoType.IntegerType
    case LongValue(_)     => ProtoType.LongType
    case DoubleValue(_)   => ProtoType.DoubleType
    case StringValue(_)   => ProtoType.StringType
    case DecimalValue(v)  =>
      val s = v.scale.max(0)
      val p = v.precision.max(s + 1)
      ProtoType.DecimalType(p, s)
    case NullValue(dt)    => dt
    // ... remaining cases map directly to their ProtoType
```

### 1.3 Shared Schema Model: ProtoSchema

```scala
package protocatalyst.schema

import protocatalyst.types._

/** Schema with a stable fingerprint for compatibility checking. */
case class ProtoSchema(
    fields: Vector[ProtoStructField],
    fingerprint: SchemaFingerprint
) extends Serializable:
  /** Cached name→field lookup — O(1) instead of O(n) */
  @transient lazy val byName: Map[String, ProtoStructField] = fields.map(f => f.name -> f).toMap
  @transient lazy val fieldNames: Set[String] = byName.keySet
  def apply(name: String): Option[ProtoStructField] = byName.get(name)

/** 64-bit fingerprint for schema compatibility.
  * Two schemas with the same fields/types/nullability/metadata produce the same fingerprint.
  * Fields are sorted by name before hashing so field order doesn't matter.
  */
opaque type SchemaFingerprint = Long

object SchemaFingerprint:
  def compute(fields: Vector[ProtoStructField]): SchemaFingerprint =
    val canonical = fields.sortBy(_.name).map(canonicalize)
    // Dual MurmurHash3 with different seeds → 64-bit fingerprint
    val hi = scala.util.hashing.MurmurHash3.orderedHash(canonical, 0x9e3779b1)
    val lo = scala.util.hashing.MurmurHash3.orderedHash(canonical, 0x517cc1b7)
    (hi.toLong << 32) | (lo.toLong & 0xffffffffL)

  /** Canonical encoding is explicit — exhaustive match over all 26 ProtoType variants,
    * no toString fallback. Metadata is included (sorted by key) because it may carry
    * behavioral semantics like encoding hints or provenance tags.
    */
  private def canonicalize(f: ProtoStructField): String =
    val meta =
      if f.metadata.isEmpty then ""
      else
        val sorted = f.metadata.toVector.sortBy(_._1).map((k, v) => s"$k=$v").mkString(",")
        s",metadata<$sorted>"
    s"${f.name}:${typeString(f.dataType)}:${f.nullable}$meta"

  private def typeString(t: ProtoType): String = t match
    case ProtoType.BooleanType      => "boolean"
    case ProtoType.IntegerType      => "integer"
    case ProtoType.StringType       => "string"
    case ProtoType.DecimalType(p,s) => s"decimal($p,$s)"
    case ProtoType.StructType(fs)   => s"struct<${fs.map(canonicalize).mkString(",")}>"
    case ProtoType.ArrayType(e, n)  => s"array<elem=${typeString(e)},containsNull=$n>"
    case ProtoType.SumType(d, vs)   => s"sum<discriminator=$d,...>"
    // ... exhaustive match for all 26 variants (no fallback)

  extension (fp: SchemaFingerprint)
    def toLong: Long = fp
    def toHex: String = f"$fp%016x"

/** Schema contract — what a compiled artifact expects from a relation.
  * Used for fast runtime validation. Name-based binding (no positional field).
  */
case class SchemaContract(
    relationName: String,
    requiredFields: Vector[FieldContract],
    fingerprint: SchemaFingerprint
) extends Serializable

case class FieldContract(
    name: String,
    expectedType: ProtoType,
    expectedNullable: Boolean
) extends Serializable
```

### 1.4 Shared Expression IR: ProtoExpr

93 expression variants organized into categories. All expressions are fully resolved at
construction time — column references carry their resolved type and nullability. There is
no separate "unresolved → resolved" analysis pass.

```scala
package protocatalyst.expr

/** Compile-time expression representation. 93 variants. */
enum ProtoExpr extends Serializable:
  // Leaf nodes
  case Literal(value: LiteralValue)                                 // Type-safe literals (no Any)
  case ColumnRef(name: String, qualifier: Option[String],           // Name-based identity
                 resolvedType: ProtoType, nullable: Boolean)        // (not ExprId-based)
  case BoundRef(index: Int, dataType: ProtoType, nullable: Boolean) // Positional (Spark interop)

  // Comparison (6): Eq, NotEq, Lt, LtEq, Gt, GtEq
  // Logical (3):    And(children: Vector), Or(children: Vector), Not
  // Null handling (4): IsNull, IsNotNull, Coalesce, NullIf
  // Arithmetic (4): Add, Subtract, Multiply, Divide

  // Math functions (13): Abs, Ceil, Floor, Round, Truncate, Sqrt, Cbrt,
  //                       Pow, Pmod, Sign, Log, Exp

  // String functions (11): Concat, Substring, Upper, Lower, Trim, Length,
  //                         Replace, StringLocate, Lpad, Rpad, StringSplit,
  //                         Reverse, StringRepeat

  // Aggregates (5): Count (with distinct flag), Sum, Avg, Min, Max

  // Control flow (3): CaseWhen, If, In
  // Pattern matching (1): Like (with optional escape)
  // Cast and alias (2): Cast, Alias

  // Subquery expressions (3): ScalarSubquery, Exists, InSubquery
  //   — each wraps a ProtoLogicalPlan

  // Window functions (9): RowNumber, Rank, DenseRank, Ntile, Lead, Lag,
  //                        FirstValue, LastValue, NthValue
  // Window specification (1): WindowExpr (wraps function + partition/order/frame)

  // Date/Time functions (15): CurrentDate, CurrentTimestamp, DateAdd, DateSub,
  //                            DateDiff, Extract, DateTrunc, ToDate, ToTimestamp,
  //                            Year, Month, DayOfMonth, Hour, Minute, Second

  // Grouping (1): Grouping (for GROUPING SETS / CUBE / ROLLUP)

  // Generator functions (4): Explode, PosExplode, Inline, Stack
  //   — used with Generate plan node / LATERAL VIEW

  // Opaque function call (1): OpaqueCall
  //   — UDFs and unknown functions, deferred to runtime
  case OpaqueCall(functionName: String, arguments: Vector[ProtoExpr],
                  returnType: Option[ProtoType], deterministic: Boolean)

// Supporting enums
enum FrameType extends Serializable: case Rows, Range
enum FrameBound extends Serializable:
  case UnboundedPreceding, UnboundedFollowing, CurrentRow
  case Preceding(n: Long)
  case Following(n: Long)
enum TrimType extends Serializable: case Both, Leading, Trailing
enum DateTimeField extends Serializable:
  case Year, Month, Day, Hour, Minute, Second, Quarter, Week
  case DayOfWeek, DayOfYear, Microsecond, Millisecond

object ProtoExpr:
  // Convenience constructors
  def lit(value: Boolean): ProtoExpr = Literal(LiteralValue.BooleanValue(value))
  def lit(value: Int): ProtoExpr    = Literal(LiteralValue.IntValue(value))
  def lit(value: Long): ProtoExpr   = Literal(LiteralValue.LongValue(value))
  def lit(value: Double): ProtoExpr = Literal(LiteralValue.DoubleValue(value))
  def lit(value: String): ProtoExpr = Literal(LiteralValue.StringValue(value))
  def litNull(dataType: ProtoType): ProtoExpr = Literal(LiteralValue.NullValue(dataType))
```

**Design decisions:**

- **`Literal(value: LiteralValue)` not `Literal(value: Any, dataType: ProtoType)`** — the `LiteralValue` enum is exhaustive and serializable. No `Any` means no runtime ClassCastException, no serialization surprises, and `typeOf` can be inferred from the value.
- **`And`/`Or` use `Vector[ProtoExpr]`** — flattened for canonicalization (optimizer can sort and deduplicate children).
- **No `ExprId`** — columns are identified by `(name, qualifier)`. See `ColumnRef` doc comment for when `ExprId` would become necessary (CSE, column pruning, subquery decorrelation).

### 1.5 Shared Relational IR: ProtoLogicalPlan

20 plan node types covering full SQL semantics including CTEs, pivots, generators, and hints.

```scala
package protocatalyst.plan

/** Logical plan nodes for compiled queries. */
enum ProtoLogicalPlan extends Serializable:
  // Leaf nodes
  case RelationRef(name: String, alias: Option[String], schemaContract: SchemaContract)
  case Values(rows: Vector[Vector[ProtoExpr]], schema: ProtoSchema)

  // Unary relational nodes
  case Project(projectList: Vector[ProtoExpr], child: ProtoLogicalPlan)
  case Filter(condition: ProtoExpr, child: ProtoLogicalPlan)
  case Aggregate(groupingExprs: Vector[ProtoExpr], aggregateExprs: Vector[ProtoExpr],
                 child: ProtoLogicalPlan)
  case Sort(order: Vector[SortOrder], child: ProtoLogicalPlan)
  case Limit(limit: Int, child: ProtoLogicalPlan)
  case Distinct(child: ProtoLogicalPlan)
  case SubqueryAlias(alias: String, child: ProtoLogicalPlan)

  // Binary / multi-child nodes
  case Join(left: ProtoLogicalPlan, right: ProtoLogicalPlan,
            joinType: JoinType, condition: Option[ProtoExpr])
  case Union(children: Vector[ProtoLogicalPlan], byName: Boolean, allowMissingColumns: Boolean)
  case Intersect(left: ProtoLogicalPlan, right: ProtoLogicalPlan, isAll: Boolean)
  case Except(left: ProtoLogicalPlan, right: ProtoLogicalPlan, isAll: Boolean)

  // Window
  case Window(windowExprs: Vector[ProtoExpr], partitionSpec: Vector[ProtoExpr],
              orderSpec: Vector[SortOrder], child: ProtoLogicalPlan)

  // CTEs
  case With(cteRelations: Vector[(String, ProtoLogicalPlan)],
            recursive: Boolean, child: ProtoLogicalPlan)

  // Pivot / Unpivot
  case Pivot(groupingExprs: Vector[ProtoExpr], pivotColumn: ProtoExpr,
             pivotValues: Vector[ProtoExpr], aggregates: Vector[ProtoExpr],
             child: ProtoLogicalPlan)
  case Unpivot(valueColumnName: String, variableColumnName: String,
               columns: Vector[(ProtoExpr, Option[String])],
               includeNulls: Boolean, child: ProtoLogicalPlan)

  // Correlated subquery join
  case LateralJoin(left: ProtoLogicalPlan, lateral: ProtoLogicalPlan,
                   condition: Option[ProtoExpr])

  // Generator (LATERAL VIEW)
  case Generate(generator: ProtoExpr, generatorOutput: Vector[String],
                outer: Boolean, child: ProtoLogicalPlan)

  // Optimizer hints
  case ResolvedHint(hints: Vector[PlanHint], child: ProtoLogicalPlan)

// Supporting types
enum JoinType extends Serializable:
  case Inner, LeftOuter, RightOuter, FullOuter, LeftSemi, LeftAnti, Cross

case class SortOrder(child: ProtoExpr, direction: SortDirection,
                     nullOrdering: NullOrdering) extends Serializable
enum SortDirection extends Serializable: case Ascending, Descending
enum NullOrdering extends Serializable: case NullsFirst, NullsLast

/** Opaque optimizer hint — core carries name + params without interpreting them.
  * Runtime backends map names to engine-specific directives.
  */
case class PlanHint(name: String, params: Vector[HintParam]) extends Serializable
enum HintParam extends Serializable:
  case StringVal(value: String)
  case IntVal(value: Int)
```

These 20 plan nodes have no Substrait equivalent for 7 of them: `Pivot`, `Unpivot`, `Generate`, `LateralJoin`, `With`, `ResolvedHint`, `Distinct`. This is a key reason for the [independent IR decision](../decisions/ADR-002-independent-ir.md).

### 1.6 Artifact Format and Versioning

```scala
package protocatalyst.artifact

/** The compiled artifact — the unit of serialization between compile time and runtime. */
case class CompiledArtifact(
    formatVersion: ArtifactVersion,
    protocatalystVersion: String,
    compiledAt: Long,                       // epoch millis
    contentHash: Long,                      // for deduplication/caching
    schemaContracts: Vector[SchemaContract], // expected schemas for relations
    plan: ProtoLogicalPlan,                 // the compiled plan
    outputSchema: ProtoSchema,              // what the plan produces
    sourceInfo: Option[SourceInfo]           // debugging: source file, line, original SQL
)

case class ArtifactVersion(major: Int, minor: Int, patch: Int):
  def isCompatibleWith(other: ArtifactVersion): Boolean =
    this.major == other.major && this.minor >= other.minor
```

#### PCAT Binary Container

The serialization format is a self-describing binary container:

```
┌──────────┬──────────────┬─────────────────────┐
│ "PCAT"   │ Format byte  │ Payload             │
│ (4 bytes)│ (1 byte)     │ (variable)          │
└──────────┴──────────────┴─────────────────────┘
             0x01 = JSON     JSON-encoded artifact
             0x02 = Protobuf Protobuf-encoded artifact
```

```scala
package protocatalyst.codec

/** Pluggable codec trait — swap JSON/protobuf transparently. */
trait ArtifactCodec:
  def format: String
  def serialize(artifact: CompiledArtifact): Array[Byte]
  def deserialize(bytes: Array[Byte]): Either[String, CompiledArtifact]

object ArtifactCodec:
  private val MagicPrefix: Array[Byte] = "PCAT".getBytes("UTF-8")
  private val FormatJson: Byte = 0x01
  private val FormatProtobuf: Byte = 0x02

  /** Serialize with header — auto-detects codec format */
  def serializeWithHeader(artifact: CompiledArtifact,
                          codec: ArtifactCodec = JsonArtifactCodec): Array[Byte] =
    val formatByte = codec.format match
      case "json"     => FormatJson
      case "protobuf" => FormatProtobuf
    MagicPrefix ++ Array(formatByte) ++ codec.serialize(artifact)

  /** Deserialize — dispatches JSON vs Protobuf based on format byte */
  def deserializeWithHeader(bytes: Array[Byte]): Either[String, CompiledArtifact] =
    val payload = bytes.drop(MagicPrefix.length + 1)
    bytes(MagicPrefix.length) match
      case FormatJson     => JsonArtifactCodec.deserialize(payload)
      case FormatProtobuf => ProtobufArtifactCodec.deserialize(payload)
```

Two codec implementations:

- **JsonArtifactCodec** — human-readable, uses uPickle, good for debugging
- **ProtobufArtifactCodec** — 3–10x smaller, uses the `proto` module's 176 generated Java classes, schema-evolved via `.proto` definitions

---

## 2. Compile-Time Pipeline

### 2.1 Query Entry Points

Two ways to produce compiled artifacts at compile time:

```scala
// 1. SQL string — parsed, resolved, optimized at compile time
val artifact = CompiledQuery.sqlOptimized("""
  SELECT u.name, COUNT(o.id) as order_count
  FROM users u JOIN orders o ON u.id = o.user_id
  WHERE o.status = 'completed'
  GROUP BY u.name
""")

// 2. Typed DSL — Scala expressions compiled to ProtoLogicalPlan via macros
val artifact = quote {
  users
    .join(orders)(_.id === _.userId)
    .filter(_.status === "completed")
    .groupBy(_.name)
    .agg(count(_.orderId).as("order_count"))
}
```

Both produce a `CompiledArtifact` containing the optimized `ProtoLogicalPlan`, `SchemaContract`s, and output `ProtoSchema`.

### 2.2 SQL Parser

Full SQL lexer and recursive-descent parser running at compile time:

- **Lexer**: tokenizes SQL keywords, identifiers, literals, operators
- **Parser**: recursive descent producing an AST
- **`AstToProtoTransform`**: AST → `ProtoLogicalPlan` with column resolution against schemas

Coverage: `SELECT`, `WHERE`, `JOIN` (all types), `GROUP BY`, `ORDER BY`, `LIMIT`, `DISTINCT`, `UNION`/`INTERSECT`/`EXCEPT`, window functions, CTEs (`WITH`), `PIVOT`/`UNPIVOT`, `LATERAL VIEW`, subqueries (scalar, `EXISTS`, `IN`), hints.

344 parser tests.

### 2.3 Typed DSL (`quote { }` Macro)

The `quote { }` macro compiles Scala expressions to `ProtoLogicalPlan`:

- **`FieldSelector`** via `transparent inline` macro — `_.name` returns `Column[User, String]`
- **Multi-column lambda select**: `.select(u => (u.name, u.age))`
- Full operation support: filter, select, orderBy, limit, distinct, join, groupBy + aggregates, union/intersect/except, subqueries (IN, EXISTS, scalar), window functions, hints

67 macro tests.

### 2.4 Compile-Time Optimizer

48 optimizer rules execute during compilation. Categories:

| Category | Rules | Examples |
|----------|------:|---------|
| Constant folding | 6 | `1 + 2` → `3`, `true AND x` → `x` |
| Boolean simplification | 5 | `NOT NOT x` → `x`, `x AND false` → `false` |
| Predicate canonicalization | 4 | `5 > x` → `x < 5`, sort AND/OR children |
| Filter combining | 3 | Merge adjacent filters, deduplicate predicates |
| Predicate pushdown | 4 | Push filter below join, project, aggregate |
| Column pruning | 3 | Remove unused columns from projections |
| Limit pushdown | 3 | Push LIMIT below sort, union, project |
| Sort elimination | 3 | Remove redundant sorts |
| Distinct elimination | 2 | Remove DISTINCT when output is already unique |
| CTE inlining | 2 | Inline single-use CTEs |
| Correlated subquery rewriting | 3 | Decorrelate where possible |
| Join optimization | 4 | Reorder joins, eliminate cross joins |
| Set operation rewriting | 3 | Simplify union/intersect/except |
| Cast simplification | 3 | Remove redundant casts |

### 2.5 ProtoLiftables

All IR types are liftable as compile-time `Expr` constants via `ProtoLiftables`. This allows the optimizer output (a runtime `ProtoLogicalPlan` value constructed during macro expansion) to be converted back into `Expr[ProtoLogicalPlan]` for splicing into generated code.

### 2.6 Serialization

Two serialization paths:

- **JSON** (uPickle) — full roundtrip support, human-readable, used for debugging
- **Protobuf** — 4 `.proto` files, 176 generated Java classes, 3–10x smaller

`ProtoConverter` (~1,400 lines) handles bidirectional Scala IR ↔ Java protobuf conversion.

---

## 3. Spark Execution Bridge

### 3.1 SparkQueryRunner

```scala
SparkQueryRunner.execute(artifactBytes: Array[Byte], spark: SparkSession): DataFrame
```

Pipeline:
1. `ArtifactParser` dispatches JSON (0x01) or Protobuf (0x02) from PCAT header
2. Decode to `CompiledArtifact` (type/expression/plan decoders for all 93 expr + 20 plan types)
3. `SchemaValidator` validates compile-time schema contracts against Spark catalog
4. Convert `ProtoLogicalPlan` → Spark `LogicalPlan` with full support for Pivot, Unpivot, Generate
5. Pass hint through (BROADCAST, SHUFFLE_MERGE, COALESCE, REPARTITION, etc.)
6. Execute via Spark

### 3.2 Decoders

Full coverage decoders exist for both formats:

- **Protobuf**: `ProtobufTypeDecoder`, `ProtobufExpressionDecoder`, `ProtobufPlanDecoder`
- **JSON**: `TypeDecoder`, `ExpressionDecoder`, `PlanDecoder`

All 93 expression types and 20 plan node types are handled.

### 3.3 SparkPlanEncoder

Bidirectional: can also encode Spark `LogicalPlan` → ProtoCatalyst JSON. Used for testing and debugging.

### 3.4 Testing

136 parity tests guarantee Spark-compatible plan structure. 289 total spark-catalyst tests.

---

## 4. Encoder System

### 4.1 ProtoEncoder

Compile-time derivation of Spark Encoders using Scala 3 `Mirror`:

```scala
inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] = ${ deriveImpl[T]('m) }
```

### 4.2 Type Coverage

| Scala Type | Catalyst Type |
|------------|---------------|
| Boolean, Byte, Short, Int, Long, Float, Double | Direct primitive mapping |
| String | StringType |
| Array[Byte] | BinaryType |
| BigDecimal | DecimalType (precision/scale inferred from value) |
| BigInt | DecimalType(38, 0) |
| java.time.LocalDate | DateType |
| java.time.Instant | TimestampType |
| java.time.LocalDateTime | TimestampNTZType |
| java.time.Duration | DayTimeIntervalType |
| java.time.Period | YearMonthIntervalType |
| Option[T] | T (nullable=true) |
| case class | StructType (recursive derivation) |
| (T1, ..., T22) | StructType (tuples 2–22) |
| Seq[T], List[T], Vector[T] | ArrayType |
| Set[T] | ArrayType |
| Map[K, V] | MapType |
| sealed trait / enum | SumType (with discriminator) |

### 4.3 Performance

`InlineRowSerializer` with compile-time specialization: 3–27x faster than Spark's `ExpressionEncoder`.

268 unit tests, 26 Spark parity tests.

---

## 5. Appendix

### A. Architecture Decisions

| Decision | Chosen Approach | Alternative | Rationale |
|----------|-----------------|-------------|-----------|
| Type representation | Custom `ProtoType` enum (26 variants) | Reuse Spark's `DataType` | Engine-independent, no Spark compile-time dependency |
| Literal representation | `LiteralValue` enum (15 variants) | `Any` | Type-safe, serializable, no ClassCastException |
| Serialization | Dual JSON + Protobuf with PCAT container | Single format | JSON for debugging, Protobuf for production (3–10x smaller) |
| Column identity | `(name, qualifier)` | ExprId | Sufficient for one-shot resolution; simpler (see [ColumnRef docs](../../core/src/main/scala/protocatalyst/expr/ProtoExpr.scala)) |
| Schema fingerprint | 64-bit dual MurmurHash3 | Single 32-bit hash | Eliminates collision risk at scale |
| Schema binding | Name-based (no `position` field) | Position-based | Robust to column reorder, simpler FieldContract |
| Interchange format | Independent IR | Substrait export | Lossy export, fundamental model clash (see [ADR-002](../decisions/ADR-002-independent-ir.md)) |
| Function handling | `OpaqueCall` fallback | Exhaustive catalog | Pragmatic: known functions get compile-time types, unknown deferred to runtime |
| `proto` module | Pure Java | Scala 3 | Consumable by both Scala 3 and 2.13 (Spark compatibility) |

### B. Open Questions

1. **Physical plan layer**: What cost model for `ProtoPhysicalPlan`? See [ROADMAP Phase 9](../../ROADMAP.md).
2. **Backend lowerings**: Direct lowering vs intermediate physical plan? See [ROADMAP Phase 10](../../ROADMAP.md).
3. **ML as plan operator**: How to vectorize `ComputeGraph` evaluation over Arrow `RecordBatch`? See [ROADMAP Phase 11](../../ROADMAP.md).
4. **Spark 4.0 Scala 3**: When upstream adds Scala 3 support, bridge `ProtoEncoder` → `AgnosticEncoder`.

### C. Related Work

- **Substrait**: Cross-engine query plan interchange format. We chose to stay independent — see [ADR-002](../decisions/ADR-002-independent-ir.md).
- **Spark Catalyst**: Runtime query optimizer. ProtoCatalyst moves 48 rules to compile time; Spark handles CBO and physical planning at runtime.
- **Spark Connect**: Wire protocol for Spark client-server. Complementary — ProtoCatalyst could serialize to Spark Connect's protobuf format as a backend lowering.
- **DataFusion**: Arrow-native query engine in Rust. Future backend target.
- **Velox**: C++ vectorized execution library. Future backend target (physical plan level).
- **Slick/Quill**: Scala compile-time query DSLs. Inspiration for `quote { }` macro; neither targets multiple backends.
- **ONNX**: ML model interchange format. `ComputeGraph` already exports to ONNX.

### D. Glossary

| Term | Definition |
|------|------------|
| ProtoType | Compile-time type representation (26 variants) |
| LiteralValue | Type-safe literal enum (15 variants) |
| ProtoExpr | Compile-time expression tree (93 variants) |
| ProtoLogicalPlan | Compile-time logical plan (20 node types) |
| SchemaContract | Expected schema for a relation, validated at runtime |
| SchemaFingerprint | 64-bit structural hash of schema fields |
| CompiledArtifact | Serialized compiled query (plan + contracts + schema) |
| PCAT | Binary container format: `"PCAT" + format byte + payload` |
| Backend lowering | Translation from ProtoLogicalPlan to engine-native plan |
| ComputeGraph | Tensor operation IR for ML workloads |
| OpaqueCall | Unresolved function call deferred to runtime |
