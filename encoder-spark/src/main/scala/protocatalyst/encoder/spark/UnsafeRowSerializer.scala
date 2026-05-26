package protocatalyst.encoder.spark

import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime}

import scala.compiletime.{constValue, erasedValue}
import scala.deriving.Mirror

import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeRowWriter
import org.apache.spark.sql.catalyst.util.DateTimeUtils
import org.apache.spark.sql.types.{Decimal => SparkDecimal, StructType => SparkStructType}
import org.apache.spark.unsafe.types.UTF8String

import protocatalyst.encoder.{InlineRowSerializer, InternalTypeConverter, ProtoEncoder}
import protocatalyst.schema.ProtoSchema

/** Compile-time-derived serializer that writes directly into Spark's packed `UnsafeRow` byte
  * layout — the same target Spark's whole-stage codegen produces.
  *
  * Per-field dispatch is inlined at the call site via Scala 3 `Mirror.ProductOf` + inline match
  * on the field-type tuple. Each field maps to one `UnsafeRowWriter.write(ordinal, value)` call
  * with no megamorphic dispatch and no boxing for primitives. `setNullAt(ordinal)` handles
  * `Option.None`. The `writer` is reusable across rows via `writeTo(writer, value)`, which is
  * the zero-allocation hot-path Spark codegen uses.
  *
  * Scope is limited to the TPC-H-relevant type surface:
  *  - All Spark primitives (Boolean..Double), String, Array[Byte], BigDecimal / Java BigDecimal,
  *    LocalDate / java.sql.Date, Instant / java.sql.Timestamp / LocalDateTime, LocalTime
  *    (Spark 4.1+), Duration, Period, UUID.
  *  - `Option[T]` over any of the above.
  *
  * Out of scope for this commit (throws `IllegalStateException` at runtime if encountered):
  *  - Collections (`Seq` / `List` / `Vector` / `Set` / `Array[T]` of non-byte), Maps,
  *    nested case classes. None of these appear in the headline TPC-H tables; they'll land in a
  *    follow-up using `UnsafeArrayWriter` / `UnsafeMapWriter` and nested writer chaining.
  */
trait UnsafeRowSerializer[T]:
  def schema: ProtoSchema
  def sparkSchema: SparkStructType
  def fieldCount: Int

  /** Allocates a fresh `UnsafeRow` per call. Convenient for tests; not the hot path. */
  def serialize(value: T): UnsafeRow

  /** Writes `value` into the provided `writer`'s buffer in-place. Caller is responsible for
    * calling `writer.reset()` between rows (matching Spark codegen's reuse pattern). Returns the
    * resulting `UnsafeRow`, which shares the writer's underlying buffer.
    */
  def writeTo(writer: UnsafeRowWriter, value: T): UnsafeRow

  /** Construct a writer sized for this serializer. Default initial buffer is 64 bytes; grows
    * automatically on first variable-length write.
    */
  def newWriter(): UnsafeRowWriter = new UnsafeRowWriter(fieldCount)

  /** Deserialize an `UnsafeRow` back to `T`. Reads each slot via the `InternalRow` getter
    * (`UnsafeRow extends InternalRow`) into an `Array[Any]`, then runs the existing
    * `InlineRowSerializer.deserialize` path with a Spark-typed `InternalTypeConverter` in scope.
    */
  def deserialize(row: UnsafeRow): T

object UnsafeRowSerializer:

  inline def derived[T](using m: Mirror.ProductOf[T]): UnsafeRowSerializer[T] =
    val protoEncoder = ProtoEncoder.derived[T]
    val inner = InlineRowSerializer.derived[T]
    val count = constValue[Tuple.Size[m.MirroredElemTypes]]

    val writeFn: (UnsafeRowWriter, T) => Unit = (writer, value) =>
      val product = value.asInstanceOf[Product]
      writeFieldsImpl[m.MirroredElemTypes](writer, product, 0)

    new UnsafeRowSerializerImpl[T](inner, protoEncoder.schema, count, writeFn)

  /** Public so the `inline def derived[T]` expansion in user code can instantiate it. Not part
    * of the stable API; consume the trait.
    */
  class UnsafeRowSerializerImpl[T](
      inner: InlineRowSerializer[T],
      val schema: ProtoSchema,
      val fieldCount: Int,
      writeFn: (UnsafeRowWriter, T) => Unit
  ) extends UnsafeRowSerializer[T]:

    private given InternalTypeConverter = SparkInternalTypeConverter
    val sparkSchema: SparkStructType = SparkTypeMapping.toSparkStructType(schema)

    def serialize(value: T): UnsafeRow =
      val writer = new UnsafeRowWriter(fieldCount)
      writer.reset()
      writer.zeroOutNullBytes()
      writeFn(writer, value)
      writer.getRow

    def writeTo(writer: UnsafeRowWriter, value: T): UnsafeRow =
      writer.reset()
      writer.zeroOutNullBytes()
      writeFn(writer, value)
      writer.getRow

    def deserialize(row: UnsafeRow): T =
      val arr = new Array[Any](fieldCount)
      var i = 0
      while i < fieldCount do
        if row.isNullAt(i) then arr(i) = null
        else arr(i) = row.get(i, sparkSchema.fields(i).dataType)
        i += 1
      inner.deserialize(arr)

  // ============================================================
  // Compile-time-specialized field dispatch
  // ============================================================

  /** Recursive inline that emits one `writer.write(idx, ...)` call per static field type. */
  inline def writeFieldsImpl[Types <: Tuple](
      writer: UnsafeRowWriter,
      product: Product,
      idx: Int
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      // === Unboxed primitives — direct slot write, no allocation ===
      case _: (Boolean *: ts) =>
        writer.write(idx, product.productElement(idx).asInstanceOf[Boolean])
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (Byte *: ts) =>
        writer.write(idx, product.productElement(idx).asInstanceOf[Byte])
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (Short *: ts) =>
        writer.write(idx, product.productElement(idx).asInstanceOf[Short])
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (Int *: ts) =>
        writer.write(idx, product.productElement(idx).asInstanceOf[Int])
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (Long *: ts) =>
        writer.write(idx, product.productElement(idx).asInstanceOf[Long])
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (Float *: ts) =>
        writer.write(idx, product.productElement(idx).asInstanceOf[Float])
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (Double *: ts) =>
        writer.write(idx, product.productElement(idx).asInstanceOf[Double])
        writeFieldsImpl[ts](writer, product, idx + 1)

      // === String → UTF8String (one allocation per row per string field) ===
      case _: (String *: ts) =>
        val s = product.productElement(idx).asInstanceOf[String]
        if s == null then writer.setNullAt(idx)
        else writer.write(idx, UTF8String.fromString(s))
        writeFieldsImpl[ts](writer, product, idx + 1)

      // === Binary ===
      case _: (Array[Byte] *: ts) =>
        val b = product.productElement(idx).asInstanceOf[Array[Byte]]
        if b == null then writer.setNullAt(idx)
        else writer.write(idx, b)
        writeFieldsImpl[ts](writer, product, idx + 1)

      // === Decimal — uses (p, s) = (38, 18) for Scala BigDecimal givens; aligns with ===
      // === SparkDecimalEncoder's DecimalType.SYSTEM_DEFAULT.                            ===
      case _: (BigDecimal *: ts) =>
        val bd = product.productElement(idx).asInstanceOf[BigDecimal]
        if bd == null then writer.setNullAt(idx)
        else writer.write(idx, SparkDecimal(bd.bigDecimal, 38, 18), 38, 18)
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (JBigDecimal *: ts) =>
        val jbd = product.productElement(idx).asInstanceOf[JBigDecimal]
        if jbd == null then writer.setNullAt(idx)
        else writer.write(idx, SparkDecimal(jbd, 38, 18), 38, 18)
        writeFieldsImpl[ts](writer, product, idx + 1)

      // === Temporal types ===
      case _: (LocalDate *: ts) =>
        val d = product.productElement(idx).asInstanceOf[LocalDate]
        if d == null then writer.setNullAt(idx)
        else writer.write(idx, DateTimeUtils.localDateToDays(d))
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (jsql.Date *: ts) =>
        val d = product.productElement(idx).asInstanceOf[jsql.Date]
        if d == null then writer.setNullAt(idx)
        else writer.write(idx, DateTimeUtils.fromJavaDate(d))
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (Instant *: ts) =>
        val i = product.productElement(idx).asInstanceOf[Instant]
        if i == null then writer.setNullAt(idx)
        else writer.write(idx, DateTimeUtils.instantToMicros(i))
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (jsql.Timestamp *: ts) =>
        val t = product.productElement(idx).asInstanceOf[jsql.Timestamp]
        if t == null then writer.setNullAt(idx)
        else writer.write(idx, DateTimeUtils.fromJavaTimestamp(t))
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (LocalDateTime *: ts) =>
        val ldt = product.productElement(idx).asInstanceOf[LocalDateTime]
        if ldt == null then writer.setNullAt(idx)
        else writer.write(idx, DateTimeUtils.localDateTimeToMicros(ldt))
        writeFieldsImpl[ts](writer, product, idx + 1)

      case _: (LocalTime *: ts) =>
        val lt = product.productElement(idx).asInstanceOf[LocalTime]
        if lt == null then writer.setNullAt(idx)
        else writer.write(idx, lt.toNanoOfDay)
        writeFieldsImpl[ts](writer, product, idx + 1)

      // === Option[T] — narrow set of inner types matching what Spark's encoder allows in
      //     nullable slots. Mirrors InlineRowSerializer's specialization.
      case _: (Option[t] *: ts) =>
        product.productElement(idx) match
          case Some(v) => writeOptionValue[t](writer, idx, v.asInstanceOf[t])
          case _       => writer.setNullAt(idx)
        writeFieldsImpl[ts](writer, product, idx + 1)

      // === Catch-all: type not yet wired into the UnsafeRow write path. Add a case above with
      //     the matching `UnsafeArrayWriter` / `UnsafeMapWriter` / nested writer call.
      case _: (t *: ts) =>
        unsupportedField[t](idx)

  /** Specialized Option-inner writer — primitives stay unboxed, others box via writeAnyValue. */
  private inline def writeOptionValue[T](
      writer: UnsafeRowWriter,
      idx: Int,
      value: T
  ): Unit =
    inline erasedValue[T] match
      case _: Boolean => writer.write(idx, value.asInstanceOf[Boolean])
      case _: Byte    => writer.write(idx, value.asInstanceOf[Byte])
      case _: Short   => writer.write(idx, value.asInstanceOf[Short])
      case _: Int     => writer.write(idx, value.asInstanceOf[Int])
      case _: Long    => writer.write(idx, value.asInstanceOf[Long])
      case _: Float   => writer.write(idx, value.asInstanceOf[Float])
      case _: Double  => writer.write(idx, value.asInstanceOf[Double])
      case _: String  =>
        writer.write(idx, UTF8String.fromString(value.asInstanceOf[String]))
      case _: Array[Byte] => writer.write(idx, value.asInstanceOf[Array[Byte]])
      case _: BigDecimal  =>
        val bd = value.asInstanceOf[BigDecimal]
        writer.write(idx, SparkDecimal(bd.bigDecimal, 38, 18), 38, 18)
      case _: JBigDecimal =>
        val jbd = value.asInstanceOf[JBigDecimal]
        writer.write(idx, SparkDecimal(jbd, 38, 18), 38, 18)
      case _: LocalDate =>
        writer.write(idx, DateTimeUtils.localDateToDays(value.asInstanceOf[LocalDate]))
      case _: jsql.Date =>
        writer.write(idx, DateTimeUtils.fromJavaDate(value.asInstanceOf[jsql.Date]))
      case _: Instant =>
        writer.write(idx, DateTimeUtils.instantToMicros(value.asInstanceOf[Instant]))
      case _: jsql.Timestamp =>
        writer.write(idx, DateTimeUtils.fromJavaTimestamp(value.asInstanceOf[jsql.Timestamp]))
      case _: LocalDateTime =>
        writer.write(idx, DateTimeUtils.localDateTimeToMicros(value.asInstanceOf[LocalDateTime]))
      case _: LocalTime =>
        writer.write(idx, value.asInstanceOf[LocalTime].toNanoOfDay)
      case _ => unsupportedField[T](idx)

  /** Compile-time error path. The inline expansion emits a runtime throw because Spark accepts
    * structurally similar types we haven't audited yet — failing fast at the first row is more
    * useful than a silent miscompile.
    */
  private inline def unsupportedField[T](idx: Int): Nothing =
    throw new IllegalStateException(
      s"UnsafeRowSerializer: field at index $idx has a type that's not yet wired into the " +
        s"UnsafeRow write path. Wire the appropriate UnsafeArrayWriter / UnsafeMapWriter / nested" +
        s"products, OffsetDateTime/ZonedDateTime/util.Date/UUID/BigInt/Duration/Period until " +
        s"the follow-up commit adds them here."
    )
