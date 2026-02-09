package protocatalyst.executor.exec.operators

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector._

import protocatalyst.arrow.ArrowSchemaConverter
import protocatalyst.executor.exec._
import protocatalyst.plan._

/** Sort operator: reorders rows based on sort key expressions. */
object SortOp:

  def execute(
      input: Batch,
      order: Vector[SortOrder],
      evaluator: ExprEvaluator,
      allocator: BufferAllocator
  ): Batch =
    if input.rowCount <= 1 then return input

    // Evaluate sort key expressions
    val sortKeys = order.map { so =>
      val vec = evaluator.eval(so.child, input)
      (vec, so.direction, so.nullOrdering)
    }

    // Build index array and sort
    val indices = (0 until input.rowCount).toArray
    val boxed = indices.map(Integer.valueOf)
    java.util.Arrays.sort(
      boxed,
      (a: Integer, b: Integer) => compareRows(a.intValue, b.intValue, sortKeys)
    )
    val sortedIndices = boxed.map(_.intValue)

    // Reorder all columns by sorted indices
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(input.schema)
    val root = VectorSchemaRoot.create(arrowSchema, allocator)
    root.allocateNew()

    for (srcIdx, dstIdx) <- sortedIndices.zipWithIndex do
      for colIdx <- 0 until input.numColumns do
        evaluator.copyValue(
          input.root.getVector(colIdx),
          srcIdx,
          root.getVector(colIdx),
          dstIdx
        )

    root.setRowCount(input.rowCount)
    Batch.fromRoot(root, input.schema)

  private[executor] def compareRows(
      a: Int,
      b: Int,
      sortKeys: Vector[(FieldVector, SortDirection, NullOrdering)]
  ): Int =
    import scala.util.boundary, boundary.break
    boundary:
      for (vec, direction, nullOrdering) <- sortKeys do
        val aNull = vec.isNull(a)
        val bNull = vec.isNull(b)

        val cmp =
          if aNull && bNull then 0
          else if aNull then
            nullOrdering match
              case NullOrdering.NullsFirst => -1
              case NullOrdering.NullsLast  => 1
          else if bNull then
            nullOrdering match
              case NullOrdering.NullsFirst => 1
              case NullOrdering.NullsLast  => -1
          else
            val aVal = Batch.getValue(vec, a)
            val bVal = Batch.getValue(vec, b)
            compareValues(aVal, bVal)

        val directed = direction match
          case SortDirection.Ascending  => cmp
          case SortDirection.Descending => -cmp

        if directed != 0 then break(directed)
      0

  private[executor] def compareValues(a: Any, b: Any): Int = (a, b) match
    case (a: Boolean, b: Boolean)                           => java.lang.Boolean.compare(a, b)
    case (a: Byte, b: Byte)                                 => java.lang.Byte.compare(a, b)
    case (a: Short, b: Short)                               => java.lang.Short.compare(a, b)
    case (a: Int, b: Int)                                   => java.lang.Integer.compare(a, b)
    case (a: Long, b: Long)                                 => java.lang.Long.compare(a, b)
    case (a: Float, b: Float)                               => java.lang.Float.compare(a, b)
    case (a: Double, b: Double)                             => java.lang.Double.compare(a, b)
    case (a: String, b: String)                             => a.compareTo(b)
    case (a: java.math.BigDecimal, b: java.math.BigDecimal) => a.compareTo(b)
    case (a: Number, b: Number) => java.lang.Double.compare(a.doubleValue, b.doubleValue)
    case _                      => a.toString.compareTo(b.toString)
