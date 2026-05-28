package protocatalyst.encoder.spark.arrow

import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant, LocalDate, LocalDateTime, LocalTime, Period, ZoneOffset}

import scala.quoted.*

import org.apache.arrow.vector.{
  BigIntVector,
  BitVector,
  DateDayVector,
  DecimalVector,
  DurationVector,
  Float4Vector,
  Float8Vector,
  IntVector,
  IntervalYearVector,
  LargeVarBinaryVector,
  LargeVarCharVector,
  SmallIntVector,
  TimeMicroVector,
  TimeStampMicroTZVector,
  TimeStampMicroVector,
  TinyIntVector,
  VarBinaryVector,
  VarCharVector,
  VectorSchemaRoot
}

/** Quoted-macro implementation of `ArrowRowDeserializer.derived[T]`.
  *
  * Emits a `(VectorSchemaRoot, Int) => T` function whose body is a direct constructor call
  * (`new T(read_0, read_1, ..., read_{n-1})`) — same jsoniter-scala pattern used by
  * [[protocatalyst.encoder.spark.UnsafeRowSerializerMacro]]. Each `read_i` reads from a
  * statically-typed Arrow vector and emits the right unboxing/null-handling.
  */
object ArrowRowDeserializerMacro:

  def derivedImpl[T: Type](
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[ArrowRowDeserializer[T]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val classSym = tpe.classSymbol.getOrElse {
      report.errorAndAbort(
        s"ArrowRowDeserializer.derived requires a case class; ${tpe.show} is not a class type."
      )
    }
    if !classSym.flags.is(Flags.Case) then
      report.errorAndAbort(
        s"ArrowRowDeserializer.derived requires a case class; ${tpe.show} is not a case class."
      )

    val readLambda: Expr[(VectorSchemaRoot, Int) => T] =
      '{ (root: VectorSchemaRoot, idx: Int) =>
        ${ buildCtorCall[T]('root, 'idx, largeVarTypes) }
      }

    '{ new ArrowRowDeserializerImpl[T]($readLambda) }

  /** Emit the constructor call `new T(read_0, read_1, ..., read_{n-1})`. */
  private def buildCtorCall[T: Type](
      root: Expr[VectorSchemaRoot],
      rowIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[T] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val classSym = tpe.classSymbol.getOrElse {
      report.errorAndAbort(s"ArrowRowDeserializer: ${tpe.show} is not a class type.")
    }
    val caseFields = classSym.caseFields
    val typeArgs: List[TypeRepr] = tpe match
      case AppliedType(_, args) => args
      case _                    => Nil

    val ctorArgs: List[Term] = caseFields.zipWithIndex.map { case (fieldSym, i) =>
      val fieldTpe = tpe.memberType(fieldSym)
      buildReadExpr(fieldTpe, i, root, rowIdx, largeVarTypes).asTerm
    }

    New(TypeTree.of[T])
      .select(classSym.primaryConstructor)
      .appliedToTypes(typeArgs)
      .appliedToArgs(ctorArgs)
      .asExprOf[T]

  // ---------------------------------------------------------------------------
  // Per-field read expression — static dispatch on the field's Scala type to a
  // concrete Arrow vector read.
  // ---------------------------------------------------------------------------

  private def buildReadExpr(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      fieldIdx: Int,
      root: Expr[VectorSchemaRoot],
      rowIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  ): Expr[Any] =
    import quotes.reflect.*
    val fieldIdxE = Expr(fieldIdx)
    tpe.asType match
      case '[Boolean] =>
        '{ $root.getVector($fieldIdxE).asInstanceOf[BitVector].get($rowIdx) == 1 }
      case '[Byte] =>
        '{ $root.getVector($fieldIdxE).asInstanceOf[TinyIntVector].get($rowIdx) }
      case '[Short] =>
        '{ $root.getVector($fieldIdxE).asInstanceOf[SmallIntVector].get($rowIdx) }
      case '[Int] =>
        '{ $root.getVector($fieldIdxE).asInstanceOf[IntVector].get($rowIdx) }
      case '[Long] =>
        '{ $root.getVector($fieldIdxE).asInstanceOf[BigIntVector].get($rowIdx) }
      case '[Float] =>
        '{ $root.getVector($fieldIdxE).asInstanceOf[Float4Vector].get($rowIdx) }
      case '[Double] =>
        '{ $root.getVector($fieldIdxE).asInstanceOf[Float8Vector].get($rowIdx) }
      case '[String] =>
        '{
          if $largeVarTypes then
            val vec = $root.getVector($fieldIdxE).asInstanceOf[LargeVarCharVector]
            if vec.isNull($rowIdx) then null
            else new String(vec.get($rowIdx), StandardCharsets.UTF_8)
          else
            val vec = $root.getVector($fieldIdxE).asInstanceOf[VarCharVector]
            if vec.isNull($rowIdx) then null
            else new String(vec.get($rowIdx), StandardCharsets.UTF_8)
        }
      case '[Array[Byte]] =>
        '{
          if $largeVarTypes then
            val vec = $root.getVector($fieldIdxE).asInstanceOf[LargeVarBinaryVector]
            if vec.isNull($rowIdx) then null else vec.get($rowIdx)
          else
            val vec = $root.getVector($fieldIdxE).asInstanceOf[VarBinaryVector]
            if vec.isNull($rowIdx) then null else vec.get($rowIdx)
        }
      case '[BigDecimal] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DecimalVector]
          if vec.isNull($rowIdx) then null else BigDecimal(vec.getObject($rowIdx))
        }
      case '[JBigDecimal] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DecimalVector]
          if vec.isNull($rowIdx) then null else vec.getObject($rowIdx)
        }
      case '[LocalDate] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DateDayVector]
          if vec.isNull($rowIdx) then null else LocalDate.ofEpochDay(vec.get($rowIdx).toLong)
        }
      case '[jsql.Date] =>
        // Spark: DateTimeUtils.toJavaDate(days) round-trips through LocalDate under defaultZone.
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DateDayVector]
          if vec.isNull($rowIdx) then null
          else jsql.Date.valueOf(LocalDate.ofEpochDay(vec.get($rowIdx).toLong))
        }
      case '[Instant] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TimeStampMicroTZVector]
          if vec.isNull($rowIdx) then null
          else
            val micros = vec.get($rowIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            Instant.ofEpochSecond(seconds, nanos)
        }
      case '[jsql.Timestamp] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TimeStampMicroTZVector]
          if vec.isNull($rowIdx) then null
          else
            val micros = vec.get($rowIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            jsql.Timestamp.from(Instant.ofEpochSecond(seconds, nanos))
        }
      case '[LocalDateTime] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TimeStampMicroVector]
          if vec.isNull($rowIdx) then null
          else
            val micros = vec.get($rowIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            LocalDateTime.ofEpochSecond(seconds, nanos.toInt, ZoneOffset.UTC)
        }
      case '[LocalTime] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TimeMicroVector]
          if vec.isNull($rowIdx) then null
          else LocalTime.ofNanoOfDay(vec.get($rowIdx) * 1000L)
        }
      case '[Duration] =>
        // DurationVector.get returns ArrowBuf (unusual API for a fixed-width vector); use
        // getObject which converts to a Duration using the vector's configured unit (micros for us).
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DurationVector]
          if vec.isNull($rowIdx) then null else vec.getObject($rowIdx)
        }
      case '[Period] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[IntervalYearVector]
          if vec.isNull($rowIdx) then null else Period.ofMonths(vec.get($rowIdx))
        }
      case '[Option[t]] => buildOptionReadExpr[t](fieldIdx, root, rowIdx, largeVarTypes)
      case _ =>
        report.errorAndAbort(
          s"ArrowRowDeserializer: unsupported field type ${tpe.show} at field $fieldIdx. " +
            "Nested types (Array[T], Map, nested Product), Variant, Enum, and Char/Varchar " +
            "are deferred to a follow-up."
        )

  /** Per-inner-type read for Option[T]. Mirrors `buildReadExpr` but wraps the value in
    * `Some(...)` or returns `None` for null slots.
    */
  private def buildOptionReadExpr[T: Type](using Quotes)(
      fieldIdx: Int,
      root: Expr[VectorSchemaRoot],
      rowIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  ): Expr[Option[T]] =
    import quotes.reflect.*
    val fieldIdxE = Expr(fieldIdx)
    Type.of[T] match
      case '[Boolean] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[BitVector]
          if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx) == 1)
        }.asExprOf[Option[T]]
      case '[Byte] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TinyIntVector]
          if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx))
        }.asExprOf[Option[T]]
      case '[Short] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[SmallIntVector]
          if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx))
        }.asExprOf[Option[T]]
      case '[Int] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[IntVector]
          if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx))
        }.asExprOf[Option[T]]
      case '[Long] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[BigIntVector]
          if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx))
        }.asExprOf[Option[T]]
      case '[Float] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[Float4Vector]
          if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx))
        }.asExprOf[Option[T]]
      case '[Double] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[Float8Vector]
          if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx))
        }.asExprOf[Option[T]]
      case '[String] =>
        '{
          if $largeVarTypes then
            val vec = $root.getVector($fieldIdxE).asInstanceOf[LargeVarCharVector]
            if vec.isNull($rowIdx) then None
            else Some(new String(vec.get($rowIdx), StandardCharsets.UTF_8))
          else
            val vec = $root.getVector($fieldIdxE).asInstanceOf[VarCharVector]
            if vec.isNull($rowIdx) then None
            else Some(new String(vec.get($rowIdx), StandardCharsets.UTF_8))
        }.asExprOf[Option[T]]
      case '[Array[Byte]] =>
        '{
          if $largeVarTypes then
            val vec = $root.getVector($fieldIdxE).asInstanceOf[LargeVarBinaryVector]
            if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx))
          else
            val vec = $root.getVector($fieldIdxE).asInstanceOf[VarBinaryVector]
            if vec.isNull($rowIdx) then None else Some(vec.get($rowIdx))
        }.asExprOf[Option[T]]
      case '[BigDecimal] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DecimalVector]
          if vec.isNull($rowIdx) then None else Some(BigDecimal(vec.getObject($rowIdx)))
        }.asExprOf[Option[T]]
      case '[JBigDecimal] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DecimalVector]
          if vec.isNull($rowIdx) then None else Some(vec.getObject($rowIdx))
        }.asExprOf[Option[T]]
      case '[LocalDate] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DateDayVector]
          if vec.isNull($rowIdx) then None
          else Some(LocalDate.ofEpochDay(vec.get($rowIdx).toLong))
        }.asExprOf[Option[T]]
      case '[jsql.Date] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DateDayVector]
          if vec.isNull($rowIdx) then None
          else Some(jsql.Date.valueOf(LocalDate.ofEpochDay(vec.get($rowIdx).toLong)))
        }.asExprOf[Option[T]]
      case '[Instant] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TimeStampMicroTZVector]
          if vec.isNull($rowIdx) then None
          else
            val micros = vec.get($rowIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            Some(Instant.ofEpochSecond(seconds, nanos))
        }.asExprOf[Option[T]]
      case '[jsql.Timestamp] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TimeStampMicroTZVector]
          if vec.isNull($rowIdx) then None
          else
            val micros = vec.get($rowIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            Some(jsql.Timestamp.from(Instant.ofEpochSecond(seconds, nanos)))
        }.asExprOf[Option[T]]
      case '[LocalDateTime] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TimeStampMicroVector]
          if vec.isNull($rowIdx) then None
          else
            val micros = vec.get($rowIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            Some(LocalDateTime.ofEpochSecond(seconds, nanos.toInt, ZoneOffset.UTC))
        }.asExprOf[Option[T]]
      case '[LocalTime] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[TimeMicroVector]
          if vec.isNull($rowIdx) then None
          else Some(LocalTime.ofNanoOfDay(vec.get($rowIdx) * 1000L))
        }.asExprOf[Option[T]]
      case '[Duration] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[DurationVector]
          if vec.isNull($rowIdx) then None else Some(vec.getObject($rowIdx))
        }.asExprOf[Option[T]]
      case '[Period] =>
        '{
          val vec = $root.getVector($fieldIdxE).asInstanceOf[IntervalYearVector]
          if vec.isNull($rowIdx) then None else Some(Period.ofMonths(vec.get($rowIdx)))
        }.asExprOf[Option[T]]
      case _ =>
        report.errorAndAbort(
          s"ArrowRowDeserializer: unsupported Option inner type ${TypeRepr.of[T].show} at field $fieldIdx."
        )
