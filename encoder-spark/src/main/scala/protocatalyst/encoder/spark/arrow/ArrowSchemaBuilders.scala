package protocatalyst.encoder.spark.arrow

import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, LocalTime, Period}

import scala.quoted.*

import org.apache.arrow.vector.types.{DateUnit, FloatingPointPrecision, IntervalUnit, TimeUnit}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}

/** Compile-time emitter of an Arrow [[Schema]] for a Scala 3 case class.
  *
  * Mirrors Spark 4.1.2's `org.apache.spark.sql.util.ArrowUtils.toArrowSchema(enc.schema,
  * timeZoneId, errorOnDuplicatedFieldNames=true, largeVarTypes)` so the output is byte-identical
  * to what Spark Connect's `ArrowSerializer[T]` would produce for the same case class.
  *
  * Scope (Phase A2): primitives, String, BigDecimal, Binary, Date/Timestamp/Time variants,
  * Duration/Period, and `Option[X]` over any of those. Nested types (Array, Iterable, Map,
  * nested case class) are deferred to a later iteration.
  *
  * Nullability follows Scala's static type:
  *   - Unboxed primitives (Boolean, Byte, Short, Int, Long, Float, Double) → `nullable=false`
  *   - All other types (including `Option[X]`) → `nullable=true`
  *
  * The macro does not validate that the case class's field names are distinct from Arrow-reserved
  * names; if a user picks `key`/`value` as field names inside a struct, Spark would also accept
  * those, so we match.
  */
object ArrowSchemaBuilders:

  /** The single point of truth for the default Decimal type. Equivalent to Spark's
    * `DecimalType.SYSTEM_DEFAULT` — what `ScalaDecimalEncoder` / `JavaDecimalEncoder` pick when
    * no annotation specifies precision/scale. Cached as a `val` so every Schema build reuses the
    * same `ArrowType.Decimal` instance.
    */
  val defaultDecimalType: ArrowType = new ArrowType.Decimal(38, 18, 128)

  /** Build an Arrow Schema for case class `T` at the call site. */
  inline def schemaFor[T](inline timeZoneId: String, inline largeVarTypes: Boolean): Schema =
    ${ schemaForImpl[T]('timeZoneId, 'largeVarTypes) }

  /** Convenience for the common Spark default (UTC, no LargeUtf8). */
  inline def schemaFor[T]: Schema = ${ schemaForImpl[T]('{ "UTC" }, '{ false }) }

  def schemaForImpl[T: Type](
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Schema] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val classSym = tpe.classSymbol.getOrElse {
      report.errorAndAbort(
        s"ArrowSchemaBuilders.schemaFor requires a case class; ${tpe.show} is not a class type."
      )
    }
    if !classSym.flags.is(Flags.Case) then
      report.errorAndAbort(
        s"ArrowSchemaBuilders.schemaFor requires a case class; ${tpe.show} is not a case class."
      )

    val fieldExprs = classSym.caseFields.map { sym =>
      val fieldTpe = tpe.memberType(sym)
      buildField(sym.name, fieldTpe, timeZoneId, largeVarTypes)
    }
    val sequenceExpr = Expr.ofList(fieldExprs)
    '{
      val list = $sequenceExpr
      val javaList = new java.util.ArrayList[Field](list.size)
      list.foreach(javaList.add(_))
      new Schema(javaList)
    }

  // -------------------------------------------------------------------------
  // Per-field Field construction. Emits a `new Field(name, FieldType(nullable,
  // arrowType, null), Collections.emptyList())` tree for each case-class field.
  // -------------------------------------------------------------------------

  private def buildField(using Quotes)(
      name: String,
      tpe: quotes.reflect.TypeRepr,
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  ): Expr[Field] =
    val nameE = Expr(name)
    val (nullable, arrowTypeE) = arrowTypeFor(tpe, timeZoneId, largeVarTypes)
    val nullableE = Expr(nullable)
    '{
      new Field(
        $nameE,
        new FieldType($nullableE, $arrowTypeE, null),
        java.util.Collections.emptyList[Field]()
      )
    }

  /** Return `(nullable, Expr[ArrowType])` for a Scala type. Unboxed primitives are non-nullable;
    * everything else (including `Option[X]`) is nullable. The Arrow type matches what
    * `ArrowUtils.toArrowType` would produce for the corresponding Spark `DataType`.
    */
  private def arrowTypeFor(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  ): (Boolean, Expr[ArrowType]) =
    import quotes.reflect.*
    tpe.asType match
      case '[Boolean] => (false, '{ ArrowType.Bool.INSTANCE })
      case '[Byte]    => (false, '{ new ArrowType.Int(8, true) })
      case '[Short]   => (false, '{ new ArrowType.Int(16, true) })
      case '[Int]     => (false, '{ new ArrowType.Int(32, true) })
      case '[Long]    => (false, '{ new ArrowType.Int(64, true) })
      case '[Float]   =>
        (false, '{ new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE) })
      case '[Double] =>
        (false, '{ new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE) })
      case '[String] =>
        // Spark switches between Utf8 and LargeUtf8 at toArrowType time based on largeVarTypes;
        // we mirror that selection at runtime so a single Schema build covers either Spark config.
        (
          true,
          '{ if $largeVarTypes then ArrowType.LargeUtf8.INSTANCE else ArrowType.Utf8.INSTANCE }
        )
      case '[Array[Byte]] =>
        (
          true,
          '{
            if $largeVarTypes then ArrowType.LargeBinary.INSTANCE
            else ArrowType.Binary.INSTANCE
          }
        )
      // Spark's Decimal default for case-class BigDecimal is precision=38, scale=18, width=128
      // (= `DecimalType.SYSTEM_DEFAULT`, what `ScalaDecimalEncoder` picks when no annotation
      // narrows it). The writer macro reads the vector's scale at runtime so changing this
      // tuple here (e.g., to add an annotation-driven path) doesn't require touching the writer.
      case '[BigDecimal]  => (true, '{ ArrowSchemaBuilders.defaultDecimalType })
      case '[JBigDecimal] => (true, '{ ArrowSchemaBuilders.defaultDecimalType })
      case '[LocalDate]   => (true, '{ new ArrowType.Date(DateUnit.DAY) })
      case '[jsql.Date]   => (true, '{ new ArrowType.Date(DateUnit.DAY) })
      case '[Instant] =>
        (true, '{ new ArrowType.Timestamp(TimeUnit.MICROSECOND, $timeZoneId) })
      case '[jsql.Timestamp] =>
        (true, '{ new ArrowType.Timestamp(TimeUnit.MICROSECOND, $timeZoneId) })
      // LocalDateTime → TimestampNTZ → Timestamp(MICROSECOND, null)
      case '[LocalDateTime] => (true, '{ new ArrowType.Timestamp(TimeUnit.MICROSECOND, null) })
      case '[LocalTime]     => (true, '{ new ArrowType.Time(TimeUnit.MICROSECOND, 64) })
      // Spark asymmetry: DayTimeInterval → Arrow Duration(MICROSECOND), but
      // YearMonthInterval → Arrow Interval(YEAR_MONTH). See `ArrowUtils.toArrowType`.
      case '[Duration] => (true, '{ new ArrowType.Duration(TimeUnit.MICROSECOND) })
      case '[Period]   => (true, '{ new ArrowType.Interval(IntervalUnit.YEAR_MONTH) })
      case '[Option[t]] =>
        // Option[X] uses X's Arrow type; outer Field is always nullable.
        val (_, innerType) = arrowTypeFor(TypeRepr.of[t], timeZoneId, largeVarTypes)
        (true, innerType)
      case _ =>
        report.errorAndAbort(
          s"ArrowSchemaBuilders: unsupported field type ${tpe.show}. Supported (Phase A2): " +
            "primitives, String, Array[Byte], BigDecimal, LocalDate/jsql.Date, " +
            "Instant/jsql.Timestamp, LocalDateTime, LocalTime, Duration, Period, " +
            "and Option of any of these."
        )
