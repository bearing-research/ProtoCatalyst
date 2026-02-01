package protocatalyst.arrow

import protocatalyst.types.*
import protocatalyst.schema.*
import protocatalyst.encoder.ProtoEncoder
import org.apache.arrow.vector.*
import org.apache.arrow.vector.complex.*
import org.apache.arrow.vector.types.pojo.Schema
import org.apache.arrow.memory.BufferAllocator
import scala.deriving.Mirror
import scala.compiletime.*
import java.nio.charset.StandardCharsets

/**
 * Compile-time specialized Arrow column writer.
 *
 * Uses the same `inline erasedValue[Types]` pattern as InlineRowSerializer
 * to generate type-specialized code for writing to Arrow vectors at compile time.
 * This eliminates runtime type dispatch that Spark's ArrowWriter performs.
 *
 * Benefits:
 *   - Zero runtime type matching for known field types
 *   - Direct vector writes with type-specialized code paths
 *   - Compile-time unrolling of field iteration
 *   - JIT-friendly specialized code
 *
 * Usage:
 * {{{
 * case class Person(name: String, age: Int) derives ProtoEncoder
 *
 * val writer = InlineArrowWriter.derived[Person]
 * val root = VectorSchemaRoot.create(writer.schema, allocator)
 * writer.write(Seq(Person("Alice", 30), Person("Bob", 25)), root)
 * }}}
 */
trait InlineArrowWriter[T]:
  /** Arrow schema for this type */
  def schema: Schema

  /** Number of fields */
  def fieldCount: Int

  /** Write a sequence of values to Arrow vectors */
  def write(values: Seq[T], root: VectorSchemaRoot): Unit

  /** Write a single value at the specified row index */
  def writeRow(value: T, root: VectorSchemaRoot, rowIndex: Int): Unit

object InlineArrowWriter:

  /**
   * Derive an InlineArrowWriter at compile time.
   * Generates type-specialized code for each field.
   */
  inline def derived[T](using m: Mirror.ProductOf[T], enc: ProtoEncoder[T]): InlineArrowWriter[T] =
    val count = constValue[Tuple.Size[m.MirroredElemTypes]]
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(enc.schema)

    // Create write function that captures inline expansion
    val writeRowFn = (value: T, root: VectorSchemaRoot, rowIndex: Int) =>
      writeFieldsImpl[m.MirroredElemTypes](value.asInstanceOf[Product], root, rowIndex, 0)

    InlineArrowWriterImpl[T](arrowSchema, count, writeRowFn)

  /** Implementation class to avoid anonymous class duplication warning */
  class InlineArrowWriterImpl[T](
      val schema: Schema,
      val fieldCount: Int,
      writeRowFn: (T, VectorSchemaRoot, Int) => Unit
  ) extends InlineArrowWriter[T]:

    def write(values: Seq[T], root: VectorSchemaRoot): Unit =
      root.allocateNew()
      var i = 0
      for value <- values do
        writeRowFn(value, root, i)
        i += 1
      root.setRowCount(values.size)

    def writeRow(value: T, root: VectorSchemaRoot, rowIndex: Int): Unit =
      writeRowFn(value, root, rowIndex)

  // ============================================================
  // Inline write with compile-time type specialization
  // ============================================================

  /** Write fields with compile-time type dispatch */
  inline def writeFieldsImpl[Types <: Tuple](
      product: Product,
      root: VectorSchemaRoot,
      rowIndex: Int,
      fieldIndex: Int
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      // === Primitives ===
      case _: (Boolean *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[BitVector]
        val value = product.productElement(fieldIndex).asInstanceOf[Boolean]
        vec.setSafe(rowIndex, if value then 1 else 0)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (Byte *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[TinyIntVector]
        val value = product.productElement(fieldIndex).asInstanceOf[Byte]
        vec.setSafe(rowIndex, value)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (Short *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[SmallIntVector]
        val value = product.productElement(fieldIndex).asInstanceOf[Short]
        vec.setSafe(rowIndex, value)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (Int *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[IntVector]
        val value = product.productElement(fieldIndex).asInstanceOf[Int]
        vec.setSafe(rowIndex, value)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (Long *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[BigIntVector]
        val value = product.productElement(fieldIndex).asInstanceOf[Long]
        vec.setSafe(rowIndex, value)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (Float *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[Float4Vector]
        val value = product.productElement(fieldIndex).asInstanceOf[Float]
        vec.setSafe(rowIndex, value)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (Double *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[Float8Vector]
        val value = product.productElement(fieldIndex).asInstanceOf[Double]
        vec.setSafe(rowIndex, value)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      // === String ===
      case _: (String *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[VarCharVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          val bytes = value.asInstanceOf[String].getBytes(StandardCharsets.UTF_8)
          vec.setSafe(rowIndex, bytes)
        else
          vec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      // === Binary ===
      case _: (Array[Byte] *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[VarBinaryVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          vec.setSafe(rowIndex, value.asInstanceOf[Array[Byte]])
        else
          vec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      // === BigDecimal ===
      case _: (BigDecimal *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[DecimalVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          // Rescale to match the vector's configured scale
          val bd = value.asInstanceOf[BigDecimal].bigDecimal
          val scaled = bd.setScale(vec.getScale, java.math.RoundingMode.HALF_UP)
          vec.setSafe(rowIndex, scaled)
        else
          vec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (java.math.BigDecimal *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[DecimalVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          // Rescale to match the vector's configured scale
          val bd = value.asInstanceOf[java.math.BigDecimal]
          val scaled = bd.setScale(vec.getScale, java.math.RoundingMode.HALF_UP)
          vec.setSafe(rowIndex, scaled)
        else
          vec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      // === Temporal types ===
      case _: (java.time.LocalDate *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[DateDayVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          vec.setSafe(rowIndex, value.asInstanceOf[java.time.LocalDate].toEpochDay.toInt)
        else
          vec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (java.time.Instant *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[TimeStampMicroTZVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          val instant = value.asInstanceOf[java.time.Instant]
          // Convert to microseconds since epoch
          val micros = instant.getEpochSecond * 1000000L + instant.getNano / 1000
          vec.setSafe(rowIndex, micros)
        else
          vec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (java.time.LocalDateTime *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[TimeStampMicroVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          val ldt = value.asInstanceOf[java.time.LocalDateTime]
          val epochSecond = ldt.toEpochSecond(java.time.ZoneOffset.UTC)
          val micros = epochSecond * 1000000L + ldt.getNano / 1000
          vec.setSafe(rowIndex, micros)
        else
          vec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      case _: (java.time.Duration *: ts) =>
        val vec = root.getVector(fieldIndex).asInstanceOf[DurationVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          val duration = value.asInstanceOf[java.time.Duration]
          // Convert to microseconds
          val micros = duration.getSeconds * 1000000L + duration.getNano / 1000
          vec.setSafe(rowIndex, micros)
        else
          vec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      // === List types ===
      case _: (List[t] *: ts) =>
        val listVec = root.getVector(fieldIndex).asInstanceOf[ListVector]
        val listValue = product.productElement(fieldIndex)
        if listValue != null then
          writeListValue[t](listVec, rowIndex, listValue.asInstanceOf[List[t]])
        else
          listVec.setNull(rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      // === Option types ===
      case _: (Option[t] *: ts) =>
        product.productElement(fieldIndex) match
          case Some(v) =>
            writeOptionValue[t](root, rowIndex, fieldIndex, v.asInstanceOf[t])
          case None =>
            setNullAt(root, fieldIndex, rowIndex)
          case null =>
            setNullAt(root, fieldIndex, rowIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

      // === Fallback for nested structs and other types ===
      case _: (t *: ts) =>
        writeAnyField[t](product, root, rowIndex, fieldIndex)
        writeFieldsImpl[ts](product, root, rowIndex, fieldIndex + 1)

  /** Write Option inner value with type specialization */
  private inline def writeOptionValue[T](
      root: VectorSchemaRoot,
      rowIndex: Int,
      fieldIndex: Int,
      value: T
  ): Unit =
    inline erasedValue[T] match
      case _: Boolean =>
        root.getVector(fieldIndex).asInstanceOf[BitVector]
          .setSafe(rowIndex, if value.asInstanceOf[Boolean] then 1 else 0)
      case _: Byte =>
        root.getVector(fieldIndex).asInstanceOf[TinyIntVector]
          .setSafe(rowIndex, value.asInstanceOf[Byte])
      case _: Short =>
        root.getVector(fieldIndex).asInstanceOf[SmallIntVector]
          .setSafe(rowIndex, value.asInstanceOf[Short])
      case _: Int =>
        root.getVector(fieldIndex).asInstanceOf[IntVector]
          .setSafe(rowIndex, value.asInstanceOf[Int])
      case _: Long =>
        root.getVector(fieldIndex).asInstanceOf[BigIntVector]
          .setSafe(rowIndex, value.asInstanceOf[Long])
      case _: Float =>
        root.getVector(fieldIndex).asInstanceOf[Float4Vector]
          .setSafe(rowIndex, value.asInstanceOf[Float])
      case _: Double =>
        root.getVector(fieldIndex).asInstanceOf[Float8Vector]
          .setSafe(rowIndex, value.asInstanceOf[Double])
      case _: String =>
        val bytes = value.asInstanceOf[String].getBytes(StandardCharsets.UTF_8)
        root.getVector(fieldIndex).asInstanceOf[VarCharVector].setSafe(rowIndex, bytes)
      case _ =>
        writeAnyOptionValue(root, rowIndex, fieldIndex, value)

  /** Write a List[T] to a ListVector with type specialization */
  private inline def writeListValue[T](
      listVec: ListVector,
      rowIndex: Int,
      list: List[T]
  ): Unit =
    val startOffset = listVec.startNewValue(rowIndex)
    val dataVec = listVec.getDataVector
    var i = 0
    for elem <- list do
      val elemIndex = startOffset + i
      writeListElement[T](dataVec, elemIndex, elem)
      i += 1
    listVec.endValue(rowIndex, list.size)

  /** Write a single list element with type specialization */
  private inline def writeListElement[T](
      dataVec: ValueVector,
      index: Int,
      value: T
  ): Unit =
    inline erasedValue[T] match
      case _: Int =>
        dataVec.asInstanceOf[IntVector].setSafe(index, value.asInstanceOf[Int])
      case _: Long =>
        dataVec.asInstanceOf[BigIntVector].setSafe(index, value.asInstanceOf[Long])
      case _: Double =>
        dataVec.asInstanceOf[Float8Vector].setSafe(index, value.asInstanceOf[Double])
      case _: Float =>
        dataVec.asInstanceOf[Float4Vector].setSafe(index, value.asInstanceOf[Float])
      case _: Boolean =>
        dataVec.asInstanceOf[BitVector].setSafe(index, if value.asInstanceOf[Boolean] then 1 else 0)
      case _: String =>
        val str = value.asInstanceOf[String]
        if str != null then
          dataVec.asInstanceOf[VarCharVector].setSafe(index, str.getBytes(StandardCharsets.UTF_8))
        else
          dataVec.asInstanceOf[VarCharVector].setNull(index)
      case _: List[t] =>
        // Nested list - the data vector is itself a ListVector
        val innerListVec = dataVec.asInstanceOf[ListVector]
        val innerList = value.asInstanceOf[List[t]]
        writeListValue[t](innerListVec, index, innerList)
      case _ =>
        // Try to handle as a product (struct) or fall back to runtime dispatch
        writeListElementGeneric(dataVec, index, value)

  /** Runtime fallback for list elements */
  private def writeListElementGeneric(
      dataVec: ValueVector,
      index: Int,
      value: Any
  ): Unit =
    if value == null then
      dataVec match
        case v: IntVector => v.setNull(index)
        case v: BigIntVector => v.setNull(index)
        case v: Float8Vector => v.setNull(index)
        case v: VarCharVector => v.setNull(index)
        case v: StructVector => v.setNull(index)
        case _ => ()
    else
      dataVec match
        case v: IntVector => v.setSafe(index, value.asInstanceOf[Int])
        case v: BigIntVector => v.setSafe(index, value.asInstanceOf[Long])
        case v: Float8Vector => v.setSafe(index, value.asInstanceOf[Double])
        case v: Float4Vector => v.setSafe(index, value.asInstanceOf[Float])
        case v: VarCharVector =>
          v.setSafe(index, value.asInstanceOf[String].getBytes(StandardCharsets.UTF_8))
        case v: StructVector =>
          // Write product fields to struct
          val product = value.asInstanceOf[Product]
          writeProductToStruct(product, v, index)
          v.setIndexDefined(index)
        case _ => ()

  /** Fallback for complex Option types */
  private def writeAnyOptionValue(
      root: VectorSchemaRoot,
      rowIndex: Int,
      fieldIndex: Int,
      value: Any
  ): Unit =
    root.getVector(fieldIndex) match
      case v: IntVector => v.setSafe(rowIndex, value.asInstanceOf[Int])
      case v: BigIntVector => v.setSafe(rowIndex, value.asInstanceOf[Long])
      case v: Float8Vector => v.setSafe(rowIndex, value.asInstanceOf[Double])
      case v: Float4Vector => v.setSafe(rowIndex, value.asInstanceOf[Float])
      case v: VarCharVector =>
        val bytes = value.toString.getBytes(StandardCharsets.UTF_8)
        v.setSafe(rowIndex, bytes)
      case v: BitVector =>
        v.setSafe(rowIndex, if value.asInstanceOf[Boolean] then 1 else 0)
      case v: StructVector =>
        // Handle Option[NestedProduct] - write the product fields to struct
        val product = value.asInstanceOf[Product]
        writeProductToStruct(product, v, rowIndex)
        v.setIndexDefined(rowIndex)
      case _ => () // Unsupported

  /** Write a product's fields to a struct vector (runtime version for Option fallback) */
  private def writeProductToStruct(
      product: Product,
      structVec: StructVector,
      rowIndex: Int
  ): Unit =
    var i = 0
    while i < product.productArity do
      val value = product.productElement(i)
      val childVec = structVec.getChildByOrdinal(i)
      if value != null then
        childVec match
          case v: IntVector => v.setSafe(rowIndex, value.asInstanceOf[Int])
          case v: BigIntVector => v.setSafe(rowIndex, value.asInstanceOf[Long])
          case v: Float8Vector => v.setSafe(rowIndex, value.asInstanceOf[Double])
          case v: Float4Vector => v.setSafe(rowIndex, value.asInstanceOf[Float])
          case v: VarCharVector =>
            val bytes = value.asInstanceOf[String].getBytes(StandardCharsets.UTF_8)
            v.setSafe(rowIndex, bytes)
          case v: BitVector =>
            v.setSafe(rowIndex, if value.asInstanceOf[Boolean] then 1 else 0)
          case v: StructVector =>
            // Recursively write nested product
            val nestedProduct = value.asInstanceOf[Product]
            writeProductToStruct(nestedProduct, v, rowIndex)
            v.setIndexDefined(rowIndex)
          case _ => ()
      else
        childVec match
          case v: VarCharVector => v.setNull(rowIndex)
          case v: IntVector => v.setNull(rowIndex)
          case v: BigIntVector => v.setNull(rowIndex)
          case v: Float8Vector => v.setNull(rowIndex)
          case v: Float4Vector => v.setNull(rowIndex)
          case v: BitVector => v.setNull(rowIndex)
          case v: StructVector => v.setNull(rowIndex)
          case _ => ()
      i += 1

  /** Set null at field position */
  private def setNullAt(root: VectorSchemaRoot, fieldIndex: Int, rowIndex: Int): Unit =
    root.getVector(fieldIndex) match
      case v: BitVector => v.setNull(rowIndex)
      case v: TinyIntVector => v.setNull(rowIndex)
      case v: SmallIntVector => v.setNull(rowIndex)
      case v: IntVector => v.setNull(rowIndex)
      case v: BigIntVector => v.setNull(rowIndex)
      case v: Float4Vector => v.setNull(rowIndex)
      case v: Float8Vector => v.setNull(rowIndex)
      case v: VarCharVector => v.setNull(rowIndex)
      case v: VarBinaryVector => v.setNull(rowIndex)
      case v: DecimalVector => v.setNull(rowIndex)
      case v: DateDayVector => v.setNull(rowIndex)
      case v: TimeStampMicroTZVector => v.setNull(rowIndex)
      case v: TimeStampMicroVector => v.setNull(rowIndex)
      case v: DurationVector => v.setNull(rowIndex)
      case v: StructVector => v.setNull(rowIndex)
      case v: ListVector => v.setNull(rowIndex)
      case _ => ()

  /** Fallback for nested structs and unknown types */
  private inline def writeAnyField[T](
      product: Product,
      root: VectorSchemaRoot,
      rowIndex: Int,
      fieldIndex: Int
  ): Unit =
    val fieldValue = product.productElement(fieldIndex)
    if fieldValue == null then
      setNullAt(root, fieldIndex, rowIndex)
    else
      summonFrom {
        case m: Mirror.ProductOf[T] =>
          // Nested product - write to struct vector
          val structVec = root.getVector(fieldIndex).asInstanceOf[StructVector]
          val nestedProduct = fieldValue.asInstanceOf[Product]
          writeStructFields[m.MirroredElemTypes](nestedProduct, structVec, rowIndex, 0)
          // Mark the struct row as valid (not null)
          structVec.setIndexDefined(rowIndex)
        case _ =>
          // Unknown type - try generic handling
          writeGenericValue(root, fieldIndex, rowIndex, fieldValue)
      }

  /** Write nested struct fields recursively */
  private inline def writeStructFields[Types <: Tuple](
      product: Product,
      structVec: StructVector,
      rowIndex: Int,
      fieldIndex: Int
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      case _: (Boolean *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[BitVector]
        val value = product.productElement(fieldIndex).asInstanceOf[Boolean]
        vec.setSafe(rowIndex, if value then 1 else 0)
        writeStructFields[ts](product, structVec, rowIndex, fieldIndex + 1)

      case _: (Int *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[IntVector]
        val value = product.productElement(fieldIndex).asInstanceOf[Int]
        vec.setSafe(rowIndex, value)
        writeStructFields[ts](product, structVec, rowIndex, fieldIndex + 1)

      case _: (Long *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[BigIntVector]
        val value = product.productElement(fieldIndex).asInstanceOf[Long]
        vec.setSafe(rowIndex, value)
        writeStructFields[ts](product, structVec, rowIndex, fieldIndex + 1)

      case _: (Double *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[Float8Vector]
        val value = product.productElement(fieldIndex).asInstanceOf[Double]
        vec.setSafe(rowIndex, value)
        writeStructFields[ts](product, structVec, rowIndex, fieldIndex + 1)

      case _: (String *: ts) =>
        val vec = structVec.getChildByOrdinal(fieldIndex).asInstanceOf[VarCharVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          val bytes = value.asInstanceOf[String].getBytes(StandardCharsets.UTF_8)
          vec.setSafe(rowIndex, bytes)
        else
          vec.setNull(rowIndex)
        writeStructFields[ts](product, structVec, rowIndex, fieldIndex + 1)

      case _: (t *: ts) =>
        // Generic fallback for struct field
        writeStructFields[ts](product, structVec, rowIndex, fieldIndex + 1)

  /** Fallback for truly unknown types */
  private def writeGenericValue(
      root: VectorSchemaRoot,
      fieldIndex: Int,
      rowIndex: Int,
      value: Any
  ): Unit =
    root.getVector(fieldIndex) match
      case v: IntVector => v.setSafe(rowIndex, value.asInstanceOf[Int])
      case v: BigIntVector => v.setSafe(rowIndex, value.asInstanceOf[Long])
      case v: Float8Vector => v.setSafe(rowIndex, value.asInstanceOf[Double])
      case v: Float4Vector => v.setSafe(rowIndex, value.asInstanceOf[Float])
      case v: VarCharVector =>
        val bytes = value.toString.getBytes(StandardCharsets.UTF_8)
        v.setSafe(rowIndex, bytes)
      case _ => ()
