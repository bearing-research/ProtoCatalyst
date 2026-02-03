package protocatalyst.encoder

import scala.compiletime._
import scala.deriving.Mirror
import scala.reflect.{ClassTag, classTag}

import protocatalyst.schema._
import protocatalyst.types._

/** Field descriptor for product type encoders. */
case class FieldEncoder[T](
    name: String,
    encoder: ProtoEncoder[T],
    nullable: Boolean
)

/** Variant descriptor for sum type (sealed trait) encoders. */
case class VariantEncoder[T](
    name: String,
    ordinal: Int,
    encoder: Option[ProtoEncoder[?]], // None for case objects (singleton)
    isSingleton: Boolean
)

/** Compile-time derived encoder for Spark. */
trait ProtoEncoder[T]:
  def schema: ProtoSchema
  def catalystType: ProtoType
  def nullable: Boolean
  def clsTag: ClassTag[T]

  /** For product types, the field encoders. Empty for primitives. */
  def fields: Vector[FieldEncoder[?]] = Vector.empty

  /** For sum types (sealed traits), the variant encoders. Empty for non-sum types. */
  def variants: Vector[VariantEncoder[?]] = Vector.empty

object ProtoEncoder:

  // === Derivation entry point ===

  /** Derive an encoder for type T at compile time. */
  inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
    inline m match
      case p: Mirror.ProductOf[T] =>
        val ct = summonInline[ClassTag[T]]
        deriveProduct[T](p, ct)
      case s: Mirror.SumOf[T] =>
        val ct = summonInline[ClassTag[T]]
        deriveEnum[T](s, ct)

  // === Product (case class) derivation ===

  private inline def deriveProduct[T](m: Mirror.ProductOf[T], ct: ClassTag[T]): ProtoEncoder[T] =
    val fieldEncoders = deriveFields[m.MirroredElemTypes, m.MirroredElemLabels]
    val structFields = fieldEncoders.map { fe =>
      ProtoStructField(fe.name, fe.encoder.catalystType, fe.nullable)
    }
    makeProductEncoder[T](fieldEncoders, ProtoSchema(structFields), ct)

  // === Sum type (enum/sealed trait) derivation ===

  private inline def deriveEnum[T](m: Mirror.SumOf[T], ct: ClassTag[T]): ProtoEncoder[T] =
    // Derive variant encoders for each case of the sealed trait/enum
    val variantEncoders = deriveVariants[m.MirroredElemTypes, m.MirroredElemLabels](0)
    // Check if all variants are singletons (simple enum) or have data (sealed trait)
    val allSingletons = variantEncoders.forall(_.isSingleton)
    if allSingletons then
      // Simple enum - store as StringType (matches Spark's EnumEncoder behavior)
      makeEnumEncoder[T](ct)
    else
      // Sealed trait with data-carrying variants - use SumType
      makeSumEncoder[T](m, variantEncoders, ct)

  /** Derive variant encoders for each case of a sum type. */
  private inline def deriveVariants[Types <: Tuple, Labels <: Tuple](
      ordinal: Int
  ): Vector[VariantEncoder[?]] =
    inline (erasedValue[Types], erasedValue[Labels]) match
      case (_: EmptyTuple, _: EmptyTuple) =>
        Vector.empty
      case (_: (t *: ts), _: (l *: ls)) =>
        val name = constValue[l].toString
        val variantEnc = deriveVariantEncoder[t](name, ordinal)
        variantEnc +: deriveVariants[ts, ls](ordinal + 1)

  /** Derive encoder for a single variant (case class or case object). */
  private inline def deriveVariantEncoder[T](name: String, ordinal: Int): VariantEncoder[T] =
    summonFrom {
      case m: Mirror.ProductOf[T] =>
        // Check if it has any fields by looking at MirroredElemTypes
        inline erasedValue[m.MirroredElemTypes] match
          case _: EmptyTuple =>
            // Zero fields = case object (singleton)
            VariantEncoder[T](name, ordinal, None, isSingleton = true)
          case _ =>
            // Has fields = case class with data
            val enc = derived[T]
            VariantEncoder[T](name, ordinal, Some(enc), isSingleton = false)
      case _ =>
        // No Mirror.ProductOf at all = singleton
        VariantEncoder[T](name, ordinal, None, isSingleton = true)
    }

  /** Factory method for simple enum encoders (all singletons). */
  def makeEnumEncoder[T](ct: ClassTag[T]): ProtoEncoder[T] = new ProtoEncoder[T]:
    def schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.StringType // Enums stored as strings
    val nullable: Boolean = false
    val clsTag: ClassTag[T] = ct

  /** Factory method for sealed trait (sum type) encoders with data-carrying variants. */
  def makeSumEncoder[T](
      mirror: Mirror.SumOf[T],
      variantEncoders: Vector[VariantEncoder[?]],
      ct: ClassTag[T]
  ): ProtoEncoder[T] =
    // Build SumType with variant information
    val sumVariants = variantEncoders.map { ve =>
      SumVariant(ve.name, ve.ordinal, ve.encoder.map(_.catalystType))
    }
    val sumType = ProtoType.SumType("_type", sumVariants)
    new SumEncoder[T](mirror, variantEncoders, sumType, ct)

  /** Encoder implementation for sealed traits with data-carrying variants. */
  class SumEncoder[T](
      val mirror: Mirror.SumOf[T],
      variantEncoders: Vector[VariantEncoder[?]],
      val catalystType: ProtoType,
      val clsTag: ClassTag[T]
  ) extends ProtoEncoder[T]:
    val schema: ProtoSchema = catalystType match
      case ProtoType.SumType(_, variants) =>
        // Schema represents the discriminated union structure
        val fields = Vector(
          ProtoStructField("_type", ProtoType.StringType, nullable = false),
          ProtoStructField("_ordinal", ProtoType.IntType, nullable = false)
        )
        ProtoSchema(fields)
      case _ => ProtoSchema(Vector.empty)
    val nullable: Boolean = false
    override def variants: Vector[VariantEncoder[?]] = variantEncoders

    /** Get the variant encoder for a given value at runtime. */
    def variantFor(value: T): VariantEncoder[?] =
      val ordinal = mirror.ordinal(value)
      variantEncoders(ordinal)

  private inline def deriveFields[Types <: Tuple, Labels <: Tuple]: Vector[FieldEncoder[?]] =
    inline (erasedValue[Types], erasedValue[Labels]) match
      case (_: EmptyTuple, _: EmptyTuple) =>
        Vector.empty
      case (_: (t *: ts), _: (l *: ls)) =>
        val name = constValue[l].toString
        val enc = summonEncoder[t]
        // Field is nullable if the encoder itself is nullable (Option, boxed primitives, etc.)
        FieldEncoder[t](name, enc, enc.nullable) +: deriveFields[ts, ls]

  private inline def summonEncoder[T]: ProtoEncoder[T] =
    summonFrom {
      case enc: ProtoEncoder[T]   => enc
      case _: Mirror.ProductOf[T] => derived[T]
      case _: Mirror.SumOf[T]     => derived[T] // Support enums as fields
      case _                      =>
        error(
          "Cannot find or derive ProtoEncoder for type.\n\n" +
            "Supported types:\n" +
            "  - Primitives: Boolean, Byte, Short, Int, Long, Float, Double, String, Array[Byte], BigDecimal, UUID\n" +
            "  - Boxed: java.lang.Integer, java.lang.Long, java.lang.Double, etc.\n" +
            "  - Temporal: java.time.LocalDate, Instant, LocalDateTime, Duration, Period\n" +
            "  - Legacy temporal: java.sql.Date, java.sql.Timestamp\n" +
            "  - Wrappers: Option[T]\n" +
            "  - Collections: Seq, List, Vector, Set, Array, Map\n" +
            "  - Products: case classes, tuples\n" +
            "  - Enums: Scala 3 enums, Java enums\n" +
            "  - Sealed traits: ADTs with case classes and case objects\n" +
            "  - UDTs: Types with a ProtoUDT[T] in scope\n\n" +
            "For custom types, either:\n" +
            "  1. Define a case class and use ProtoEncoder.derived[YourType]\n" +
            "  2. Provide a given ProtoEncoder[YourType] instance manually\n" +
            "  3. Define a ProtoUDT[YourType] for complex serialization needs"
        )
    }

  private inline def isOption[T]: Boolean =
    inline erasedValue[T] match
      case _: Option[?] => true
      case _            => false

  // Factory method for product encoders (must be accessible from inline expansion)
  def makeProductEncoder[T](
      fieldEncoders: Vector[FieldEncoder[?]],
      theSchema: ProtoSchema,
      ct: ClassTag[T]
  ): ProtoEncoder[T] = new ProtoEncoder[T]:
    def schema: ProtoSchema = theSchema
    val catalystType: ProtoType = ProtoType.StructType(theSchema.fields)
    val nullable: Boolean = false
    val clsTag: ClassTag[T] = ct
    override def fields: Vector[FieldEncoder[?]] = fieldEncoders

  // === Primitive encoders ===

  private class PrimitiveEncoder[T](val catalystType: ProtoType, val clsTag: ClassTag[T])
      extends ProtoEncoder[T]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val nullable: Boolean = false

  given ProtoEncoder[Boolean] = PrimitiveEncoder(ProtoType.BooleanType, classTag[Boolean])
  given ProtoEncoder[Byte] = PrimitiveEncoder(ProtoType.ByteType, classTag[Byte])
  given ProtoEncoder[Short] = PrimitiveEncoder(ProtoType.ShortType, classTag[Short])
  given ProtoEncoder[Int] = PrimitiveEncoder(ProtoType.IntType, classTag[Int])
  given ProtoEncoder[Long] = PrimitiveEncoder(ProtoType.LongType, classTag[Long])
  given ProtoEncoder[Float] = PrimitiveEncoder(ProtoType.FloatType, classTag[Float])
  given ProtoEncoder[Double] = PrimitiveEncoder(ProtoType.DoubleType, classTag[Double])
  given ProtoEncoder[String] = PrimitiveEncoder(ProtoType.StringType, classTag[String])
  given ProtoEncoder[Array[Byte]] = PrimitiveEncoder(ProtoType.BinaryType, classTag[Array[Byte]])
  given ProtoEncoder[BigDecimal] =
    PrimitiveEncoder(ProtoType.DecimalType(38, 18), classTag[BigDecimal])
  given scalaBigIntEncoder: ProtoEncoder[BigInt] =
    PrimitiveEncoder(ProtoType.DecimalType(38, 0), classTag[BigInt])

  // java.time types
  given ProtoEncoder[java.time.LocalDate] =
    PrimitiveEncoder(ProtoType.DateType, classTag[java.time.LocalDate])
  given ProtoEncoder[java.time.Instant] =
    PrimitiveEncoder(ProtoType.TimestampType, classTag[java.time.Instant])
  given ProtoEncoder[java.time.LocalDateTime] =
    PrimitiveEncoder(ProtoType.TimestampNTZType, classTag[java.time.LocalDateTime])
  given ProtoEncoder[java.time.Duration] =
    PrimitiveEncoder(ProtoType.DayTimeIntervalType, classTag[java.time.Duration])
  given ProtoEncoder[java.time.Period] =
    PrimitiveEncoder(ProtoType.YearMonthIntervalType, classTag[java.time.Period])
  given ProtoEncoder[java.time.LocalTime] =
    PrimitiveEncoder(ProtoType.TimeType(6), classTag[java.time.LocalTime])

  // UUID (stored as StringType - canonical 36-character representation)
  given ProtoEncoder[java.util.UUID] =
    PrimitiveEncoder(ProtoType.StringType, classTag[java.util.UUID])

  // java.sql types (legacy compatibility)
  given javaSqlDateEncoder: ProtoEncoder[java.sql.Date] =
    PrimitiveEncoder(ProtoType.DateType, classTag[java.sql.Date])
  given javaSqlTimestampEncoder: ProtoEncoder[java.sql.Timestamp] =
    PrimitiveEncoder(ProtoType.TimestampType, classTag[java.sql.Timestamp])

  // === Boxed primitive encoders ===
  // Java wrapper classes - nullable since they can be null (unlike primitives)
  // Note: Scala 3 unifies scala.Int with java.lang.Integer, etc.
  // We use explicit names to avoid conflicts with primitive encoders.

  private class BoxedPrimitiveEncoder[T](val catalystType: ProtoType, val clsTag: ClassTag[T])
      extends ProtoEncoder[T]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val nullable: Boolean = true // Boxed types can be null

  // Note: In Scala 3, java.lang.Integer IS scala.Int at the type level, but they differ at runtime
  // We provide boxed encoders with explicit names and lower priority to avoid ambiguity
  given boxedBooleanEncoder: ProtoEncoder[java.lang.Boolean] =
    BoxedPrimitiveEncoder(ProtoType.BooleanType, classTag[java.lang.Boolean])
  given boxedByteEncoder: ProtoEncoder[java.lang.Byte] =
    BoxedPrimitiveEncoder(ProtoType.ByteType, classTag[java.lang.Byte])
  given boxedShortEncoder: ProtoEncoder[java.lang.Short] =
    BoxedPrimitiveEncoder(ProtoType.ShortType, classTag[java.lang.Short])
  given boxedIntEncoder: ProtoEncoder[java.lang.Integer] =
    BoxedPrimitiveEncoder(ProtoType.IntType, classTag[java.lang.Integer])
  given boxedLongEncoder: ProtoEncoder[java.lang.Long] =
    BoxedPrimitiveEncoder(ProtoType.LongType, classTag[java.lang.Long])
  given boxedFloatEncoder: ProtoEncoder[java.lang.Float] =
    BoxedPrimitiveEncoder(ProtoType.FloatType, classTag[java.lang.Float])
  given boxedDoubleEncoder: ProtoEncoder[java.lang.Double] =
    BoxedPrimitiveEncoder(ProtoType.DoubleType, classTag[java.lang.Double])
  given boxedCharEncoder: ProtoEncoder[java.lang.Character] =
    BoxedPrimitiveEncoder(ProtoType.StringType, classTag[java.lang.Character])

  // Java BigDecimal and BigInteger
  given javaBigDecimalEncoder: ProtoEncoder[java.math.BigDecimal] =
    BoxedPrimitiveEncoder(ProtoType.DecimalType(38, 18), classTag[java.math.BigDecimal])
  given javaBigIntegerEncoder: ProtoEncoder[java.math.BigInteger] =
    BoxedPrimitiveEncoder(ProtoType.DecimalType(38, 0), classTag[java.math.BigInteger])

  // Null type (java.lang.Void) - for null-only columns
  given voidEncoder: ProtoEncoder[java.lang.Void] =
    PrimitiveEncoder(ProtoType.NullType, classTag[java.lang.Void])

  // === Char(n) and Varchar(n) encoders ===
  // These are String at runtime but with length constraints in the schema.
  // Use these factory functions when you need to specify the max length.

  /** Create a String encoder with Char(n) type (fixed-length string). */
  def charEncoder(length: Int): ProtoEncoder[String] =
    PrimitiveEncoder(ProtoType.CharType(length), classTag[String])

  /** Create a String encoder with Varchar(n) type (variable-length string with max). */
  def varcharEncoder(length: Int): ProtoEncoder[String] =
    PrimitiveEncoder(ProtoType.VarcharType(length), classTag[String])

  // === Option encoder ===

  given optionEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Option[T]] =
    OptionEncoder(enc)

  private class OptionEncoder[T](enc: ProtoEncoder[T]) extends ProtoEncoder[Option[T]]:
    val schema: ProtoSchema = enc.schema
    val catalystType: ProtoType = enc.catalystType
    val nullable: Boolean = true
    val clsTag: ClassTag[Option[T]] = ClassTag(classOf[Option[?]])

  // === Tuple encoders ===
  // Tuples are Products in Scala 3, but we provide explicit given instances
  // for better error messages and to ensure they work without explicit derivation

  given tuple2Encoder[A, B](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B]
  ): ProtoEncoder[(A, B)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable)
      ),
      ClassTag(classOf[Tuple2[?, ?]])
    )

  given tuple3Encoder[A, B, C](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C]
  ): ProtoEncoder[(A, B, C)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable)
      ),
      ClassTag(classOf[Tuple3[?, ?, ?]])
    )

  given tuple4Encoder[A, B, C, D](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D]
  ): ProtoEncoder[(A, B, C, D)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable)
      ),
      ClassTag(classOf[Tuple4[?, ?, ?, ?]])
    )

  given tuple5Encoder[A, B, C, D, E](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E]
  ): ProtoEncoder[(A, B, C, D, E)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable)
      ),
      ClassTag(classOf[Tuple5[?, ?, ?, ?, ?]])
    )

  given tuple6Encoder[A, B, C, D, E, F](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F]
  ): ProtoEncoder[(A, B, C, D, E, F)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable)
      ),
      ClassTag(classOf[Tuple6[?, ?, ?, ?, ?, ?]])
    )

  given tuple7Encoder[A, B, C, D, E, F, G](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G]
  ): ProtoEncoder[(A, B, C, D, E, F, G)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable)
      ),
      ClassTag(classOf[Tuple7[?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple8Encoder[A, B, C, D, E, F, G, H](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable)
      ),
      ClassTag(classOf[Tuple8[?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple9Encoder[A, B, C, D, E, F, G, H, I](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable)
      ),
      ClassTag(classOf[Tuple9[?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple10Encoder[A, B, C, D, E, F, G, H, I, J](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable)
      ),
      ClassTag(classOf[Tuple10[?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple11Encoder[A, B, C, D, E, F, G, H, I, J, K](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable)
      ),
      ClassTag(classOf[Tuple11[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple12Encoder[A, B, C, D, E, F, G, H, I, J, K, L](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable)
      ),
      ClassTag(classOf[Tuple12[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple13Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable)
      ),
      ClassTag(classOf[Tuple13[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple14Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable)
      ),
      ClassTag(classOf[Tuple14[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple15Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N],
      encO: ProtoEncoder[O]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable),
        FieldEncoder("_15", encO, encO.nullable)
      ),
      ClassTag(classOf[Tuple15[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple16Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N],
      encO: ProtoEncoder[O],
      encP: ProtoEncoder[P]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable),
        FieldEncoder("_15", encO, encO.nullable),
        FieldEncoder("_16", encP, encP.nullable)
      ),
      ClassTag(classOf[Tuple16[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple17Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N],
      encO: ProtoEncoder[O],
      encP: ProtoEncoder[P],
      encQ: ProtoEncoder[Q]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable),
        FieldEncoder("_15", encO, encO.nullable),
        FieldEncoder("_16", encP, encP.nullable),
        FieldEncoder("_17", encQ, encQ.nullable)
      ),
      ClassTag(classOf[Tuple17[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple18Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N],
      encO: ProtoEncoder[O],
      encP: ProtoEncoder[P],
      encQ: ProtoEncoder[Q],
      encR: ProtoEncoder[R]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable),
        FieldEncoder("_15", encO, encO.nullable),
        FieldEncoder("_16", encP, encP.nullable),
        FieldEncoder("_17", encQ, encQ.nullable),
        FieldEncoder("_18", encR, encR.nullable)
      ),
      ClassTag(classOf[Tuple18[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple19Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N],
      encO: ProtoEncoder[O],
      encP: ProtoEncoder[P],
      encQ: ProtoEncoder[Q],
      encR: ProtoEncoder[R],
      encS: ProtoEncoder[S]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable),
        FieldEncoder("_15", encO, encO.nullable),
        FieldEncoder("_16", encP, encP.nullable),
        FieldEncoder("_17", encQ, encQ.nullable),
        FieldEncoder("_18", encR, encR.nullable),
        FieldEncoder("_19", encS, encS.nullable)
      ),
      ClassTag(classOf[Tuple19[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple20Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N],
      encO: ProtoEncoder[O],
      encP: ProtoEncoder[P],
      encQ: ProtoEncoder[Q],
      encR: ProtoEncoder[R],
      encS: ProtoEncoder[S],
      encT: ProtoEncoder[T]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable),
        FieldEncoder("_15", encO, encO.nullable),
        FieldEncoder("_16", encP, encP.nullable),
        FieldEncoder("_17", encQ, encQ.nullable),
        FieldEncoder("_18", encR, encR.nullable),
        FieldEncoder("_19", encS, encS.nullable),
        FieldEncoder("_20", encT, encT.nullable)
      ),
      ClassTag(classOf[Tuple20[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple21Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N],
      encO: ProtoEncoder[O],
      encP: ProtoEncoder[P],
      encQ: ProtoEncoder[Q],
      encR: ProtoEncoder[R],
      encS: ProtoEncoder[S],
      encT: ProtoEncoder[T],
      encU: ProtoEncoder[U]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable),
        FieldEncoder("_15", encO, encO.nullable),
        FieldEncoder("_16", encP, encP.nullable),
        FieldEncoder("_17", encQ, encQ.nullable),
        FieldEncoder("_18", encR, encR.nullable),
        FieldEncoder("_19", encS, encS.nullable),
        FieldEncoder("_20", encT, encT.nullable),
        FieldEncoder("_21", encU, encU.nullable)
      ),
      ClassTag(classOf[Tuple21[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  given tuple22Encoder[A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V](using
      encA: ProtoEncoder[A],
      encB: ProtoEncoder[B],
      encC: ProtoEncoder[C],
      encD: ProtoEncoder[D],
      encE: ProtoEncoder[E],
      encF: ProtoEncoder[F],
      encG: ProtoEncoder[G],
      encH: ProtoEncoder[H],
      encI: ProtoEncoder[I],
      encJ: ProtoEncoder[J],
      encK: ProtoEncoder[K],
      encL: ProtoEncoder[L],
      encM: ProtoEncoder[M],
      encN: ProtoEncoder[N],
      encO: ProtoEncoder[O],
      encP: ProtoEncoder[P],
      encQ: ProtoEncoder[Q],
      encR: ProtoEncoder[R],
      encS: ProtoEncoder[S],
      encT: ProtoEncoder[T],
      encU: ProtoEncoder[U],
      encV: ProtoEncoder[V]
  ): ProtoEncoder[(A, B, C, D, E, F, G, H, I, J, K, L, M, N, O, P, Q, R, S, T, U, V)] =
    makeTupleEncoder(
      Vector(
        FieldEncoder("_1", encA, encA.nullable),
        FieldEncoder("_2", encB, encB.nullable),
        FieldEncoder("_3", encC, encC.nullable),
        FieldEncoder("_4", encD, encD.nullable),
        FieldEncoder("_5", encE, encE.nullable),
        FieldEncoder("_6", encF, encF.nullable),
        FieldEncoder("_7", encG, encG.nullable),
        FieldEncoder("_8", encH, encH.nullable),
        FieldEncoder("_9", encI, encI.nullable),
        FieldEncoder("_10", encJ, encJ.nullable),
        FieldEncoder("_11", encK, encK.nullable),
        FieldEncoder("_12", encL, encL.nullable),
        FieldEncoder("_13", encM, encM.nullable),
        FieldEncoder("_14", encN, encN.nullable),
        FieldEncoder("_15", encO, encO.nullable),
        FieldEncoder("_16", encP, encP.nullable),
        FieldEncoder("_17", encQ, encQ.nullable),
        FieldEncoder("_18", encR, encR.nullable),
        FieldEncoder("_19", encS, encS.nullable),
        FieldEncoder("_20", encT, encT.nullable),
        FieldEncoder("_21", encU, encU.nullable),
        FieldEncoder("_22", encV, encV.nullable)
      ),
      ClassTag(classOf[Tuple22[?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?]])
    )

  private def makeTupleEncoder[T](
      fieldEncoders: Vector[FieldEncoder[?]],
      ct: ClassTag[T]
  ): ProtoEncoder[T] =
    val structFields = fieldEncoders.map { fe =>
      ProtoStructField(fe.name, fe.encoder.catalystType, fe.nullable)
    }
    makeProductEncoder[T](fieldEncoders, ProtoSchema(structFields), ct)

  // === Collection encoders ===

  given seqEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Seq[T]] =
    CollectionEncoder(enc, ClassTag(classOf[Seq[?]]))

  given listEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[List[T]] =
    CollectionEncoder(enc, ClassTag(classOf[List[?]]))

  given vectorEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Vector[T]] =
    CollectionEncoder(enc, ClassTag(classOf[Vector[?]]))

  given setEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Set[T]] =
    CollectionEncoder(enc, ClassTag(classOf[Set[?]]))

  given arrayEncoder[T](using
      enc: ProtoEncoder[T],
      ct: ClassTag[Array[T]]
  ): ProtoEncoder[Array[T]] =
    CollectionEncoder(enc, ct)

  private class CollectionEncoder[C, T](enc: ProtoEncoder[T], val clsTag: ClassTag[C])
      extends ProtoEncoder[C]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.ArrayType(enc.catalystType, enc.nullable)
    val nullable: Boolean = false

  given mapEncoder[K, V](using
      keyEnc: ProtoEncoder[K],
      valEnc: ProtoEncoder[V]
  ): ProtoEncoder[Map[K, V]] =
    MapEncoder(keyEnc, valEnc)

  private class MapEncoder[K, V](keyEnc: ProtoEncoder[K], valEnc: ProtoEncoder[V])
      extends ProtoEncoder[Map[K, V]]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType =
      ProtoType.MapType(keyEnc.catalystType, valEnc.catalystType, valEnc.nullable)
    val nullable: Boolean = false
    val clsTag: ClassTag[Map[K, V]] = ClassTag(classOf[Map[?, ?]])

  // === Java enum encoder ===

  given javaEnumEncoder[E <: java.lang.Enum[E]](using ct: ClassTag[E]): ProtoEncoder[E] =
    JavaEnumEncoder(ct)

  private class JavaEnumEncoder[E <: java.lang.Enum[E]](val clsTag: ClassTag[E])
      extends ProtoEncoder[E]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.StringType // Java enums stored as strings
    val nullable: Boolean = false

  // === UDT (User-Defined Type) encoder ===

  /** Create an encoder for a type with a ProtoUDT. The encoder wraps the UDT's sqlType with UDT
    * metadata for proper serialization.
    *
    * Usage:
    * {{{
    * case class Point(x: Double, y: Double)
    * object PointUDT extends ProtoUDT[Point] { ... }
    *
    * // Option 1: Explicit encoder creation
    * given ProtoEncoder[Point] = ProtoEncoder.fromUDT(PointUDT)
    *
    * // Option 2: Use the udtEncoder given with implicit UDT
    * given ProtoUDT[Point] = PointUDT
    * val enc = summon[ProtoEncoder[Point]]  // Uses udtEncoder
    * }}}
    */
  def fromUDT[T >: Null](udt: ProtoUDT[T]): ProtoEncoder[T] =
    ProtoUDT.register(udt)
    UDTEncoder(udt)

  /** Implicit encoder derivation for types with a ProtoUDT in scope. This allows automatic encoder
    * creation when a UDT is available.
    */
  given udtEncoder[T >: Null](using udt: ProtoUDT[T]): ProtoEncoder[T] =
    fromUDT(udt)

  private class UDTEncoder[T >: Null](udt: ProtoUDT[T]) extends ProtoEncoder[T]:
    val schema: ProtoSchema = udt.sqlType match
      case ProtoType.StructType(fields) => ProtoSchema(fields)
      case _                            => ProtoSchema(Vector.empty)
    val catalystType: ProtoType = udt.protoType
    val nullable: Boolean = udt.nullable
    val clsTag: ClassTag[T] = udt.classTag

    /** Access the underlying UDT for serialization/deserialization. */
    def protoUDT: ProtoUDT[T] = udt

  /** Extract the UDT from an encoder if it's a UDT encoder. */
  def extractUDT[T >: Null](enc: ProtoEncoder[T]): Option[ProtoUDT[T]] = enc match
    case udtEnc: UDTEncoder[T @unchecked] => Some(udtEnc.protoUDT)
    case _                                => None
