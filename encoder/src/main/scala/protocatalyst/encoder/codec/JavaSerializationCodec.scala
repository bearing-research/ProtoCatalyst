package protocatalyst.encoder.codec

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

/** A codec that uses Java Serialization as its output format. Thread-safe, no dependencies, but
  * slower than alternatives like Kryo or Fory.
  *
  * Matches Spark's JavaSerializationCodec from codecs.scala.
  */
class JavaCodecImpl[I] extends BinaryCodec[I]:
  def encode(in: I): Array[Byte] =
    if in == null then return null
    val baos = ByteArrayOutputStream()
    val oos = ObjectOutputStream(baos)
    try
      oos.writeObject(in)
      baos.toByteArray
    finally oos.close()

  def decode(out: Array[Byte]): I =
    if out == null then return null.asInstanceOf[I]
    val bais = ByteArrayInputStream(out)
    val ois = ObjectInputStream(bais)
    try
      ois.readObject().asInstanceOf[I]
    finally
      ois.close()

/** Factory for Java serialization codec. Implements the factory pattern `() => Codec` for lazy
  * instantiation.
  */
object JavaSerializationCodec extends (() => BinaryCodec[Any]):
  def apply(): BinaryCodec[Any] = new JavaCodecImpl[Any]
