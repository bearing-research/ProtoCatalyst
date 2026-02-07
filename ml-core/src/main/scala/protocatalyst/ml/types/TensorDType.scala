package protocatalyst.ml.types

import java.io.Serializable

/** Element data type for tensors. */
enum TensorDType extends Serializable:
  case Float16
  case Float32
  case Float64
  case BFloat16
  case Int8
  case Int16
  case Int32
  case Int64
  case UInt8
  case Bool
  case Complex64
  case Complex128

/** Tensor dimension — static (known at compile time) or dynamic (resolved at runtime). */
enum Dim extends Serializable:
  case Static(size: Int)
  case Dynamic(name: Option[String])

object Dim:
  def apply(size: Int): Dim = Static(size)
  def dynamic: Dim = Dynamic(None)
  def dynamic(name: String): Dim = Dynamic(Some(name))

/** Data layout for spatial tensors. */
enum DataLayout extends Serializable:
  case Default
  case NCHW // batch, channels, height, width
  case NHWC // batch, height, width, channels

/** Runtime tensor type, carried in the IR. */
case class TensorType(
    dtype: TensorDType,
    shape: Vector[Dim],
    layout: DataLayout = DataLayout.Default
) extends Serializable:
  def rank: Int = shape.size

  def isFullyStatic: Boolean = shape.forall:
    case Dim.Static(_) => true
    case _             => false

  def staticShape: Option[Vector[Int]] =
    if isFullyStatic then Some(shape.map { case Dim.Static(s) => s; case _ => -1 })
    else None
