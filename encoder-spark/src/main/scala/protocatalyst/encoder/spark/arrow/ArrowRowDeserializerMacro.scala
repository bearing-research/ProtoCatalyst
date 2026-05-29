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
  VarCharVector,
  VectorSchemaRoot
}
import org.apache.arrow.vector.complex.{ListVector, MapVector, StructVector}

/** Quoted-macro implementation of `ArrowRowDeserializer.derived[T]`.
  *
  * Emits a `(VectorSchemaRoot, Int) => T` function whose body is a direct constructor call
  * (`new T(read_0, read_1, ..., read_{n-1})`) — same jsoniter-scala pattern used by
  * [[protocatalyst.encoder.spark.UnsafeRowSerializerMacro]]. Each `read_i` reads from a
  * statically-typed Arrow vector and emits the right unboxing/null-handling.
  *
  * The per-value reader is recursive: a nested case class reads its children out of a
  * `StructVector`; a collection reads its elements out of a `ListVector`'s data vector over the
  * offset range. The recursion is keyed on `(FieldVector, elementIndex)` and expressed with
  * `Type[_]`/`Expr[_]` so it composes across the nested `Quotes` each splice introduces.
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

  /** Emit the top-level constructor call `new T(read_0, ..., read_{n-1})`, each child reading from
    * its own top-level vector `root.getVector(i)`.
    */
  private def buildCtorCall[T: Type](
      root: Expr[VectorSchemaRoot],
      rowIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[T] =
    structCtor[T]('{ (i: Int) => $root.getVector(i) }, rowIdx, largeVarTypes)

  /** Element type of a non-Array collection that maps to Arrow `List`. Returns `None` otherwise
    * (Map has its own `ArrowType.Map` handling).
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
  // Recursive per-value read. Reads a value of static type `V` from the Arrow
  // vector `vec` at element index `elemIdx`.
  // ---------------------------------------------------------------------------

  private def buildValueRead[V: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*
    Type.of[V] match
      case '[Boolean] =>
        '{ $vec.asInstanceOf[BitVector].get($elemIdx) == 1 }
      case '[Byte] =>
        '{ $vec.asInstanceOf[TinyIntVector].get($elemIdx) }
      case '[Short] =>
        '{ $vec.asInstanceOf[SmallIntVector].get($elemIdx) }
      case '[Int] =>
        '{ $vec.asInstanceOf[IntVector].get($elemIdx) }
      case '[Long] =>
        '{ $vec.asInstanceOf[BigIntVector].get($elemIdx) }
      case '[Float] =>
        '{ $vec.asInstanceOf[Float4Vector].get($elemIdx) }
      case '[Double] =>
        '{ $vec.asInstanceOf[Float8Vector].get($elemIdx) }
      case '[String] =>
        '{
          if $largeVarTypes then
            val vc = $vec.asInstanceOf[LargeVarCharVector]
            if vc.isNull($elemIdx) then null
            else new String(vc.get($elemIdx), StandardCharsets.UTF_8)
          else
            val vc = $vec.asInstanceOf[VarCharVector]
            if vc.isNull($elemIdx) then null
            else new String(vc.get($elemIdx), StandardCharsets.UTF_8)
        }
      case '[Array[Byte]] =>
        '{
          if $largeVarTypes then
            val vc = $vec.asInstanceOf[LargeVarBinaryVector]
            if vc.isNull($elemIdx) then null else vc.get($elemIdx)
          else
            val vc = $vec.asInstanceOf[VarBinaryVector]
            if vc.isNull($elemIdx) then null else vc.get($elemIdx)
        }
      case '[BigDecimal] =>
        '{
          val vc = $vec.asInstanceOf[DecimalVector]
          if vc.isNull($elemIdx) then null else BigDecimal(vc.getObject($elemIdx))
        }
      case '[JBigDecimal] =>
        '{
          val vc = $vec.asInstanceOf[DecimalVector]
          if vc.isNull($elemIdx) then null else vc.getObject($elemIdx)
        }
      case '[LocalDate] =>
        '{
          val vc = $vec.asInstanceOf[DateDayVector]
          if vc.isNull($elemIdx) then null else LocalDate.ofEpochDay(vc.get($elemIdx).toLong)
        }
      case '[jsql.Date] =>
        // Spark: DateTimeUtils.toJavaDate(days) round-trips through LocalDate under defaultZone.
        '{
          val vc = $vec.asInstanceOf[DateDayVector]
          if vc.isNull($elemIdx) then null
          else jsql.Date.valueOf(LocalDate.ofEpochDay(vc.get($elemIdx).toLong))
        }
      case '[Instant] =>
        '{
          val vc = $vec.asInstanceOf[TimeStampMicroTZVector]
          if vc.isNull($elemIdx) then null
          else
            val micros = vc.get($elemIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            Instant.ofEpochSecond(seconds, nanos)
        }
      case '[jsql.Timestamp] =>
        '{
          val vc = $vec.asInstanceOf[TimeStampMicroTZVector]
          if vc.isNull($elemIdx) then null
          else
            val micros = vc.get($elemIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            jsql.Timestamp.from(Instant.ofEpochSecond(seconds, nanos))
        }
      case '[LocalDateTime] =>
        '{
          val vc = $vec.asInstanceOf[TimeStampMicroVector]
          if vc.isNull($elemIdx) then null
          else
            val micros = vc.get($elemIdx)
            val seconds = Math.floorDiv(micros, 1000000L)
            val nanos = Math.floorMod(micros, 1000000L) * 1000L
            LocalDateTime.ofEpochSecond(seconds, nanos.toInt, ZoneOffset.UTC)
        }
      case '[LocalTime] =>
        '{
          val vc = $vec.asInstanceOf[TimeMicroVector]
          if vc.isNull($elemIdx) then null
          else LocalTime.ofNanoOfDay(vc.get($elemIdx) * 1000L)
        }
      case '[Duration] =>
        // DurationVector.get returns ArrowBuf (unusual API for a fixed-width vector); use
        // getObject which converts to a Duration using the vector's configured unit (micros).
        '{
          val vc = $vec.asInstanceOf[DurationVector]
          if vc.isNull($elemIdx) then null else vc.getObject($elemIdx)
        }
      case '[Period] =>
        '{
          val vc = $vec.asInstanceOf[IntervalYearVector]
          if vc.isNull($elemIdx) then null else Period.ofMonths(vc.get($elemIdx))
        }
      case '[Option[t]] =>
        buildOptionRead[t](vec, elemIdx, largeVarTypes)
      case '[Array[t]] =>
        buildArrayListRead[t](vec, elemIdx, largeVarTypes)
      case _ =>
        val tpe = TypeRepr.of[V]
        mapKeyValueType(tpe) match
          case Some((kt, vt)) =>
            kt.asType match
              case '[k] =>
                vt.asType match
                  case '[v] => buildMapRead[k, v](vec, elemIdx, largeVarTypes)
          case None =>
            seqElementType(tpe) match
              case Some(elem) =>
                elem.asType match
                  case '[e] => buildSeqListRead[e](tpe, vec, elemIdx, largeVarTypes)
              case None =>
                val classSym = tpe.classSymbol
                if classSym.isDefined && classSym.get.flags.is(Flags.Case) then
                  buildStructRead[V](vec, elemIdx, largeVarTypes)
                else
                  report.errorAndAbort(
                    s"ArrowRowDeserializer: unsupported field type ${tpe.show}. Supported: primitives, " +
                      "String, Array[Byte], BigDecimal, temporal types, nested case classes (Struct), " +
                      "Array[T]/Seq/List/Vector (List), Map[K, V], and Option of any of these."
                  )

  /** `Option[T]`: `None` for a null slot, else `Some(read)`. Works uniformly for every inner type
    * (primitive or reference) because `ValueVector.isNull` is defined on all of them and the inner
    * read is only evaluated for non-null slots.
    */
  private def buildOptionRead[T: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Option[T]] =
    '{
      if $vec.isNull($elemIdx) then None
      else Some(${ buildValueRead[T](vec, elemIdx, largeVarTypes).asExprOf[T] })
    }

  /** Read a nested case class from a `StructVector` at `elemIdx`. */
  private def buildStructRead[V: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Any] =
    def ctor(sv: Expr[StructVector])(using Quotes): Expr[V] =
      structCtor[V](
        '{ (i: Int) => $sv.getChildByOrdinal(i).asInstanceOf[FieldVector] },
        elemIdx,
        largeVarTypes
      )
    '{
      val sv = $vec.asInstanceOf[StructVector]
      if sv.isNull($elemIdx) then null.asInstanceOf[V]
      else ${ ctor('sv) }
    }

  /** Build `new V(child_0, ..., child_{n-1})` where `child_j` reads from `childVec(j)` (the j-th
    * child vector). Used both at the top level (children are `root.getVector(j)`) and for nested
    * structs (children are `structVector.getChildByOrdinal(j)`).
    */
  private def structCtor[V: Type](
      childVec: Expr[Int => FieldVector],
      elemIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[V] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[V]
    val classSym = tpe.classSymbol.get
    val caseFields = classSym.caseFields
    val typeArgs: List[TypeRepr] = tpe match
      case AppliedType(_, args) => args
      case _                    => Nil
    val ctorArgs = caseFields.zipWithIndex.map { case (sym, j) =>
      val childTpe = tpe.memberType(sym)
      childTpe.asType match
        case '[ct] =>
          val cv = '{ $childVec(${ Expr(j) }) }
          buildValueRead[ct](cv, elemIdx, largeVarTypes).asExprOf[ct].asTerm
    }
    New(TypeTree.of[V])
      .select(classSym.primaryConstructor)
      .appliedToTypes(typeArgs)
      .appliedToArgs(ctorArgs)
      .asExprOf[V]

  /** Read a `Seq`/`List`/`Vector`/… from a `ListVector` at `elemIdx`. The concrete builder is
    * chosen from the declared field type so `Vector[x]` reconstructs a `Vector`, etc.; everything
    * else (incl. `Seq`/`List`) reconstructs a `List`, which conforms to those declared types.
    */
  private def buildSeqListRead[E: Type](using Quotes)(
      collTpe: quotes.reflect.TypeRepr,
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  ): Expr[Any] =
    import quotes.reflect.*
    // Build with the precise collection type `C` (not a widened `Iterable`) so the result Expr
    // statically conforms to the declared field type when spliced into the constructor.
    def build[C: Type](builder: Expr[scala.collection.mutable.Builder[E, C]]): Expr[C] =
      '{
        val lv = $vec.asInstanceOf[ListVector]
        if lv.isNull($elemIdx) then null.asInstanceOf[C]
        else
          val off = lv.getOffsetBuffer
          val start = off.getInt($elemIdx.toLong * 4L)
          val end = off.getInt(($elemIdx.toLong + 1L) * 4L)
          val data = lv.getDataVector
          val b = $builder
          var k = start
          while k < end do
            b += ${ buildValueRead[E]('data, 'k, largeVarTypes).asExprOf[E] }
            k += 1
          b.result()
      }
    if collTpe <:< TypeRepr.of[Vector[Any]] || collTpe <:< TypeRepr.of[IndexedSeq[Any]] then
      build[Vector[E]]('{ Vector.newBuilder[E] })
    else if collTpe <:< TypeRepr.of[Set[Any]] then build[Set[E]]('{ Set.newBuilder[E] })
    else build[List[E]]('{ List.newBuilder[E] })

  /** Read an `Array[E]` (E != Byte) from a `ListVector` at `elemIdx`. Requires a `ClassTag[E]`. */
  private def buildArrayListRead[E: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Any] =
    import quotes.reflect.*
    val ct = Expr.summon[scala.reflect.ClassTag[E]].getOrElse {
      report.errorAndAbort(
        s"ArrowRowDeserializer: cannot summon ClassTag for Array element ${TypeRepr.of[E].show}."
      )
    }
    '{
      val lv = $vec.asInstanceOf[ListVector]
      if lv.isNull($elemIdx) then null
      else
        val off = lv.getOffsetBuffer
        val start = off.getInt($elemIdx.toLong * 4L)
        val end = off.getInt(($elemIdx.toLong + 1L) * 4L)
        val data = lv.getDataVector
        val n = end - start
        given scala.reflect.ClassTag[E] = $ct
        val a = new Array[E](n)
        var k = 0
        while k < n do
          a(k) = ${ buildValueRead[E]('data, '{ start + k }, largeVarTypes).asExprOf[E] }
          k += 1
        a
    }

  /** Read a `Map[K, V]` from a `MapVector` at `elemIdx` (a list of `{key, value}` structs). Builds
    * an immutable `Map`, which conforms to a declared `Map`/`collection.Map` field type.
    */
  private def buildMapRead[K: Type, V: Type](
      vec: Expr[FieldVector],
      elemIdx: Expr[Int],
      largeVarTypes: Expr[Boolean]
  )(using Quotes): Expr[Any] =
    '{
      val mv = $vec.asInstanceOf[MapVector]
      if mv.isNull($elemIdx) then null
      else
        val off = mv.getOffsetBuffer
        val start = off.getInt($elemIdx.toLong * 4L)
        val end = off.getInt(($elemIdx.toLong + 1L) * 4L)
        val entries = mv.getDataVector.asInstanceOf[StructVector]
        val keyVec = entries.getChildByOrdinal(0).asInstanceOf[FieldVector]
        val valVec = entries.getChildByOrdinal(1).asInstanceOf[FieldVector]
        val b = Map.newBuilder[K, V]
        var k = start
        while k < end do
          val key = ${ buildValueRead[K]('keyVec, 'k, largeVarTypes).asExprOf[K] }
          val value = ${ buildValueRead[V]('valVec, 'k, largeVarTypes).asExprOf[V] }
          b += (key -> value)
          k += 1
        b.result()
    }
