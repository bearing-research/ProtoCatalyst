# ProtoCatalyst Roadmap

## Current State (v0.1)

### Completed

**Core Encoder System**
- [x] ProtoEncoder with compile-time derivation (Scala 3 Mirror)
- [x] ProtoType enum covering all Spark DataTypes
- [x] ProtoSchema for structured type information

**Type Support**
- [x] Primitives: Boolean, Byte, Short, Int, Long, Float, Double
- [x] String, Array[Byte] (Binary)
- [x] BigInt, BigDecimal (DecimalType)
- [x] Date/Time: LocalDate, Instant, LocalDateTime, java.sql.Date/Timestamp
- [x] Intervals: Duration (DayTimeIntervalType), Period (YearMonthIntervalType)
- [x] Collections: List, Seq, Vector, Set, Array, Map
- [x] Tuples: Tuple2-22
- [x] Option[T] (nullable wrapper)
- [x] Case classes (nested structs)
- [x] Scala 3 enums, Java enums
- [x] Char(n), Varchar(n), NullType

**Serialization**
- [x] InlineRowSerializer with compile-time specialization
- [x] InternalTypeConverter abstraction
- [x] Mock runtime for testing without Spark dependency

**Arrow Integration**
- [x] InlineArrowWriter for Arrow batch writing
- [x] ArrowBatchBuilder for resource management

**Testing**
- [x] 382 unit tests passing
- [x] 14 Spark parity tests (golden file comparison)
- [x] Benchmark infrastructure (JMH)

**Documentation**
- [x] ENCODER_DEEP_DIVE.md
- [x] SPARK_INTEGRATION.md

---

## Phase 1: Type Completeness

**Priority: High**

- [x] UUID encoder (common in modern applications)
- [ ] java.util.Date encoder (legacy compatibility)
- [ ] OffsetDateTime, ZonedDateTime (timezone-aware timestamps)
- [ ] CalendarInterval encoder (defined in ProtoType, needs encoder)
- [ ] LocalTime encoder improvements

**Parity Tests**
- [ ] Add parity tests for remaining primitive types (Byte, Short, Float)
- [ ] Add parity tests for date/time types (LocalDate, LocalDateTime)
- [ ] Add parity tests for binary type
- [ ] Edge case tests (nulls, empty collections, boundary values)

---

## Phase 2: Spark Integration (Schema-based)

**Priority: High**

**Goal**: Enable `DataFrame` creation from ProtoCatalyst-encoded data

```scala
// Target API
val people: Seq[Person] = ...
val df = spark.createDataFrame(
  people.map(p => ProtoRow.toInternalRow(p)),
  SchemaConverter.toSparkSchema(ProtoEncoder[Person].schema)
)
```

**Tasks**
- [ ] Implement `ProtoRow.toInternalRow[T]` using InlineRowSerializer
- [ ] Implement `ProtoRow.fromInternalRow[T]` (deserialization)
- [ ] Add integration tests with actual Spark
- [ ] Verify schema compatibility with Spark's ExpressionEncoder output

---

## Phase 3: Full Spark Encoder

**Priority: Medium**

**Goal**: Enable full `Dataset[T]` API

```scala
// Target API
import protocatalyst.spark.implicits._

case class Person(name: String, age: Int) derives ProtoEncoder

val ds: Dataset[Person] = spark.createDataset(Seq(
  Person("Alice", 30),
  Person("Bob", 25)
))

ds.filter(_.age > 25).map(p => p.copy(name = p.name.toUpperCase))
```

**Tasks**
- [ ] Implement `toAgnosticEncoder[T]`: ProtoEncoder → AgnosticEncoder
- [ ] Handle all ProtoType variants in type mapping
- [ ] Create `protocatalyst.spark.implicits` for implicit encoder derivation
- [ ] Integration tests for Dataset operations (map, filter, groupBy, join)
- [ ] Performance benchmarks vs native ExpressionEncoder

---

## Phase 4: Advanced Features

**Priority: Low**

**Sum Types / ADTs**
- [ ] Sealed trait encoding with discriminator field
- [ ] Pattern matching in Spark SQL

**User-Defined Types**
- [ ] ProtoUDT interface for custom serialization
- [ ] Integration with Spark's UserDefinedType

**Query Optimization**
- [ ] Predicate pushdown verification
- [ ] Column pruning support
- [ ] Statistics collection

---

## Phase 5: Ecosystem Integration

**Priority: Future**

**Connectors**
- [ ] Parquet reader/writer using ProtoCatalyst schemas
- [ ] Delta Lake integration
- [ ] Iceberg integration

**Streaming**
- [ ] Structured Streaming support
- [ ] Kafka serialization/deserialization

**Cross-Platform**
- [ ] Arrow IPC for language interop (Python, Rust)
- [ ] gRPC/Connect serialization

---

## Version Milestones

| Version | Target | Key Features |
|---------|--------|--------------|
| 0.1.0 | Current | Core encoder, parity tests, Arrow writer |
| 0.2.0 | Phase 1 | Type completeness, comprehensive parity tests |
| 0.3.0 | Phase 2 | DataFrame integration, InternalRow conversion |
| 0.4.0 | Phase 3 | Full Dataset[T] API support |
| 1.0.0 | Phase 4+ | Production-ready, advanced features |

---

## Contributing

See individual phase tasks above. Priority items:

1. **Quick wins**: UUID encoder, additional parity tests
2. **High impact**: `toInternalRow` / `fromInternalRow` implementation
3. **Long-term**: Full AgnosticEncoder integration

---

## Architecture Decisions

### Why compile-time derivation?

- Zero runtime reflection overhead
- Type errors caught at compile time
- Inline expansion for primitive types
- Compatible with GraalVM native-image

### Why separate from Spark?

- Test without Spark dependency (mock-runtime)
- Support Arrow independently
- Cleaner module boundaries
- Easier to maintain across Spark versions

### Why golden file testing?

- Guarantees byte-level compatibility with Spark
- Catches subtle serialization differences
- Documents expected behavior
- Enables testing without Spark runtime
