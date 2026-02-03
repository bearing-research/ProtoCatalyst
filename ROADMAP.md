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
- [x] Date/Time: LocalDate, LocalTime, Instant, LocalDateTime, java.sql.Date/Timestamp
- [x] Timezone-aware: OffsetDateTime, ZonedDateTime, java.util.Date
- [x] Intervals: Duration (DayTimeIntervalType), Period (YearMonthIntervalType)
- [x] Collections: List, Seq, Vector, Set, Array, Map
- [x] Tuples: Tuple2-22
- [x] Option[T] (nullable wrapper)
- [x] Case classes (nested structs)
- [x] Scala 3 enums, Java enums
- [x] Char(n), Varchar(n), NullType, UUID

**Serialization**
- [x] InlineRowSerializer with compile-time specialization
- [x] InternalTypeConverter abstraction
- [x] Mock runtime for testing without Spark dependency

**Arrow Integration**
- [x] InlineArrowWriter for Arrow batch writing
- [x] ArrowBatchBuilder for resource management

**Testing**
- [x] 268 unit tests passing
- [x] 26 Spark parity tests (golden file comparison)
- [x] Benchmark infrastructure (JMH)

**Documentation**
- [x] ENCODER_DEEP_DIVE.md
- [x] SPARK_INTEGRATION.md
- [x] SPARK_MIGRATION_GUIDE.md
- [x] BENCHMARKS.md

---

## Phase 1: Type Completeness ✅

**Status: Complete**

- [x] UUID encoder (common in modern applications)
- [x] java.util.Date encoder (legacy compatibility)
- [x] OffsetDateTime, ZonedDateTime (timezone-aware timestamps)
- [x] LocalTime encoder (TimeType with precision support)
- [ ] CalendarInterval encoder - *deferred* (requires Spark internal type)

**Parity Tests**
- [x] Add parity tests for remaining primitive types (Byte, Short, Float)
- [x] Add parity tests for date/time types (LocalDate, LocalDateTime)
- [x] Add parity tests for binary type
- [x] Edge case tests (nulls, empty collections, boundary values)

---

## Phase 2: Spark Integration Readiness ✅

**Status: Complete**

**Goal**: Prepare ProtoCatalyst for direct integration into Spark as the Scala 3 encoder

**Type Coverage**
- [x] 100% parity with Spark's AgnosticEncoder types
- [x] All primitives, boxed types, temporals, collections covered
- [x] Tuples 2-22, enums (Java + Scala 3), sealed traits
- [x] VariantType supported at schema level (encoder deferred - requires Spark type)

**Performance Benchmarks**
- [x] Collection benchmarks (List/Map at 10, 100, 1000 elements)
- [x] Memory allocation benchmarks with GC profiler
- [x] 10-27x faster roundtrip vs ExpressionEncoder

**Documentation**
- [x] SPARK_MIGRATION_GUIDE.md - Integration guide for Spark team
- [x] BENCHMARKS.md - Performance testing documentation
- [x] File mapping: ProtoCatalyst → Spark equivalents

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
| 0.1.0 | Complete | Core encoder, parity tests, Arrow writer |
| 0.2.0 | Complete | Type completeness, comprehensive parity tests |
| 0.3.0 | Complete | Spark integration readiness, benchmarks, migration guide |
| 0.4.0 | Phase 3 | Full Dataset[T] API support (when Spark supports Scala 3) |
| 1.0.0 | Phase 4+ | Production-ready, ported into Spark |

---

## Contributing

See individual phase tasks above. Priority items:

1. **High impact**: Spark Scala 3 integration (Phase 3 - when Spark adds Scala 3 support)
2. **Medium impact**: Additional parity tests, edge case coverage
3. **Long-term**: Port ProtoCatalyst into Spark codebase

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
