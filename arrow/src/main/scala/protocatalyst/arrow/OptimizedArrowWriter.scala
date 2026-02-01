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
 * Optimized Arrow writer with vector caching.
 *
 * Key optimizations over InlineArrowWriter:
 *   1. Vectors are cached once per write batch, not looked up per row
 *   2. Uses indexed iteration instead of for-each
 *   3. Minimizes virtual method calls in hot path
 *
 * Usage:
 * {{{
 * case class Person(name: String, age: Int) derives ProtoEncoder
 *
 * val writer = OptimizedArrowWriter.derived[Person]
 * val root = VectorSchemaRoot.create(writer.schema, allocator)
 * writer.write(data, root)
 * }}}
 */
trait OptimizedArrowWriter[T]:
  def schema: Schema
  def fieldCount: Int
  def write(values: Seq[T], root: VectorSchemaRoot): Unit
  def writeIndexed(values: IndexedSeq[T], root: VectorSchemaRoot): Unit

object OptimizedArrowWriter:

  inline def derived[T](using m: Mirror.ProductOf[T], enc: ProtoEncoder[T]): OptimizedArrowWriter[T] =
    val count = constValue[Tuple.Size[m.MirroredElemTypes]]
    val arrowSchema = ArrowSchemaConverter.toArrowSchema(enc.schema)

    // Capture the inline-expanded write function
    val writeRowFn = (product: Product, vectors: Array[ValueVector], rowIndex: Int) =>
      writeFieldsCached[m.MirroredElemTypes](product, vectors, rowIndex, 0)

    OptimizedArrowWriterImpl[T](arrowSchema, count, writeRowFn)

  class OptimizedArrowWriterImpl[T](
      val schema: Schema,
      val fieldCount: Int,
      writeRowFn: (Product, Array[ValueVector], Int) => Unit
  ) extends OptimizedArrowWriter[T]:

    def write(values: Seq[T], root: VectorSchemaRoot): Unit =
      writeIndexed(values.toIndexedSeq, root)

    def writeIndexed(values: IndexedSeq[T], root: VectorSchemaRoot): Unit =
      root.allocateNew()
      val size = values.size

      // Cache vectors once - this is the key optimization
      val vectors = new Array[ValueVector](fieldCount)
      var i = 0
      while i < fieldCount do
        vectors(i) = root.getVector(i)
        i += 1

      // Write all rows with cached vectors
      i = 0
      while i < size do
        writeRowFn(values(i).asInstanceOf[Product], vectors, i)
        i += 1

      root.setRowCount(size)

  /** Write fields using cached vector array */
  inline def writeFieldsCached[Types <: Tuple](
      product: Product,
      vectors: Array[ValueVector],
      rowIndex: Int,
      fieldIndex: Int
  ): Unit =
    inline erasedValue[Types] match
      case _: EmptyTuple => ()

      case _: (Boolean *: ts) =>
        vectors(fieldIndex).asInstanceOf[BitVector]
          .setSafe(rowIndex, if product.productElement(fieldIndex).asInstanceOf[Boolean] then 1 else 0)
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (Byte *: ts) =>
        vectors(fieldIndex).asInstanceOf[TinyIntVector]
          .setSafe(rowIndex, product.productElement(fieldIndex).asInstanceOf[Byte])
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (Short *: ts) =>
        vectors(fieldIndex).asInstanceOf[SmallIntVector]
          .setSafe(rowIndex, product.productElement(fieldIndex).asInstanceOf[Short])
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (Int *: ts) =>
        vectors(fieldIndex).asInstanceOf[IntVector]
          .setSafe(rowIndex, product.productElement(fieldIndex).asInstanceOf[Int])
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (Long *: ts) =>
        vectors(fieldIndex).asInstanceOf[BigIntVector]
          .setSafe(rowIndex, product.productElement(fieldIndex).asInstanceOf[Long])
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (Float *: ts) =>
        vectors(fieldIndex).asInstanceOf[Float4Vector]
          .setSafe(rowIndex, product.productElement(fieldIndex).asInstanceOf[Float])
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (Double *: ts) =>
        vectors(fieldIndex).asInstanceOf[Float8Vector]
          .setSafe(rowIndex, product.productElement(fieldIndex).asInstanceOf[Double])
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (String *: ts) =>
        val vec = vectors(fieldIndex).asInstanceOf[VarCharVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          vec.setSafe(rowIndex, value.asInstanceOf[String].getBytes(StandardCharsets.UTF_8))
        else
          vec.setNull(rowIndex)
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (java.time.LocalDate *: ts) =>
        val vec = vectors(fieldIndex).asInstanceOf[DateDayVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          vec.setSafe(rowIndex, value.asInstanceOf[java.time.LocalDate].toEpochDay.toInt)
        else
          vec.setNull(rowIndex)
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      case _: (java.time.Instant *: ts) =>
        val vec = vectors(fieldIndex).asInstanceOf[TimeStampMicroTZVector]
        val value = product.productElement(fieldIndex)
        if value != null then
          val instant = value.asInstanceOf[java.time.Instant]
          val micros = instant.getEpochSecond * 1000000L + instant.getNano / 1000
          vec.setSafe(rowIndex, micros)
        else
          vec.setNull(rowIndex)
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)

      // Fallback for other types
      case _: (t *: ts) =>
        writeFieldsCached[ts](product, vectors, rowIndex, fieldIndex + 1)
