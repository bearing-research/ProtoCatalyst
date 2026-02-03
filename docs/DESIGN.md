# ProtoCatalyst Design Document

## Executive Summary

ProtoCatalyst is a Scala 3 library that moves safe, deterministic parts of Spark SQL/Catalyst work from runtime to compile time. It provides two deliverables:

1. **Encoder Derivation**: Compile-time derivation of Spark Encoders without runtime reflection
2. **Compiled Query Artifacts**: Pre-analyzed query plans that skip analyzer work at runtime

**Key constraint**: ProtoCatalyst does NOT replace cost-based optimization, physical planning, or AQE. It only performs always-safe, deterministic transformations.

---

## 1. Shared Architecture

### 1.1 Design Principles

```
┌─────────────────────────────────────────────────────────────┐
│                    Compile Time (Scala 3)                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ ProtoType   │  │ ProtoSchema │  │ ProtoExpr /         │  │
│  │ (types)     │──│ (schemas)   │──│ ProtoLogicalPlan    │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
│         │                │                    │              │
│         ▼                ▼                    ▼              │
│  ┌─────────────────────────────────────────────────────────┐│
│  │              CompiledArtifact (serializable)            ││
│  └─────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ (artifact bytes / class)
┌─────────────────────────────────────────────────────────────┐
│                    Runtime (Spark)                          │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────────────┐  │
│  │ Schema      │  │ Binder      │  │ Catalyst            │  │
│  │ Validation  │─▶│ (AttrRefs)  │─▶│ Optimizer + AQE     │  │
│  └─────────────┘  └─────────────┘  └─────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 Shared Type Model: ProtoType

```scala
package protocatalyst.types

import scala.quoted.*

/**
 * Compile-time representation of Spark DataTypes.
 * Deliberately minimal - only types we can fully reason about at compile time.
 */
enum ProtoType:
  // Primitives (nullable flag separate)
  case BooleanType
  case ByteType
  case ShortType
  case IntegerType
  case LongType
  case FloatType
  case DoubleType
  case StringType
  case BinaryType
  case DateType
  case TimestampType
  case TimestampNTZType

  // Decimal with precision/scale
  case DecimalType(precision: Int, scale: Int)

  // Complex types
  case ArrayType(elementType: ProtoType, containsNull: Boolean)
  case MapType(keyType: ProtoType, valueType: ProtoType, valueContainsNull: Boolean)
  case StructType(fields: Vector[ProtoStructField])

  // Placeholder for types we can't fully resolve at compile time
  case UnresolvedType(hint: String)

case class ProtoStructField(
  name: String,
  dataType: ProtoType,
  nullable: Boolean,
  metadata: Map[String, String] = Map.empty
)

/** Nullability tracking - critical for correct encoder generation */
enum Nullability:
  case NonNull      // Scala non-Option type, field marked NOT NULL
  case Nullable     // Option[T] or nullable field
  case Unknown      // Can't determine at compile time (deferred to runtime check)
```

### 1.3 Shared Schema Model: ProtoSchema

```scala
package protocatalyst.schema

import protocatalyst.types.*

/**
 * A schema with a stable fingerprint for compatibility checking.
 * The fingerprint is computed from field names, types, and nullability.
 */
case class ProtoSchema(
  fields: Vector[ProtoStructField],
  fingerprint: SchemaFingerprint
):
  def fieldNames: Set[String] = fields.map(_.name).toSet
  def apply(name: String): Option[ProtoStructField] = fields.find(_.name == name)

/**
 * Stable fingerprint for schema compatibility.
 * Uses structural hashing - two schemas with same fields/types/nullability = same fingerprint.
 */
opaque type SchemaFingerprint = Long

object SchemaFingerprint:
  def compute(fields: Vector[ProtoStructField]): SchemaFingerprint =
    // MurmurHash3 over canonical field representation
    // Canonical = sorted by name, normalized type names
    val canonical = fields.sortBy(_.name).map(canonicalize)
    scala.util.hashing.MurmurHash3.orderedHash(canonical)

  private def canonicalize(f: ProtoStructField): String =
    s"${f.name}:${typeString(f.dataType)}:${f.nullable}"

  private def typeString(t: ProtoType): String = t match
    case ProtoType.StructType(fs) => s"struct<${fs.map(canonicalize).mkString(",")}>"
    case ProtoType.ArrayType(e, n) => s"array<${typeString(e)},$n>"
    case ProtoType.MapType(k, v, n) => s"map<${typeString(k)},${typeString(v)},$n>"
    case ProtoType.DecimalType(p, s) => s"decimal($p,$s)"
    case other => other.toString.toLowerCase

  extension (fp: SchemaFingerprint)
    def toLong: Long = fp
    def toHex: String = f"$fp%016x"

/**
 * Schema contract - what a compiled artifact expects from a relation.
 * Used for fast runtime validation.
 */
case class SchemaContract(
  relationName: String,
  requiredFields: Vector[FieldContract],
  fingerprint: SchemaFingerprint
)

case class FieldContract(
  name: String,
  expectedType: ProtoType,
  expectedNullable: Boolean,
  position: Int  // For positional binding optimization
)
```

### 1.4 Shared Expression IR: ProtoExpr

```scala
package protocatalyst.expr

import protocatalyst.types.*

/**
 * Compile-time expression representation.
 * Intentionally minimal - covers common SQL expressions.
 * Each node carries its resolved type for downstream use.
 */
enum ProtoExpr:
  // Leaf nodes
  case Literal(value: Any, dataType: ProtoType)
  case ColumnRef(name: String, qualifier: Option[String], resolvedType: ProtoType, nullable: Boolean)
  case BoundRef(ordinal: Int, dataType: ProtoType, nullable: Boolean)  // Post-binding

  // Comparison
  case Eq(left: ProtoExpr, right: ProtoExpr)
  case NotEq(left: ProtoExpr, right: ProtoExpr)
  case Lt(left: ProtoExpr, right: ProtoExpr)
  case LtEq(left: ProtoExpr, right: ProtoExpr)
  case Gt(left: ProtoExpr, right: ProtoExpr)
  case GtEq(left: ProtoExpr, right: ProtoExpr)

  // Logical
  case And(children: Vector[ProtoExpr])  // Flattened for canonicalization
  case Or(children: Vector[ProtoExpr])
  case Not(child: ProtoExpr)

  // Null handling
  case IsNull(child: ProtoExpr)
  case IsNotNull(child: ProtoExpr)
  case Coalesce(children: Vector[ProtoExpr])

  // Arithmetic
  case Add(left: ProtoExpr, right: ProtoExpr)
  case Subtract(left: ProtoExpr, right: ProtoExpr)
  case Multiply(left: ProtoExpr, right: ProtoExpr)
  case Divide(left: ProtoExpr, right: ProtoExpr)

  // String
  case Concat(children: Vector[ProtoExpr])
  case Substring(str: ProtoExpr, pos: ProtoExpr, len: ProtoExpr)
  case Upper(child: ProtoExpr)
  case Lower(child: ProtoExpr)

  // Aggregates
  case Count(child: ProtoExpr, distinct: Boolean)
  case Sum(child: ProtoExpr)
  case Avg(child: ProtoExpr)
  case Min(child: ProtoExpr)
  case Max(child: ProtoExpr)

  // Control flow
  case CaseWhen(branches: Vector[(ProtoExpr, ProtoExpr)], elseValue: Option[ProtoExpr])
  case If(predicate: ProtoExpr, trueValue: ProtoExpr, falseValue: ProtoExpr)
  case In(value: ProtoExpr, list: Vector[ProtoExpr])

  // Cast
  case Cast(child: ProtoExpr, targetType: ProtoType)

  // Alias
  case Alias(child: ProtoExpr, name: String)

  // Opaque function call - for UDFs and unknown functions
  // NOT type-checked at compile time, deferred to runtime
  case OpaqueCall(
    functionName: String,
    arguments: Vector[ProtoExpr],
    returnType: Option[ProtoType],  // None = runtime resolution
    deterministic: Boolean
  )

object ProtoExpr:
  /** Get the resolved type of an expression */
  def typeOf(expr: ProtoExpr): ProtoType = expr match
    case Literal(_, dt) => dt
    case ColumnRef(_, _, dt, _) => dt
    case BoundRef(_, dt, _) => dt
    case Eq(_, _) | NotEq(_, _) | Lt(_, _) | LtEq(_, _) | Gt(_, _) | GtEq(_, _) => ProtoType.BooleanType
    case And(_) | Or(_) | Not(_) | IsNull(_) | IsNotNull(_) => ProtoType.BooleanType
    case Add(l, _) => typeOf(l)  // Simplified - real impl needs promotion rules
    case Subtract(l, _) => typeOf(l)
    case Multiply(l, _) => typeOf(l)
    case Divide(_, _) => ProtoType.DoubleType
    case Count(_, _) => ProtoType.LongType
    case Sum(c) => typeOf(c)
    case Avg(_) => ProtoType.DoubleType
    case Min(c) => typeOf(c)
    case Max(c) => typeOf(c)
    case Cast(_, target) => target
    case Alias(c, _) => typeOf(c)
    case Concat(_) => ProtoType.StringType
    case Upper(_) | Lower(_) => ProtoType.StringType
    case Substring(_, _, _) => ProtoType.StringType
    case Coalesce(cs) => typeOf(cs.head)
    case CaseWhen(branches, _) => typeOf(branches.head._2)
    case If(_, t, _) => typeOf(t)
    case In(_, _) => ProtoType.BooleanType
    case OpaqueCall(_, _, Some(rt), _) => rt
    case OpaqueCall(_, _, None, _) => ProtoType.UnresolvedType("opaque return")
```

### 1.5 Shared Relational IR: ProtoLogicalPlan

```scala
package protocatalyst.plan

import protocatalyst.expr.*
import protocatalyst.schema.*
import protocatalyst.types.*

/**
 * Minimal logical plan nodes for compiled queries.
 * ~15 nodes covering common SQL patterns.
 */
enum ProtoLogicalPlan:
  // Leaf nodes
  case RelationRef(
    name: String,
    alias: Option[String],
    schemaContract: SchemaContract  // Expected schema
  )

  case Values(
    rows: Vector[Vector[ProtoExpr]],
    schema: ProtoSchema
  )

  // Unary nodes
  case Project(
    projectList: Vector[ProtoExpr],  // All should be Alias or resolve to named
    child: ProtoLogicalPlan
  )

  case Filter(
    condition: ProtoExpr,
    child: ProtoLogicalPlan
  )

  case Aggregate(
    groupingExprs: Vector[ProtoExpr],
    aggregateExprs: Vector[ProtoExpr],
    child: ProtoLogicalPlan
  )

  case Sort(
    order: Vector[SortOrder],
    global: Boolean,  // true = final sort, false = per-partition
    child: ProtoLogicalPlan
  )

  case Limit(
    limit: Int,
    child: ProtoLogicalPlan
  )

  case Distinct(
    child: ProtoLogicalPlan
  )

  case SubqueryAlias(
    alias: String,
    child: ProtoLogicalPlan
  )

  // Binary nodes
  case Join(
    left: ProtoLogicalPlan,
    right: ProtoLogicalPlan,
    joinType: JoinType,
    condition: Option[ProtoExpr]
  )

  case Union(
    children: Vector[ProtoLogicalPlan],
    byName: Boolean,
    allowMissingColumns: Boolean
  )

  // Set operations
  case Intersect(
    left: ProtoLogicalPlan,
    right: ProtoLogicalPlan,
    isAll: Boolean
  )

  case Except(
    left: ProtoLogicalPlan,
    right: ProtoLogicalPlan,
    isAll: Boolean
  )

  // Window
  case Window(
    windowExprs: Vector[ProtoExpr],
    partitionSpec: Vector[ProtoExpr],
    orderSpec: Vector[SortOrder],
    child: ProtoLogicalPlan
  )

enum JoinType:
  case Inner, LeftOuter, RightOuter, FullOuter, LeftSemi, LeftAnti, Cross

case class SortOrder(
  child: ProtoExpr,
  direction: SortDirection,
  nullOrdering: NullOrdering
)

enum SortDirection:
  case Ascending, Descending

enum NullOrdering:
  case NullsFirst, NullsLast
```

### 1.6 Artifact Format and Versioning

```scala
package protocatalyst.artifact

import protocatalyst.plan.*
import protocatalyst.schema.*

/**
 * The compiled artifact - what gets serialized and consumed by Spark.
 */
case class CompiledArtifact(
  // Versioning
  formatVersion: ArtifactVersion,
  protocatalystVersion: String,
  compiledAt: Long,  // epoch millis

  // Content hash for deduplication/caching
  contentHash: Long,

  // Schema contracts this artifact depends on
  schemaContracts: Vector[SchemaContract],

  // The compiled plan
  plan: ProtoLogicalPlan,

  // Output schema
  outputSchema: ProtoSchema,

  // Metadata
  sourceInfo: Option[SourceInfo]  // For debugging
)

/**
 * Semantic versioning for artifact format.
 * Major bump = breaking change in serialization
 * Minor bump = new node types (backwards compatible for old consumers)
 * Patch bump = bug fixes
 */
case class ArtifactVersion(major: Int, minor: Int, patch: Int):
  def isCompatibleWith(other: ArtifactVersion): Boolean =
    this.major == other.major && this.minor >= other.minor

object ArtifactVersion:
  val current = ArtifactVersion(1, 0, 0)

case class SourceInfo(
  sourceFile: String,
  lineNumber: Int,
  originalSql: Option[String]
)

/**
 * Serialization format - simple binary with magic header.
 * Future: consider using protobuf/flatbuffers for cross-language support.
 */
object ArtifactCodec:
  val Magic: Array[Byte] = "PCAT".getBytes

  def serialize(artifact: CompiledArtifact): Array[Byte] =
    // For MVP: Java serialization
    // Production: custom binary format or protobuf
    import java.io.*
    val baos = new ByteArrayOutputStream()
    baos.write(Magic)
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(artifact)
    oos.close()
    baos.toByteArray

  def deserialize(bytes: Array[Byte]): Either[String, CompiledArtifact] =
    if !bytes.startsWith(Magic) then
      Left("Invalid magic header")
    else
      try
        import java.io.*
        val bais = new ByteArrayInputStream(bytes.drop(Magic.length))
        val ois = new ObjectInputStream(bais)
        Right(ois.readObject().asInstanceOf[CompiledArtifact])
      catch
        case e: Exception => Left(s"Deserialization failed: ${e.getMessage}")

  extension (arr: Array[Byte])
    private def startsWith(prefix: Array[Byte]): Boolean =
      arr.length >= prefix.length && arr.take(prefix.length).sameElements(prefix)
```

---

## 2. Deliverable 1: Encoder Derivation

### 2.1 Overview

Generate Spark `Encoder[T]` instances at compile time using Scala 3 mirrors, producing serialization/deserialization code equivalent to what `ExpressionEncoder` does at runtime.

### 2.2 Derivation Strategy

```scala
package protocatalyst.encoder

import scala.quoted.*
import scala.deriving.Mirror
import protocatalyst.types.*

/**
 * The derived encoder output - contains everything Spark needs.
 */
trait ProtoEncoder[T]:
  /** Catalyst schema for this type */
  def schema: ProtoSchema

  /** Spark DataType (computed from schema) */
  def catalystType: ProtoType

  /** Serialize T to Catalyst InternalRow representation */
  def serialize: T => Any  // Any = InternalRow for structs, primitive for leaves

  /** Deserialize from Catalyst InternalRow */
  def deserialize: Any => T

  /** Is the root type nullable? */
  def nullable: Boolean

object ProtoEncoder:
  /** Main derivation entry point */
  inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
    ${ deriveImpl[T]('m) }

  /** Macro implementation */
  private def deriveImpl[T: Type](m: Expr[Mirror.Of[T]])(using Quotes): Expr[ProtoEncoder[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val typeSymbol = tpe.typeSymbol

    // Determine derivation path
    if typeSymbol.flags.is(Flags.Case) then
      deriveCaseClass[T](m)
    else if tpe <:< TypeRepr.of[Product] then
      deriveProduct[T](m)
    else if tpe <:< TypeRepr.of[Option[?]] then
      deriveOption[T]
    else if tpe <:< TypeRepr.of[Seq[?]] then
      deriveSeq[T]
    else if tpe <:< TypeRepr.of[Map[?, ?]] then
      deriveMap[T]
    else
      derivePrimitive[T]

  private def deriveCaseClass[T: Type](m: Expr[Mirror.Of[T]])(using Quotes): Expr[ProtoEncoder[T]] =
    import quotes.reflect.*

    val tpe = TypeRepr.of[T]
    val sym = tpe.typeSymbol
    val fields = sym.caseFields

    // Build field encoders at compile time
    val fieldEncoders: List[(String, Expr[ProtoEncoder[?]], TypeRepr, Boolean)] =
      fields.map { field =>
        val fieldName = field.name
        val fieldType = tpe.memberType(field)
        val isOption = fieldType <:< TypeRepr.of[Option[?]]
        val innerType = if isOption then fieldType.typeArgs.head else fieldType

        // Recursively derive encoder for field type
        val encoderExpr = innerType.asType match
          case '[t] => '{ ProtoEncoder.derived[t] }

        (fieldName, encoderExpr, fieldType, isOption)
      }

    // Generate schema
    val schemaExpr: Expr[ProtoSchema] = '{
      val fields = Vector(${
        Expr.ofList(fieldEncoders.map { case (name, enc, _, isOpt) =>
          '{ ProtoStructField(${ Expr(name) }, $enc.catalystType, ${ Expr(isOpt) } || $enc.nullable) }
        })
      }*)
      ProtoSchema(fields, SchemaFingerprint.compute(fields))
    }

    // Generate serializer
    val serializerExpr: Expr[T => Any] = '{
      (value: T) =>
        // Create InternalRow with serialized fields
        val values = new Array[Any](${ Expr(fields.length) })
        ${
          Expr.block(
            fieldEncoders.zipWithIndex.map { case ((name, enc, fieldType, isOpt), idx) =>
              fieldType.asType match
                case '[Option[inner]] =>
                  '{
                    val fieldValue = ${ Select.unique('value.asTerm, name).asExprOf[Option[inner]] }
                    values(${ Expr(idx) }) = fieldValue.map(v => $enc.asInstanceOf[ProtoEncoder[inner]].serialize(v)).orNull
                  }
                case '[t] =>
                  '{
                    val fieldValue = ${ Select.unique('value.asTerm, name).asExprOf[t] }
                    values(${ Expr(idx) }) = $enc.asInstanceOf[ProtoEncoder[t]].serialize(fieldValue)
                  }
            },
            '{ org.apache.spark.sql.catalyst.InternalRow.fromSeq(values.toSeq) }
          )
        }
    }

    // Generate deserializer
    val deserializerExpr: Expr[Any => T] = '{
      (row: Any) =>
        val r = row.asInstanceOf[org.apache.spark.sql.catalyst.InternalRow]
        ${
          val args = fieldEncoders.zipWithIndex.map { case ((name, enc, fieldType, isOpt), idx) =>
            fieldType.asType match
              case '[Option[inner]] =>
                '{
                  if r.isNullAt(${ Expr(idx) }) then None
                  else Some($enc.asInstanceOf[ProtoEncoder[inner]].deserialize(r.get(${ Expr(idx) }, null)))
                }
              case '[t] =>
                '{ $enc.asInstanceOf[ProtoEncoder[t]].deserialize(r.get(${ Expr(idx) }, null)) }
          }
          // Call case class constructor
          Apply(
            Select.unique(New(TypeTree.of[T]), "<init>"),
            args.map(_.asTerm).toList
          ).asExprOf[T]
        }
    }

    '{
      new ProtoEncoder[T]:
        val schema = $schemaExpr
        val catalystType = ProtoType.StructType(schema.fields)
        val serialize = $serializerExpr
        val deserialize = $deserializerExpr
        val nullable = false  // Case classes themselves not nullable at root
    }
```

### 2.3 Type Coverage

#### MVP (Phase 1)

| Scala Type | Catalyst Type | Notes |
|------------|---------------|-------|
| Boolean | BooleanType | Direct mapping |
| Byte | ByteType | Direct mapping |
| Short | ShortType | Direct mapping |
| Int | IntegerType | Direct mapping |
| Long | LongType | Direct mapping |
| Float | FloatType | Direct mapping |
| Double | DoubleType | Direct mapping |
| String | StringType | Direct mapping |
| Array[Byte] | BinaryType | Direct mapping |
| BigDecimal | DecimalType(38,18) | Configurable precision |
| java.time.LocalDate | DateType | |
| java.time.Instant | TimestampType | |
| java.time.LocalDateTime | TimestampNTZType | |
| Option[T] | T (nullable=true) | Nullable wrapper |
| case class | StructType | Recursive derivation |
| (T1, T2, ...) | StructType | Tuple as struct |

#### Deferred (Phase 2+)

| Scala Type | Notes |
|------------|-------|
| Seq[T], List[T], Vector[T] | ArrayType - needs element encoder |
| Set[T] | ArrayType - dedup semantics |
| Map[K, V] | MapType - needs key/value encoders |
| sealed trait / enum | Needs discriminator strategy |
| java.time.Duration | Spark 3.3+ DayTimeIntervalType |
| java.util.UUID | String or Binary encoding |
| Nested Options | Option[Option[T]] - discouraged |

### 2.4 API for Spark

```scala
package protocatalyst.spark

import org.apache.spark.sql.{Encoder, SparkSession}
import org.apache.spark.sql.catalyst.encoders.ExpressionEncoder
import protocatalyst.encoder.ProtoEncoder

/**
 * Minimal API surface for Spark integration.
 */
object Encoders:
  /**
   * Create a Spark Encoder from a ProtoEncoder.
   * This is the main entry point Spark code calls.
   */
  inline def encoder[T]: Encoder[T] =
    val proto = ProtoEncoder.derived[T]
    protoToSpark(proto)

  /**
   * Convert ProtoEncoder to Spark's ExpressionEncoder.
   * Bridges our compile-time representation to Spark's runtime encoder.
   */
  def protoToSpark[T](proto: ProtoEncoder[T]): ExpressionEncoder[T] =
    // Convert ProtoSchema to Spark StructType
    val sparkSchema = SchemaConverter.toSparkSchema(proto.schema)

    // Create serializer expression tree
    val serializer = SerializerBuilder.build(proto)

    // Create deserializer expression tree
    val deserializer = DeserializerBuilder.build(proto)

    ExpressionEncoder[T](
      schema = sparkSchema,
      flat = false,
      serializer = serializer,
      deserializer = deserializer,
      clsTag = scala.reflect.ClassTag[T](???) // Derived from Type
    )

/**
 * Extension methods for SparkSession.
 */
extension (spark: SparkSession)
  inline def createDataset[T](data: Seq[T]): Dataset[T] =
    spark.createDataset(data)(Encoders.encoder[T])

  inline def emptyDataset[T]: Dataset[T] =
    spark.emptyDataset(Encoders.encoder[T])
```

### 2.5 Tricky Parts

#### Nullability Semantics

```scala
/**
 * Nullability rules:
 *
 * 1. Option[T] -> nullable = true
 * 2. T (non-Option) -> nullable = false at root, but Spark may still have nulls
 * 3. Nested structs: propagate nullability from field definitions
 * 4. Collections: elementContainsNull from Option element type
 *
 * RISK: Spark's DataFrame operations can introduce nulls even for non-nullable fields.
 * MITIGATION:
 *   - Document that non-Option fields may throw NPE on null data
 *   - Provide `withNullSafety` mode that wraps accessors
 */
enum NullHandling:
  case Strict      // Throw on unexpected null
  case Permissive  // Return default/zero for primitives, None for objects
  case Annotated   // Use @nullable annotation to override

/**
 * Example: handling null for non-Option Int field
 */
// Strict mode (default):
val intValue = row.getInt(idx)  // Throws if null

// Permissive mode:
val intValue = if row.isNullAt(idx) then 0 else row.getInt(idx)
```

#### Catalyst DataType Mapping

```scala
object SchemaConverter:
  def toSparkSchema(proto: ProtoSchema): StructType =
    StructType(proto.fields.map(toSparkField))

  def toSparkField(f: ProtoStructField): StructField =
    StructField(f.name, toSparkType(f.dataType), f.nullable, toSparkMetadata(f.metadata))

  def toSparkType(pt: ProtoType): DataType = pt match
    case ProtoType.BooleanType => org.apache.spark.sql.types.BooleanType
    case ProtoType.IntegerType => org.apache.spark.sql.types.IntegerType
    case ProtoType.LongType => org.apache.spark.sql.types.LongType
    case ProtoType.DoubleType => org.apache.spark.sql.types.DoubleType
    case ProtoType.StringType => org.apache.spark.sql.types.StringType
    case ProtoType.DecimalType(p, s) => org.apache.spark.sql.types.DecimalType(p, s)
    case ProtoType.ArrayType(elem, nulls) =>
      org.apache.spark.sql.types.ArrayType(toSparkType(elem), nulls)
    case ProtoType.MapType(k, v, nulls) =>
      org.apache.spark.sql.types.MapType(toSparkType(k), toSparkType(v), nulls)
    case ProtoType.StructType(fields) =>
      StructType(fields.map(toSparkField))
    case ProtoType.DateType => org.apache.spark.sql.types.DateType
    case ProtoType.TimestampType => org.apache.spark.sql.types.TimestampType
    case ProtoType.TimestampNTZType => org.apache.spark.sql.types.TimestampNTZType
    case _ => throw IllegalArgumentException(s"Unsupported ProtoType: $pt")
```

#### Performance Considerations

```scala
/**
 * Performance optimizations:
 *
 * 1. AVOID REFLECTION: All type info resolved at compile time
 * 2. SPECIALIZE PRIMITIVES: Generate direct getInt/getLong/etc calls, not generic get()
 * 3. INLINE SMALL STRUCTS: For case classes with <= 4 primitive fields, inline accessors
 * 4. CACHE ENCODERS: Encoder instances are immutable, reuse across operations
 * 5. UNSAFE ACCESS: Use UnsafeRow methods where available for zero-copy
 */

// Example: specialized primitive access
// Generated code for case class Point(x: Int, y: Int):
val serialize = (p: Point) =>
  val row = new GenericInternalRow(2)
  row.setInt(0, p.x)  // Specialized, not row.update(0, p.x)
  row.setInt(1, p.y)
  row

val deserialize = (row: InternalRow) =>
  Point(row.getInt(0), row.getInt(1))  // Direct primitive access
```

#### Error Messages

```scala
/**
 * Compile-time error reporting.
 * Use quotes.reflect.report for clear, actionable errors.
 */
object EncoderErrors:
  def unsupportedType(using Quotes)(tpe: quotes.reflect.TypeRepr): Nothing =
    quotes.reflect.report.errorAndAbort(
      s"""Cannot derive ProtoEncoder for type: ${tpe.show}
         |
         |Supported types:
         |  - Primitives: Boolean, Byte, Short, Int, Long, Float, Double, String
         |  - Temporal: java.time.LocalDate, Instant, LocalDateTime
         |  - Wrappers: Option[T], Array[Byte], BigDecimal
         |  - Composites: case class, Tuple
         |
         |For custom types, implement ProtoEncoder[YourType] manually:
         |  given ProtoEncoder[YourType] = ProtoEncoder.from(schema, serialize, deserialize)
         |""".stripMargin
    )

  def nonCaseClass(using Quotes)(tpe: quotes.reflect.TypeRepr): Nothing =
    quotes.reflect.report.errorAndAbort(
      s"""Cannot derive ProtoEncoder for non-case class: ${tpe.show}
         |
         |Only case classes can be automatically derived.
         |For regular classes, either:
         |  1. Convert to a case class
         |  2. Provide a manual ProtoEncoder instance
         |""".stripMargin
    )

  def nullablePrimitive(using Quotes)(fieldName: String, tpe: quotes.reflect.TypeRepr): Unit =
    quotes.reflect.report.warning(
      s"""Field '$fieldName' has primitive type ${tpe.show} but may receive null values from Spark.
         |Consider using Option[${tpe.show}] if nulls are expected.
         |""".stripMargin
    )
```

---

## 3. Deliverable 2: Compiled Query Artifacts

### 3.1 CompiledQuery Structure

```scala
package protocatalyst.query

import protocatalyst.artifact.*
import protocatalyst.plan.*
import protocatalyst.schema.*
import protocatalyst.expr.*

/**
 * Type-safe compiled query wrapper.
 * A is the output row type (typically a case class or tuple).
 */
final class CompiledQuery[A] private (
  val artifact: CompiledArtifact,
  val encoder: ProtoEncoder[A]
):
  /** Human-readable query description for debugging */
  def describe: String = QueryPrinter.print(artifact.plan)

  /** Schema contracts that must be validated at runtime */
  def requiredSchemas: Vector[SchemaContract] = artifact.schemaContracts

  /** Expected output schema */
  def outputSchema: ProtoSchema = artifact.outputSchema

  /** Content hash for caching */
  def contentHash: Long = artifact.contentHash

  /** Serialize to bytes for storage/transmission */
  def toBytes: Array[Byte] = ArtifactCodec.serialize(artifact)

object CompiledQuery:
  /** Compile a SQL string at compile time */
  inline def sql[A](inline query: String): CompiledQuery[A] =
    ${ compileSQL[A]('query) }

  /** Compile a DSL expression at compile time */
  inline def dsl[A](inline builder: QueryBuilder ?=> Query[A]): CompiledQuery[A] =
    ${ compileDSL[A]('builder) }

  /** Load a pre-compiled artifact */
  def fromBytes[A](bytes: Array[Byte])(using enc: ProtoEncoder[A]): Either[String, CompiledQuery[A]] =
    ArtifactCodec.deserialize(bytes).map(art => new CompiledQuery(art, enc))
```

### 3.2 Macro Implementation

```scala
package protocatalyst.query

import scala.quoted.*

private def compileSQL[A: Type](query: Expr[String])(using Quotes): Expr[CompiledQuery[A]] =
  import quotes.reflect.*

  // Extract literal SQL string
  val sql = query.valueOrAbort

  // 1. Parse SQL to AST
  val parsed = SqlParser.parse(sql) match
    case Left(err) => report.errorAndAbort(s"SQL parse error: $err")
    case Right(ast) => ast

  // 2. Resolve types against expected output A
  val outputEncoder = '{ ProtoEncoder.derived[A] }
  val expectedSchema = analyzeOutputType[A]

  // 3. Build ProtoLogicalPlan with schema inference
  val (plan, contracts) = PlanBuilder.fromSqlAst(parsed, expectedSchema)

  // 4. Run compile-time optimizations (canonicalization only)
  val canonicalized = Canonicalizer.canonicalize(plan)

  // 5. Compute content hash
  val hash = PlanHasher.hash(canonicalized)

  // 6. Build artifact expression
  val artifactExpr = '{
    CompiledArtifact(
      formatVersion = ArtifactVersion.current,
      protocatalystVersion = BuildInfo.version,
      compiledAt = ${ Expr(System.currentTimeMillis()) },
      contentHash = ${ Expr(hash) },
      schemaContracts = ${ Expr.ofVector(contracts) },
      plan = ${ Expr.ofPlan(canonicalized) },
      outputSchema = $outputEncoder.schema,
      sourceInfo = Some(SourceInfo(
        ${ Expr(Position.ofMacroExpansion.sourceFile.path) },
        ${ Expr(Position.ofMacroExpansion.startLine) },
        Some($query)
      ))
    )
  }

  '{ new CompiledQuery[A]($artifactExpr, $outputEncoder) }
```

### 3.3 Schema Binding at Runtime

```scala
package protocatalyst.spark

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.expressions.AttributeReference
import protocatalyst.query.*
import protocatalyst.schema.*
import protocatalyst.plan.*

/**
 * Runtime binder - validates schema contracts and binds to Spark's AttributeReferences.
 */
object QueryBinder:

  case class BindingResult(
    plan: LogicalPlan,           // Spark LogicalPlan with bound attributes
    warnings: Vector[String]      // Non-fatal compatibility notes
  )

  case class BindingError(
    relation: String,
    expected: SchemaContract,
    actual: StructType,
    mismatches: Vector[FieldMismatch]
  )

  enum FieldMismatch:
    case Missing(name: String)
    case TypeMismatch(name: String, expected: ProtoType, actual: DataType)
    case NullabilityMismatch(name: String, expectedNullable: Boolean, actualNullable: Boolean)

  /**
   * Bind a compiled query to actual Spark relations.
   * This is the "fast validate + bind" step.
   */
  def bind(
    compiled: CompiledQuery[?],
    spark: SparkSession,
    relationResolver: String => Option[LogicalPlan]  // How to look up tables
  ): Either[Vector[BindingError], BindingResult] =

    var errors = Vector.empty[BindingError]
    var warnings = Vector.empty[String]

    // 1. Validate all schema contracts
    for contract <- compiled.requiredSchemas do
      relationResolver(contract.relationName) match
        case None =>
          errors :+= BindingError(
            contract.relationName,
            contract,
            StructType(Nil),
            Vector(FieldMismatch.Missing(s"Relation not found: ${contract.relationName}"))
          )

        case Some(relation) =>
          val actual = relation.schema
          val mismatches = validateContract(contract, actual)
          if mismatches.nonEmpty then
            errors :+= BindingError(contract.relationName, contract, actual, mismatches)

    if errors.nonEmpty then
      Left(errors)
    else
      // 2. Convert ProtoLogicalPlan to Spark LogicalPlan with bound attributes
      val sparkPlan = convertPlan(compiled.artifact.plan, relationResolver)
      Right(BindingResult(sparkPlan, warnings))

  private def validateContract(contract: SchemaContract, actual: StructType): Vector[FieldMismatch] =
    var mismatches = Vector.empty[FieldMismatch]

    for field <- contract.requiredFields do
      actual.find(_.name.equalsIgnoreCase(field.name)) match
        case None =>
          mismatches :+= FieldMismatch.Missing(field.name)

        case Some(actualField) =>
          // Check type compatibility
          if !isTypeCompatible(field.expectedType, actualField.dataType) then
            mismatches :+= FieldMismatch.TypeMismatch(
              field.name, field.expectedType, actualField.dataType
            )

          // Check nullability (warning only if actual is more nullable)
          if !field.expectedNullable && actualField.nullable then
            // This is a warning, not error - actual can be more permissive
            () // Record warning

    mismatches

  private def isTypeCompatible(expected: ProtoType, actual: DataType): Boolean =
    // Exact match or safe widening
    (expected, actual) match
      case (ProtoType.IntegerType, IntegerType) => true
      case (ProtoType.IntegerType, LongType) => true  // Safe widening
      case (ProtoType.LongType, LongType) => true
      case (ProtoType.StringType, StringType) => true
      case (ProtoType.DoubleType, DoubleType) => true
      case (ProtoType.DoubleType, FloatType) => true  // Widening
      // ... other cases
      case _ => false
```

### 3.4 Skip Analyzer Boundary

```scala
/**
 * Precise definition of what's skipped vs still run.
 *
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║                    SKIPPED (compile-time done)                   ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ • SQL Parsing (already parsed)                                   ║
 * ║ • Name Resolution (ColumnRef -> BoundRef with ordinal)           ║
 * ║ • Type Checking (all expressions typed in ProtoExpr)             ║
 * ║ • Implicit Cast Insertion (casts explicit in plan)               ║
 * ║ • Star Expansion (SELECT * already expanded)                     ║
 * ║ • Alias Resolution (all aliases resolved)                        ║
 * ║ • Subquery Decorrelation (subqueries inlined where safe)         ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║                    STILL RUNS (runtime required)                 ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║ • Schema Validation (actual vs expected)                         ║
 * ║ • Attribute Binding (BoundRef ordinal -> AttributeReference)     ║
 * ║ • Cost-Based Optimization (join reordering, etc)                 ║
 * ║ • Predicate Pushdown (requires statistics)                       ║
 * ║ • Physical Planning (scan selection, join strategies)            ║
 * ║ • AQE (runtime re-optimization)                                  ║
 * ║ • UDF Resolution (OpaqueCall -> actual UDF)                      ║
 * ║ • View Expansion (if views referenced)                           ║
 * ╚══════════════════════════════════════════════════════════════════╝
 */

/**
 * Integration point in Spark's query execution.
 */
object ProtoCatalystIntegration:

  /**
   * Execute a compiled query, skipping analyzer phases.
   */
  def execute[A](
    spark: SparkSession,
    compiled: CompiledQuery[A]
  ): DataFrame =

    // 1. Bind (fast path)
    val bound = QueryBinder.bind(
      compiled,
      spark,
      name => Some(spark.table(name).queryExecution.analyzed)
    ) match
      case Left(errors) => throw BindingException(errors)
      case Right(result) => result

    // 2. Skip directly to optimizer
    // Instead of: unresolved -> analyzer -> optimizer
    // We do:      bound (already resolved) -> optimizer
    val optimized = spark.sessionState.optimizer.execute(bound.plan)

    // 3. Continue normal execution: optimizer -> physical planning -> execution
    val qe = spark.sessionState.executePlan(optimized)

    new DataFrame(spark, qe, compiled.encoder.toSparkEncoder)
```

### 3.5 Minimal IR Node Set (15 nodes)

| # | Node | Purpose | Example SQL |
|---|------|---------|-------------|
| 1 | `RelationRef` | Table/view reference | `FROM users` |
| 2 | `Project` | Column selection | `SELECT a, b` |
| 3 | `Filter` | Row filtering | `WHERE x > 5` |
| 4 | `Join` | All join types | `JOIN orders ON ...` |
| 5 | `Aggregate` | GROUP BY + aggs | `GROUP BY city` |
| 6 | `Sort` | ORDER BY | `ORDER BY name` |
| 7 | `Limit` | Row limiting | `LIMIT 100` |
| 8 | `Distinct` | Deduplication | `SELECT DISTINCT` |
| 9 | `Union` | Union operations | `UNION ALL` |
| 10 | `Intersect` | Set intersection | `INTERSECT` |
| 11 | `Except` | Set difference | `EXCEPT` |
| 12 | `SubqueryAlias` | Aliased subqueries | `(SELECT ...) AS t` |
| 13 | `Window` | Window functions | `OVER (PARTITION BY)` |
| 14 | `Values` | Inline data | `VALUES (1, 'a')` |
| 15 | `With` (future) | CTEs | `WITH t AS (...)` |

### 3.6 Handling Unknown/Opaque Functions

```scala
/**
 * Strategy for UDFs and unknown functions.
 *
 * PRINCIPLE: Unknown functions are NOT type-checked at compile time.
 * They become OpaqueCall nodes that are resolved at runtime.
 */

enum FunctionResolution:
  /** Known function with compile-time type rules */
  case Builtin(name: String, resolver: TypeResolver)

  /** UDF registered at runtime - defer all checking */
  case RuntimeUDF(name: String)

  /** Completely unknown - may fail at runtime */
  case Unknown(name: String)

object FunctionCatalog:
  // Built-in functions we understand
  val builtins: Map[String, TypeResolver] = Map(
    "upper" -> TypeResolver.string1,
    "lower" -> TypeResolver.string1,
    "concat" -> TypeResolver.stringN,
    "coalesce" -> TypeResolver.firstNonNull,
    "count" -> TypeResolver.toLong,
    "sum" -> TypeResolver.numeric1,
    "avg" -> TypeResolver.toDouble,
    "min" -> TypeResolver.sameAsInput,
    "max" -> TypeResolver.sameAsInput,
    // ... ~50 common functions
  )

  def resolve(name: String, args: Vector[ProtoExpr]): ProtoExpr =
    builtins.get(name.toLowerCase) match
      case Some(resolver) =>
        resolver.resolve(name, args) match
          case Right(expr) => expr
          case Left(err) => throw CompileTimeError(s"Function '$name': $err")

      case None =>
        // Unknown function - create opaque call
        // Return type unknown, will be resolved at runtime
        ProtoExpr.OpaqueCall(
          functionName = name,
          arguments = args,
          returnType = None,  // Runtime resolution
          deterministic = false  // Assume non-deterministic for safety
        )

/**
 * At runtime, OpaqueCall is resolved:
 */
object OpaqueCallResolver:
  def resolve(call: ProtoExpr.OpaqueCall, spark: SparkSession): Expression =
    val registry = spark.sessionState.functionRegistry

    registry.lookupFunction(FunctionIdentifier(call.functionName)) match
      case Some(info) =>
        // Found! Create Spark expression
        val sparkArgs = call.arguments.map(convertExpr)
        info.builder(sparkArgs)

      case None =>
        throw AnalysisException(s"Function '${call.functionName}' not found")
```

### 3.7 Canonicalization Example

```scala
package protocatalyst.optimize

import protocatalyst.expr.*
import protocatalyst.plan.*

/**
 * Canonicalization transforms - make plans stable and comparable.
 * These are ALWAYS safe, deterministic, and improve plan caching.
 */
object Canonicalizer:

  def canonicalize(plan: ProtoLogicalPlan): ProtoLogicalPlan =
    val passes = Seq(
      flattenAndOr,
      sortAndChildren,
      deduplicateAndOr,
      foldConstants,
      simplifyBooleans,
      normalizeComparisons
    )
    passes.foldLeft(plan)((p, pass) => transformPlan(p, pass))

  /**
   * Flatten nested AND/OR into flat vectors.
   *
   * Before: AND(a, AND(b, c))
   * After:  AND(a, b, c)
   */
  val flattenAndOr: ProtoExpr => ProtoExpr = {
    case ProtoExpr.And(children) =>
      val flattened = children.flatMap {
        case ProtoExpr.And(inner) => inner
        case other => Vector(other)
      }
      ProtoExpr.And(flattened.map(flattenAndOr))

    case ProtoExpr.Or(children) =>
      val flattened = children.flatMap {
        case ProtoExpr.Or(inner) => inner
        case other => Vector(other)
      }
      ProtoExpr.Or(flattened.map(flattenAndOr))

    case other => other
  }

  /**
   * Sort AND/OR children for deterministic ordering.
   * Uses stable hash of expression structure.
   *
   * Before: AND(c > 3, a = 1, b < 2)
   * After:  AND(a = 1, b < 2, c > 3)  // Sorted by column name
   */
  val sortAndChildren: ProtoExpr => ProtoExpr = {
    case ProtoExpr.And(children) =>
      ProtoExpr.And(children.sortBy(exprSortKey))

    case ProtoExpr.Or(children) =>
      ProtoExpr.Or(children.sortBy(exprSortKey))

    case other => other
  }

  private def exprSortKey(e: ProtoExpr): String = e match
    case ProtoExpr.ColumnRef(name, qual, _, _) => qual.getOrElse("") + "." + name
    case ProtoExpr.Eq(l, r) => s"eq:${exprSortKey(l)}:${exprSortKey(r)}"
    case ProtoExpr.Lt(l, r) => s"lt:${exprSortKey(l)}:${exprSortKey(r)}"
    case _ => e.hashCode.toString

  /**
   * Remove duplicate predicates.
   *
   * Before: AND(a = 1, b = 2, a = 1)
   * After:  AND(a = 1, b = 2)
   */
  val deduplicateAndOr: ProtoExpr => ProtoExpr = {
    case ProtoExpr.And(children) =>
      ProtoExpr.And(children.distinctBy(exprSortKey))
    case ProtoExpr.Or(children) =>
      ProtoExpr.Or(children.distinctBy(exprSortKey))
    case other => other
  }

  /**
   * Fold compile-time constants.
   *
   * Before: 1 + 2
   * After:  3
   */
  val foldConstants: ProtoExpr => ProtoExpr = {
    case ProtoExpr.Add(ProtoExpr.Literal(a: Int, _), ProtoExpr.Literal(b: Int, _)) =>
      ProtoExpr.Literal(a + b, ProtoType.IntegerType)

    case ProtoExpr.Concat(children) if children.forall(_.isInstanceOf[ProtoExpr.Literal]) =>
      val str = children.collect { case ProtoExpr.Literal(s: String, _) => s }.mkString
      ProtoExpr.Literal(str, ProtoType.StringType)

    case other => other
  }

  /**
   * Simplify boolean expressions.
   *
   * Before: AND(true, a = 1)  |  OR(false, b = 2)  |  NOT(NOT(x))
   * After:  a = 1             |  b = 2             |  x
   */
  val simplifyBooleans: ProtoExpr => ProtoExpr = {
    case ProtoExpr.And(children) =>
      val simplified = children.filterNot(_ == ProtoExpr.Literal(true, ProtoType.BooleanType))
      if simplified.exists(_ == ProtoExpr.Literal(false, ProtoType.BooleanType)) then
        ProtoExpr.Literal(false, ProtoType.BooleanType)
      else simplified match
        case Vector() => ProtoExpr.Literal(true, ProtoType.BooleanType)
        case Vector(single) => single
        case multiple => ProtoExpr.And(multiple)

    case ProtoExpr.Or(children) =>
      val simplified = children.filterNot(_ == ProtoExpr.Literal(false, ProtoType.BooleanType))
      if simplified.exists(_ == ProtoExpr.Literal(true, ProtoType.BooleanType)) then
        ProtoExpr.Literal(true, ProtoType.BooleanType)
      else simplified match
        case Vector() => ProtoExpr.Literal(false, ProtoType.BooleanType)
        case Vector(single) => single
        case multiple => ProtoExpr.Or(multiple)

    case ProtoExpr.Not(ProtoExpr.Not(inner)) => inner

    case other => other
  }

  /**
   * Normalize comparisons to canonical form (column on left).
   *
   * Before: 5 > x
   * After:  x < 5
   */
  val normalizeComparisons: ProtoExpr => ProtoExpr = {
    case ProtoExpr.Gt(lit: ProtoExpr.Literal, col: ProtoExpr.ColumnRef) =>
      ProtoExpr.Lt(col, lit)
    case ProtoExpr.GtEq(lit: ProtoExpr.Literal, col: ProtoExpr.ColumnRef) =>
      ProtoExpr.LtEq(col, lit)
    case ProtoExpr.Lt(lit: ProtoExpr.Literal, col: ProtoExpr.ColumnRef) =>
      ProtoExpr.Gt(col, lit)
    case ProtoExpr.LtEq(lit: ProtoExpr.Literal, col: ProtoExpr.ColumnRef) =>
      ProtoExpr.GtEq(col, lit)
    case other => other
  }

/**
 * Example of plan stability improvement:
 *
 * Query 1: SELECT * FROM t WHERE a = 1 AND b = 2 AND c = 3
 * Query 2: SELECT * FROM t WHERE c = 3 AND a = 1 AND b = 2
 *
 * Before canonicalization: Different plan hashes, no cache hit
 * After canonicalization:  Same plan hash (predicates sorted), cache hit!
 */
```

---

## 4. Phased Upstream Plan

### Phase 1: Encoder Derivation Only

**Scope**: Ship `ProtoEncoder` derivation as standalone library.

**Deliverables**:
- `protocatalyst-encoder` artifact
- `ProtoEncoder.derived[T]` macro
- `Encoders.encoder[T]` Spark integration
- Documentation and migration guide from `Encoders.product`

**Integration Points**:
```scala
// User code changes from:
import spark.implicits._
val ds = spark.createDataset(data)

// To:
import protocatalyst.spark.Encoders.encoder
val ds = spark.createDataset(data)(encoder[MyCase])

// Or with extension:
import protocatalyst.spark.given
val ds = spark.createDataset(data)  // encoder derived implicitly
```

**Success Metrics**:
| Metric | Target |
|--------|--------|
| Compile time overhead | < 200ms per encoder |
| Runtime serialization perf | >= ExpressionEncoder |
| Type coverage | All primitives + case classes + Option |
| Error message clarity | Zero "macro expansion failed" errors |

**Risks & Mitigations**:
| Risk | Mitigation |
|------|------------|
| Binary compat with Spark versions | Test matrix: Spark 3.4, 3.5, 4.0 |
| Scala 2/3 interop | Scala 3 only; Scala 2 uses existing encoders |
| Complex nested types | Defer to Phase 2; clear error for unsupported |

**Timeline**: 3-4 months

---

### Phase 2: Compiled Artifacts (No Skip)

**Scope**: Ship compiled query artifacts, executed via `spark.sql()` without skipping analyzer.

**Deliverables**:
- `protocatalyst-query` artifact
- `CompiledQuery.sql[A]("...")` macro
- `ArtifactCodec` serialization
- Artifact caching layer
- Canonicalization passes

**Integration Points**:
```scala
// Compile query at build time
val query = CompiledQuery.sql[UserStats]("""
  SELECT user_id, COUNT(*) as cnt
  FROM events
  WHERE event_type = 'click'
  GROUP BY user_id
""")

// Execute at runtime (still goes through analyzer for validation)
val df = spark.proto.execute(query)

// Or with artifact caching
spark.proto.registerArtifact("user_clicks", query)
val df = spark.proto.cached("user_clicks")
```

**Success Metrics**:
| Metric | Target |
|--------|--------|
| Artifact size | < 10KB for typical query |
| Canonicalization cache hit rate | > 80% for equivalent queries |
| Runtime validation overhead | < 5ms per query |
| SQL coverage | SELECT/JOIN/GROUP BY/ORDER BY/LIMIT |

**Risks & Mitigations**:
| Risk | Mitigation |
|------|------------|
| SQL parser divergence from Spark | Use Spark's parser at compile time via plugin |
| Schema evolution | Fingerprint-based validation with clear errors |
| Function coverage | OpaqueCall fallback for unknown functions |

**Timeline**: 4-6 months (after Phase 1)

---

### Phase 3: Analyzer Skip Path

**Scope**: Full analyzer bypass for compiled artifacts.

**Deliverables**:
- `QueryBinder` implementation
- Spark `SessionExtensions` integration
- Analyzer skip path in QueryExecution
- Metrics and monitoring

**Integration Points**:
```scala
// Spark configuration
spark.conf.set("spark.sql.protocatalyst.enabled", "true")
spark.conf.set("spark.sql.protocatalyst.skipAnalyzer", "true")

// Direct execution skipping analyzer
val query = CompiledQuery.sql[Result]("SELECT ...")
val df = spark.proto.executeFast(query)  // Skips analyzer

// Automatic detection (future)
spark.sql("SELECT ...") // Detects pre-compiled artifact, uses fast path
```

**Success Metrics**:
| Metric | Target |
|--------|--------|
| Analyzer time saved | > 90% for compiled queries |
| End-to-end latency reduction | 10-50ms for small queries |
| Binding validation time | < 2ms |
| Correctness | 100% result parity with non-skip path |

**Risks & Mitigations**:
| Risk | Mitigation |
|------|------------|
| Semantic divergence | Extensive test suite comparing skip vs non-skip |
| Schema mismatch at runtime | Fast-fail binding with clear errors |
| Catalyst internals changes | Abstract over Spark version differences |
| Performance regression | A/B testing framework, feature flag |

**Timeline**: 6-9 months (after Phase 2)

---

## 5. Appendix

### A. Tradeoffs Summary

| Decision | Chosen Approach | Alternative | Rationale |
|----------|-----------------|-------------|-----------|
| Type representation | Custom `ProtoType` enum | Reuse Spark's `DataType` | Avoids Spark compile-time dependency |
| Serialization | Java serialization (MVP) | Protobuf | Simplicity; migrate later |
| Function handling | Opaque fallback | Exhaustive catalog | Pragmatic coverage |
| Nullability | Option = nullable | Annotation-based | Scala idiom alignment |
| Schema binding | Name-based | Position-based | Robustness to column reorder |

### B. Open Questions

1. **View expansion**: Should compiled queries that reference views expand them at compile time or defer?
   - Recommendation: Defer; views may change between compile and runtime.

2. **Partition pruning hints**: Should compile-time analysis emit partition filter hints?
   - Recommendation: Phase 4; requires catalog access at compile time.

3. **Cross-version compatibility**: How to handle Spark 3.x vs 4.x differences?
   - Recommendation: Abstract IR + version-specific lowering modules.

4. **Incremental compilation**: How to handle queries that span multiple files?
   - Recommendation: Each `CompiledQuery` is self-contained; no cross-file deps.

### C. Related Work

- **Slick**: Scala DSL that compiles to SQL; similar goals but doesn't target Spark
- **Quill**: Compile-time query generation; inspiration for DSL approach
- **Spark Connect**: Wire protocol for Spark; complementary, not competing
- **Velox**: Execution engine; operates at different layer (physical)

### D. Glossary

| Term | Definition |
|------|------------|
| ProtoType | ProtoCatalyst's compile-time type representation |
| ProtoExpr | Compile-time expression tree |
| ProtoLogicalPlan | Compile-time logical plan |
| SchemaContract | Expected schema for a relation, used for runtime validation |
| Artifact | Serialized compiled query |
| Binding | Process of connecting compile-time plan to runtime relations |
| Canonicalization | Normalizing plans for stability and caching |
