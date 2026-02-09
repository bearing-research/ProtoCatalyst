package protocatalyst.executor.exec

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.expr.ProtoExpr
import protocatalyst.schema.ProtoSchema
import protocatalyst.types._

/** Wrapper around Arrow VectorSchemaRoot with ProtoSchema metadata.
  *
  * Represents a columnar batch of rows — the unit of data flow between operators. All operators
  * consume and produce Batch instances.
  */
final class Batch private (
    val root: VectorSchemaRoot,
    val schema: ProtoSchema,
    val rowCount: Int
):
  /** Get a column vector by field name. */
  def column(name: String): FieldVector =
    val idx = schema.fields.indexWhere(_.name.equalsIgnoreCase(name))
    if idx < 0 then throw ExecutionException(s"Column not found: $name")
    root.getVector(idx)

  /** Get a column vector by ordinal index. */
  def column(index: Int): FieldVector =
    if index < 0 || index >= root.getFieldVectors.size then
      throw ExecutionException(
        s"Column index out of bounds: $index (size: ${root.getFieldVectors.size})"
      )
    root.getVector(index)

  /** Number of columns in this batch. */
  def numColumns: Int = schema.fields.size

  def isEmpty: Boolean = rowCount == 0

  /** Close the underlying Arrow resources. */
  def close(): Unit = root.close()

object Batch:

  /** Create a Batch from an existing VectorSchemaRoot. */
  def fromRoot(root: VectorSchemaRoot, schema: ProtoSchema): Batch =
    new Batch(root, schema, root.getRowCount)

  /** Create an empty batch with the given schema. */
  def empty(schema: ProtoSchema, allocator: BufferAllocator): Batch =
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.setRowCount(0)
    new Batch(root, schema, 0)

  /** Create a batch from literal value rows (for VALUES clause). */
  def fromValues(
      rows: Vector[Vector[ProtoExpr]],
      schema: ProtoSchema,
      allocator: BufferAllocator
  ): Batch =
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    for (row, rowIdx) <- rows.zipWithIndex do
      for (expr, colIdx) <- row.zipWithIndex do
        expr match
          case ProtoExpr.Literal(lit) =>
            setLiteralValue(root.getVector(colIdx), rowIdx, lit)
          case other =>
            throw ExecutionException(s"VALUES clause expects literals, got: $other")

    root.setRowCount(rows.size)
    new Batch(root, schema, rows.size)

  /** Write a LiteralValue into an Arrow vector at the given row index. */
  private[exec] def setLiteralValue(vec: FieldVector, row: Int, lit: LiteralValue): Unit =
    lit match
      case LiteralValue.BooleanValue(v) =>
        vec.asInstanceOf[BitVector].setSafe(row, if v then 1 else 0)
      case LiteralValue.ByteValue(v) =>
        vec.asInstanceOf[TinyIntVector].setSafe(row, v)
      case LiteralValue.ShortValue(v) =>
        vec.asInstanceOf[SmallIntVector].setSafe(row, v)
      case LiteralValue.IntValue(v) =>
        vec.asInstanceOf[IntVector].setSafe(row, v)
      case LiteralValue.LongValue(v) =>
        vec.asInstanceOf[BigIntVector].setSafe(row, v)
      case LiteralValue.FloatValue(v) =>
        vec.asInstanceOf[Float4Vector].setSafe(row, v)
      case LiteralValue.DoubleValue(v) =>
        vec.asInstanceOf[Float8Vector].setSafe(row, v)
      case LiteralValue.StringValue(v) =>
        val bytes = v.getBytes("UTF-8")
        vec.asInstanceOf[VarCharVector].setSafe(row, bytes, 0, bytes.length)
      case LiteralValue.BinaryValue(v) =>
        val arr = v.unsafeArray.asInstanceOf[Array[Byte]]
        vec.asInstanceOf[VarBinaryVector].setSafe(row, arr, 0, arr.length)
      case LiteralValue.DecimalValue(v) =>
        vec.asInstanceOf[DecimalVector].setSafe(row, v.bigDecimal)
      case LiteralValue.DateValue(epochDays) =>
        vec.asInstanceOf[DateDayVector].setSafe(row, epochDays)
      case LiteralValue.TimestampValue(epochMicros) =>
        vec.asInstanceOf[TimeStampMicroTZVector].setSafe(row, epochMicros)
      case LiteralValue.TimeValue(micros) =>
        vec.asInstanceOf[TimeMicroVector].setSafe(row, micros)
      case LiteralValue.CalendarIntervalValue(months, days, micros) =>
        vec.asInstanceOf[IntervalMonthDayNanoVector].setSafe(row, months, days, micros * 1000)
      case LiteralValue.NullValue(_) =>
        setNull(vec, row)

  /** Set a null value in any vector type. */
  private[executor] def setNull(vec: FieldVector, row: Int): Unit =
    vec match
      case v: BitVector              => v.setNull(row)
      case v: TinyIntVector          => v.setNull(row)
      case v: SmallIntVector         => v.setNull(row)
      case v: IntVector              => v.setNull(row)
      case v: BigIntVector           => v.setNull(row)
      case v: Float4Vector           => v.setNull(row)
      case v: Float8Vector           => v.setNull(row)
      case v: VarCharVector          => v.setNull(row)
      case v: VarBinaryVector        => v.setNull(row)
      case v: DecimalVector          => v.setNull(row)
      case v: DateDayVector          => v.setNull(row)
      case v: TimeStampMicroTZVector => v.setNull(row)
      case v: TimeMicroVector        => v.setNull(row)
      case _                         => vec.setNull(row)

  /** Read a scalar value from an Arrow vector at the given row index. */
  private[executor] def getValue(vec: FieldVector, row: Int): Any =
    if vec.isNull(row) then null
    else
      vec match
        case v: BitVector              => v.get(row) != 0
        case v: TinyIntVector          => v.get(row).toByte
        case v: SmallIntVector         => v.get(row).toShort
        case v: IntVector              => v.get(row)
        case v: BigIntVector           => v.get(row)
        case v: Float4Vector           => v.get(row)
        case v: Float8Vector           => v.get(row)
        case v: VarCharVector          => new String(v.get(row), "UTF-8")
        case v: VarBinaryVector        => v.get(row)
        case v: DecimalVector          => v.getObject(row)
        case v: DateDayVector          => v.get(row)
        case v: TimeStampMicroTZVector => v.get(row)
        case v: TimeMicroVector        => v.get(row)
        case _                         => vec.getObject(row)
