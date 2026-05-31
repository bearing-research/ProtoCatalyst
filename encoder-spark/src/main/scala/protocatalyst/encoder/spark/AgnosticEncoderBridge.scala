package protocatalyst.encoder.spark

import org.apache.spark.sql.catalyst.encoders.AgnosticEncoder
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.*
import org.apache.spark.sql.types.{DecimalType, Metadata}

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.types.ProtoType

/** Lowers the project's engine-independent [[ProtoEncoder]] (derived at compile time via
  * `Mirror`/`inline`, replacing Spark's reflective `ScalaReflection.encoderFor`) into Spark's own
  * `AgnosticEncoder`. From there `ExpressionEncoder(_)` and Spark's codegen take over unchanged —
  * so this bridge is the seam that lets Spark's typed-encoder pipeline run with **zero Scala
  * reflection / no `TypeTag`**.
  *
  * This is a plain recursive runtime function (per design doc D4): dispatch is on the
  * `ProtoType` shape plus `clsTag.runtimeClass` (which disambiguates leaves that normalize to the
  * same `ProtoType` — e.g. the unboxed/boxed split). Types `ProtoEncoder` supports but Spark
  * cannot represent are rejected here at invocation (D3).
  *
  * M1 scope: all leaf encoders (primitives unboxed+boxed, String, Binary, Scala/Java BigInt &
  * BigDecimal, Null, temporal: Date/LocalDate/Timestamp/Instant/LocalDateTime, Duration/Period,
  * CalendarInterval, Variant, Char/Varchar) + nested `Product`. Extensions Spark's `encoderFor`
  * rejects (UUID, OffsetDateTime, ZonedDateTime, java.util.Date, LocalTime/TimeType, Char-boxed)
  * are rejected here (D3); collections/Map/Option land in M2.
  */
object AgnosticEncoderBridge:

  /** Lower a `ProtoEncoder[T]` to the `AgnosticEncoder[T]` Spark would have derived. */
  def toAgnostic[T](enc: ProtoEncoder[T]): AgnosticEncoder[T] =
    lower(enc).asInstanceOf[AgnosticEncoder[T]]

  private def lower(enc: ProtoEncoder[?]): AgnosticEncoder[?] =
    val rc = enc.clsTag.runtimeClass
    enc.catalystType match
      case ProtoType.BooleanType =>
        if isUnboxed(enc) then PrimitiveBooleanEncoder else BoxedBooleanEncoder
      case ProtoType.ByteType =>
        if isUnboxed(enc) then PrimitiveByteEncoder else BoxedByteEncoder
      case ProtoType.ShortType =>
        if isUnboxed(enc) then PrimitiveShortEncoder else BoxedShortEncoder
      case ProtoType.IntegerType =>
        if isUnboxed(enc) then PrimitiveIntEncoder else BoxedIntEncoder
      case ProtoType.LongType =>
        if isUnboxed(enc) then PrimitiveLongEncoder else BoxedLongEncoder
      case ProtoType.FloatType =>
        if isUnboxed(enc) then PrimitiveFloatEncoder else BoxedFloatEncoder
      case ProtoType.DoubleType =>
        if isUnboxed(enc) then PrimitiveDoubleEncoder else BoxedDoubleEncoder

      case ProtoType.StringType =>
        // String normalizes here, as do extensions Spark lacks (UUID, boxed Character). clsTag splits them.
        if rc == classOf[String] then StringEncoder
        else reject(enc, "Spark has no String-backed encoder for this type (UUID/Character)")
      case ProtoType.BinaryType => BinaryEncoder
      case ProtoType.NullType   => NullEncoder

      case ProtoType.DecimalType(p, s) =>
        // BigInt/BigInteger normalize to DecimalType(38,0) too; clsTag disambiguates from BigDecimal.
        if rc == classOf[BigInt] then ScalaBigIntEncoder
        else if rc == classOf[java.math.BigInteger] then JavaBigIntEncoder
        else if rc == classOf[BigDecimal] then ScalaDecimalEncoder(DecimalType(p, s))
        else if rc == classOf[java.math.BigDecimal] then
          JavaDecimalEncoder(DecimalType(p, s), lenientSerialization = false)
        else reject(enc, s"unexpected runtime class for DecimalType($p,$s)")

      case ProtoType.DateType =>
        if rc == classOf[java.time.LocalDate] then LocalDateEncoder(lenientSerialization = false)
        else if rc == classOf[java.sql.Date] then DateEncoder(lenientSerialization = false)
        else reject(enc, "unexpected runtime class for DateType")
      case ProtoType.TimestampType =>
        if rc == classOf[java.time.Instant] then InstantEncoder(lenientSerialization = false)
        else if rc == classOf[java.sql.Timestamp] then TimestampEncoder(lenientSerialization = false)
        else reject(enc, "extension timestamp (OffsetDateTime/ZonedDateTime/java.util.Date) — M4 via TransformingEncoder")
      case ProtoType.TimestampNTZType    => LocalDateTimeEncoder
      case ProtoType.DayTimeIntervalType => DayTimeIntervalEncoder
      case ProtoType.YearMonthIntervalType => YearMonthIntervalEncoder
      case ProtoType.CalendarIntervalType  => CalendarIntervalEncoder
      case ProtoType.VariantType           => VariantEncoder
      case ProtoType.TimeType(_) =>
        reject(enc, "LocalTime/TimeType — Spark 4.1 has no AgnosticEncoder for it")
      case ProtoType.CharType(n)    => CharEncoder(n)
      case ProtoType.VarcharType(n) => VarcharEncoder(n)

      case ProtoType.StructType(_) =>
        // ProtoEncoder.fields carries the sub-encoders (catalystType.StructType only carries the
        // sub-DataTypes); traverse fields so nested clsTags survive. Field nullability comes from
        // the *lowered child's* `.nullable` — Spark's authoritative rule (`EncoderField(name, enc,
        // enc.nullable)`, where `enc.nullable = !isPrimitive`) — not ProtoEncoder's field flag,
        // which hardcodes `false` for non-primitive leaves like String.
        val encoderFields = enc.fields.map { fe =>
          val child = lower(fe.encoder)
          EncoderField(fe.name, child, child.nullable, Metadata.empty)
        }
        ProductEncoder(enc.clsTag, encoderFields, None)
      // ArrayType/MapType (collections) land in M2; SumType/UDTType/UnresolvedType are out of the
      // confirmed scope (sum types have no AgnosticEncoder; UDT is deferred).
      case other =>
        reject(enc, s"no lowering yet for ProtoType $other (M2: Array/Map/Option; UDT/Sum out of scope)")

  /** True when the encoder's runtime class is a JVM primitive (unboxed) — distinguishes
    * `PrimitiveXEncoder` from `BoxedXEncoder` for ProtoTypes that both normalize to (e.g.) IntegerType.
    */
  private def isUnboxed(enc: ProtoEncoder[?]): Boolean =
    enc.clsTag.runtimeClass.isPrimitive

  /** Runtime rejection (design doc D3): the bridge is a runtime function, so types `ProtoEncoder`
    * supports but Spark cannot represent are rejected here at invocation.
    */
  private def reject(enc: ProtoEncoder[?], why: String): Nothing =
    throw new IllegalArgumentException(
      s"AgnosticEncoderBridge: cannot lower ${enc.catalystType} " +
        s"(clsTag=${enc.clsTag.runtimeClass.getName}) — $why."
    )
