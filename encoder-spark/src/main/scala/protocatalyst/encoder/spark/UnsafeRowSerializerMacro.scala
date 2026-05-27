package protocatalyst.encoder.spark

import java.{sql => jsql}
import java.math.{BigDecimal => JBigDecimal}
import java.time.{Duration, Instant, LocalDate, LocalDateTime, LocalTime, Period}
import java.util.UUID

import scala.quoted.*

import org.apache.spark.sql.catalyst.expressions.UnsafeRow
import org.apache.spark.sql.catalyst.expressions.codegen.UnsafeRowWriter
import org.apache.spark.sql.catalyst.util.{DateTimeUtils, IntervalUtils}
import org.apache.spark.sql.types.{Decimal => SparkDecimal}
import org.apache.spark.unsafe.types.UTF8String

import protocatalyst.schema.ProtoSchema

/** Scala 3 quoted-macro implementation of `UnsafeRowSerializer.derived[T]`.
  *
  * Emits a direct case-class constructor call for the deserialize path
  * (`new T(row.getLong(0), row.getInt(1), ..., row.getUTF8String(k).toString, ...)`) and a
  * sequence of direct `writer.write(i, value.field)` calls for the serialize path. No
  * `Array[Any]`, no `Mirror.fromProduct`, no primitive boxing on either side.
  *
  * Modeled on jsoniter-scala's `JsonCodecMaker`. The previous `inline match` implementation
  * (step 2) closed the megamorphic-dispatch and double-array gaps but still went through
  * `ArrayProduct` + `Mirror.fromProduct`, which forces every primitive through
  * `java.lang.Long`/`Integer` boxing. This macro eliminates that final allocation source.
  */
object UnsafeRowSerializerMacro:

  def derivedImpl[T: Type](
      schemaExpr: Expr[ProtoSchema]
  )(using Quotes): Expr[UnsafeRowSerializer[T]] =
    import quotes.reflect.*
    val tpe = TypeRepr.of[T]
    val classSym = tpe.classSymbol.getOrElse {
      report.errorAndAbort(
        s"UnsafeRowSerializer.derived requires a case class; ${tpe.show} is not a class type."
      )
    }
    if !classSym.flags.is(Flags.Case) then
      report.errorAndAbort(
        s"UnsafeRowSerializer.derived requires a case class; ${tpe.show} is not a case class."
      )

    val caseFields = classSym.caseFields
    val n = caseFields.length
    val countExpr = Expr(n)

    // Type arguments applied to `T`. For `T = Box[String]` this is `List(String)`; for a
    // non-generic case class it's `Nil`. We need these to construct the call via TypeApply
    // when the primary constructor is polymorphic.
    val typeArgs: List[TypeRepr] = tpe match
      case AppliedType(_, args) => args
      case _                    => Nil

    // === readFn: UnsafeRow => T ===
    //
    // Emits a lambda whose body is `new T(readExpr_0, readExpr_1, ..., readExpr_{n-1})`. Each
    // `readExpr_i` is a fully-typed expression (`row.getLong(i)`, `row.getInt(i)`, etc.) so the
    // ctor call is monomorphic, JIT-inlinable, no boxing.
    val readMT = MethodType(List("row"))(_ => List(TypeRepr.of[UnsafeRow]), _ => tpe)
    val readLambdaTerm: Term = Lambda(
      Symbol.spliceOwner,
      readMT,
      { (_, params) =>
        val rowParam = params.head.asInstanceOf[Term]
        val rowExpr = rowParam.asExprOf[UnsafeRow]
        val ctorArgs = caseFields.zipWithIndex.map { case (fieldSym, idx) =>
          val fieldTpe = tpe.memberType(fieldSym)
          buildReadExpr(fieldTpe, idx, rowExpr).asTerm
        }
        // `New(TypeTree.of[T])` preserves applied type arguments (e.g. `Box[String]`, not bare
        // `Box`). For polymorphic case classes the primary constructor takes type args first;
        // `appliedToTypes` handles that, `appliedToArgs` the value args.
        New(TypeTree.of[T])
          .select(classSym.primaryConstructor)
          .appliedToTypes(typeArgs)
          .appliedToArgs(ctorArgs)
      }
    )
    val readLambda = readLambdaTerm.asExprOf[UnsafeRow => T]

    // === writeFn: (UnsafeRowWriter, T) => Unit ===
    //
    // Emits a lambda whose body is a Block of N write statements, one per field. Each statement
    // dispatches by static field type and either writes the primitive directly or does a null
    // check + variable-length write.
    val writeMT = MethodType(List("writer", "value"))(
      _ => List(TypeRepr.of[UnsafeRowWriter], tpe),
      _ => TypeRepr.of[Unit]
    )
    val writeLambdaTerm: Term = Lambda(
      Symbol.spliceOwner,
      writeMT,
      { (_, params) =>
        val writerParam = params(0).asInstanceOf[Term]
        val valueParam = params(1).asInstanceOf[Term]
        val writerExpr = writerParam.asExprOf[UnsafeRowWriter]
        val statements: List[Term] = caseFields.zipWithIndex.map { case (fieldSym, idx) =>
          val fieldTpe = tpe.memberType(fieldSym)
          val fieldAccess = Select(valueParam, fieldSym)
          buildWriteExpr(fieldTpe, idx, writerExpr, fieldAccess).asTerm
        }
        // Block semantics: stats evaluated, expr is the result. For Unit-valued lambda we put
        // a final `()` as the result expression.
        Block(statements, Literal(UnitConstant()))
      }
    )
    val writeLambda = writeLambdaTerm.asExprOf[(UnsafeRowWriter, T) => Unit]

    '{
      new UnsafeRowSerializer.UnsafeRowSerializerImpl[T](
        $schemaExpr,
        $countExpr,
        $writeLambda,
        $readLambda
      )
    }

  // -------------------------------------------------------------------------
  // Per-field expression builders. Mirror the inline-match dispatch but emit
  // first-class Expr / Term trees so the constructor / write-statement can be
  // built as one inlined call.
  // -------------------------------------------------------------------------

  /** Build the read expression for a case-class field of static type `tpe` at slot `idx`. */
  private def buildReadExpr(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      idx: Int,
      row: Expr[UnsafeRow]
  ): Expr[Any] =
    import quotes.reflect.*
    val idxE = Expr(idx)
    tpe.asType match
      case '[Boolean] => '{ $row.getBoolean($idxE) }
      case '[Byte]    => '{ $row.getByte($idxE) }
      case '[Short]   => '{ $row.getShort($idxE) }
      case '[Int]     => '{ $row.getInt($idxE) }
      case '[Long]    => '{ $row.getLong($idxE) }
      case '[Float]   => '{ $row.getFloat($idxE) }
      case '[Double]  => '{ $row.getDouble($idxE) }
      case '[String]  =>
        '{ if $row.isNullAt($idxE) then null else $row.getUTF8String($idxE).toString }
      case '[Array[Byte]] =>
        '{ if $row.isNullAt($idxE) then null else $row.getBinary($idxE) }
      case '[BigDecimal] =>
        '{
          if $row.isNullAt($idxE) then null
          else BigDecimal($row.getDecimal($idxE, 38, 18).toJavaBigDecimal)
        }
      case '[JBigDecimal] =>
        '{
          if $row.isNullAt($idxE) then null
          else $row.getDecimal($idxE, 38, 18).toJavaBigDecimal
        }
      case '[LocalDate] =>
        '{
          if $row.isNullAt($idxE) then null
          else DateTimeUtils.daysToLocalDate($row.getInt($idxE))
        }
      case '[jsql.Date] =>
        '{
          if $row.isNullAt($idxE) then null
          else DateTimeUtils.toJavaDate($row.getInt($idxE))
        }
      case '[Instant] =>
        '{
          if $row.isNullAt($idxE) then null
          else DateTimeUtils.microsToInstant($row.getLong($idxE))
        }
      case '[jsql.Timestamp] =>
        '{
          if $row.isNullAt($idxE) then null
          else DateTimeUtils.toJavaTimestamp($row.getLong($idxE))
        }
      case '[LocalDateTime] =>
        '{
          if $row.isNullAt($idxE) then null
          else DateTimeUtils.microsToLocalDateTime($row.getLong($idxE))
        }
      case '[LocalTime] =>
        '{
          if $row.isNullAt($idxE) then null
          else LocalTime.ofNanoOfDay($row.getLong($idxE))
        }
      case '[Duration] =>
        '{
          if $row.isNullAt($idxE) then null
          else IntervalUtils.microsToDuration($row.getLong($idxE))
        }
      case '[Period] =>
        '{
          if $row.isNullAt($idxE) then null
          else IntervalUtils.monthsToPeriod($row.getInt($idxE))
        }
      case '[UUID] =>
        '{
          if $row.isNullAt($idxE) then null
          else UUID.fromString($row.getUTF8String($idxE).toString)
        }
      case '[Option[t]] => buildOptionReadExpr[t](idx, row)
      case _            =>
        report.errorAndAbort(
          s"UnsafeRowSerializer: unsupported field type ${tpe.show} at index $idx"
        )

  private def buildOptionReadExpr[T: Type](using Quotes)(
      idx: Int,
      row: Expr[UnsafeRow]
  ): Expr[Option[T]] =
    import quotes.reflect.*
    val idxE = Expr(idx)
    Type.of[T] match
      case '[Boolean] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getBoolean($idxE)) }
          .asExprOf[Option[T]]
      case '[Byte] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getByte($idxE)) }
          .asExprOf[Option[T]]
      case '[Short] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getShort($idxE)) }
          .asExprOf[Option[T]]
      case '[Int] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getInt($idxE)) }
          .asExprOf[Option[T]]
      case '[Long] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getLong($idxE)) }
          .asExprOf[Option[T]]
      case '[Float] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getFloat($idxE)) }
          .asExprOf[Option[T]]
      case '[Double] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getDouble($idxE)) }
          .asExprOf[Option[T]]
      case '[String] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getUTF8String($idxE).toString) }
          .asExprOf[Option[T]]
      case '[Array[Byte]] =>
        '{ if $row.isNullAt($idxE) then None else Some($row.getBinary($idxE)) }
          .asExprOf[Option[T]]
      case '[BigDecimal] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(BigDecimal($row.getDecimal($idxE, 38, 18).toJavaBigDecimal))
        }.asExprOf[Option[T]]
      case '[JBigDecimal] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some($row.getDecimal($idxE, 38, 18).toJavaBigDecimal)
        }.asExprOf[Option[T]]
      case '[LocalDate] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(DateTimeUtils.daysToLocalDate($row.getInt($idxE)))
        }.asExprOf[Option[T]]
      case '[jsql.Date] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(DateTimeUtils.toJavaDate($row.getInt($idxE)))
        }.asExprOf[Option[T]]
      case '[Instant] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(DateTimeUtils.microsToInstant($row.getLong($idxE)))
        }.asExprOf[Option[T]]
      case '[jsql.Timestamp] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(DateTimeUtils.toJavaTimestamp($row.getLong($idxE)))
        }.asExprOf[Option[T]]
      case '[LocalDateTime] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(DateTimeUtils.microsToLocalDateTime($row.getLong($idxE)))
        }.asExprOf[Option[T]]
      case '[LocalTime] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(LocalTime.ofNanoOfDay($row.getLong($idxE)))
        }.asExprOf[Option[T]]
      case '[Duration] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(IntervalUtils.microsToDuration($row.getLong($idxE)))
        }.asExprOf[Option[T]]
      case '[Period] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(IntervalUtils.monthsToPeriod($row.getInt($idxE)))
        }.asExprOf[Option[T]]
      case '[UUID] =>
        '{
          if $row.isNullAt($idxE) then None
          else Some(UUID.fromString($row.getUTF8String($idxE).toString))
        }.asExprOf[Option[T]]
      case _ =>
        report.errorAndAbort(
          s"UnsafeRowSerializer: unsupported Option inner type ${TypeRepr.of[T].show} at index $idx"
        )

  /** Build the write expression for a case-class field of static type `tpe` at slot `idx`. */
  private def buildWriteExpr(using Quotes)(
      tpe: quotes.reflect.TypeRepr,
      idx: Int,
      writer: Expr[UnsafeRowWriter],
      fieldAccess: quotes.reflect.Term
  ): Expr[Unit] =
    import quotes.reflect.*
    val idxE = Expr(idx)
    tpe.asType match
      case '[Boolean] => '{ $writer.write($idxE, ${ fieldAccess.asExprOf[Boolean] }) }
      case '[Byte]    => '{ $writer.write($idxE, ${ fieldAccess.asExprOf[Byte] }) }
      case '[Short]   => '{ $writer.write($idxE, ${ fieldAccess.asExprOf[Short] }) }
      case '[Int]     => '{ $writer.write($idxE, ${ fieldAccess.asExprOf[Int] }) }
      case '[Long]    => '{ $writer.write($idxE, ${ fieldAccess.asExprOf[Long] }) }
      case '[Float]   => '{ $writer.write($idxE, ${ fieldAccess.asExprOf[Float] }) }
      case '[Double]  => '{ $writer.write($idxE, ${ fieldAccess.asExprOf[Double] }) }
      case '[String] =>
        val v = fieldAccess.asExprOf[String]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, UTF8String.fromString($v))
        }
      case '[Array[Byte]] =>
        val v = fieldAccess.asExprOf[Array[Byte]]
        '{ if $v == null then $writer.setNullAt($idxE) else $writer.write($idxE, $v) }
      case '[BigDecimal] =>
        val v = fieldAccess.asExprOf[BigDecimal]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, SparkDecimal($v.bigDecimal, 38, 18), 38, 18)
        }
      case '[JBigDecimal] =>
        val v = fieldAccess.asExprOf[JBigDecimal]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, SparkDecimal($v, 38, 18), 38, 18)
        }
      case '[LocalDate] =>
        val v = fieldAccess.asExprOf[LocalDate]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.localDateToDays($v))
        }
      case '[jsql.Date] =>
        val v = fieldAccess.asExprOf[jsql.Date]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.fromJavaDate($v))
        }
      case '[Instant] =>
        val v = fieldAccess.asExprOf[Instant]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.instantToMicros($v))
        }
      case '[jsql.Timestamp] =>
        val v = fieldAccess.asExprOf[jsql.Timestamp]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.fromJavaTimestamp($v))
        }
      case '[LocalDateTime] =>
        val v = fieldAccess.asExprOf[LocalDateTime]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.localDateTimeToMicros($v))
        }
      case '[LocalTime] =>
        val v = fieldAccess.asExprOf[LocalTime]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, $v.toNanoOfDay)
        }
      case '[Duration] =>
        val v = fieldAccess.asExprOf[Duration]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, IntervalUtils.durationToMicros($v))
        }
      case '[Period] =>
        val v = fieldAccess.asExprOf[Period]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, IntervalUtils.periodToMonths($v))
        }
      case '[UUID] =>
        val v = fieldAccess.asExprOf[UUID]
        '{
          if $v == null then $writer.setNullAt($idxE)
          else $writer.write($idxE, UTF8String.fromString($v.toString))
        }
      case '[Option[t]] => buildOptionWriteExpr[t](idx, writer, fieldAccess)
      case _            =>
        report.errorAndAbort(
          s"UnsafeRowSerializer: unsupported field type ${tpe.show} at index $idx"
        )

  private def buildOptionWriteExpr[T: Type](using Quotes)(
      idx: Int,
      writer: Expr[UnsafeRowWriter],
      fieldAccess: quotes.reflect.Term
  ): Expr[Unit] =
    import quotes.reflect.*
    val idxE = Expr(idx)
    // Cast the field access to the matched concrete Option[X] before quoting. This way the
    // `.get` call inside each quote returns a concretely-typed value rather than `v: T` (the
    // macro's bound type variable), which the compiler can't dispatch overloads against.
    Type.of[T] match
      case '[Boolean] =>
        val opt = fieldAccess.asExprOf[Option[Boolean]]
        '{ if $opt.isEmpty then $writer.setNullAt($idxE) else $writer.write($idxE, $opt.get) }
      case '[Byte] =>
        val opt = fieldAccess.asExprOf[Option[Byte]]
        '{ if $opt.isEmpty then $writer.setNullAt($idxE) else $writer.write($idxE, $opt.get) }
      case '[Short] =>
        val opt = fieldAccess.asExprOf[Option[Short]]
        '{ if $opt.isEmpty then $writer.setNullAt($idxE) else $writer.write($idxE, $opt.get) }
      case '[Int] =>
        val opt = fieldAccess.asExprOf[Option[Int]]
        '{ if $opt.isEmpty then $writer.setNullAt($idxE) else $writer.write($idxE, $opt.get) }
      case '[Long] =>
        val opt = fieldAccess.asExprOf[Option[Long]]
        '{ if $opt.isEmpty then $writer.setNullAt($idxE) else $writer.write($idxE, $opt.get) }
      case '[Float] =>
        val opt = fieldAccess.asExprOf[Option[Float]]
        '{ if $opt.isEmpty then $writer.setNullAt($idxE) else $writer.write($idxE, $opt.get) }
      case '[Double] =>
        val opt = fieldAccess.asExprOf[Option[Double]]
        '{ if $opt.isEmpty then $writer.setNullAt($idxE) else $writer.write($idxE, $opt.get) }
      case '[String] =>
        val opt = fieldAccess.asExprOf[Option[String]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, UTF8String.fromString($opt.get))
        }
      case '[Array[Byte]] =>
        val opt = fieldAccess.asExprOf[Option[Array[Byte]]]
        '{ if $opt.isEmpty then $writer.setNullAt($idxE) else $writer.write($idxE, $opt.get) }
      case '[BigDecimal] =>
        val opt = fieldAccess.asExprOf[Option[BigDecimal]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, SparkDecimal($opt.get.bigDecimal, 38, 18), 38, 18)
        }
      case '[JBigDecimal] =>
        val opt = fieldAccess.asExprOf[Option[JBigDecimal]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, SparkDecimal($opt.get, 38, 18), 38, 18)
        }
      case '[LocalDate] =>
        val opt = fieldAccess.asExprOf[Option[LocalDate]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.localDateToDays($opt.get))
        }
      case '[jsql.Date] =>
        val opt = fieldAccess.asExprOf[Option[jsql.Date]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.fromJavaDate($opt.get))
        }
      case '[Instant] =>
        val opt = fieldAccess.asExprOf[Option[Instant]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.instantToMicros($opt.get))
        }
      case '[jsql.Timestamp] =>
        val opt = fieldAccess.asExprOf[Option[jsql.Timestamp]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.fromJavaTimestamp($opt.get))
        }
      case '[LocalDateTime] =>
        val opt = fieldAccess.asExprOf[Option[LocalDateTime]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, DateTimeUtils.localDateTimeToMicros($opt.get))
        }
      case '[LocalTime] =>
        val opt = fieldAccess.asExprOf[Option[LocalTime]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, $opt.get.toNanoOfDay)
        }
      case '[Duration] =>
        val opt = fieldAccess.asExprOf[Option[Duration]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, IntervalUtils.durationToMicros($opt.get))
        }
      case '[Period] =>
        val opt = fieldAccess.asExprOf[Option[Period]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, IntervalUtils.periodToMonths($opt.get))
        }
      case '[UUID] =>
        val opt = fieldAccess.asExprOf[Option[UUID]]
        '{
          if $opt.isEmpty then $writer.setNullAt($idxE)
          else $writer.write($idxE, UTF8String.fromString($opt.get.toString))
        }
      case _ =>
        report.errorAndAbort(
          s"UnsafeRowSerializer: unsupported Option inner type ${TypeRepr.of[T].show} at index $idx"
        )
