package protocatalyst.ml.types

import java.io.Serializable

/** Raw tensor data for constant values. */
case class TensorData(
    dtype: TensorDType,
    shape: Vector[Int],
    rawBytes: Array[Byte]
) extends Serializable:
  def numElements: Long = shape.map(_.toLong).product

  override def equals(other: Any): Boolean = other match
    case that: TensorData =>
      this.dtype == that.dtype &&
      this.shape == that.shape &&
      java.util.Arrays.equals(this.rawBytes, that.rawBytes)
    case _ => false

  override def hashCode(): Int =
    val h1 = dtype.hashCode()
    val h2 = shape.hashCode()
    val h3 = java.util.Arrays.hashCode(rawBytes)
    31 * (31 * h1 + h2) + h3

/** Weight/parameter initializer strategy. */
enum Initializer extends Serializable:
  case Zeros
  case Ones
  case Xavier(gain: Double)
  case Kaiming(mode: String, nonlinearity: String)
  case Normal(mean: Double, std: Double)
  case Uniform(low: Double, high: Double)

/** Padding mode for Pad operations. */
enum PadMode extends Serializable:
  case Constant, Reflect, Edge

/** Reduction mode for loss functions. */
enum Reduction extends Serializable:
  case Mean, Sum, None

/** Attribute values for opaque/custom operations. */
enum OpAttribute extends Serializable:
  case IntAttr(value: Long)
  case FloatAttr(value: Double)
  case StringAttr(value: String)
  case IntsAttr(values: Vector[Long])
  case FloatsAttr(values: Vector[Double])
  case TensorAttr(value: TensorData)
