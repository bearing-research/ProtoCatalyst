package protocatalyst.mock

import protocatalyst.encoder.ProtoRow

/** Mock row representation for expression evaluation. Similar to Spark's InternalRow. Implements
  * ProtoRow for compatibility with RowEncoder.
  */
case class MockRow(values: Vector[Any]) extends ProtoRow:
  def length: Int = values.size

  def get(i: Int): Any =
    if i < 0 || i >= values.size then
      throw IndexOutOfBoundsException(s"Ordinal $i out of bounds [0, ${values.size})")
    values(i)

  def isNullAt(i: Int): Boolean =
    get(i) == null

  def getBoolean(i: Int): Boolean = get(i).asInstanceOf[Boolean]
  def getByte(i: Int): Byte = get(i).asInstanceOf[Byte]
  def getShort(i: Int): Short = get(i).asInstanceOf[Short]
  def getInt(i: Int): Int = get(i).asInstanceOf[Int]
  def getLong(i: Int): Long = get(i).asInstanceOf[Long]
  def getFloat(i: Int): Float = get(i).asInstanceOf[Float]
  def getDouble(i: Int): Double = get(i).asInstanceOf[Double]

  def getString(i: Int): String = get(i) match
    case s: String            => s
    case utf8: MockUTF8String => utf8.value
    case other                => other.toString

  def getBinary(i: Int): Array[Byte] = get(i).asInstanceOf[Array[Byte]]

  def getDecimal(i: Int): BigDecimal = get(i) match
    case bd: BigDecimal            => bd
    case jbd: java.math.BigDecimal => BigDecimal(jbd)
    case l: Long                   => BigDecimal(l)
    case i: Int                    => BigDecimal(i)
    case d: Double                 => BigDecimal(d)
    case other                     => throw ClassCastException(s"Cannot cast $other to BigDecimal")

  def getSeq[T](i: Int): Seq[T] = get(i) match
    case arr: MockArrayData => arr.values.asInstanceOf[Seq[T]]
    case seq: Seq[?]        => seq.asInstanceOf[Seq[T]]
    case other              => throw ClassCastException(s"Cannot cast $other to Seq")

  def getMap[K, V](i: Int): Map[K, V] = get(i) match
    case mapData: MockMapData => mapData.keys.zip(mapData.values).toMap.asInstanceOf[Map[K, V]]
    case m: Map[?, ?]         => m.asInstanceOf[Map[K, V]]
    case other                => throw ClassCastException(s"Cannot cast $other to Map")

  def getStruct(i: Int): ProtoRow = get(i) match
    case row: MockRow     => row
    case row: ProtoRow    => row
    case product: Product => MockRow(product.productIterator.toVector)
    case other            => throw ClassCastException(s"Cannot cast $other to ProtoRow")

  def toSeq: Seq[Any] = values

  def copy(): ProtoRow = MockRow(values)

  /** Alias for length (legacy compatibility) */
  def size: Int = length

object MockRow:
  def apply(values: Any*): MockRow = MockRow(values.toVector)
  val empty: MockRow = MockRow(Vector.empty)

  def fromSeq(values: Seq[Any]): MockRow = MockRow(values.toVector)

  /** Convert from any ProtoRow */
  def fromProtoRow(row: ProtoRow): MockRow = row match
    case mr: MockRow => mr
    case _           => MockRow(row.toSeq.toVector)
