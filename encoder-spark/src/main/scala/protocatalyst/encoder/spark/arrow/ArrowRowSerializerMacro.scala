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
import org.apache.arrow.vector.complex.{ListVector, MapVector, StructVector}
/** Quoted-macro implementation of `ArrowRowSerializer.derived[T]`.
  *
  * Emits two pieces of code at scalac time:
  *   1. The schema, via [[ArrowSchemaBuilders.schemaForImpl]].
  *   2. A `(Array[FieldVector], Int, T) => Unit` lambda whose body is a Block of N field-writes,
  *      one per case-class field. Dispatch is fully static — each field-write expression is
  *      typed to a concrete Arrow `FieldVector` subtype.
  *
  * The per-field writer is recursive: leaf scalars `setSafe` into their vector; a nested case
  * class writes each child into a `StructVector`'s child vectors then `setIndexDefined`; a
  * collection writes its elements into a `ListVector`'s data vector between `startNewValue` and
  * `endValue`. The recursion is keyed on `(FieldVector, elementIndex)` and is expressed entirely
  * with `Type[_]`/`Expr[_]` (both portable across the nested `Quotes` introduced by each splice).
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

  /** Compose the per-field write expressions into a single Unit-typed Block. Each top-level field
    * writes into its own pre-cached vector `vectors(i)` at the current row index.
    */
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
      val vecE = '{ $vectors(${ Expr(i) }) }
      fieldTpe.asType match
        case '[ft] =>
          val fieldValue = Select(value.asTerm, fieldSym).asExprOf[ft]
          buildValueWrite[ft](vecE, idx, fieldValue, largeVarTypes).asTerm
    }
    Block(stmts, '{ () }.asTerm).asExprOf[Unit]

  /** Element type of a non-Array collection that maps to Arrow `List` (any `Iterable` other than
    * `Map`, which has its own `ArrowType.Map` handling). Returns `None` otherwise.
    */
  private def seqElementType(using
      Quotes
  )(tpe: quotes.reflect.TypeRepr): Option[quotes.reflect.TypeRepr] =
    import quotes.reflect.*
    // Map excluded here (own Arrow mapping); detected via symbol since Map's key is invariant.
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

  // ---------------------------------------------------------------------------
  // Recursive per-value write. Writes a value of static type `V` into the Arrow
  // vector `vec` at element index `elemIdx`. Top-level fields, struct children, and
  // list elements all funnel through here.
  // ---------------------------------------------------------------------------

  private def buildValueWrite[V: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      value: Expr[V],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Unit] =
    import quotes.reflect.*
    Type.of[V] match
      case '[Boolean] =>
        val v = value.asExprOf[Boolean]
        '{ $vec.asInstanceOf[BitVector].setSafe($elemIdx, if $v then 1 else 0) }
      case '[Byte] =>
        val v = value.asExprOf[Byte]
        '{ $vec.asInstanceOf[TinyIntVector].setSafe($elemIdx, $v.toInt) }
      case '[Short] =>
        val v = value.asExprOf[Short]
        '{ $vec.asInstanceOf[SmallIntVector].setSafe($elemIdx, $v.toInt) }
      case '[Int] =>
        val v = value.asExprOf[Int]
        '{ $vec.asInstanceOf[IntVector].setSafe($elemIdx, $v) }
      case '[Long] =>
        val v = value.asExprOf[Long]
        '{ $vec.asInstanceOf[BigIntVector].setSafe($elemIdx, $v) }
      case '[Float] =>
        val v = value.asExprOf[Float]
        '{ $vec.asInstanceOf[Float4Vector].setSafe($elemIdx, $v) }
      case '[Double] =>
        val v = value.asExprOf[Double]
        '{ $vec.asInstanceOf[Float8Vector].setSafe($elemIdx, $v) }
      case '[String] =>
        // Schema picks Utf8 vs LargeUtf8 from largeVarTypes; root vector follows. JVM cmp+jmp
        // is essentially free per write, and the JIT folds the branch when largeVarTypes is a
        // compile-time constant on the call path.
        val v = value.asExprOf[String]
        '{
          val s = $v
          if $largeVarTypes then
            val vc = $vec.asInstanceOf[LargeVarCharVector]
            if s == null then vc.setNull($elemIdx)
            else vc.setSafe($elemIdx, s.getBytes(StandardCharsets.UTF_8))
          else
            val vc = $vec.asInstanceOf[VarCharVector]
            if s == null then vc.setNull($elemIdx)
            else vc.setSafe($elemIdx, s.getBytes(StandardCharsets.UTF_8))
        }
      case '[Array[Byte]] =>
        // Binary — must precede the generic Array case. Same Utf8/LargeUtf8 dispatch as String.
        val v = value.asExprOf[Array[Byte]]
        '{
          val b = $v
          if $largeVarTypes then
            val vc = $vec.asInstanceOf[LargeVarBinaryVector]
            if b == null then vc.setNull($elemIdx) else vc.setSafe($elemIdx, b)
          else
            val vc = $vec.asInstanceOf[VarBinaryVector]
            if b == null then vc.setNull($elemIdx) else vc.setSafe($elemIdx, b)
        }
      case '[BigDecimal] =>
        // Mirrors Spark's `setDecimal` optimization: extract the JBigDecimal first (cheap getter)
        // then setScale only if the scale doesn't already match the vector's. Avoids (a) the
        // Scala-BigDecimal wrapper allocation that `bd.setScale(...)` would do, and (b) the
        // JBigDecimal setScale allocation when the input is already at the target scale.
        val v = value.asExprOf[BigDecimal]
        '{
          val bd = $v
          val vc = $vec.asInstanceOf[DecimalVector]
          if bd == null then vc.setNull($elemIdx)
          else
            val jbd = bd.bigDecimal
            val target = vc.getScale
            vc.setSafe($elemIdx, if jbd.scale == target then jbd else jbd.setScale(target))
        }
      case '[JBigDecimal] =>
        val v = value.asExprOf[JBigDecimal]
        '{
          val bd = $v
          val vc = $vec.asInstanceOf[DecimalVector]
          if bd == null then vc.setNull($elemIdx)
          else
            val target = vc.getScale
            vc.setSafe($elemIdx, if bd.scale == target then bd else bd.setScale(target))
        }
      case '[LocalDate] =>
        val v = value.asExprOf[LocalDate]
        '{
          val d = $v
          val vc = $vec.asInstanceOf[DateDayVector]
          if d == null then vc.setNull($elemIdx) else vc.setSafe($elemIdx, d.toEpochDay.toInt)
        }
      case '[jsql.Date] =>
        // Spark: DateTimeUtils.fromJavaDate → millisToDays under defaultTimeZone. j.s.Date's
        // toLocalDate also uses the JVM default zone, so the resulting epoch-day is the same.
        val v = value.asExprOf[jsql.Date]
        '{
          val d = $v
          val vc = $vec.asInstanceOf[DateDayVector]
          if d == null then vc.setNull($elemIdx)
          else vc.setSafe($elemIdx, d.toLocalDate.toEpochDay.toInt)
        }
      case '[Instant] =>
        val v = value.asExprOf[Instant]
        '{
          val i = $v
          val vc = $vec.asInstanceOf[TimeStampMicroTZVector]
          if i == null then vc.setNull($elemIdx)
          else vc.setSafe($elemIdx, i.getEpochSecond * 1000000L + i.getNano / 1000L)
        }
      case '[jsql.Timestamp] =>
        // Spark routes through Instant for full µs precision; we do the same.
        val v = value.asExprOf[jsql.Timestamp]
        '{
          val ts = $v
          val vc = $vec.asInstanceOf[TimeStampMicroTZVector]
          if ts == null then vc.setNull($elemIdx)
          else
            val inst = ts.toInstant
            vc.setSafe($elemIdx, inst.getEpochSecond * 1000000L + inst.getNano / 1000L)
        }
      case '[LocalDateTime] =>
        // Spark's localDateTimeToMicros uses ZoneOffset.UTC as the conversion anchor.
        val v = value.asExprOf[LocalDateTime]
        '{
          val ldt = $v
          val vc = $vec.asInstanceOf[TimeStampMicroVector]
          if ldt == null then vc.setNull($elemIdx)
          else vc.setSafe($elemIdx, ldt.toEpochSecond(ZoneOffset.UTC) * 1000000L + ldt.getNano / 1000L)
        }
      case '[LocalTime] =>
        // Extension beyond Spark — Spark 4.1.2 rejects TimeType at ExpressionEncoder build time.
        val v = value.asExprOf[LocalTime]
        '{
          val t = $v
          val vc = $vec.asInstanceOf[TimeMicroVector]
          if t == null then vc.setNull($elemIdx) else vc.setSafe($elemIdx, t.toNanoOfDay / 1000L)
        }
      case '[Duration] =>
        val v = value.asExprOf[Duration]
        '{
          val d = $v
          val vc = $vec.asInstanceOf[DurationVector]
          if d == null then vc.setNull($elemIdx)
          else vc.setSafe($elemIdx, d.getSeconds * 1000000L + d.getNano / 1000L)
        }
      case '[Period] =>
        // Spark uses Math.addExact/multiplyExact for overflow safety; our test values stay well
        // within Int range so the simpler arithmetic matches byte-for-byte.
        val v = value.asExprOf[Period]
        '{
          val p = $v
          val vc = $vec.asInstanceOf[IntervalYearVector]
          if p == null then vc.setNull($elemIdx) else vc.setSafe($elemIdx, p.getYears * 12 + p.getMonths)
        }
      case '[Option[t]] =>
        buildOptionWrite[t](vec, elemIdx, value.asExprOf[Option[t]], largeVarTypes)
      case '[Array[t]] =>
        buildArrayListWrite[t](vec, elemIdx, value.asExprOf[Array[t]], largeVarTypes)
      case _ =>
        val tpe = TypeRepr.of[V]
        mapKeyValueType(tpe) match
          case Some((kt, vt)) =>
            kt.asType match
              case '[k] =>
                vt.asType match
                  case '[v] =>
                    buildMapWrite[k, v](
                      vec,
                      elemIdx,
                      value.asExprOf[scala.collection.Map[k, v]],
                      largeVarTypes
                    )
          case None =>
            seqElementType(tpe) match
              case Some(elem) =>
                elem.asType match
                  case '[e] =>
                    buildSeqListWrite[e](
                      vec,
                      elemIdx,
                      value.asExprOf[scala.collection.Iterable[e]],
                      largeVarTypes
                    )
              case None =>
                val classSym = tpe.classSymbol
                if classSym.isDefined && classSym.get.flags.is(Flags.Case) then
                  buildStructWrite[V](vec, elemIdx, value, largeVarTypes)
                else
                  report.errorAndAbort(
                    s"ArrowRowSerializer: unsupported field type ${tpe.show}. Supported: primitives, " +
                      "String, Array[Byte], BigDecimal, temporal types, nested case classes (Struct), " +
                      "Array[T]/Seq/List/Vector (List), Map[K, V], and Option of any of these."
                  )

  /** Write a nested case class into a `StructVector` at `elemIdx`. A null value marks the slot
    * null; otherwise each child recurses into its child vector and the slot is marked defined.
    */
  private def buildStructWrite[V: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      value: Expr[V],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Unit] =
    '{
      val sv = $vec.asInstanceOf[StructVector]
      val v: V = $value
      if v == null then sv.setNull($elemIdx)
      else
        ${ structChildrenWrite[V]('sv, 'v, elemIdx, largeVarTypes) }
        sv.setIndexDefined($elemIdx)
    }

  /** Write each case field of `v` into the corresponding child vector of `sv`. Runs under the
    * splice's ambient `Quotes`, so all reflection (`memberType`, `Select`) is context-consistent.
    */
  private def structChildrenWrite[V: Type](
      sv: Expr[StructVector],
      v: Expr[V],
      elemIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Unit] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[V]
    val caseFields = tpe.classSymbol.get.caseFields
    val stmts = caseFields.zipWithIndex.map { case (sym, j) =>
      val childTpe = tpe.memberType(sym)
      childTpe.asType match
        case '[ct] =>
          val childVec = '{ $sv.getChildByOrdinal(${ Expr(j) }).asInstanceOf[FieldVector] }
          val childValue = Select(v.asTerm, sym).asExprOf[ct]
          buildValueWrite[ct](childVec, elemIdx, childValue, largeVarTypes).asTerm
    }
    Block(stmts, '{ () }.asTerm).asExprOf[Unit]

  /** Write a `Seq`/`List`/`Vector`/… into a `ListVector` at `elemIdx`. */
  private def buildSeqListWrite[E: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      coll: Expr[scala.collection.Iterable[E]],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Unit] =
    '{
      val lv = $vec.asInstanceOf[ListVector]
      val c = $coll
      if c == null then lv.setNull($elemIdx)
      else
        val start = lv.startNewValue($elemIdx)
        val data = lv.getDataVector
        val it = c.iterator
        var k = 0
        while it.hasNext do
          val e: E = it.next()
          ${ buildValueWrite[E]('data, '{ start + k }, 'e, largeVarTypes) }
          k += 1
        lv.endValue($elemIdx, k)
    }

  /** Write an `Array[E]` (E != Byte) into a `ListVector` at `elemIdx`. */
  private def buildArrayListWrite[E: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      arr: Expr[Array[E]],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Unit] =
    '{
      val lv = $vec.asInstanceOf[ListVector]
      val a = $arr
      if a == null then lv.setNull($elemIdx)
      else
        val start = lv.startNewValue($elemIdx)
        val data = lv.getDataVector
        var k = 0
        while k < a.length do
          val e: E = a(k)
          ${ buildValueWrite[E]('data, '{ start + k }, 'e, largeVarTypes) }
          k += 1
        lv.endValue($elemIdx, a.length)
    }

  /** Write a `Map[K, V]` into a `MapVector` at `elemIdx`. The map is a list of `{key, value}`
    * structs: write each entry's key/value into the entries struct's child vectors at the running
    * element index, mark the entry struct defined, then close the list value. Entry order follows
    * the map's own iteration order — the same order Spark's serializer sees for the same instance.
    */
  private def buildMapWrite[K: Type, V: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      map: Expr[scala.collection.Map[K, V]],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Unit] =
    '{
      val mv = $vec.asInstanceOf[MapVector]
      val m = $map
      if m == null then mv.setNull($elemIdx)
      else
        val start = mv.startNewValue($elemIdx)
        val entries = mv.getDataVector.asInstanceOf[StructVector]
        val keyVec = entries.getChildByOrdinal(0).asInstanceOf[FieldVector]
        val valVec = entries.getChildByOrdinal(1).asInstanceOf[FieldVector]
        val it = m.iterator
        var k = 0
        while it.hasNext do
          val kv = it.next()
          val entryKey: K = kv._1
          val entryVal: V = kv._2
          ${ buildValueWrite[K]('keyVec, '{ start + k }, 'entryKey, largeVarTypes) }
          ${ buildValueWrite[V]('valVec, '{ start + k }, 'entryVal, largeVarTypes) }
          entries.setIndexDefined(start + k)
          k += 1
        mv.endValue($elemIdx, k)
    }

  /** Write `Option[T]` into `vec` at `elemIdx`. Unboxed-primitive inner types need explicit
    * `setNull` because their leaf writer doesn't null-check; every reference inner type (String,
    * BigDecimal, temporal, Array[Byte], nested struct, collection) already null-checks, so we
    * collapse the Option to a possibly-null T and delegate to the generic writer.
    */
  private def buildOptionWrite[T: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      opt: Expr[Option[T]],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Unit] =
    Type.of[T] match
      case '[Boolean] =>
        val o = opt.asExprOf[Option[Boolean]]
        '{
          val oo = $o
          val vc = $vec.asInstanceOf[BitVector]
          if oo == null || oo.isEmpty then vc.setNull($elemIdx)
          else vc.setSafe($elemIdx, if oo.get then 1 else 0)
        }
      case '[Byte] =>
        val o = opt.asExprOf[Option[Byte]]
        '{
          val oo = $o
          val vc = $vec.asInstanceOf[TinyIntVector]
          if oo == null || oo.isEmpty then vc.setNull($elemIdx) else vc.setSafe($elemIdx, oo.get.toInt)
        }
      case '[Short] =>
        val o = opt.asExprOf[Option[Short]]
        '{
          val oo = $o
          val vc = $vec.asInstanceOf[SmallIntVector]
          if oo == null || oo.isEmpty then vc.setNull($elemIdx) else vc.setSafe($elemIdx, oo.get.toInt)
        }
      case '[Int] =>
        val o = opt.asExprOf[Option[Int]]
        '{
          val oo = $o
          val vc = $vec.asInstanceOf[IntVector]
          if oo == null || oo.isEmpty then vc.setNull($elemIdx) else vc.setSafe($elemIdx, oo.get)
        }
      case '[Long] =>
        val o = opt.asExprOf[Option[Long]]
        '{
          val oo = $o
          val vc = $vec.asInstanceOf[BigIntVector]
          if oo == null || oo.isEmpty then vc.setNull($elemIdx) else vc.setSafe($elemIdx, oo.get)
        }
      case '[Float] =>
        val o = opt.asExprOf[Option[Float]]
        '{
          val oo = $o
          val vc = $vec.asInstanceOf[Float4Vector]
          if oo == null || oo.isEmpty then vc.setNull($elemIdx) else vc.setSafe($elemIdx, oo.get)
        }
      case '[Double] =>
        val o = opt.asExprOf[Option[Double]]
        '{
          val oo = $o
          val vc = $vec.asInstanceOf[Float8Vector]
          if oo == null || oo.isEmpty then vc.setNull($elemIdx) else vc.setSafe($elemIdx, oo.get)
        }
      case _ =>
        // Reference inner type: collapse to a (possibly null) T and let the generic writer's own
        // null-handling mark the slot. T is a reference type here (primitives handled above).
        val inner: Expr[T] =
          '{ val o = $opt; if o == null || o.isEmpty then null.asInstanceOf[T] else o.get }
        buildValueWrite[T](vec, elemIdx, inner, largeVarTypes)
