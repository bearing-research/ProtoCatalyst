package protocatalyst.dsl

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.expr.ProtoExpr
import protocatalyst.types.*

/** Type-safe column reference.
  *
  * @tparam A
  *   The row type this column belongs to (e.g., User)
  * @tparam T
  *   The column's value type (e.g., String for a name field)
  */
final class Column[A, T] private[dsl] (
    val name: String,
    val protoType: ProtoType,
    val nullable: Boolean,
    val columnEncoder: ProtoEncoder[T],
    private val qualifier: Option[String] = None
) extends Expr[T]:

  def encoder: ProtoEncoder[T] = columnEncoder

  def toProtoExpr: ProtoExpr =
    ProtoExpr.ColumnRef(name, qualifier, protoType, nullable)

  /** Create a qualified column reference (table.column) */
  def qualified(qual: String): Column[A, T] =
    new Column[A, T](name, protoType, nullable, columnEncoder, Some(qual))

  override def toString: String = s"Column($name: $protoType)"

object Column:
  /** Create a column from name and encoder */
  def apply[A, T](name: String)(using enc: ProtoEncoder[T]): Column[A, T] =
    new Column[A, T](name, enc.catalystType, enc.nullable, enc, None)

  /** Create a column with explicit type info */
  private[dsl] def apply[A, T](
      name: String,
      protoType: ProtoType,
      nullable: Boolean,
      enc: ProtoEncoder[T]
  ): Column[A, T] =
    new Column[A, T](name, protoType, nullable, enc, None)

/** Columns accessor for a record type A. Generated at compile time from ProtoEncoder[A].
  *
  * This trait is implemented by the Table macro to provide typed field access.
  */
trait Columns[A]:
  /** Get the schema fields */
  def allColumns: Vector[Column[A, ?]]

  /** Get a column by name (runtime lookup, prefer typed accessors) */
  def column[T](name: String)(using enc: ProtoEncoder[T]): Column[A, T] =
    allColumns.find(_.name == name) match
      case Some(col) => col.asInstanceOf[Column[A, T]]
      case None      => throw new IllegalArgumentException(s"Column $name not found")
