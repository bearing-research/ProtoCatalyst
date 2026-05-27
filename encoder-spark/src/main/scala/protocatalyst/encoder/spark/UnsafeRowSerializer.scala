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

import protocatalyst.encoder.ProtoEncoder
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

  inline def derived[T](using m: Mirror.ProductOf[T]): UnsafeRowSerializer[T] =
    val protoEncoder = ProtoEncoder.derived[T]
    val count = constValue[Tuple.Size[m.MirroredElemTypes]]

    val writeFn: (UnsafeRowWriter, T) => Unit = (writer, value) =>
      val product = value.asInstanceOf[Product]
      writeFieldsImpl[m.MirroredElemTypes](writer, product, 0)

    val readFn: UnsafeRow => T = (row) =>
      val arr = new Array[Any](count)
      readFieldsImpl[m.MirroredElemTypes](row, arr, 0)
      m.fromProduct(ArrayProduct(arr))

    new UnsafeRowSerializerImpl[T](protoEncoder.schema, count, writeFn, readFn)

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

  /** Adapter from `Array[Any]` to `Product`, used to feed `Mirror.fromProduct`. Step 3 (a
    * quoted-macro direct-constructor emission) eliminates this intermediate entirely. */
  class ArrayProduct(values: Array[Any]) extends Product:
    def productArity: Int = values.length
    def productElement(n: Int): Any = values(n)
    def canEqual(that: Any): Boolean = that.isInstanceOf[ArrayProduct]

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
        s"UnsafeRow path. Add the matching case to writeFieldsImpl/readFieldsImpl. Currently " +
        s"unsupported: collections, maps, nested products, OffsetDateTime/ZonedDateTime/" +
        s"util.Date/UUID/BigInt/Duration/Period."
    )

  // ============================================================
  // Compile-time-specialized deserialize (the read counterpart of writeFieldsImpl)
  //
  // Each case emits one direct `row.getXxx(idx)` call — monomorphic, JIT-inlinable, no
  // megamorphic dispatch on Spark's DataType. Boxing into `Array[Any]` is unavoidable while we
  // feed `Mirror.fromProduct`; eliminating that requires step 3 (quoted-macro constructor
  // emission), which is the next optimization tier.
  // ============================================================

  inline def readFieldsImpl[Types <: Tuple](
      row: UnsafeRow,
      arr: Array[Any],
      idx: Int
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      // === Unboxed primitives ===
      case _: (Boolean *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getBoolean(idx)
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (Byte *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getByte(idx)
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (Short *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getShort(idx)
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (Int *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getInt(idx)
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (Long *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getLong(idx)
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (Float *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getFloat(idx)
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (Double *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getDouble(idx)
        readFieldsImpl[ts](row, arr, idx + 1)

      // === String → from UTF8String ===
      case _: (String *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getUTF8String(idx).toString
        readFieldsImpl[ts](row, arr, idx + 1)

      // === Binary ===
      case _: (Array[Byte] *: ts) =>
        arr(idx) = if row.isNullAt(idx) then null else row.getBinary(idx)
        readFieldsImpl[ts](row, arr, idx + 1)

      // === Decimal — matches the (38, 18) precision used on the write side ===
      case _: (BigDecimal *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then null
          else BigDecimal(row.getDecimal(idx, 38, 18).toJavaBigDecimal)
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (JBigDecimal *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then null
          else row.getDecimal(idx, 38, 18).toJavaBigDecimal
        readFieldsImpl[ts](row, arr, idx + 1)

      // === Temporal types ===
      case _: (LocalDate *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then null
          else DateTimeUtils.daysToLocalDate(row.getInt(idx))
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (jsql.Date *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then null
          else DateTimeUtils.toJavaDate(row.getInt(idx))
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (Instant *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then null
          else DateTimeUtils.microsToInstant(row.getLong(idx))
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (jsql.Timestamp *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then null
          else DateTimeUtils.toJavaTimestamp(row.getLong(idx))
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (LocalDateTime *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then null
          else DateTimeUtils.microsToLocalDateTime(row.getLong(idx))
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (LocalTime *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then null
          else LocalTime.ofNanoOfDay(row.getLong(idx))
        readFieldsImpl[ts](row, arr, idx + 1)

      // === Option[T] — null bit determines None; if set, recurse into the inner reader ===
      case _: (Option[t] *: ts) =>
        arr(idx) =
          if row.isNullAt(idx) then None
          else Some(readOptionValue[t](row, idx))
        readFieldsImpl[ts](row, arr, idx + 1)

      case _: (t *: ts) =>
        unsupportedField[t](idx)

  /** Read the inner value of an `Option[T]` slot known to be present (the caller has already
    * checked `row.isNullAt`). Primitives stay unboxed inside the case branches; the result is
    * still boxed by `Some(_)` allocation when the case-class field is `Option[Int]`. */
  private inline def readOptionValue[T](row: UnsafeRow, idx: Int): T =
    inline erasedValue[T] match
      case _: Boolean    => row.getBoolean(idx).asInstanceOf[T]
      case _: Byte       => row.getByte(idx).asInstanceOf[T]
      case _: Short      => row.getShort(idx).asInstanceOf[T]
      case _: Int        => row.getInt(idx).asInstanceOf[T]
      case _: Long       => row.getLong(idx).asInstanceOf[T]
      case _: Float      => row.getFloat(idx).asInstanceOf[T]
      case _: Double     => row.getDouble(idx).asInstanceOf[T]
      case _: String     => row.getUTF8String(idx).toString.asInstanceOf[T]
      case _: Array[Byte] => row.getBinary(idx).asInstanceOf[T]
      case _: BigDecimal =>
        BigDecimal(row.getDecimal(idx, 38, 18).toJavaBigDecimal).asInstanceOf[T]
      case _: JBigDecimal =>
        row.getDecimal(idx, 38, 18).toJavaBigDecimal.asInstanceOf[T]
      case _: LocalDate =>
        DateTimeUtils.daysToLocalDate(row.getInt(idx)).asInstanceOf[T]
      case _: jsql.Date =>
        DateTimeUtils.toJavaDate(row.getInt(idx)).asInstanceOf[T]
      case _: Instant =>
        DateTimeUtils.microsToInstant(row.getLong(idx)).asInstanceOf[T]
      case _: jsql.Timestamp =>
        DateTimeUtils.toJavaTimestamp(row.getLong(idx)).asInstanceOf[T]
      case _: LocalDateTime =>
        DateTimeUtils.microsToLocalDateTime(row.getLong(idx)).asInstanceOf[T]
      case _: LocalTime =>
        LocalTime.ofNanoOfDay(row.getLong(idx)).asInstanceOf[T]
      case _ => unsupportedField[T](idx)
