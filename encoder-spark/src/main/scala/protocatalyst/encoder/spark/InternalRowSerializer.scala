package protocatalyst.encoder.spark

import scala.deriving.Mirror

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow
import org.apache.spark.sql.types.{StructType => SparkStructType}

import protocatalyst.encoder.{InlineRowSerializer, InternalTypeConverter, ProtoEncoder}
import protocatalyst.schema.ProtoSchema

/** Compile-time-derived serializer that targets Spark's `InternalRow` directly — the same
  * external contract `ExpressionEncoder[T].createSerializer()` satisfies.
  *
  * Implemented by composing the existing `InlineRowSerializer.derived[T]` (which inlines all
  * per-field dispatch at compile time and writes into `Array[Any]`) with a Spark-targeted
  * `InternalTypeConverter`. The resulting `Array[Any]` is wrapped in `GenericInternalRow`. No
  * runtime reflection is involved on the ProtoCatalyst side; the only runtime work is the
  * Spark-typed conversions inside the converter (UTF8String, Decimal, epoch micros, …).
  *
  * This is the Phase A apples-to-apples comparison target. A subsequent `UnsafeRowSerializer`
  * (Phase A.2) will target `UnsafeRow` byte layout for byte-identical comparison with Spark's
  * whole-stage codegen output.
  */
trait InternalRowSerializer[T]:
  /** The schema this serializer writes / reads. */
  def schema: ProtoSchema

  /** Spark's view of the same schema, useful for `InternalRow.get(i, dataType)` callers. */
  def sparkSchema: SparkStructType

  /** Number of top-level fields. */
  def fieldCount: Int

  /** Serialize a value to an `InternalRow` (concretely `GenericInternalRow`). */
  def serialize(value: T): InternalRow

  /** Deserialize an `InternalRow` back to `T`. */
  def deserialize(row: InternalRow): T

object InternalRowSerializer:

  /** Derive an `InternalRowSerializer[T]` at compile time. */
  inline def derived[T](using m: Mirror.ProductOf[T]): InternalRowSerializer[T] =
    val inner = InlineRowSerializer.derived[T]
    val protoEncoder = ProtoEncoder.derived[T]
    new InternalRowSerializerImpl[T](inner, protoEncoder.schema)

  /** Public so the `inline def derived[T]` expansion in user code can instantiate it. Not part
    * of the stable API; use the trait. */
  class InternalRowSerializerImpl[T](
      inner: InlineRowSerializer[T],
      val schema: ProtoSchema
  ) extends InternalRowSerializer[T]:

    private given InternalTypeConverter = SparkInternalTypeConverter

    val sparkSchema: SparkStructType = SparkTypeMapping.toSparkStructType(schema)
    val fieldCount: Int = inner.fieldCount

    def serialize(value: T): InternalRow =
      val arr = inner.serialize(value)
      new GenericInternalRow(arr)

    def deserialize(row: InternalRow): T =
      val arr = new Array[Any](fieldCount)
      var i = 0
      while i < fieldCount do
        if row.isNullAt(i) then arr(i) = null
        else arr(i) = row.get(i, sparkSchema.fields(i).dataType)
        i += 1
      inner.deserialize(arr)
