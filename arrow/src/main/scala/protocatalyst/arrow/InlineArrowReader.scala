package protocatalyst.arrow

import protocatalyst.types.*
import protocatalyst.schema.*
import protocatalyst.encoder.ProtoEncoder
import org.apache.arrow.vector.*
import org.apache.arrow.vector.complex.*
import org.apache.arrow.vector.types.pojo.Schema
import scala.deriving.Mirror
import scala.compiletime.*
import java.nio.charset.StandardCharsets

/**
 * Compile-time specialized Arrow column reader.
 *
 * Uses the same `inline erasedValue[Types]` pattern as InlineArrowWriter
 * to generate type-specialized code for reading from Arrow vectors at compile time.
 * This eliminates runtime type dispatch that Spark's ArrowColumnVector performs.
 *
 * Benefits:
 *   - Zero runtime type matching for known field types
 *   - Direct vector reads with type-specialized code paths
 *   - Compile-time unrolling of field iteration
 *   - JIT-friendly specialized code
 *
 * Usage:
 * {{{
 * case class Person(name: String, age: Int) derives ProtoEncoder
 *
 * val reader = InlineArrowReader.derived[Person]
 * val persons = reader.read(root)  // Seq[Person]
 * }}}
 */
trait InlineArrowReader[T]:
  /** Arrow schema for this type */
  def schema: Schema

  /** Number of fields */
  def fieldCount: Int

  /** Read all rows from Arrow vectors */
  def read(root: VectorSchemaRoot): Seq[T]

  /** Read a single row at the specified index */
  def readRow(root: VectorSchemaRoot, rowIndex: Int): T

object InlineArrowReader:

  /**
   * Derive an InlineArrowReader at compile time.
   * Generates type-specialized code for each field.
   */
  inline def derived[T](using m: Mirror.ProductOf[T], enc: ProtoEncoder[T]): InlineArrowReader[T] =
    val count = constValue[Tuple.Size[m.MirroredElemTypes]]
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(enc.schema)

    // Create read function that captures inline expansion
    val readRowFn = (root: VectorSchemaRoot, rowIndex: Int) =>
      val values = new Array[Any](count)
      readFieldsImpl[m.MirroredElemTypes](root, rowIndex, 0, values)
      m.fromProduct(ArrayProduct(values))

    InlineArrowReaderImpl[T](arrowSchema, count, readRowFn)

  /** Implementation class to avoid anonymous class duplication warning */
  class InlineArrowReaderImpl[T](
      val schema: Schema,
      val fieldCount: Int,
      readRowFn: (VectorSchemaRoot, Int) => T
  ) extends InlineArrowReader[T]:

    def read(root: VectorSchemaRoot): Seq[T] =
      val rowCount = root.getRowCount
      val result = new Array[Any](rowCount)
      var i = 0
      while i < rowCount do
        result(i) = readRowFn(root, i)
        i += 1
      result.toSeq.asInstanceOf[Seq[T]]

    def readRow(root: VectorSchemaRoot, rowIndex: Int): T =
      readRowFn(root, rowIndex)

  // ============================================================
  // Inline read with compile-time type specialization
  // ============================================================

  /** Read fields with compile-time type dispatch */
  inline def readFieldsImpl[Types <: Tuple](
      root: VectorSchemaRoot,
      rowIndex: Int,
      fieldIndex: Int,
      values: Array[Any]
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      // === Primitives ===
      case _: (Boolean *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[BitVector]
        values(fieldIndex) = vec.get(rowIndex) == 1
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (Byte *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[TinyIntVector]
        values(fieldIndex) = vec.get(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (Short *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[SmallIntVector]
        values(fieldIndex) = vec.get(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (Int *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[IntVector]
        values(fieldIndex) = vec.get(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (Long *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[BigIntVector]
        values(fieldIndex) = vec.get(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (Float *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[Float4Vector]
        values(fieldIndex) = vec.get(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (Double *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[Float8Vector]
        values(fieldIndex) = vec.get(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      // === String ===
      case _: (String *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[VarCharVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else new String(vec.get(rowIndex), StandardCharsets.UTF_8)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      // === Binary ===
      case _: (Array[Byte] *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[VarBinaryVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else vec.get(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      // === BigDecimal ===
      case _: (BigDecimal *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[DecimalVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else BigDecimal(vec.getObject(rowIndex))
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (java.math.BigDecimal *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[DecimalVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else vec.getObject(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      // === Temporal types ===
      case _: (java.time.LocalDate *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[DateDayVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else java.time.LocalDate.ofEpochDay(vec.get(rowIndex).toLong)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (java.time.Instant *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[TimeStampMicroTZVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else
            val micros = vec.get(rowIndex)
            val epochSecond = micros / 1000000L
            val nanoAdjustment = (micros % 1000000L) * 1000
            java.time.Instant.ofEpochSecond(epochSecond, nanoAdjustment)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (java.time.LocalDateTime *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[TimeStampMicroVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else
            val micros = vec.get(rowIndex)
            val epochSecond = micros / 1000000L
            val nanoAdjustment = (micros % 1000000L) * 1000
            java.time.LocalDateTime.ofEpochSecond(
              epochSecond,
              nanoAdjustment.toInt,
              java.time.ZoneOffset.UTC
            )
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      case _: (java.time.Duration *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[DurationVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else vec.getObject(rowIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      // === Option types ===
      case _: (Option[t] *: ts) =>
        val isNull = isNullAt(root, fieldIndex, rowIndex)
        values(fieldIndex) =
          if isNull then None
          else Some(readOptionValue[t](root, rowIndex, fieldIndex))
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

      // === Fallback for nested structs and other types ===
      case _: (t *: ts) =>
        values(fieldIndex) = readAnyField[t](root, rowIndex, fieldIndex)
        readFieldsImpl[ts](root, rowIndex, fieldIndex + 1, values)

  /** Read Option inner value with type specialization */
  private inline def readOptionValue[T](
      root: VectorSchemaRoot,
      rowIndex: Int,
      fieldIndex: Int
  ): T =
    inline erasedValue[T] match
      case _: Boolean =>
        (root.getVector(fieldIndex).asInstanceOf[BitVector].get(rowIndex) == 1).asInstanceOf[T]
      case _: Byte =>
        root.getVector(fieldIndex).asInstanceOf[TinyIntVector].get(rowIndex).asInstanceOf[T]
      case _: Short =>
        root.getVector(fieldIndex).asInstanceOf[SmallIntVector].get(rowIndex).asInstanceOf[T]
      case _: Int =>
        root.getVector(fieldIndex).asInstanceOf[IntVector].get(rowIndex).asInstanceOf[T]
      case _: Long =>
        root.getVector(fieldIndex).asInstanceOf[BigIntVector].get(rowIndex).asInstanceOf[T]
      case _: Float =>
        root.getVector(fieldIndex).asInstanceOf[Float4Vector].get(rowIndex).asInstanceOf[T]
      case _: Double =>
        root.getVector(fieldIndex).asInstanceOf[Float8Vector].get(rowIndex).asInstanceOf[T]
      case _: String =>
        val bytes = root.getVector(fieldIndex).asInstanceOf[VarCharVector].get(rowIndex)
        new String(bytes, StandardCharsets.UTF_8).asInstanceOf[T]
      case _ =>
        // Try to summon a Mirror for nested products
        summonFrom {
          case m: Mirror.ProductOf[T] =>
            // Nested product - read from struct vector
            val structVec = root.getVector(fieldIndex).asInstanceOf[StructVector]
            val nestedCount = constValue[Tuple.Size[m.MirroredElemTypes]]
            val nestedValues = new Array[Any](nestedCount)
            readStructFields[m.MirroredElemTypes](structVec, rowIndex, 0, nestedValues)
            m.fromProduct(ArrayProduct(nestedValues))
          case _ =>
            readAnyOptionValue(root, rowIndex, fieldIndex).asInstanceOf[T]
        }

  /** Fallback for complex Option types */
  private def readAnyOptionValue(
      root: VectorSchemaRoot,
      rowIndex: Int,
      fieldIndex: Int
  ): Any =
    root.getVector(fieldIndex) match
      case v: IntVector => v.get(rowIndex)
      case v: BigIntVector => v.get(rowIndex)
      case v: Float8Vector => v.get(rowIndex)
      case v: Float4Vector => v.get(rowIndex)
      case v: VarCharVector =>
        new String(v.get(rowIndex), StandardCharsets.UTF_8)
      case v: BitVector => v.get(rowIndex) == 1
      case v: DateDayVector =>
        java.time.LocalDate.ofEpochDay(v.get(rowIndex).toLong)
      case v: TimeStampMicroTZVector =>
        val micros = v.get(rowIndex)
        java.time.Instant.ofEpochSecond(micros / 1000000L, (micros % 1000000L) * 1000)
      case v: StructVector =>
        // Return the struct vector + rowIndex - caller needs Mirror to reconstruct
        // For runtime fallback, we read fields into an array
        readProductFromStruct(v, rowIndex)
      case _ => null

  /** Read a product's fields from a struct vector (runtime version for Option fallback) */
  private def readProductFromStruct(
      structVec: StructVector,
      rowIndex: Int
  ): Array[Any] =
    val numChildren = structVec.size()
    val values = new Array[Any](numChildren)
    var i = 0
    while i < numChildren do
      val childVec = structVec.getChildByOrdinal(i)
      values(i) = childVec match
        case v: IntVector if !v.isNull(rowIndex) => v.get(rowIndex)
        case v: BigIntVector if !v.isNull(rowIndex) => v.get(rowIndex)
        case v: Float8Vector if !v.isNull(rowIndex) => v.get(rowIndex)
        case v: Float4Vector if !v.isNull(rowIndex) => v.get(rowIndex)
        case v: VarCharVector if !v.isNull(rowIndex) =>
          new String(v.get(rowIndex), StandardCharsets.UTF_8)
        case v: BitVector if !v.isNull(rowIndex) => v.get(rowIndex) == 1
        case _ => null
      i += 1
    values

  /** Check if field is null at position */
  private def isNullAt(root: VectorSchemaRoot, fieldIndex: Int, rowIndex: Int): Boolean =
    root.getVector(fieldIndex) match
      case v: BitVector => v.isNull(rowIndex)
      case v: TinyIntVector => v.isNull(rowIndex)
      case v: SmallIntVector => v.isNull(rowIndex)
      case v: IntVector => v.isNull(rowIndex)
      case v: BigIntVector => v.isNull(rowIndex)
      case v: Float4Vector => v.isNull(rowIndex)
      case v: Float8Vector => v.isNull(rowIndex)
      case v: VarCharVector => v.isNull(rowIndex)
      case v: VarBinaryVector => v.isNull(rowIndex)
      case v: DecimalVector => v.isNull(rowIndex)
      case v: DateDayVector => v.isNull(rowIndex)
      case v: TimeStampMicroTZVector => v.isNull(rowIndex)
      case v: TimeStampMicroVector => v.isNull(rowIndex)
      case v: DurationVector => v.isNull(rowIndex)
      case v: StructVector => v.isNull(rowIndex)
      case v: ListVector => v.isNull(rowIndex)
      case _ => true

  /** Fallback for nested structs and unknown types */
  private inline def readAnyField[T](
      root: VectorSchemaRoot,
      rowIndex: Int,
      fieldIndex: Int
  ): T =
    if isNullAt(root, fieldIndex, rowIndex) then
      null.asInstanceOf[T]
    else
      summonFrom {
        case m: Mirror.ProductOf[T] =>
          // Nested product - read from struct vector
          val structVec = root.getVector(fieldIndex).asInstanceOf[StructVector]
          val nestedCount = constValue[Tuple.Size[m.MirroredElemTypes]]
          val nestedValues = new Array[Any](nestedCount)
          readStructFields[m.MirroredElemTypes](structVec, rowIndex, 0, nestedValues)
          m.fromProduct(ArrayProduct(nestedValues))
        case _ =>
          // Unknown type - try generic handling
          readGenericValue(root, fieldIndex, rowIndex).asInstanceOf[T]
      }

  /** Read nested struct fields recursively */
  private inline def readStructFields[Types <: Tuple](
      structVec: StructVector,
      rowIndex: Int,
      fieldIndex: Int,
      values: Array[Any]
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      case _: (Boolean *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[BitVector]
        values(fieldIndex) = vec.get(rowIndex) == 1
        readStructFields[ts](structVec, rowIndex, fieldIndex + 1, values)

      case _: (Int *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[IntVector]
        values(fieldIndex) = vec.get(rowIndex)
        readStructFields[ts](structVec, rowIndex, fieldIndex + 1, values)

      case _: (Long *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[BigIntVector]
        values(fieldIndex) = vec.get(rowIndex)
        readStructFields[ts](structVec, rowIndex, fieldIndex + 1, values)

      case _: (Double *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[Float8Vector]
        values(fieldIndex) = vec.get(rowIndex)
        readStructFields[ts](structVec, rowIndex, fieldIndex + 1, values)

      case _: (String *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[VarCharVector]
        values(fieldIndex) =
          if vec.isNull(rowIndex) then null
          else new String(vec.get(rowIndex), StandardCharsets.UTF_8)
        readStructFields[ts](structVec, rowIndex, fieldIndex + 1, values)

      case _: (t *: ts) =>
        // Generic fallback for struct field
        values(fieldIndex) = null
        readStructFields[ts](structVec, rowIndex, fieldIndex + 1, values)

  /** Fallback for truly unknown types */
  private def readGenericValue(
      root: VectorSchemaRoot,
      fieldIndex: Int,
      rowIndex: Int
  ): Any =
    root.getVector(fieldIndex) match
      case v: IntVector => v.get(rowIndex)
      case v: BigIntVector => v.get(rowIndex)
      case v: Float8Vector => v.get(rowIndex)
      case v: Float4Vector => v.get(rowIndex)
      case v: VarCharVector =>
        new String(v.get(rowIndex), StandardCharsets.UTF_8)
      case _ => null

  /** Helper to construct product from array */
  class ArrayProduct(values: Array[Any]) extends Product:
    def productArity: Int = values.length
    def productElement(n: Int): Any = values(n)
    def canEqual(that: Any): Boolean = that.isInstanceOf[ArrayProduct]
