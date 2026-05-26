# Encoder Parity: ProtoCatalyst ↔ Spark 4.1 AgnosticEncoder

Source of truth for the comparison work that will go into the Phase A benchmark
report. Cross-references every Spark 4.1.2 `AgnosticEncoder` variant against the
matching `ProtoEncoder` derivation in this repo.

- Spark source: `spark-sql-api_2.13:4.1.2` →
  `org/apache/spark/sql/catalyst/encoders/AgnosticEncoder.scala` and
  `org/apache/spark/sql/catalyst/ScalaReflection.scala`.
- ProtoCatalyst source: `encoder/src/main/scala/protocatalyst/encoder/ProtoEncoder.scala`
  and `core/src/main/scala/protocatalyst/types/ProtoType.scala`.
- Live cross-check: `encoder-spark/src/test/.../UnsafeRowSerializerSpec.scala`
  (15 tests, runs on Spark 4.1.2 catalyst jars via `CrossVersion.for3Use2_13`).

### 4.0 → 4.1 deltas worth knowing

- `LocalTimeEncoder` added (`LeafEncoder[LocalTime](TimeType())`). Our
  `given ProtoEncoder[LocalTime] -> TimeType(6)` now matches Spark exactly; this
  was the only "ProtoCatalyst ahead of Spark on a temporal type" gap and it has
  closed.
- `GeographyEncoder` and `GeometryEncoder` added for the new
  `Geography` / `Geometry` spatial types. These follow the same Spark-class
  external-type pattern as `Decimal` / `CalendarInterval` / `VariantVal`, so per
  our triage they stay out of scope for the report.

## Quick verdict

- **Type-level parity is effectively complete** for the surface area that
  matters to Dataset[T] users: every primitive, boxed primitive, string,
  binary, decimal family, temporal family, interval, option, array,
  collection, map, tuple, product, and Java enum has a matching encoder.
- **ProtoCatalyst exceeds Spark** in six places: `OffsetDateTime`,
  `ZonedDateTime`, `java.util.Date`, `java.util.UUID`, `java.lang.Character`,
  and sealed-trait sum types. (`LocalTime` was a gap on 4.0 and closed in 4.1.)
- **Five deliberate gaps** where the Spark-internal class itself is the
  external type — `o.a.s.s.t.Decimal`, `CalendarInterval`, `VariantVal`,
  `Geography`, `Geometry` (the last two are 4.1 additions). Adding these would
  couple `encoder` to Spark, so they should live behind the `spark-catalyst`
  bridge instead.
- **One semantic divergence**: Spark's lenient flag on
  `Date`/`Timestamp`/`Java BigDecimal` is not modelled. We instead provide
  separate `given`s per external Java/Scala input type and let the implicit
  scope pick. The resulting `DataType` is identical; only the input-type
  flexibility differs.

## Spark 4.1 → ProtoCatalyst type map

Legend: ✅ matches, ✳️ matches with caveat, ➕ ProtoCatalyst has it / Spark doesn't,
➖ Spark has it / ProtoCatalyst doesn't, ⚠ behavioural difference.

### Primitive leaf encoders

| Spark encoder                | External type           | Spark `DataType` | ProtoCatalyst given             | ProtoType         | Status |
|------------------------------|-------------------------|------------------|---------------------------------|-------------------|--------|
| `PrimitiveBooleanEncoder`    | `Boolean`               | `BooleanType`    | `given ProtoEncoder[Boolean]`   | `BooleanType`     | ✅ |
| `PrimitiveByteEncoder`       | `Byte`                  | `ByteType`       | `given ProtoEncoder[Byte]`      | `ByteType`        | ✅ |
| `PrimitiveShortEncoder`      | `Short`                 | `ShortType`      | `given ProtoEncoder[Short]`     | `ShortType`       | ✅ |
| `PrimitiveIntEncoder`        | `Int`                   | `IntegerType`    | `given ProtoEncoder[Int]`       | `IntegerType`     | ✅ |
| `PrimitiveLongEncoder`       | `Long`                  | `LongType`       | `given ProtoEncoder[Long]`      | `LongType`        | ✅ |
| `PrimitiveFloatEncoder`      | `Float`                 | `FloatType`      | `given ProtoEncoder[Float]`     | `FloatType`       | ✅ |
| `PrimitiveDoubleEncoder`     | `Double`                | `DoubleType`     | `given ProtoEncoder[Double]`    | `DoubleType`      | ✅ |

### Boxed leaf encoders

| Spark encoder            | External type             | Spark `DataType` | ProtoCatalyst given                       | ProtoType      | Status |
|--------------------------|---------------------------|------------------|-------------------------------------------|----------------|--------|
| `BoxedBooleanEncoder`    | `java.lang.Boolean`       | `BooleanType`    | `boxedBooleanEncoder`                     | `BooleanType`  | ✅ |
| `BoxedByteEncoder`       | `java.lang.Byte`          | `ByteType`       | `boxedByteEncoder`                        | `ByteType`     | ✅ |
| `BoxedShortEncoder`      | `java.lang.Short`         | `ShortType`      | `boxedShortEncoder`                       | `ShortType`    | ✅ |
| `BoxedIntEncoder`        | `java.lang.Integer`       | `IntegerType`    | `boxedIntEncoder`                         | `IntegerType`  | ✅ |
| `BoxedLongEncoder`       | `java.lang.Long`          | `LongType`       | `boxedLongEncoder`                        | `LongType`     | ✅ |
| `BoxedFloatEncoder`      | `java.lang.Float`         | `FloatType`      | `boxedFloatEncoder`                       | `FloatType`    | ✅ |
| `BoxedDoubleEncoder`     | `java.lang.Double`        | `DoubleType`     | `boxedDoubleEncoder`                      | `DoubleType`   | ✅ |
| —                        | `java.lang.Character`     | —                | `boxedCharEncoder`                        | `StringType`   | ➕ |

### String / binary / null

| Spark encoder       | External type        | Spark `DataType`        | ProtoCatalyst             | ProtoType                  | Status |
|---------------------|----------------------|-------------------------|---------------------------|----------------------------|--------|
| `StringEncoder`     | `String`             | `StringType`            | `given ProtoEncoder[String]` | `StringType`           | ✅ |
| `CharEncoder(n)`    | `String`             | `CharType(n)`           | `ProtoEncoder.charEncoder(n)` | `CharType(n)`         | ✅ |
| `VarcharEncoder(n)` | `String`             | `VarcharType(n)`        | `ProtoEncoder.varcharEncoder(n)` | `VarcharType(n)`   | ✅ |
| `BinaryEncoder`     | `Array[Byte]`        | `BinaryType`            | `given ProtoEncoder[Array[Byte]]` | `BinaryType`      | ✅ |
| `NullEncoder`       | `java.lang.Void`     | `NullType`              | `voidEncoder`             | `NullType`                 | ✅ |

### Decimal family

| Spark encoder                  | External type             | Spark `DataType`            | ProtoCatalyst                  | ProtoType            | Status |
|--------------------------------|---------------------------|-----------------------------|--------------------------------|----------------------|--------|
| `SparkDecimalEncoder`          | `o.a.s.s.t.Decimal`       | `DecimalType.SYSTEM_DEFAULT`| —                              | —                    | ➖ deliberate (Spark coupling) |
| `ScalaDecimalEncoder`          | `BigDecimal`              | `DecimalType(38,18)`        | `given ProtoEncoder[BigDecimal]`  | `DecimalType(38,18)` | ✅ |
| `JavaDecimalEncoder` (lenient) | `java.math.BigDecimal`    | `DecimalType(38,18)`        | `javaBigDecimalEncoder`        | `DecimalType(38,18)` | ✳️ lenient flag not modelled |
| `ScalaBigIntEncoder`           | `BigInt`                  | `DecimalType(38,0)`         | `scalaBigIntEncoder`           | `DecimalType(38,0)`  | ✅ |
| `JavaBigIntEncoder`            | `java.math.BigInteger`    | `DecimalType(38,0)`         | `javaBigIntegerEncoder`        | `DecimalType(38,0)`  | ✅ |

### Temporal family

| Spark encoder                       | External type             | Spark `DataType`                 | ProtoCatalyst                      | ProtoType                  | Status |
|-------------------------------------|---------------------------|----------------------------------|------------------------------------|----------------------------|--------|
| `DateEncoder(lenient)`              | `java.sql.Date`           | `DateType`                       | `javaSqlDateEncoder`               | `DateType`                 | ✳️ lenient flag not modelled |
| `LocalDateEncoder(lenient)`         | `java.time.LocalDate`     | `DateType`                       | `given ProtoEncoder[LocalDate]`    | `DateType`                 | ✳️ lenient flag not modelled |
| `TimestampEncoder(lenient)`         | `java.sql.Timestamp`      | `TimestampType`                  | `javaSqlTimestampEncoder`          | `TimestampType`            | ✳️ lenient flag not modelled |
| `InstantEncoder(lenient)`           | `java.time.Instant`       | `TimestampType`                  | `given ProtoEncoder[Instant]`      | `TimestampType`            | ✳️ lenient flag not modelled |
| `LocalDateTimeEncoder`              | `java.time.LocalDateTime` | `TimestampNTZType`               | `given ProtoEncoder[LocalDateTime]`| `TimestampNTZType`         | ✅ |
| `LocalTimeEncoder`                  | `java.time.LocalTime`     | `TimeType()` (default precision 6)| `given ProtoEncoder[LocalTime]`    | `TimeType(6)`              | ✅ matches in Spark 4.1+ |
| —                                   | `java.time.OffsetDateTime`| —                                | `given ProtoEncoder[OffsetDateTime]`| `TimestampType`            | ➕ ProtoCatalyst normalises to UTC `Instant` |
| —                                   | `java.time.ZonedDateTime` | —                                | `given ProtoEncoder[ZonedDateTime]`| `TimestampType`            | ➕ ProtoCatalyst normalises to UTC `Instant` |
| —                                   | `java.util.Date`          | —                                | `javaUtilDateEncoder`              | `TimestampType`            | ➕ |

### Intervals & variants

| Spark encoder                | External type                 | Spark `DataType`               | ProtoCatalyst                    | ProtoType                  | Status |
|------------------------------|-------------------------------|--------------------------------|----------------------------------|----------------------------|--------|
| `DayTimeIntervalEncoder`     | `java.time.Duration`          | `DayTimeIntervalType()`        | `given ProtoEncoder[Duration]`   | `DayTimeIntervalType`      | ✅ |
| `YearMonthIntervalEncoder`   | `java.time.Period`            | `YearMonthIntervalType()`      | `given ProtoEncoder[Period]`     | `YearMonthIntervalType`    | ✅ |
| `CalendarIntervalEncoder`    | `o.a.s.unsafe.CalendarInterval` | `CalendarIntervalType`       | — (only `ProtoType` + `LiteralValue`) | `CalendarIntervalType` | ➖ deliberate (Spark coupling) |
| `VariantEncoder`             | `o.a.s.unsafe.VariantVal`     | `VariantType`                  | — (only `ProtoType`)             | `VariantType`              | ➖ deliberate (Spark coupling) |
| `GeographyEncoder` (Spark 4.1+) | `o.a.s.s.t.Geography`         | `GeographyType(srid)`          | —                                | —                          | ➖ deliberate (Spark coupling) |
| `GeometryEncoder` (Spark 4.1+)  | `o.a.s.s.t.Geometry`          | `GeometryType(srid)`           | —                                | —                          | ➖ deliberate (Spark coupling) |

### Special / extras

| Spark encoder                                 | External type                       | Notes                                                                                        | Status |
|-----------------------------------------------|-------------------------------------|----------------------------------------------------------------------------------------------|--------|
| —                                             | `java.util.UUID`                    | ProtoCatalyst maps to `StringType` (canonical 36-char form).                                 | ➕ |
| `ScalaEnumEncoder`                            | `Enumeration#Value`                 | Scala-2 `object Foo extends Enumeration { val Bar = Value }`. Irrelevant in Scala 3.         | ➖ N/A |
| `JavaEnumEncoder`                             | `E <: java.lang.Enum[E]`            | `javaEnumEncoder` (`StringType`).                                                            | ✅ |
| Scala 3 enum / sealed singleton variants      | —                                   | Treated as Java enum → `StringType` via `makeEnumEncoder` when every variant is a singleton. | ✳️ Spark has no first-class Scala-3 enum support; behaviour aligns when used through a singleton-only enum. |
| —                                             | sealed trait with data variants     | `SumType(discriminator, variants)`.                                                          | ➕ no Spark equivalent in `DataType` |

### Compound encoders

| Spark encoder                                    | ProtoCatalyst              | Notes                                                                                   | Status |
|--------------------------------------------------|----------------------------|-----------------------------------------------------------------------------------------|--------|
| `OptionEncoder[E]`                               | `optionEncoder[T]`         | `nullable = true`, same inner `DataType`.                                               | ✅ |
| `ArrayEncoder[E]`                                | `arrayEncoder[T]`          | `ArrayType(elem, containsNull = elem.nullable)`.                                        | ✅ |
| `IterableEncoder[C, E]` (Seq/List/Set/Vector/…)  | `seqEncoder/listEncoder/vectorEncoder/setEncoder` | `ArrayType(elem, containsNull = elem.nullable)`. ProtoCatalyst lacks `lenientSerialization`; mostly relevant for `Row` encoders. | ✳️ |
| `MapEncoder[C, K, V]`                            | `mapEncoder[K, V]`         | `MapType(k, v, valueContainsNull = v.nullable)`.                                        | ✅ |
| `ProductEncoder[K]` (case classes & tuples)      | `ProtoEncoder.derived[T]` (via Scala 3 `Mirror.ProductOf`) + explicit Tuple2..22 givens | Spark uses Scala-2 `TypeTag` reflection; ProtoCatalyst uses compile-time `Mirror`. Same resulting `StructType`. | ✅ |
| `RowEncoder(fields)` / `UnboundRowEncoder`       | `RowEncoder` (over `ProtoRow`) | We model rows over our `ProtoRow`, not `org.apache.spark.sql.Row`. Bridge in `spark-catalyst`. | ✳️ |
| `JavaBeanEncoder`                                | `JavaBeanEncoder.apply[T](beanClass)` | Runtime introspection in both; same `StructType` layout.                          | ✅ |
| `UDTEncoder[E]` (annotation + registry)          | `ProtoUDT[T]` + `udtEncoder` | Equivalent shape; ProtoCatalyst registers via given UDT in scope instead of Java annotation. | ✳️ |
| `TransformingEncoder[I, O]` (Kryo, Java serialization) | `TransformingEncoder[T]` with `BinaryCodec` (Kryo, Java serialization, Fory) | Both produce `BinaryType` storage. ProtoCatalyst adds Fory; Spark does not.       | ✳️ |

## Semantic divergences (worth calling out in the report)

1. **Lenient serialization flag.** Spark's `DateEncoder`, `LocalDateEncoder`,
   `TimestampEncoder`, `InstantEncoder`, and `JavaDecimalEncoder` carry a
   `lenientSerialization` flag that, when set, allows the *other* compatible
   external types as input. ProtoCatalyst's design provides distinct `given`
   instances per external type, so the implicit scope at the call site
   decides which input is accepted — there is no runtime "be lenient" switch.
   The materialised `DataType` is identical; only the input-flexibility
   surface differs. For the report, this is a *design choice*, not a gap.

2. **Sealed traits with data.** ProtoCatalyst lifts Scala 3 `enum` /
   `sealed trait` ADTs into `SumType(discriminatorField, variants)` — a
   first-class IR concept with no Spark `DataType` equivalent. Spark would
   require representing the same shape as a UDT or as a manually-discriminated
   struct. This is an *additional capability* of ProtoCatalyst that does not
   serialize losslessly into a Spark schema; cover in the qualitative
   section of the report, not the perf table.

3. **Spark-class external types.** Five Spark types use Spark-internal
   classes as their external representation: `o.a.s.s.t.Decimal`,
   `CalendarInterval`, `VariantVal`, and (4.1+) `Geography`, `Geometry`.
   ProtoCatalyst intentionally does not add `given ProtoEncoder` instances
   for these because doing so would couple the `encoder` Scala 3 module to
   Spark. The corresponding `ProtoType` cases exist for the first three
   (`DecimalType`, `CalendarIntervalType`, `VariantType`); the spatial
   types have no IR representation at all. A `spark-catalyst` bridge is
   the right home for these if we ever need them.

4. **`Row` vs `ProtoRow`.** Spark's `RowEncoder` operates over
   `org.apache.spark.sql.Row`. ProtoCatalyst's `RowEncoder` operates over our
   own `ProtoRow` to keep the `encoder` module Spark-free. Parity at the
   *schema* level is exact; runtime row instances differ.

## What to do before the benchmark report

- [x] Audit confirms type parity. Carry the result into Phase A.
- [ ] Task #2: produce a machine-checked parity test that derives every
      cell of the matrix above for both Spark and ProtoCatalyst and
      asserts `StructType` / `DataType` equality. Without that test, the
      blog claim is only as good as this hand-written audit.
- [ ] Decide on Spark-coupling for `Decimal`/`CalendarInterval`/`VariantVal`
      bridges (Task #3) — likely "out of scope for the benchmark."
- [ ] Document lenient-flag design choice in the report's "Spark semantics
      preserved" sidebar so it isn't read as a missing feature.
