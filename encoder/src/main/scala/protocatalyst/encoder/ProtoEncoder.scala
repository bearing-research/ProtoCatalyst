package protocatalyst.encoder

import protocatalyst.schema.*
import protocatalyst.types.*
import scala.deriving.Mirror
import scala.compiletime.*

/** Field descriptor for product type encoders. */
case class FieldEncoder[T](
    name: String,
    encoder: ProtoEncoder[T],
    nullable: Boolean
)

/** Compile-time derived encoder for Spark. */
trait ProtoEncoder[T]:
  def schema: ProtoSchema
  def catalystType: ProtoType
  def nullable: Boolean

  /** For product types, the field encoders. Empty for primitives. */
  def fields: Vector[FieldEncoder[?]] = Vector.empty

object ProtoEncoder:

  // === Derivation entry point ===

  /** Derive an encoder for type T at compile time. */
  inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
    inline m match
      case p: Mirror.ProductOf[T] => deriveProduct[T](p)
      case _: Mirror.SumOf[T] =>
        error(
          "Sum types (enums/sealed traits) are not yet supported by ProtoEncoder.\n" +
            "Consider using a case class wrapper or wait for future enum support."
        )

  // === Product (case class) derivation ===

  private inline def deriveProduct[T](m: Mirror.ProductOf[T]): ProtoEncoder[T] =
    val fieldEncoders = deriveFields[m.MirroredElemTypes, m.MirroredElemLabels]
    val structFields = fieldEncoders.map { fe =>
      ProtoStructField(fe.name, fe.encoder.catalystType, fe.nullable)
    }
    makeProductEncoder[T](fieldEncoders, ProtoSchema(structFields))

  private inline def deriveFields[Types <: Tuple, Labels <: Tuple]: Vector[FieldEncoder[?]] =
    inline (erasedValue[Types], erasedValue[Labels]) match
      case (_: EmptyTuple, _: EmptyTuple) =>
        Vector.empty
      case (_: (t *: ts), _: (l *: ls)) =>
        val name = constValue[l].toString
        val enc = summonEncoder[t]
        val isNullable = isOption[t]
        FieldEncoder[t](name, enc, isNullable) +: deriveFields[ts, ls]

  private inline def summonEncoder[T]: ProtoEncoder[T] =
    summonFrom {
      case enc: ProtoEncoder[T] => enc
      case _: Mirror.ProductOf[T] => derived[T]
      case _ =>
        error(
          "Cannot find or derive ProtoEncoder for type.\n\n" +
            "Supported types:\n" +
            "  - Primitives: Boolean, Byte, Short, Int, Long, Float, Double, String, Array[Byte], BigDecimal\n" +
            "  - Temporal: java.time.LocalDate, Instant, LocalDateTime\n" +
            "  - Wrappers: Option[T]\n" +
            "  - Collections: Seq, List, Vector, Set, Array, Map\n" +
            "  - Products: case classes, tuples\n\n" +
            "For custom types, either:\n" +
            "  1. Define a case class and use ProtoEncoder.derived[YourType]\n" +
            "  2. Provide a given ProtoEncoder[YourType] instance manually"
        )
    }

  private inline def isOption[T]: Boolean =
    inline erasedValue[T] match
      case _: Option[?] => true
      case _ => false

  // Factory method for product encoders (must be accessible from inline expansion)
  def makeProductEncoder[T](
      fieldEncoders: Vector[FieldEncoder[?]],
      theSchema: ProtoSchema
  ): ProtoEncoder[T] = new ProtoEncoder[T]:
    def schema: ProtoSchema = theSchema
    val catalystType: ProtoType = ProtoType.StructType(theSchema.fields)
    val nullable: Boolean = false
    override def fields: Vector[FieldEncoder[?]] = fieldEncoders

  // === Primitive encoders ===

  given ProtoEncoder[Boolean] = PrimitiveEncoder(ProtoType.BooleanType)
  given ProtoEncoder[Byte] = PrimitiveEncoder(ProtoType.ByteType)
  given ProtoEncoder[Short] = PrimitiveEncoder(ProtoType.ShortType)
  given ProtoEncoder[Int] = PrimitiveEncoder(ProtoType.IntType)
  given ProtoEncoder[Long] = PrimitiveEncoder(ProtoType.LongType)
  given ProtoEncoder[Float] = PrimitiveEncoder(ProtoType.FloatType)
  given ProtoEncoder[Double] = PrimitiveEncoder(ProtoType.DoubleType)
  given ProtoEncoder[String] = PrimitiveEncoder(ProtoType.StringType)
  given ProtoEncoder[Array[Byte]] = PrimitiveEncoder(ProtoType.BinaryType)
  given ProtoEncoder[BigDecimal] = PrimitiveEncoder(ProtoType.DecimalType(38, 18))

  // java.time types
  given ProtoEncoder[java.time.LocalDate] = PrimitiveEncoder(ProtoType.DateType)
  given ProtoEncoder[java.time.Instant] = PrimitiveEncoder(ProtoType.TimestampType)
  given ProtoEncoder[java.time.LocalDateTime] = PrimitiveEncoder(ProtoType.TimestampNTZType)

  private class PrimitiveEncoder[T](val catalystType: ProtoType) extends ProtoEncoder[T]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val nullable: Boolean = false

  // === Option encoder ===

  given optionEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Option[T]] =
    OptionEncoder(enc)

  private class OptionEncoder[T](enc: ProtoEncoder[T]) extends ProtoEncoder[Option[T]]:
    val schema: ProtoSchema = enc.schema
    val catalystType: ProtoType = enc.catalystType
    val nullable: Boolean = true

  // === Tuple encoders ===
  // Tuples are Products in Scala 3, but we provide explicit given instances
  // for better error messages and to ensure they work without explicit derivation

  given tuple2Encoder[A, B](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B]
  ): ProtoEncoder[(A, B)] =
    makeTupleEncoder(Vector(
      FieldEncoder("_1", encA, encA.nullable),
      FieldEncoder("_2", encB, encB.nullable)
    ))

  given tuple3Encoder[A, B, C](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C]
  ): ProtoEncoder[(A, B, C)] =
    makeTupleEncoder(Vector(
      FieldEncoder("_1", encA, encA.nullable),
      FieldEncoder("_2", encB, encB.nullable),
      FieldEncoder("_3", encC, encC.nullable)
    ))

  given tuple4Encoder[A, B, C, D](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D]
  ): ProtoEncoder[(A, B, C, D)] =
    makeTupleEncoder(Vector(
      FieldEncoder("_1", encA, encA.nullable),
      FieldEncoder("_2", encB, encB.nullable),
      FieldEncoder("_3", encC, encC.nullable),
      FieldEncoder("_4", encD, encD.nullable)
    ))

  given tuple5Encoder[A, B, C, D, E](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E]
  ): ProtoEncoder[(A, B, C, D, E)] =
    makeTupleEncoder(Vector(
      FieldEncoder("_1", encA, encA.nullable),
      FieldEncoder("_2", encB, encB.nullable),
      FieldEncoder("_3", encC, encC.nullable),
      FieldEncoder("_4", encD, encD.nullable),
      FieldEncoder("_5", encE, encE.nullable)
    ))

  private def makeTupleEncoder[T](fieldEncoders: Vector[FieldEncoder[?]]): ProtoEncoder[T] =
    val structFields = fieldEncoders.map { fe =>
      ProtoStructField(fe.name, fe.encoder.catalystType, fe.nullable)
    }
    makeProductEncoder[T](fieldEncoders, ProtoSchema(structFields))

  // === Collection encoders ===

  given seqEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Seq[T]] =
    ArrayEncoder(enc)

  given listEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[List[T]] =
    ArrayEncoder(enc)

  given vectorEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Vector[T]] =
    ArrayEncoder(enc)

  given setEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Set[T]] =
    ArrayEncoder(enc)

  given arrayEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Array[T]] =
    ArrayEncoder(enc)

  private class ArrayEncoder[C, T](enc: ProtoEncoder[T]) extends ProtoEncoder[C]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.ArrayType(enc.catalystType, enc.nullable)
    val nullable: Boolean = false

  given mapEncoder[K, V](using keyEnc: ProtoEncoder[K], valEnc: ProtoEncoder[V]): ProtoEncoder[Map[K, V]] =
    MapEncoder(keyEnc, valEnc)

  private class MapEncoder[K, V](keyEnc: ProtoEncoder[K], valEnc: ProtoEncoder[V]) extends ProtoEncoder[Map[K, V]]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.MapType(keyEnc.catalystType, valEnc.catalystType, valEnc.nullable)
    val nullable: Boolean = false
