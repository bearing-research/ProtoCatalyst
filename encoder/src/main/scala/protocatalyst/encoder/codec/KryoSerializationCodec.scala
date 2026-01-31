package protocatalyst.encoder.codec

import com.esotericsoftware.kryo.Kryo
import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.util.DefaultInstantiatorStrategy
import org.objenesis.strategy.StdInstantiatorStrategy

/**
 * A codec that uses Kryo for serialization.
 * ~10x faster than Java serialization.
 *
 * Thread-safe via ThreadLocal Kryo instances.
 *
 * @param configure Optional configuration function for Kryo instance
 */
class KryoCodecImpl(configure: Kryo => Unit = _ => ()) extends BinaryCodec[Any]:
  // Thread-local Kryo instances for thread safety
  private val kryoPool = new ThreadLocal[Kryo]:
    override def initialValue(): Kryo =
      val kryo = new Kryo()
      kryo.setRegistrationRequired(false)
      kryo.setInstantiatorStrategy(
        new DefaultInstantiatorStrategy(new StdInstantiatorStrategy())
      )
      configure(kryo)
      kryo

  def encode(in: Any): Array[Byte] =
    if in == null then return null
    val output = new Output(4096, -1)
    try
      kryoPool.get().writeClassAndObject(output, in)
      output.toBytes
    finally
      output.close()

  def decode(out: Array[Byte]): Any =
    if out == null then return null
    val input = new Input(out)
    try
      kryoPool.get().readClassAndObject(input)
    finally
      input.close()

object KryoCodecImpl:
  /** Create with custom Kryo configuration. */
  def withConfig(configure: Kryo => Unit): KryoCodecImpl =
    new KryoCodecImpl(configure)

/**
 * Factory for Kryo serialization codec.
 * Implements the factory pattern `() => Codec` for lazy instantiation.
 */
object KryoSerializationCodec extends (() => BinaryCodec[Any]):
  def apply(): BinaryCodec[Any] = new KryoCodecImpl()
