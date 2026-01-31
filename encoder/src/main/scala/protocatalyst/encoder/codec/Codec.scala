package protocatalyst.encoder.codec

/**
 * Codec for doing conversions between two representations.
 * Matches Spark's Codec trait from codecs.scala.
 *
 * The design uses a factory pattern (`() => Codec`) for two purposes:
 * 1. Lazy instantiation: Codecs are created on-demand
 * 2. Serialization safety: The factory is serializable; the codec instance may not be
 *
 * @tparam I input type (external representation, e.g., user's domain object)
 * @tparam O output type (internal representation, e.g., Array[Byte])
 */
trait Codec[I, O] extends Serializable:
  /** Encode external representation to internal. */
  def encode(in: I): O

  /** Decode internal representation to external. */
  def decode(out: O): I

/** Type alias for codecs that serialize to byte arrays. */
type BinaryCodec[T] = Codec[T, Array[Byte]]
