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

  /** Decode every column (back-compat). */
  def decode(input: Batch, schema: ProtoSchema): TypedColumns =
    decode(input, schema, (0 until schema.fields.size).toSet, Set.empty)

  def decode(input: Batch, schema: ProtoSchema, needed: Set[Int]): TypedColumns =
    decode(input, schema, needed, Set.empty)

  /** Decode only the columns in `needed` (the rest are left `null` and never read). Columns in
    * `asDouble` are decimal columns the query uses only as `double` (`Cast(.. AS DOUBLE)`): decode them
    * straight into a `double[]` instead of a `BigDecimal[]`, so the retained array is 8 bytes/cell and
    * the AST reads them via a TDoubleColumn. At scale this removes the per-cell BigDecimal that
    * otherwise dominates GC. */
  def decode(input: Batch, schema: ProtoSchema, needed: Set[Int], asDouble: Set[Int]): TypedColumns =
    val n = input.rowCount
    val numCols = schema.fields.size
    val data = new Array[Object](numCols)
    val nulls = new Array[Array[Boolean]](numCols)
    var c = 0
    while c < numCols do
      if needed.contains(c) then
        val vec = input.column(c)
        data(c) = if asDouble.contains(c) then decodeDecimalAsDouble(vec, n) else decodeColumn(vec, n)
        nulls(c) = nullMask(vec, n)
      c += 1
    new TypedColumns(data, nulls, n)

  /** A decimal column decoded as `double[]` (for columns used only as `double`). */
  private def decodeDecimalAsDouble(vec: FieldVector, n: Int): Array[Double] =
    vec match
      case v: DecimalVector =>
        Array.tabulate(n)(i => if v.isNull(i) then 0.0 else v.getObject(i).doubleValue)
      case other => // not actually a decimal (shouldn't happen) — fall back to a numeric read
        Array.tabulate(n)(i => if other.isNull(i) then 0.0 else other.getObject(i).asInstanceOf[Number].doubleValue)

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
      case v: TimeStampMicroTZVector =>
        Array.tabulate(n)(i => if v.isNull(i) then 0L else v.get(i))
      case v: TimeStampMicroVector =>
        Array.tabulate(n)(i => if v.isNull(i) then 0L else v.get(i))
      case v: Float8Vector   => Array.tabulate(n)(i => if v.isNull(i) then 0.0 else v.get(i))
      case v: Float4Vector   => Array.tabulate(n)(i => if v.isNull(i) then 0.0 else v.get(i).toDouble)
      case v: DecimalVector  =>
        Array.tabulate(n)(i => if v.isNull(i) then null else v.getObject(i))
      case v: VarCharVector =>
        Array.tabulate(n)(i =>
          if v.isNull(i) then null
          else new String(v.get(i), java.nio.charset.StandardCharsets.UTF_8)
        )
      case _ => new Array[Long](n)
