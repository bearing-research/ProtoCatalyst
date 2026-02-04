package protocatalyst.catalyst.json

import io.circe.{DecodingFailure, HCursor, Json}
import org.apache.spark.sql.types._

/** Decodes JSON (upickle format) directly to Spark DataType.
  *
  * upickle uses "$type" discriminator with full class paths like:
  * "protocatalyst.types.ProtoType.BooleanType"
  */
object TypeDecoder {

  // Explicit Either types to avoid conflict with Spark's Left/Right expressions
  private type EitherResult[A] = scala.Either[DecodingFailure, A]
  private def success[A](a: A): EitherResult[A] = scala.Right(a)
  private def failure[A](msg: String, history: List[io.circe.CursorOp]): EitherResult[A] =
    scala.Left(DecodingFailure(msg, history))

  def decode(json: Json): EitherResult[DataType] =
    decode(json.hcursor)

  def decode(c: HCursor): EitherResult[DataType] = {
    c.get[String]("$type").flatMap { typeName =>
      typeName match {
        // Primitive types
        case "protocatalyst.types.ProtoType.BooleanType" =>
          success(BooleanType)
        case "protocatalyst.types.ProtoType.ByteType" =>
          success(ByteType)
        case "protocatalyst.types.ProtoType.ShortType" =>
          success(ShortType)
        case "protocatalyst.types.ProtoType.IntegerType" =>
          success(IntegerType)
        case "protocatalyst.types.ProtoType.LongType" =>
          success(LongType)
        case "protocatalyst.types.ProtoType.FloatType" =>
          success(FloatType)
        case "protocatalyst.types.ProtoType.DoubleType" =>
          success(DoubleType)
        case "protocatalyst.types.ProtoType.StringType" =>
          success(StringType)
        case "protocatalyst.types.ProtoType.BinaryType" =>
          success(BinaryType)

        // Temporal types
        case "protocatalyst.types.ProtoType.DateType" =>
          success(DateType)
        case "protocatalyst.types.ProtoType.TimestampType" =>
          success(TimestampType)
        case "protocatalyst.types.ProtoType.TimestampNTZType" =>
          success(TimestampNTZType)
        case "protocatalyst.types.ProtoType.DayTimeIntervalType" =>
          success(DayTimeIntervalType())
        case "protocatalyst.types.ProtoType.YearMonthIntervalType" =>
          success(YearMonthIntervalType())
        case "protocatalyst.types.ProtoType.CalendarIntervalType" =>
          success(CalendarIntervalType)

        // Special types
        case "protocatalyst.types.ProtoType.NullType" =>
          success(NullType)
        case "protocatalyst.types.ProtoType.VariantType" =>
          success(VariantType)

        // Parameterized types
        case "protocatalyst.types.ProtoType.DecimalType" =>
          for {
            precision <- c.get[Int]("precision")
            scale <- c.get[Int]("scale")
          } yield DecimalType(precision, scale)

        case "protocatalyst.types.ProtoType.CharType" =>
          c.get[Int]("length").map(CharType(_))

        case "protocatalyst.types.ProtoType.VarcharType" =>
          c.get[Int]("length").map(VarcharType(_))

        case "protocatalyst.types.ProtoType.TimeType" =>
          // Spark doesn't have TimeType, map to StringType
          success(StringType)

        // Complex types
        case "protocatalyst.types.ProtoType.ArrayType" =>
          for {
            elemJson <- c.get[Json]("elementType")
            elemType <- decode(elemJson)
            containsNull <- c.get[Boolean]("containsNull")
          } yield ArrayType(elemType, containsNull)

        case "protocatalyst.types.ProtoType.MapType" =>
          for {
            keyJson <- c.get[Json]("keyType")
            keyType <- decode(keyJson)
            valueJson <- c.get[Json]("valueType")
            valueType <- decode(valueJson)
            valueContainsNull <- c.get[Boolean]("valueContainsNull")
          } yield MapType(keyType, valueType, valueContainsNull)

        case "protocatalyst.types.ProtoType.StructType" =>
          for {
            fieldsJson <- c.get[Vector[Json]]("fields")
            fields <- decodeStructFields(fieldsJson)
          } yield StructType(fields.toArray)

        // UDT and unresolved
        case "protocatalyst.types.ProtoType.UDTType" =>
          // Map UDT to its SQL type
          c.get[Json]("sqlType").flatMap(decode)

        case "protocatalyst.types.ProtoType.UnresolvedType" =>
          // Unresolved types become StringType as fallback
          success(StringType)

        case "protocatalyst.types.ProtoType.SumType" =>
          // Sum types (ADTs) become StringType for now
          success(StringType)

        case other =>
          failure(s"Unknown ProtoType: $other", c.history)
      }
    }
  }

  private def decodeStructFields(jsons: Vector[Json]): EitherResult[Vector[StructField]] = {
    var result: Vector[StructField] = Vector.empty
    var error: Option[DecodingFailure] = None
    val iter = jsons.iterator
    while (iter.hasNext && error.isEmpty) {
      decodeStructField(iter.next().hcursor) match {
        case scala.Right(field) => result = result :+ field
        case scala.Left(err) => error = Some(err)
      }
    }
    error match {
      case Some(err) => scala.Left(err)
      case None => scala.Right(result)
    }
  }

  def decodeStructField(c: HCursor): EitherResult[StructField] = {
    for {
      name <- c.get[String]("name")
      dataTypeJson <- c.get[Json]("dataType")
      dataType <- decode(dataTypeJson)
      nullable <- c.get[Boolean]("nullable")
    } yield StructField(name, dataType, nullable)
  }
}
