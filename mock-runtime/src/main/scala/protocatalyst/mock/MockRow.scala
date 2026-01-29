package protocatalyst.mock

/**
 * Mock row representation for expression evaluation.
 * Similar to Spark's InternalRow.
 */
case class MockRow(values: Vector[Any]):
  def get(ordinal: Int): Any =
    if ordinal < 0 || ordinal >= values.size then
      throw IndexOutOfBoundsException(s"Ordinal $ordinal out of bounds [0, ${values.size})")
    values(ordinal)

  def isNullAt(ordinal: Int): Boolean =
    get(ordinal) == null

  def getBoolean(ordinal: Int): Boolean = get(ordinal).asInstanceOf[Boolean]
  def getByte(ordinal: Int): Byte = get(ordinal).asInstanceOf[Byte]
  def getShort(ordinal: Int): Short = get(ordinal).asInstanceOf[Short]
  def getInt(ordinal: Int): Int = get(ordinal).asInstanceOf[Int]
  def getLong(ordinal: Int): Long = get(ordinal).asInstanceOf[Long]
  def getFloat(ordinal: Int): Float = get(ordinal).asInstanceOf[Float]
  def getDouble(ordinal: Int): Double = get(ordinal).asInstanceOf[Double]
  def getString(ordinal: Int): String = get(ordinal).asInstanceOf[String]

  def size: Int = values.size

  def copy(): MockRow = MockRow(values)

object MockRow:
  def apply(values: Any*): MockRow = MockRow(values.toVector)
  val empty: MockRow = MockRow(Vector.empty)

  def fromSeq(values: Seq[Any]): MockRow = MockRow(values.toVector)
