package protocatalyst.mock

/**
 * Mock Spark DataType hierarchy - mimics org.apache.spark.sql.types.DataType.
 * Used for validating ProtoType compatibility without Spark dependency.
 */
sealed trait MockDataType:
  def typeName: String
  def simpleString: String = typeName

object MockDataType:
  case object BooleanType extends MockDataType:
    def typeName = "boolean"

  case object ByteType extends MockDataType:
    def typeName = "byte"

  case object ShortType extends MockDataType:
    def typeName = "short"

  case object IntegerType extends MockDataType:
    def typeName = "integer"

  case object LongType extends MockDataType:
    def typeName = "long"

  case object FloatType extends MockDataType:
    def typeName = "float"

  case object DoubleType extends MockDataType:
    def typeName = "double"

  case object StringType extends MockDataType:
    def typeName = "string"

  case object BinaryType extends MockDataType:
    def typeName = "binary"

  case object DateType extends MockDataType:
    def typeName = "date"

  case object TimestampType extends MockDataType:
    def typeName = "timestamp"

  case object TimestampNTZType extends MockDataType:
    def typeName = "timestamp_ntz"

  case class DecimalType(precision: Int, scale: Int) extends MockDataType:
    def typeName = s"decimal($precision,$scale)"

  case class ArrayType(elementType: MockDataType, containsNull: Boolean) extends MockDataType:
    def typeName = s"array<${elementType.typeName}>"

  case class MapType(keyType: MockDataType, valueType: MockDataType, valueContainsNull: Boolean) extends MockDataType:
    def typeName = s"map<${keyType.typeName},${valueType.typeName}>"

  case class StructType(fields: Vector[MockStructField]) extends MockDataType:
    def typeName = s"struct<${fields.map(f => s"${f.name}:${f.dataType.typeName}").mkString(",")}>"

    def apply(name: String): Option[MockStructField] =
      fields.find(_.name.equalsIgnoreCase(name))

    def fieldNames: Vector[String] = fields.map(_.name)

    def find(p: MockStructField => Boolean): Option[MockStructField] =
      fields.find(p)

    def fieldIndex(name: String): Int =
      fields.indexWhere(_.name.equalsIgnoreCase(name))

case class MockStructField(
    name: String,
    dataType: MockDataType,
    nullable: Boolean = true,
    metadata: Map[String, String] = Map.empty
)
