package protocatalyst.dsl

import protocatalyst.encoder.ProtoEncoder
import protocatalyst.expr.ProtoExpr
import protocatalyst.types._

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
