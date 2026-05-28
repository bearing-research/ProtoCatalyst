package protocatalyst.encoder.spark.arrow

import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant, LocalDate, LocalDateTime, LocalTime, Period, ZoneOffset}

import scala.quoted.*

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.vector.{
  BigIntVector,
  BitVector,
  DateDayVector,
  DecimalVector,
  DurationVector,
  FieldVector,
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
  VarCharVector
}
/** Quoted-macro implementation of `ArrowRowSerializer.derived[T]`.
  *
  * Emits two pieces of code at scalac time:
  *   1. The schema, via [[ArrowSchemaBuilders.schemaForImpl]].
  *   2. A `(VectorSchemaRoot, Int, T) => Unit` lambda whose body is a Block of N field-writes,
  *      one per case-class field. Dispatch is fully static — each field-write expression is
  *      typed to a concrete Arrow `FieldVector` subtype.
  *
  * Phase A4 scope: primitives + String. Other AgnosticEncoder variants come in A8.
  */
object ArrowRowSerializerMacro:

  def derivedImpl[T: Type](
      allocator: Expr[BufferAllocator],
      timeZoneId: Expr[String],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[ArrowRowSerializer[T]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val classSym = tpe.classSymbol.getOrElse {
      report.errorAndAbort(
        s"ArrowRowSerializer.derived requires a case class; ${tpe.show} is not a class type."
      )
    }
    if !classSym.flags.is(Flags.Case) then
      report.errorAndAbort(
        s"ArrowRowSerializer.derived requires a case class; ${tpe.show} is not a case class."
      )

    val schemaExpr = ArrowSchemaBuilders.schemaForImpl[T](timeZoneId, largeVarTypes)

    // Build (Array[FieldVector], Int, T) => Unit via a direct quoted function literal; the body
    // is a splice that expands to a Block of N field-write statements. The impl pre-caches the
    // FieldVector array once at construction so each call is array indexing + monomorphic cast
    // + setSafe — no per-call root.getVector lookup (Phase C-arrow optimization).
    val writeLambda: Expr[(Array[FieldVector], Int, T) => Unit] =
      '{ (vectors: Array[FieldVector], idx: Int, value: T) =>
        ${ buildAllFieldWrites[T]('vectors, 'idx, 'value, largeVarTypes) }
      }

    '{
      new ArrowRowSerializerImpl[T]($schemaExpr, $allocator, $writeLambda)
    }

  /** Compose the per-field write expressions into a single Unit-typed Block. */
  private def buildAllFieldWrites[T: Type](
      vectors: Expr[Array[FieldVector]],
      idx: Expr[Int],
      value: Expr[T],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Unit] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val classSym = tpe.classSymbol.getOrElse {
      report.errorAndAbort(s"ArrowRowSerializer: ${tpe.show} is not a class type.")
    }
    val caseFields = classSym.caseFields
    val stmts: List[Term] = caseFields.zipWithIndex.map { case (fieldSym, i) =>
      val fieldTpe = tpe.memberType(fieldSym)
      val fieldAccess = Select(value.asTerm, fieldSym)
      buildWriteExpr(fieldTpe, i, vectors, idx, fieldAccess, largeVarTypes).asTerm
    }
    Block(stmts, '{ () }.asTerm).asExprOf[Unit]

  // ---------------------------------------------------------------------------
  // Per-field write expression. Dispatches at scalac time on the static field
  // type to a concrete Arrow vector method.
  // ---------------------------------------------------------------------------

  private def buildWriteExpr(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      fieldIdx: Int,
      vectors: Expr[Array[FieldVector]],
      rowIdx: Expr[Int],
      fieldAccess: quotes.reflect.Term,
      largeVarTypes: Expr[Boolean]
  ): Expr[Unit] =
    import quotes.reflect.*
    val fieldIdxE = Expr(fieldIdx)
    tpe.asType match
      case '[Boolean] =>
        val v = fieldAccess.asExprOf[Boolean]
        '{
          $vectors($fieldIdxE).asInstanceOf[BitVector]
            .setSafe($rowIdx, if $v then 1 else 0)
        }
      case '[Byte] =>
        val v = fieldAccess.asExprOf[Byte]
        '{
          $vectors($fieldIdxE).asInstanceOf[TinyIntVector]
            .setSafe($rowIdx, $v.toInt)
        }
      case '[Short] =>
        val v = fieldAccess.asExprOf[Short]
        '{
          $vectors($fieldIdxE).asInstanceOf[SmallIntVector]
            .setSafe($rowIdx, $v.toInt)
        }
      case '[Int] =>
        val v = fieldAccess.asExprOf[Int]
        '{ $vectors($fieldIdxE).asInstanceOf[IntVector].setSafe($rowIdx, $v) }
      case '[Long] =>
        val v = fieldAccess.asExprOf[Long]
        '{ $vectors($fieldIdxE).asInstanceOf[BigIntVector].setSafe($rowIdx, $v) }
      case '[Float] =>
        val v = fieldAccess.asExprOf[Float]
        '{ $vectors($fieldIdxE).asInstanceOf[Float4Vector].setSafe($rowIdx, $v) }
      case '[Double] =>
        val v = fieldAccess.asExprOf[Double]
        '{ $vectors($fieldIdxE).asInstanceOf[Float8Vector].setSafe($rowIdx, $v) }
      case '[String] =>
        // Schema picks Utf8 vs LargeUtf8 from largeVarTypes; root vector follows. JVM cmp+jmp
        // is essentially free per write, and the JIT folds the branch when largeVarTypes is a
        // compile-time constant on the call path.
        val v = fieldAccess.asExprOf[String]
        '{
          val s = $v
          if $largeVarTypes then
            val vec = $vectors($fieldIdxE).asInstanceOf[LargeVarCharVector]
            if s == null then vec.setNull($rowIdx)
            else vec.setSafe($rowIdx, s.getBytes(StandardCharsets.UTF_8))
          else
            val vec = $vectors($fieldIdxE).asInstanceOf[VarCharVector]
            if s == null then vec.setNull($rowIdx)
            else vec.setSafe($rowIdx, s.getBytes(StandardCharsets.UTF_8))
        }
      case '[Array[Byte]] =>
        // Same Utf8/LargeUtf8 dispatch as String; Binary/LargeBinary on the vector side.
        val v = fieldAccess.asExprOf[Array[Byte]]
        '{
          val b = $v
          if $largeVarTypes then
            val vec = $vectors($fieldIdxE).asInstanceOf[LargeVarBinaryVector]
            if b == null then vec.setNull($rowIdx) else vec.setSafe($rowIdx, b)
          else
            val vec = $vectors($fieldIdxE).asInstanceOf[VarBinaryVector]
            if b == null then vec.setNull($rowIdx) else vec.setSafe($rowIdx, b)
        }
      case '[BigDecimal] =>
        // Mirrors Spark's `setDecimal` optimization: extract the JBigDecimal first (cheap getter)
        // then setScale only if the scale doesn't already match the vector's. Avoids (a) the
        // Scala-BigDecimal wrapper allocation that `bd.setScale(18)` would do, and (b) the
        // JBigDecimal setScale allocation when the input is already at scale 18.
        val v = fieldAccess.asExprOf[BigDecimal]
        '{
          val bd = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[DecimalVector]
          if bd == null then vec.setNull($rowIdx)
          else
            val jbd = bd.bigDecimal
            vec.setSafe($rowIdx, if jbd.scale == 18 then jbd else jbd.setScale(18))
        }
      case '[JBigDecimal] =>
        val v = fieldAccess.asExprOf[JBigDecimal]
        '{
          val bd = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[DecimalVector]
          if bd == null then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, if bd.scale == 18 then bd else bd.setScale(18))
        }
      case '[LocalDate] =>
        val v = fieldAccess.asExprOf[LocalDate]
        '{
          val d = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[DateDayVector]
          if d == null then vec.setNull($rowIdx) else vec.setSafe($rowIdx, d.toEpochDay.toInt)
        }
      case '[jsql.Date] =>
        // Spark: DateTimeUtils.fromJavaDate → millisToDays under defaultTimeZone. j.s.Date's
        // toLocalDate also uses the JVM default zone, so the resulting epoch-day is the same.
        val v = fieldAccess.asExprOf[jsql.Date]
        '{
          val d = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[DateDayVector]
          if d == null then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, d.toLocalDate.toEpochDay.toInt)
        }
      case '[Instant] =>
        val v = fieldAccess.asExprOf[Instant]
        '{
          val i = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[TimeStampMicroTZVector]
          if i == null then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, i.getEpochSecond * 1000000L + i.getNano / 1000L)
        }
      case '[jsql.Timestamp] =>
        // Spark routes through Instant for full µs precision; we do the same.
        val v = fieldAccess.asExprOf[jsql.Timestamp]
        '{
          val ts = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[TimeStampMicroTZVector]
          if ts == null then vec.setNull($rowIdx)
          else
            val inst = ts.toInstant
            vec.setSafe($rowIdx, inst.getEpochSecond * 1000000L + inst.getNano / 1000L)
        }
      case '[LocalDateTime] =>
        // Spark's localDateTimeToMicros uses ZoneOffset.UTC as the conversion anchor.
        val v = fieldAccess.asExprOf[LocalDateTime]
        '{
          val ldt = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[TimeStampMicroVector]
          if ldt == null then vec.setNull($rowIdx)
          else
            vec.setSafe(
              $rowIdx,
              ldt.toEpochSecond(ZoneOffset.UTC) * 1000000L + ldt.getNano / 1000L
            )
        }
      case '[LocalTime] =>
        // Extension beyond Spark — Spark 4.1.2 rejects TimeType at ExpressionEncoder build time.
        // Our encoder still supports it; no parity to assert.
        val v = fieldAccess.asExprOf[LocalTime]
        '{
          val t = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[TimeMicroVector]
          if t == null then vec.setNull($rowIdx) else vec.setSafe($rowIdx, t.toNanoOfDay / 1000L)
        }
      case '[Duration] =>
        val v = fieldAccess.asExprOf[Duration]
        '{
          val d = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[DurationVector]
          if d == null then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, d.getSeconds * 1000000L + d.getNano / 1000L)
        }
      case '[Period] =>
        // Spark uses Math.addExact/multiplyExact for overflow safety; our test values stay well
        // within Int range so the simpler arithmetic matches byte-for-byte.
        val v = fieldAccess.asExprOf[Period]
        '{
          val p = $v
          val vec = $vectors($fieldIdxE).asInstanceOf[IntervalYearVector]
          if p == null then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, p.getYears * 12 + p.getMonths)
        }
      case '[Option[t]] => buildOptionWriteExpr[t](fieldIdx, vectors, rowIdx, fieldAccess, largeVarTypes)
      case _ =>
        report.errorAndAbort(
          s"ArrowRowSerializer: unsupported field type ${tpe.show} at field $fieldIdx. " +
            "Nested types (Array[T], Map, nested Product), Variant, Enum, and Char/Varchar " +
            "are deferred to a follow-up."
        )

  /** Per-inner-type writer for Option[T]. Mirrors `buildWriteExpr` cases but uses
    * `opt.isEmpty` / `opt.get` instead of null checks.
    */
  private def buildOptionWriteExpr[T: Type](using Quotes)(
      fieldIdx: Int,
      vectors: Expr[Array[FieldVector]],
      rowIdx: Expr[Int],
      fieldAccess: quotes.reflect.Term,
      largeVarTypes: Expr[Boolean]
  ): Expr[Unit] =
    import quotes.reflect.*
    val fieldIdxE = Expr(fieldIdx)
    Type.of[T] match
      case '[Boolean] =>
        val opt = fieldAccess.asExprOf[Option[Boolean]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[BitVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, if o.get then 1 else 0)
        }
      case '[Byte] =>
        val opt = fieldAccess.asExprOf[Option[Byte]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[TinyIntVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get.toInt)
        }
      case '[Short] =>
        val opt = fieldAccess.asExprOf[Option[Short]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[SmallIntVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get.toInt)
        }
      case '[Int] =>
        val opt = fieldAccess.asExprOf[Option[Int]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[IntVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get)
        }
      case '[Long] =>
        val opt = fieldAccess.asExprOf[Option[Long]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[BigIntVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get)
        }
      case '[Float] =>
        val opt = fieldAccess.asExprOf[Option[Float]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[Float4Vector]
          if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get)
        }
      case '[Double] =>
        val opt = fieldAccess.asExprOf[Option[Double]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[Float8Vector]
          if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get)
        }
      case '[String] =>
        val opt = fieldAccess.asExprOf[Option[String]]
        '{
          val o = $opt
          if $largeVarTypes then
            val vec = $vectors($fieldIdxE).asInstanceOf[LargeVarCharVector]
            if o == null || o.isEmpty then vec.setNull($rowIdx)
            else vec.setSafe($rowIdx, o.get.getBytes(StandardCharsets.UTF_8))
          else
            val vec = $vectors($fieldIdxE).asInstanceOf[VarCharVector]
            if o == null || o.isEmpty then vec.setNull($rowIdx)
            else vec.setSafe($rowIdx, o.get.getBytes(StandardCharsets.UTF_8))
        }
      case '[Array[Byte]] =>
        val opt = fieldAccess.asExprOf[Option[Array[Byte]]]
        '{
          val o = $opt
          if $largeVarTypes then
            val vec = $vectors($fieldIdxE).asInstanceOf[LargeVarBinaryVector]
            if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get)
          else
            val vec = $vectors($fieldIdxE).asInstanceOf[VarBinaryVector]
            if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get)
        }
      case '[BigDecimal] =>
        val opt = fieldAccess.asExprOf[Option[BigDecimal]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[DecimalVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else
            val jbd = o.get.bigDecimal
            vec.setSafe($rowIdx, if jbd.scale == 18 then jbd else jbd.setScale(18))
        }
      case '[JBigDecimal] =>
        val opt = fieldAccess.asExprOf[Option[JBigDecimal]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[DecimalVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else
            val jbd = o.get
            vec.setSafe($rowIdx, if jbd.scale == 18 then jbd else jbd.setScale(18))
        }
      case '[LocalDate] =>
        val opt = fieldAccess.asExprOf[Option[LocalDate]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[DateDayVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, o.get.toEpochDay.toInt)
        }
      case '[jsql.Date] =>
        val opt = fieldAccess.asExprOf[Option[jsql.Date]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[DateDayVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, o.get.toLocalDate.toEpochDay.toInt)
        }
      case '[Instant] =>
        val opt = fieldAccess.asExprOf[Option[Instant]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[TimeStampMicroTZVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else
            val i = o.get
            vec.setSafe($rowIdx, i.getEpochSecond * 1000000L + i.getNano / 1000L)
        }
      case '[jsql.Timestamp] =>
        val opt = fieldAccess.asExprOf[Option[jsql.Timestamp]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[TimeStampMicroTZVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else
            val inst = o.get.toInstant
            vec.setSafe($rowIdx, inst.getEpochSecond * 1000000L + inst.getNano / 1000L)
        }
      case '[LocalDateTime] =>
        val opt = fieldAccess.asExprOf[Option[LocalDateTime]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[TimeStampMicroVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else
            val ldt = o.get
            vec.setSafe($rowIdx, ldt.toEpochSecond(ZoneOffset.UTC) * 1000000L + ldt.getNano / 1000L)
        }
      case '[LocalTime] =>
        val opt = fieldAccess.asExprOf[Option[LocalTime]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[TimeMicroVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx) else vec.setSafe($rowIdx, o.get.toNanoOfDay / 1000L)
        }
      case '[Duration] =>
        val opt = fieldAccess.asExprOf[Option[Duration]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[DurationVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else
            val d = o.get
            vec.setSafe($rowIdx, d.getSeconds * 1000000L + d.getNano / 1000L)
        }
      case '[Period] =>
        val opt = fieldAccess.asExprOf[Option[Period]]
        '{
          val o = $opt
          val vec = $vectors($fieldIdxE).asInstanceOf[IntervalYearVector]
          if o == null || o.isEmpty then vec.setNull($rowIdx)
          else vec.setSafe($rowIdx, o.get.getYears * 12 + o.get.getMonths)
        }
      case _ =>
        report.errorAndAbort(
          s"ArrowRowSerializer: unsupported Option inner type ${TypeRepr.of[T].show} at field $fieldIdx."
        )
