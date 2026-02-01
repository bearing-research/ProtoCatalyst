package protocatalyst.encoder

import java.io.Serializable

/**
 * Generic row type for ProtoCatalyst, equivalent to Spark's Row.
 * Provides positional access to values with type-specific getters.
 *
 * Unlike case classes which have compile-time known structure,
 * ProtoRow is a dynamic container that can hold any values.
 * Schema is provided externally via RowEncoder.
 */
trait ProtoRow extends Serializable:
  /** Number of fields in this row */
  def length: Int

  /** Get value at position i as Any */
  def get(i: Int): Any

  /** Check if value at position i is null */
  def isNullAt(i: Int): Boolean

  /** Apply is alias for get */
  def apply(i: Int): Any = get(i)

  // Type-specific getters
  def getBoolean(i: Int): Boolean
  def getByte(i: Int): Byte
  def getShort(i: Int): Short
  def getInt(i: Int): Int
  def getLong(i: Int): Long
  def getFloat(i: Int): Float
  def getDouble(i: Int): Double
  def getString(i: Int): String
  def getBinary(i: Int): Array[Byte]
  def getDecimal(i: Int): BigDecimal

  // Collection getters
  def getSeq[T](i: Int): Seq[T]
  def getMap[K, V](i: Int): Map[K, V]
  def getStruct(i: Int): ProtoRow

  /** Convert to Seq[Any] */
  def toSeq: Seq[Any]

  /** Create a copy of this row */
  def copy(): ProtoRow

object ProtoRow:
  /** Create a ProtoRow from values */
  def apply(values: Any*): ProtoRow = GenericProtoRow(values.toVector)

  /** Create from a Seq */
  def fromSeq(values: Seq[Any]): ProtoRow = GenericProtoRow(values.toVector)

  /** Empty row */
  val empty: ProtoRow = GenericProtoRow(Vector.empty)

/**
 * Default implementation of ProtoRow backed by a Vector.
 */
case class GenericProtoRow(values: Vector[Any]) extends ProtoRow:
  def length: Int = values.size

  def get(i: Int): Any =
    if i < 0 || i >= values.size then
      throw IndexOutOfBoundsException(s"Index $i out of bounds [0, $length)")
    values(i)

  def isNullAt(i: Int): Boolean = get(i) == null

  def getBoolean(i: Int): Boolean = get(i).asInstanceOf[Boolean]
  def getByte(i: Int): Byte = get(i).asInstanceOf[Byte]
  def getShort(i: Int): Short = get(i).asInstanceOf[Short]
  def getInt(i: Int): Int = get(i).asInstanceOf[Int]
  def getLong(i: Int): Long = get(i).asInstanceOf[Long]
  def getFloat(i: Int): Float = get(i).asInstanceOf[Float]
  def getDouble(i: Int): Double = get(i).asInstanceOf[Double]

  def getString(i: Int): String = get(i) match
    case s: String => s
    case other => other.toString

  def getBinary(i: Int): Array[Byte] = get(i).asInstanceOf[Array[Byte]]

  def getDecimal(i: Int): BigDecimal = get(i) match
    case bd: BigDecimal => bd
    case jbd: java.math.BigDecimal => BigDecimal(jbd)
    case l: Long => BigDecimal(l)
    case i: Int => BigDecimal(i)
    case d: Double => BigDecimal(d)
    case other => throw ClassCastException(s"Cannot cast $other to BigDecimal")

  def getSeq[T](i: Int): Seq[T] = get(i).asInstanceOf[Seq[T]]

  def getMap[K, V](i: Int): Map[K, V] = get(i).asInstanceOf[Map[K, V]]

  def getStruct(i: Int): ProtoRow = get(i) match
    case row: ProtoRow => row
    case product: Product => GenericProtoRow(product.productIterator.toVector)
    case other => throw ClassCastException(s"Cannot cast $other to ProtoRow")

  def toSeq: Seq[Any] = values

  def copy(): ProtoRow = GenericProtoRow(values)
