package protocatalyst.sql

import scala.quoted.*

import protocatalyst.schema.*
import protocatalyst.types.*

/** Compile-time schema derivation using Quotes reflection API.
  *
  * This derives ProtoSchema directly from Type[A] within a macro context, enabling the schema to be
  * available as a compile-time value for plan building and optimization.
  */
object CompileTimeSchemaDerivation:

  /** Derive schema from type A at compile time.
    *
    * @tparam A
    *   The type to derive schema from (must be a case class)
    * @return
    *   Either an error message or the derived ProtoSchema
    */
  def deriveSchema[A: Type](using q: Quotes): Either[String, ProtoSchema] =
    import q.reflect.*

    val tpe = TypeRepr.of[A]

    // Check if it's a case class (product type)
    tpe.classSymbol match
      case Some(sym) if sym.flags.is(Flags.Case) =>
        val caseFields = sym.caseFields
        deriveFieldsFromCaseClass(tpe, caseFields)

      case Some(_) =>
        Left(s"Type ${tpe.show} is not a case class")
      case None =>
        Left(s"Type ${tpe.show} has no class symbol")

  private def deriveFieldsFromCaseClass(using
      q: Quotes
  )(tpe: q.reflect.TypeRepr, caseFields: List[q.reflect.Symbol]): Either[String, ProtoSchema] =
    val fieldsResult = caseFields.foldLeft[Either[String, Vector[ProtoStructField]]](
      Right(Vector.empty)
    ) { (acc, field) =>
      acc.flatMap { fields =>
        val fieldName = field.name
        val fieldType = tpe.memberType(field)
        deriveProtoTypeFromRepr(fieldType).map { protoType =>
          val nullable = isTypeNullable(fieldType)
          fields :+ ProtoStructField(fieldName, protoType, nullable)
        }
      }
    }
    fieldsResult.map(ProtoSchema(_))

  private def isTypeNullable(using q: Quotes)(tpe: q.reflect.TypeRepr): Boolean =
    import q.reflect.*

    tpe.dealias match
      case AppliedType(tycon, _) if tycon.typeSymbol.fullName == "scala.Option" => true
      case _ if tpe <:< TypeRepr.of[AnyRef] =>
        // Reference types that aren't Option are non-nullable by default
        false
      case _ => false

  private def deriveProtoTypeFromRepr(using
      q: Quotes
  )(tpe: q.reflect.TypeRepr): Either[String, ProtoType] =
    import q.reflect.*

    val dealised = tpe.dealias

    // Check primitive types first
    if dealised =:= TypeRepr.of[Boolean] then Right(ProtoType.BooleanType)
    else if dealised =:= TypeRepr.of[Byte] then Right(ProtoType.ByteType)
    else if dealised =:= TypeRepr.of[Short] then Right(ProtoType.ShortType)
    else if dealised =:= TypeRepr.of[Int] then Right(ProtoType.IntegerType)
    else if dealised =:= TypeRepr.of[Long] then Right(ProtoType.LongType)
    else if dealised =:= TypeRepr.of[Float] then Right(ProtoType.FloatType)
    else if dealised =:= TypeRepr.of[Double] then Right(ProtoType.DoubleType)
    else if dealised =:= TypeRepr.of[String] then Right(ProtoType.StringType)
    else if dealised =:= TypeRepr.of[Array[Byte]] then Right(ProtoType.BinaryType)
    else if dealised =:= TypeRepr.of[BigDecimal] then Right(ProtoType.DecimalType(38, 18))
    else if dealised =:= TypeRepr.of[java.math.BigDecimal] then
      Right(ProtoType.DecimalType(38, 18))
    else if dealised =:= TypeRepr.of[BigInt] then Right(ProtoType.DecimalType(38, 0))
    else if dealised =:= TypeRepr.of[java.math.BigInteger] then Right(ProtoType.DecimalType(38, 0))
    // java.time types
    else if dealised =:= TypeRepr.of[java.time.LocalDate] then Right(ProtoType.DateType)
    else if dealised =:= TypeRepr.of[java.time.Instant] then Right(ProtoType.TimestampType)
    else if dealised =:= TypeRepr.of[java.time.LocalDateTime] then Right(ProtoType.TimestampNTZType)
    else if dealised =:= TypeRepr.of[java.time.Duration] then Right(ProtoType.DayTimeIntervalType)
    else if dealised =:= TypeRepr.of[java.time.Period] then Right(ProtoType.YearMonthIntervalType)
    else if dealised =:= TypeRepr.of[java.time.LocalTime] then Right(ProtoType.TimeType(6))
    else if dealised =:= TypeRepr.of[java.time.OffsetDateTime] then Right(ProtoType.TimestampType)
    else if dealised =:= TypeRepr.of[java.time.ZonedDateTime] then Right(ProtoType.TimestampType)
    // java.sql types (legacy)
    else if dealised =:= TypeRepr.of[java.sql.Date] then Right(ProtoType.DateType)
    else if dealised =:= TypeRepr.of[java.sql.Timestamp] then Right(ProtoType.TimestampType)
    else if dealised =:= TypeRepr.of[java.util.Date] then Right(ProtoType.TimestampType)
    // UUID
    else if dealised =:= TypeRepr.of[java.util.UUID] then Right(ProtoType.StringType)
    // Boxed primitives
    else if dealised =:= TypeRepr.of[java.lang.Boolean] then Right(ProtoType.BooleanType)
    else if dealised =:= TypeRepr.of[java.lang.Byte] then Right(ProtoType.ByteType)
    else if dealised =:= TypeRepr.of[java.lang.Short] then Right(ProtoType.ShortType)
    else if dealised =:= TypeRepr.of[java.lang.Integer] then Right(ProtoType.IntegerType)
    else if dealised =:= TypeRepr.of[java.lang.Long] then Right(ProtoType.LongType)
    else if dealised =:= TypeRepr.of[java.lang.Float] then Right(ProtoType.FloatType)
    else if dealised =:= TypeRepr.of[java.lang.Double] then Right(ProtoType.DoubleType)
    else deriveComplexProtoType(dealised)

  private def deriveComplexProtoType(using
      q: Quotes
  )(dealised: q.reflect.TypeRepr): Either[String, ProtoType] =
    import q.reflect.*

    dealised match
      // Option[T]
      case AppliedType(tycon, List(elementType)) if tycon.typeSymbol.fullName == "scala.Option" =>
        deriveProtoTypeFromRepr(elementType)

      // Seq, List, Vector, Set - all become ArrayType
      case AppliedType(tycon, List(elementType))
          if tycon.typeSymbol.fullName == "scala.collection.immutable.Seq" ||
            tycon.typeSymbol.fullName == "scala.collection.immutable.List" ||
            tycon.typeSymbol.fullName == "scala.collection.immutable.Vector" ||
            tycon.typeSymbol.fullName == "scala.collection.immutable.Set" ||
            tycon.typeSymbol.fullName == "scala.collection.Seq" ||
            tycon.typeSymbol.fullName == "scala.Seq" ||
            tycon.typeSymbol.fullName == "scala.List" =>
        deriveProtoTypeFromRepr(elementType).map { elemType =>
          ProtoType.ArrayType(elemType, isTypeNullable(elementType))
        }

      // Array[T]
      case AppliedType(tycon, List(elementType))
          if tycon.typeSymbol.fullName.startsWith("scala.Array") =>
        deriveProtoTypeFromRepr(elementType).map { elemType =>
          ProtoType.ArrayType(elemType, isTypeNullable(elementType))
        }

      // Map[K, V]
      case AppliedType(tycon, List(keyType, valueType))
          if tycon.typeSymbol.fullName == "scala.collection.immutable.Map" ||
            tycon.typeSymbol.fullName == "scala.Predef.Map" =>
        for
          kt <- deriveProtoTypeFromRepr(keyType)
          vt <- deriveProtoTypeFromRepr(valueType)
        yield ProtoType.MapType(kt, vt, isTypeNullable(valueType))

      // Nested case class
      case _ if dealised.classSymbol.exists(_.flags.is(Flags.Case)) =>
        deriveNestedCaseClass(dealised).map { schema =>
          ProtoType.StructType(schema.fields)
        }

      case _ =>
        Left(s"Unsupported type for compile-time schema derivation: ${dealised.show}")

  private def deriveNestedCaseClass(using
      q: Quotes
  )(tpe: q.reflect.TypeRepr): Either[String, ProtoSchema] =
    import q.reflect.*

    tpe.classSymbol match
      case Some(sym) if sym.flags.is(Flags.Case) =>
        val caseFields = sym.caseFields
        deriveFieldsFromCaseClass(tpe, caseFields)
      case _ =>
        Left(s"Type ${tpe.show} is not a case class")
