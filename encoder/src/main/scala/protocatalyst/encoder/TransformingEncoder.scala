package protocatalyst.encoder

import scala.reflect.ClassTag

import com.esotericsoftware.kryo.Kryo
import org.apache.fury.config.FuryBuilder

import protocatalyst.encoder.codec.{
  BinaryCodec,
  ForyCodecImpl,
  ForySerializationCodec,
  JavaSerializationCodec,
  KryoCodecImpl,
  KryoSerializationCodec
}
import protocatalyst.schema.ProtoSchema
import protocatalyst.types.ProtoType

/** Encoder that uses a codec for types that cannot be encoded natively. The value is serialized to
  * bytes and stored as BinaryType.
  *
  * This is the fallback for types that can't be represented using standard encoders, such as
  * third-party library types without a ProtoUDT definition.
  *
  * Matches Spark's TransformingEncoder design:
  *   - Takes a codec provider (factory) for lazy instantiation and serialization safety
  *   - Stores transformed values as BinaryType in the schema
  *
  * @param clsTag
  *   ClassTag for the external type
  * @param codecProvider
  *   Factory for creating codec instances (lazy for serialization)
  */
class TransformingEncoder[T](
    val clsTag: ClassTag[T],
    val codecProvider: () => BinaryCodec[Any]
) extends ProtoEncoder[T]:
  val schema: ProtoSchema = ProtoSchema(Vector.empty)
  val catalystType: ProtoType = ProtoType.BinaryType
  val nullable: Boolean = true

  /** Get or create the codec instance (lazily initialized). */
  @transient private lazy val codec: BinaryCodec[Any] = codecProvider()

  /** Encode value to bytes using the codec. */
  def encode(value: T): Array[Byte] = codec.encode(value)

  /** Decode bytes to value using the codec. */
  def decode(bytes: Array[Byte]): T = codec.decode(bytes).asInstanceOf[T]

object TransformingEncoder:
  /** Create a TransformingEncoder with a specific codec provider.
    *
    * @param codecProvider
    *   Factory function that creates the codec instance
    */
  def apply[T: ClassTag](codecProvider: () => BinaryCodec[Any]): TransformingEncoder[T] =
    new TransformingEncoder[T](summon[ClassTag[T]], codecProvider)

  /** Create a TransformingEncoder using Java serialization. No external dependencies required, but
    * slower than Kryo or Fory.
    *
    * Example:
    * {{{
    * class ThirdPartyType(val data: String) extends Serializable
    * given ProtoEncoder[ThirdPartyType] = TransformingEncoder.java[ThirdPartyType]
    * }}}
    */
  def java[T: ClassTag]: TransformingEncoder[T] =
    apply[T](JavaSerializationCodec)

  /** Create a TransformingEncoder using the default codec (currently Java serialization).
    */
  def default[T: ClassTag]: TransformingEncoder[T] =
    java[T]

  /** Create a TransformingEncoder using Kryo serialization. ~10x faster than Java serialization.
    * Requires kryo dependency.
    *
    * Example:
    * {{{
    * case class ThirdPartyType(data: String)
    * given ProtoEncoder[ThirdPartyType] = TransformingEncoder.kryo[ThirdPartyType]
    * }}}
    */
  def kryo[T: ClassTag]: TransformingEncoder[T] =
    apply[T](KryoSerializationCodec)

  /** Create a TransformingEncoder using Kryo with custom configuration.
    *
    * Example:
    * {{{
    * given ProtoEncoder[MyType] = TransformingEncoder.kryo[MyType] { kryo =>
    *   kryo.register(classOf[MyType])
    * }
    * }}}
    */
  def kryo[T: ClassTag](configure: Kryo => Unit): TransformingEncoder[T] =
    apply[T](() => KryoCodecImpl.withConfig(configure))

  /** Create a TransformingEncoder using Apache Fory (formerly Fury) serialization. Up to 170x
    * faster than Java serialization with Scala 3 native support. Requires fory-scala_3 dependency.
    *
    * Example:
    * {{{
    * case class ThirdPartyType(data: String)
    * given ProtoEncoder[ThirdPartyType] = TransformingEncoder.fory[ThirdPartyType]
    * }}}
    */
  def fory[T: ClassTag]: TransformingEncoder[T] =
    apply[T](ForySerializationCodec)

  /** Create a TransformingEncoder using Fory with custom configuration.
    *
    * Example:
    * {{{
    * given ProtoEncoder[MyType] = TransformingEncoder.fory[MyType] { builder =>
    *   builder.requireClassRegistration(true)
    * }
    * }}}
    */
  def fory[T: ClassTag](configure: FuryBuilder => FuryBuilder): TransformingEncoder[T] =
    apply[T](() => ForyCodecImpl.withConfig(configure))
