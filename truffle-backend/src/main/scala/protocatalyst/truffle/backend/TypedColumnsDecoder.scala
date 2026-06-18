package protocatalyst.truffle.backend

import org.apache.arrow.vector.*

import protocatalyst.executor.exec.Batch
import protocatalyst.schema.ProtoSchema
import protocatalyst.truffle.typed.TypedColumns

/** Decode an Arrow [[Batch]] into the typed, nullable column model the Layer-1 nodes read. Integer
  * and date-like vectors become `long[]`, floating/decimal become `double[]`, and each column's
  * validity bitmap becomes a null mask (`null` when the column has no nulls). The value stored for a
  * null cell is irrelevant — the typed nodes check the mask before reading.
  */
object TypedColumnsDecoder:

  def decode(input: Batch, schema: ProtoSchema): TypedColumns =
    val n = input.rowCount
    val numCols = schema.fields.size
    val data = new Array[Object](numCols)
    val nulls = new Array[Array[Boolean]](numCols)
    var c = 0
    while c < numCols do
      val vec = input.column(c)
      data(c) = decodeColumn(vec, n)
      nulls(c) = nullMask(vec, n)
      c += 1
    new TypedColumns(data, nulls, n)

  private def nullMask(vec: FieldVector, n: Int): Array[Boolean] =
    if vec.getNullCount == 0 then null
    else Array.tabulate(n)(i => vec.isNull(i))

  // Arrow's primitive get() throws on a null slot, so every read is guarded; the value stored for a
  // null cell is a harmless placeholder the typed nodes never read (they consult the null mask first).
  private def decodeColumn(vec: FieldVector, n: Int): Object =
    vec match
      case v: BigIntVector   => Array.tabulate(n)(i => if v.isNull(i) then 0L else v.get(i))
      case v: IntVector      => Array.tabulate(n)(i => if v.isNull(i) then 0L else v.get(i).toLong)
      case v: SmallIntVector => Array.tabulate(n)(i => if v.isNull(i) then 0L else v.get(i).toLong)
      case v: TinyIntVector  => Array.tabulate(n)(i => if v.isNull(i) then 0L else v.get(i).toLong)
      case v: DateDayVector  => Array.tabulate(n)(i => if v.isNull(i) then 0L else v.get(i).toLong)
      case v: Float8Vector   => Array.tabulate(n)(i => if v.isNull(i) then 0.0 else v.get(i))
      case v: Float4Vector   => Array.tabulate(n)(i => if v.isNull(i) then 0.0 else v.get(i).toDouble)
      case v: DecimalVector  =>
        Array.tabulate(n)(i => if v.isNull(i) then 0.0 else v.getObject(i).doubleValue)
      case _ => new Array[Long](n)
