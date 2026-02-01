package protocatalyst.encoder

import protocatalyst.schema.*
import protocatalyst.types.*
import scala.deriving.Mirror
import scala.compiletime.*
import scala.reflect.{ClassTag, classTag}

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
  private inline def deriveVariants[Types <: Tuple, Labels <: Tuple](ordinal: Int): Vector[VariantEncoder[?]] =
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
    val catalystType: ProtoType = ProtoType.StringType  // Enums stored as strings
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
      case enc: ProtoEncoder[T] => enc
      case _: Mirror.ProductOf[T] => derived[T]
      case _: Mirror.SumOf[T] => derived[T]  // Support enums as fields
      case _ =>
        error(
          "Cannot find or derive ProtoEncoder for type.\n\n" +
            "Supported types:\n" +
            "  - Primitives: Boolean, Byte, Short, Int, Long, Float, Double, String, Array[Byte], BigDecimal\n" +
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
      case _ => false

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

  private class PrimitiveEncoder[T](val catalystType: ProtoType, val clsTag: ClassTag[T]) extends ProtoEncoder[T]:
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
  given ProtoEncoder[BigDecimal] = PrimitiveEncoder(ProtoType.DecimalType(38, 18), classTag[BigDecimal])

  // java.time types
  given ProtoEncoder[java.time.LocalDate] = PrimitiveEncoder(ProtoType.DateType, classTag[java.time.LocalDate])
  given ProtoEncoder[java.time.Instant] = PrimitiveEncoder(ProtoType.TimestampType, classTag[java.time.Instant])
  given ProtoEncoder[java.time.LocalDateTime] = PrimitiveEncoder(ProtoType.TimestampNTZType, classTag[java.time.LocalDateTime])
  given ProtoEncoder[java.time.Duration] = PrimitiveEncoder(ProtoType.DayTimeIntervalType, classTag[java.time.Duration])
  given ProtoEncoder[java.time.Period] = PrimitiveEncoder(ProtoType.YearMonthIntervalType, classTag[java.time.Period])

  // java.sql types (legacy compatibility)
  given javaSqlDateEncoder: ProtoEncoder[java.sql.Date] = PrimitiveEncoder(ProtoType.DateType, classTag[java.sql.Date])
  given javaSqlTimestampEncoder: ProtoEncoder[java.sql.Timestamp] = PrimitiveEncoder(ProtoType.TimestampType, classTag[java.sql.Timestamp])

  // === Boxed primitive encoders ===
  // Java wrapper classes - nullable since they can be null (unlike primitives)
  // Note: Scala 3 unifies scala.Int with java.lang.Integer, etc.
  // We use explicit names to avoid conflicts with primitive encoders.

  private class BoxedPrimitiveEncoder[T](val catalystType: ProtoType, val clsTag: ClassTag[T]) extends ProtoEncoder[T]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val nullable: Boolean = true // Boxed types can be null

  // Note: In Scala 3, java.lang.Integer IS scala.Int at the type level, but they differ at runtime
  // We provide boxed encoders with explicit names and lower priority to avoid ambiguity
  given boxedBooleanEncoder: ProtoEncoder[java.lang.Boolean] = BoxedPrimitiveEncoder(ProtoType.BooleanType, classTag[java.lang.Boolean])
  given boxedByteEncoder: ProtoEncoder[java.lang.Byte] = BoxedPrimitiveEncoder(ProtoType.ByteType, classTag[java.lang.Byte])
  given boxedShortEncoder: ProtoEncoder[java.lang.Short] = BoxedPrimitiveEncoder(ProtoType.ShortType, classTag[java.lang.Short])
  given boxedIntEncoder: ProtoEncoder[java.lang.Integer] = BoxedPrimitiveEncoder(ProtoType.IntType, classTag[java.lang.Integer])
  given boxedLongEncoder: ProtoEncoder[java.lang.Long] = BoxedPrimitiveEncoder(ProtoType.LongType, classTag[java.lang.Long])
  given boxedFloatEncoder: ProtoEncoder[java.lang.Float] = BoxedPrimitiveEncoder(ProtoType.FloatType, classTag[java.lang.Float])
  given boxedDoubleEncoder: ProtoEncoder[java.lang.Double] = BoxedPrimitiveEncoder(ProtoType.DoubleType, classTag[java.lang.Double])
  given boxedCharEncoder: ProtoEncoder[java.lang.Character] = BoxedPrimitiveEncoder(ProtoType.StringType, classTag[java.lang.Character])

  // Java BigDecimal and BigInteger
  given javaBigDecimalEncoder: ProtoEncoder[java.math.BigDecimal] = BoxedPrimitiveEncoder(ProtoType.DecimalType(38, 18), classTag[java.math.BigDecimal])
  given javaBigIntegerEncoder: ProtoEncoder[java.math.BigInteger] = BoxedPrimitiveEncoder(ProtoType.DecimalType(38, 0), classTag[java.math.BigInteger])

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

  private def makeTupleEncoder[T](fieldEncoders: Vector[FieldEncoder[?]], ct: ClassTag[T]): ProtoEncoder[T] =
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

  given arrayEncoder[T](using enc: ProtoEncoder[T], ct: ClassTag[Array[T]]): ProtoEncoder[Array[T]] =
    CollectionEncoder(enc, ct)

  private class CollectionEncoder[C, T](enc: ProtoEncoder[T], val clsTag: ClassTag[C]) extends ProtoEncoder[C]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.ArrayType(enc.catalystType, enc.nullable)
    val nullable: Boolean = false

  given mapEncoder[K, V](using keyEnc: ProtoEncoder[K], valEnc: ProtoEncoder[V]): ProtoEncoder[Map[K, V]] =
    MapEncoder(keyEnc, valEnc)

  private class MapEncoder[K, V](keyEnc: ProtoEncoder[K], valEnc: ProtoEncoder[V]) extends ProtoEncoder[Map[K, V]]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.MapType(keyEnc.catalystType, valEnc.catalystType, valEnc.nullable)
    val nullable: Boolean = false
    val clsTag: ClassTag[Map[K, V]] = ClassTag(classOf[Map[?, ?]])

  // === Java enum encoder ===

  given javaEnumEncoder[E <: java.lang.Enum[E]](using ct: ClassTag[E]): ProtoEncoder[E] =
    JavaEnumEncoder(ct)

  private class JavaEnumEncoder[E <: java.lang.Enum[E]](val clsTag: ClassTag[E]) extends ProtoEncoder[E]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.StringType // Java enums stored as strings
    val nullable: Boolean = false

  // === UDT (User-Defined Type) encoder ===

  /**
   * Create an encoder for a type with a ProtoUDT.
   * The encoder wraps the UDT's sqlType with UDT metadata for proper serialization.
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

  /**
   * Implicit encoder derivation for types with a ProtoUDT in scope.
   * This allows automatic encoder creation when a UDT is available.
   */
  given udtEncoder[T >: Null](using udt: ProtoUDT[T]): ProtoEncoder[T] =
    fromUDT(udt)

  private class UDTEncoder[T >: Null](udt: ProtoUDT[T]) extends ProtoEncoder[T]:
    val schema: ProtoSchema = udt.sqlType match
      case ProtoType.StructType(fields) => ProtoSchema(fields)
      case _ => ProtoSchema(Vector.empty)
    val catalystType: ProtoType = udt.protoType
    val nullable: Boolean = udt.nullable
    val clsTag: ClassTag[T] = udt.classTag

    /** Access the underlying UDT for serialization/deserialization. */
    def protoUDT: ProtoUDT[T] = udt

  /** Extract the UDT from an encoder if it's a UDT encoder. */
  def extractUDT[T >: Null](enc: ProtoEncoder[T]): Option[ProtoUDT[T]] = enc match
    case udtEnc: UDTEncoder[T @unchecked] => Some(udtEnc.protoUDT)
    case _ => None
