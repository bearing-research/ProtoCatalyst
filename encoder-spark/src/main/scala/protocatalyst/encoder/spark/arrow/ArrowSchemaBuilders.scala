package protocatalyst.encoder.spark.arrow

import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, LocalTime, Period}

import scala.quoted.*

import org.apache.arrow.vector.complex.MapVector
import org.apache.arrow.vector.types.{DateUnit, FloatingPointPrecision, IntervalUnit, TimeUnit}
import org.apache.arrow.vector.types.pojo.{ArrowType, Field, FieldType, Schema}

/** Compile-time emitter of an Arrow [[Schema]] for a Scala 3 case class.
  *
  * Mirrors Spark 4.1.2's `org.apache.spark.sql.util.ArrowUtils.toArrowSchema(enc.schema,
  * timeZoneId, errorOnDuplicatedFieldNames=true, largeVarTypes)` so the output is byte-identical
  * to what Spark Connect's `ArrowSerializer[T]` would produce for the same case class.
  *
  * Scope: primitives, String, BigDecimal, Binary, Date/Timestamp/Time variants, Duration/Period,
  * nested case classes (Arrow `Struct`), `Array[T]`/`Seq`/`List`/`Vector` (Arrow `List`, child
  * field named "element"), `Map[K,V]` (Arrow `Map`, an "entries" struct of "key"/"value"), and
  * `Option[X]` over any of those.
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
  // Per-field Field construction. Recursive: leaf scalars emit a childless
  // `new Field(name, FieldType(nullable, arrowType, null), emptyList)`; nested
  // case classes emit `ArrowType.Struct` with one child Field per case field;
  // Array[T]/Seq/List/Vector emit `ArrowType.List` with a single "element" child
  // child, and Map[K,V] -> ArrowType.Map (mirroring `ArrowUtils.toArrowField`).
  // -------------------------------------------------------------------------

  private def buildField(using Quotes)(
      name: String,
      tpe: quotes.reflect.TypeRepr,
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  ): Expr[Field] =
    fieldOfType(name, tpe, nullabilityOf(tpe), timeZoneId, largeVarTypes)

  /** Whether a Scala type maps to a nullable Arrow field. Unboxed primitives are non-nullable;
    * everything else (String, BigDecimal, temporal, collections, nested case classes, Option[X])
    * is nullable. Mirrors the nullability the `AgnosticEncoder` hierarchy assigns.
    */
  private def nullabilityOf(using Quotes)(tpe: quotes.reflect.TypeRepr): Boolean =
    import quotes.reflect.*
    tpe.asType match
      case '[Boolean] | '[Byte] | '[Short] | '[Int] | '[Long] | '[Float] | '[Double] => false
      case _                                                                         => true

  /** Element type of a non-Array Scala collection that maps to Arrow `List` (any `Iterable`
    * subtype other than `Map`). Returns `None` for non-collections and for maps.
    */
  private def seqElementType(using
      Quotes
  )(tpe: quotes.reflect.TypeRepr): Option[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    // Map is excluded here (it has its own Arrow mapping) — detected via symbol, not `<:<`,
    // because Map is invariant in its key so `Map[String,Int] <:< Map[Any,Any]` is false.
    if mapKeyValueType(tpe).isDefined then None
    else if tpe <:< TypeRepr.of[scala.collection.Iterable[Any]] then
      tpe.baseType(TypeRepr.of[scala.collection.Iterable].typeSymbol).typeArgs.headOption
    else None

  /** `(keyType, valueType)` if `tpe` is a `scala.collection.Map`, else `None`. Uses `baseType`
    * (symbol-based) rather than `<:<` because Map's key parameter is invariant.
    */
  private def mapKeyValueType(using
      Quotes
  )(tpe: quotes.reflect.TypeRepr): Option[(quotes.reflect.TypeRepr, quotes.reflect.TypeRepr)] =
    import quotes.reflect.*
    tpe.baseType(TypeRepr.of[scala.collection.Map].typeSymbol) match
      case AppliedType(_, k :: v :: Nil) => Some((k, v))
      case _                             => None

  /** Recursive Field builder. `nullable` is decided by the caller (top-level field, struct child,
    * or list element), so Option only needs to force it true and recurse on the inner type.
    */
  private def fieldOfType(using Quotes)(
      name: String,
      tpe: quotes.reflect.TypeRepr,
      nullable: Boolean,
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  ): Expr[Field] =
    import quotes.reflect.*
    val nameE = Expr(name)
    val nullableE = Expr(nullable)
    tpe.asType match
      // Option[X]: outer field is nullable; structure comes from X.
      case '[Option[t]] =>
        fieldOfType(name, TypeRepr.of[t], true, timeZoneId, largeVarTypes)
      // Array[Byte] is Binary, NOT a list — must precede the generic Array case.
      case '[Array[Byte]] =>
        leafField(nameE, nullableE, leafArrowType(tpe, timeZoneId, largeVarTypes))
      // Array[T] (T != Byte) → List with an "element" child.
      case '[Array[t]] =>
        listField(nameE, nullableE, TypeRepr.of[t], timeZoneId, largeVarTypes)
      // Map[K, V] → Map(false) with an "entries" struct child of "key"/"value".
      case _ if mapKeyValueType(tpe).isDefined =>
        val (keyTpe, valTpe) = mapKeyValueType(tpe).get
        mapField(nameE, nullableE, keyTpe, valTpe, timeZoneId, largeVarTypes)
      // Seq/List/Vector/Set/... → List with an "element" child.
      case _ if seqElementType(tpe).isDefined =>
        listField(nameE, nullableE, seqElementType(tpe).get, timeZoneId, largeVarTypes)
      case _ =>
        val classSym = tpe.classSymbol
        if classSym.isDefined && classSym.get.flags.is(Flags.Case) then
          // Nested case class → Struct with one child per case field.
          val childExprs = classSym.get.caseFields.map { sym =>
            val fieldTpe = tpe.memberType(sym)
            fieldOfType(sym.name, fieldTpe, nullabilityOf(fieldTpe), timeZoneId, largeVarTypes)
          }
          structField(nameE, nullableE, childExprs)
        else leafField(nameE, nullableE, leafArrowType(tpe, timeZoneId, largeVarTypes))

  /** Childless leaf field. */
  private def leafField(using Quotes)(
      nameE: Expr[String],
      nullableE: Expr[Boolean],
      arrowTypeE: Expr[ArrowType]
  ): Expr[Field] =
    '{
      new Field(
        $nameE,
        new FieldType($nullableE, $arrowTypeE, null),
        java.util.Collections.emptyList[Field]()
      )
    }

  /** `ArrowType.List` field with a single child named "element" (Spark's `ArrowUtils` name). The
    * element's nullability is `containsNull` — derived from the element type like any other field.
    */
  private def listField(using Quotes)(
      nameE: Expr[String],
      nullableE: Expr[Boolean],
      elemTpe: quotes.reflect.TypeRepr,
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  ): Expr[Field] =
    val childE = fieldOfType("element", elemTpe, nullabilityOf(elemTpe), timeZoneId, largeVarTypes)
    '{
      val jl = new java.util.ArrayList[Field](1)
      jl.add($childE)
      new Field($nameE, new FieldType($nullableE, ArrowType.List.INSTANCE, null), jl)
    }

  /** `ArrowType.Map(false)` field. Mirrors `ArrowUtils.toArrowField`: a single child named
    * `MapVector.DATA_VECTOR_NAME` ("entries") — a non-nullable Struct of `MapVector.KEY_NAME`
    * ("key", non-nullable) and `MapVector.VALUE_NAME` ("value", nullable per the value type).
    */
  private def mapField(using Quotes)(
      nameE: Expr[String],
      nullableE: Expr[Boolean],
      keyTpe: quotes.reflect.TypeRepr,
      valTpe: quotes.reflect.TypeRepr,
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  ): Expr[Field] =
    val keyChild =
      fieldOfType(MapVector.KEY_NAME, keyTpe, false, timeZoneId, largeVarTypes)
    val valChild =
      fieldOfType(MapVector.VALUE_NAME, valTpe, nullabilityOf(valTpe), timeZoneId, largeVarTypes)
    val entriesName = Expr(MapVector.DATA_VECTOR_NAME)
    '{
      val entryChildren = new java.util.ArrayList[Field](2)
      entryChildren.add($keyChild)
      entryChildren.add($valChild)
      val entries =
        new Field($entriesName, new FieldType(false, ArrowType.Struct.INSTANCE, null), entryChildren)
      val jl = new java.util.ArrayList[Field](1)
      jl.add(entries)
      new Field($nameE, new FieldType($nullableE, new ArrowType.Map(false), null), jl)
    }

  /** `ArrowType.Struct` field whose children are the supplied per-case-field Field exprs. */
  private def structField(using Quotes)(
      nameE: Expr[String],
      nullableE: Expr[Boolean],
      childExprs: List[Expr[Field]]
  ): Expr[Field] =
    val childrenE = Expr.ofList(childExprs)
    '{
      val cs = $childrenE
      val jl = new java.util.ArrayList[Field](cs.size)
      cs.foreach(jl.add(_))
      new Field($nameE, new FieldType($nullableE, ArrowType.Struct.INSTANCE, null), jl)
    }

  /** Arrow type for a leaf (scalar) Scala type. Does not handle Option/collections/structs — the
    * caller routes those before reaching here. Matches `ArrowUtils.toArrowType`.
    */
  private def leafArrowType(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  ): Expr[ArrowType] =
    import quotes.reflect.*
    tpe.asType match
      case '[Boolean] => '{ ArrowType.Bool.INSTANCE }
      case '[Byte]    => '{ new ArrowType.Int(8, true) }
      case '[Short]   => '{ new ArrowType.Int(16, true) }
      case '[Int]     => '{ new ArrowType.Int(32, true) }
      case '[Long]    => '{ new ArrowType.Int(64, true) }
      case '[Float]   => '{ new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE) }
      case '[Double]  => '{ new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE) }
      case '[String] =>
        // Spark switches between Utf8 and LargeUtf8 at toArrowType time based on largeVarTypes;
        // we mirror that selection at runtime so a single Schema build covers either Spark config.
        '{ if $largeVarTypes then ArrowType.LargeUtf8.INSTANCE else ArrowType.Utf8.INSTANCE }
      case '[Array[Byte]] =>
        '{
          if $largeVarTypes then ArrowType.LargeBinary.INSTANCE else ArrowType.Binary.INSTANCE
        }
      // Spark's Decimal default for case-class BigDecimal is precision=38, scale=18, width=128
      // (= `DecimalType.SYSTEM_DEFAULT`, what `ScalaDecimalEncoder` picks when no annotation
      // narrows it). The writer macro reads the vector's scale at runtime so changing this
      // tuple here (e.g., to add an annotation-driven path) doesn't require touching the writer.
      case '[BigDecimal]  => '{ ArrowSchemaBuilders.defaultDecimalType }
      case '[JBigDecimal] => '{ ArrowSchemaBuilders.defaultDecimalType }
      case '[LocalDate]   => '{ new ArrowType.Date(DateUnit.DAY) }
      case '[jsql.Date]   => '{ new ArrowType.Date(DateUnit.DAY) }
      case '[Instant] =>
        '{ new ArrowType.Timestamp(TimeUnit.MICROSECOND, $timeZoneId) }
      case '[jsql.Timestamp] =>
        '{ new ArrowType.Timestamp(TimeUnit.MICROSECOND, $timeZoneId) }
      // LocalDateTime → TimestampNTZ → Timestamp(MICROSECOND, null)
      case '[LocalDateTime] => '{ new ArrowType.Timestamp(TimeUnit.MICROSECOND, null) }
      case '[LocalTime]     => '{ new ArrowType.Time(TimeUnit.MICROSECOND, 64) }
      // Spark asymmetry: DayTimeInterval → Arrow Duration(MICROSECOND), but
      // YearMonthInterval → Arrow Interval(YEAR_MONTH). See `ArrowUtils.toArrowType`.
      case '[Duration] => '{ new ArrowType.Duration(TimeUnit.MICROSECOND) }
      case '[Period]   => '{ new ArrowType.Interval(IntervalUnit.YEAR_MONTH) }
      case _ =>
        report.errorAndAbort(
          s"ArrowSchemaBuilders: unsupported field type ${tpe.show}. Supported: primitives, " +
            "String, Array[Byte], BigDecimal, LocalDate/jsql.Date, Instant/jsql.Timestamp, " +
            "LocalDateTime, LocalTime, Duration, Period, nested case classes (Struct), " +
            "Array[T]/Seq/List/Vector (List), Map[K, V], and Option of any of these."
        )
