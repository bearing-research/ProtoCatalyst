package protocatalyst.encoder.codec

import org.apache.fury.{Fury, ThreadSafeFury}
import org.apache.fury.config.FuryBuilder

/**
 * A codec that uses Apache Fory (formerly Fury) for serialization.
 * Up to 170x faster than Java serialization with Scala 3 native support.
 *
 * Thread-safe via ThreadSafeFury wrapper.
 *
 * @see https://fory.apache.org/docs/guide/scala_guide/
 */
class ForyCodecImpl(fury: ThreadSafeFury) extends BinaryCodec[Any]:
  def encode(in: Any): Array[Byte] =
    if in == null then return null
    fury.serialize(in)

  def decode(out: Array[Byte]): Any =
    if out == null then return null
    fury.deserialize(out)

object ForyCodecImpl:
  /** Default configuration with Scala optimizations. */
  def default: ForyCodecImpl =
    val fury = Fury.builder()
      .withScalaOptimizationEnabled(true)
      .withRefTracking(true) // Important for Scala circular refs
      .requireClassRegistration(false)
      .buildThreadSafeFury()
    new ForyCodecImpl(fury)

  /** Create with custom Fory configuration. */
  def withConfig(configure: FuryBuilder => FuryBuilder): ForyCodecImpl =
    val builder = Fury.builder()
      .withScalaOptimizationEnabled(true)
      .withRefTracking(true)
    val fury = configure(builder).buildThreadSafeFury()
    new ForyCodecImpl(fury)

/**
 * Factory for Fory serialization codec.
 * Implements the factory pattern `() => Codec` for lazy instantiation.
 */
object ForySerializationCodec extends (() => BinaryCodec[Any]):
  def apply(): BinaryCodec[Any] = ForyCodecImpl.default
