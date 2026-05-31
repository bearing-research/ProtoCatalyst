package protocatalyst.encoder.spark

import org.apache.spark.sql.catalyst.encoders.AgnosticEncoder
import org.apache.spark.sql.catalyst.encoders.AgnosticEncoders.*
import org.apache.spark.sql.types.Metadata

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
  * M0 scope: the seven primitives (unboxed + boxed) + `String` + nested `Product` (`StructType`).
  * Collections/Map/Option/Decimal/temporal/enum/UDT land in M1–M4.
  */
object AgnosticEncoderBridge:

  /** Lower a `ProtoEncoder[T]` to the `AgnosticEncoder[T]` Spark would have derived. */
  def toAgnostic[T](enc: ProtoEncoder[T]): AgnosticEncoder[T] =
    lower(enc).asInstanceOf[AgnosticEncoder[T]]

  private def lower(enc: ProtoEncoder[?]): AgnosticEncoder[?] =
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
        StringEncoder
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
      case other =>
        throw new IllegalArgumentException(
          s"AgnosticEncoderBridge (M0): no lowering yet for ProtoType $other " +
            s"(clsTag=${enc.clsTag.runtimeClass.getName}). Supported in M0: primitives, String, Product."
        )

  /** True when the encoder's runtime class is a JVM primitive (unboxed) — distinguishes
    * `PrimitiveXEncoder` from `BoxedXEncoder` for ProtoTypes that both normalize to (e.g.) IntegerType.
    */
  private def isUnboxed(enc: ProtoEncoder[?]): Boolean =
    enc.clsTag.runtimeClass.isPrimitive
