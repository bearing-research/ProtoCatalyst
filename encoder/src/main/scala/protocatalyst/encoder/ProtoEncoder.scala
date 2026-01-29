package protocatalyst.encoder

import protocatalyst.schema.*
import protocatalyst.types.*
import scala.deriving.Mirror

/** Compile-time derived encoder for Spark. */
trait ProtoEncoder[T]:
  def schema: ProtoSchema
  def catalystType: ProtoType
  def nullable: Boolean

object ProtoEncoder:

  /** Derive an encoder for type T at compile time. */
  inline def derived[T](using m: Mirror.Of[T]): ProtoEncoder[T] =
    inline m match
      case _: Mirror.ProductOf[T] => productEncoder[T]
      case _ => error("Only product types (case classes) are supported")

  // Non-inline helper to avoid code duplication warning
  private def productEncoder[T]: ProtoEncoder[T] =
    // Placeholder - actual implementation uses quotes/macros
    ProductEncoderImpl[T]()

  private class ProductEncoderImpl[T] extends ProtoEncoder[T]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val catalystType: ProtoType = ProtoType.StructType(Vector.empty)
    val nullable: Boolean = false

  private inline def error(msg: String): Nothing =
    scala.compiletime.error(msg)

  // Primitive encoders
  given ProtoEncoder[Boolean] = PrimitiveEncoder(ProtoType.BooleanType)
  given ProtoEncoder[Byte] = PrimitiveEncoder(ProtoType.ByteType)
  given ProtoEncoder[Short] = PrimitiveEncoder(ProtoType.ShortType)
  given ProtoEncoder[Int] = PrimitiveEncoder(ProtoType.IntType)
  given ProtoEncoder[Long] = PrimitiveEncoder(ProtoType.LongType)
  given ProtoEncoder[Float] = PrimitiveEncoder(ProtoType.FloatType)
  given ProtoEncoder[Double] = PrimitiveEncoder(ProtoType.DoubleType)
  given ProtoEncoder[String] = PrimitiveEncoder(ProtoType.StringType)

  private class PrimitiveEncoder[T](val catalystType: ProtoType) extends ProtoEncoder[T]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)
    val nullable: Boolean = false

  given optionEncoder[T](using enc: ProtoEncoder[T]): ProtoEncoder[Option[T]] =
    OptionEncoder(enc)

  private class OptionEncoder[T](enc: ProtoEncoder[T]) extends ProtoEncoder[Option[T]]:
    val schema: ProtoSchema = enc.schema
    val catalystType: ProtoType = enc.catalystType
    val nullable: Boolean = true
