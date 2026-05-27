package protocatalyst.encoder.spark

import scala.deriving.Mirror

import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeRowWriter
import org.apache.spark.sql.types.{StructType => SparkStructType}

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.schema.ProtoSchema

/** Compile-time-derived serializer that writes directly into Spark's packed `UnsafeRow` byte
  * layout — the same target Spark's whole-stage codegen produces.
  *
  * Implementation: a Scala 3 `quoted.Expr` macro
  * ([[protocatalyst.encoder.spark.UnsafeRowSerializerMacro]]) emits a direct case-class
  * constructor call for the deserialize path (`new T(row.getLong(0), row.getInt(1), ...)`) and a
  * sequence of `writer.write(idx, value.field)` statements for the serialize path. No
  * `Array[Any]` intermediates, no `Mirror.fromProduct` boxing, no megamorphic dispatch on
  * `DataType` at runtime. Matches jsoniter-scala's `JsonCodecMaker` pattern.
  *
  * The `UnsafeRowWriter` is cached as an instance field (mirroring Spark's `UnsafeProjection`)
  * and reused across `serialize()` calls. The returned `UnsafeRow` is the same instance every
  * call — callers retaining the row must `.copy()`.
  *
  * Supported field types (covered by the macro; anything else is rejected at COMPILE time with
  * a clear `report.errorAndAbort` message — strictly stronger than a runtime exception):
  *
  *  - Primitives: `Boolean`, `Byte`, `Short`, `Int`, `Long`, `Float`, `Double`.
  *  - Variable-length leaves: `String`, `Array[Byte]`, `BigDecimal`, `java.math.BigDecimal`.
  *  - Temporal: `java.time.LocalDate`, `java.sql.Date`, `java.time.Instant`,
  *    `java.sql.Timestamp`, `java.time.LocalDateTime`, `java.time.LocalTime` (Spark 4.1+),
  *    `java.time.Duration`, `java.time.Period`.
  *  - `java.util.UUID` (stored as `StringType`, canonical 36-char form).
  *  - `Option[T]` over any of the above.
  *
  * Not yet supported (caught at compile time, will be added in follow-ups):
  *  - Collections (`Seq` / `List` / `Vector` / `Set` / `Array[T]` of non-byte). Need
  *    `UnsafeArrayWriter` integration.
  *  - Maps. Need `UnsafeMapWriter` integration.
  *  - Nested case classes. Need recursive macro construction.
  *  - `OffsetDateTime`, `ZonedDateTime`, `java.util.Date`, `BigInt`. Each just needs more cases.
  *  - Spark-class external types (`Decimal`, `CalendarInterval`, `VariantVal`, `Geography`,
  *    `Geometry`). Belong in a Spark-coupling bridge, not the core macro.
  */
trait UnsafeRowSerializer[T]:
  def schema: ProtoSchema
  def sparkSchema: SparkStructType
  def fieldCount: Int

  /** Serializes `value` via an internally-cached `UnsafeRowWriter`. The returned `UnsafeRow` is
    * owned by this serializer — it is the same instance every call, with its byte buffer
    * overwritten. Callers that need to retain a row across subsequent `serialize()` calls must
    * call `.copy()`. This matches Spark's `UnsafeProjection` mutable-row contract.
    */
  def serialize(value: T): UnsafeRow

  /** Writes `value` into the provided `writer`'s buffer in-place. Useful when the caller wants
    * to manage writer lifecycle explicitly (e.g. iterating with a single shared writer across
    * multiple serializers). The returned `UnsafeRow` shares the writer's buffer; the caller
    * owns reset semantics.
    */
  def writeTo(writer: UnsafeRowWriter, value: T): UnsafeRow

  /** Construct a writer sized for this serializer. Default initial buffer is 64 bytes; grows
    * automatically on first variable-length write.
    */
  def newWriter(): UnsafeRowWriter = new UnsafeRowWriter(fieldCount)

  /** Deserialize an `UnsafeRow` back to `T`. Each field is read via the right typed
    * `UnsafeRow` getter (`getLong` / `getInt` / `getUTF8String` / …) dispatched at compile
    * time — no megamorphic `row.get(i, dataType)` and no second-pass strategy lookup.
    */
  def deserialize(row: UnsafeRow): T

object UnsafeRowSerializer:

  /** Derive a serializer at compile time. The schema is computed via `ProtoEncoder.derived[T]`
    * (which uses `Mirror.Of[T]`); the read/write lambdas are emitted by a quoted macro that
    * builds a direct case-class constructor call for deserialize and a sequence of direct
    * `writer.write(i, value.field)` statements for serialize. No `Array[Any]` intermediates,
    * no primitive boxing — matches Spark's whole-stage codegen pattern.
    */
  inline def derived[T](using m: Mirror.ProductOf[T]): UnsafeRowSerializer[T] =
    val schema = ProtoEncoder.derived[T].schema
    derivedMacroEntry[T](schema)

  /** Internal entry point that the macro splices into. Kept package-public so the inline
    * expansion in `derived` can splice it from user code. */
  private inline def derivedMacroEntry[T](schema: ProtoSchema): UnsafeRowSerializer[T] =
    ${ UnsafeRowSerializerMacro.derivedImpl[T]('schema) }

  /** Public so the `inline def derived[T]` expansion in user code can instantiate it. Not part
    * of the stable API; consume the trait.
    */
  class UnsafeRowSerializerImpl[T](
      val schema: ProtoSchema,
      val fieldCount: Int,
      writeFn: (UnsafeRowWriter, T) => Unit,
      readFn: UnsafeRow => T
  ) extends UnsafeRowSerializer[T]:

    val sparkSchema: SparkStructType = SparkTypeMapping.toSparkStructType(schema)

    // Cached writer — mirrors Spark's UnsafeProjection pattern. Allocated once per serializer
    // instance; per-call `serialize()` resets and reuses. Plain instance field rather than
    // ThreadLocal: in per-task Spark execution there's one task per thread, so the extra
    // `ThreadLocal.get` lookup would be pure overhead. Callers sharing a serializer across
    // threads should construct their own writer and use `writeTo` instead.
    private val cachedWriter = new UnsafeRowWriter(fieldCount)

    def serialize(value: T): UnsafeRow =
      cachedWriter.reset()
      cachedWriter.zeroOutNullBytes()
      writeFn(cachedWriter, value)
      cachedWriter.getRow

    def writeTo(writer: UnsafeRowWriter, value: T): UnsafeRow =
      writer.reset()
      writer.zeroOutNullBytes()
      writeFn(writer, value)
      writer.getRow

    def deserialize(row: UnsafeRow): T = readFn(row)

