package protocatalyst.encoder

import protocatalyst.types.ProtoType
import scala.reflect.ClassTag

/**
 * User-Defined Type (UDT) for ProtoCatalyst.
 *
 * A UDT allows custom types to be stored in Spark DataFrames by defining
 * how to serialize/deserialize to a standard SQL type.
 *
 * This mirrors Spark's UserDefinedType pattern:
 * - Define the underlying SQL storage type (sqlType)
 * - Implement serialize/deserialize between user type and SQL datum
 *
 * Example usage:
 * {{{
 * case class Point(x: Double, y: Double)
 *
 * class PointUDT extends ProtoUDT[Point]:
 *   def sqlType: ProtoType = ProtoType.StructType(Vector(
 *     ProtoStructField("x", ProtoType.DoubleType, false),
 *     ProtoStructField("y", ProtoType.DoubleType, false)
 *   ))
 *
 *   def serialize(point: Point): Any = Array(point.x, point.y)
 *
 *   def deserialize(datum: Any): Point = datum match
 *     case arr: Array[Any] => Point(arr(0).asInstanceOf[Double], arr(1).asInstanceOf[Double])
 *     case row: Product => Point(row.productElement(0).asInstanceOf[Double],
 *                                row.productElement(1).asInstanceOf[Double])
 *
 *   def userClass: Class[Point] = classOf[Point]
 * }}}
 *
 * @tparam UserType The user-facing type (must be a reference type, can be null)
 */
trait ProtoUDT[UserType >: Null]:

  /**
   * The underlying SQL type used for storage.
   * Common patterns:
   * - StructType for complex types (like Vector, Matrix)
   * - ArrayType for variable-length data
   * - BinaryType for opaque serialized data
   */
  def sqlType: ProtoType

  /**
   * Serialize a user object to a SQL datum.
   *
   * The returned value should match the sqlType:
   * - For StructType: Array[Any] with field values
   * - For ArrayType: Array[T] of element type
   * - For BinaryType: Array[Byte]
   * - For primitives: the primitive value
   *
   * @param obj The user object to serialize (may be null)
   * @return SQL datum compatible with sqlType
   */
  def serialize(obj: UserType): Any

  /**
   * Deserialize a SQL datum back to the user type.
   *
   * @param datum The SQL datum (compatible with sqlType)
   * @return The user object
   */
  def deserialize(datum: Any): UserType

  /**
   * The Class of the user type.
   * Used for runtime type checking and ClassTag creation.
   */
  def userClass: Class[UserType]

  /**
   * ClassTag for the user type.
   * Derived from userClass.
   */
  final def classTag: ClassTag[UserType] = ClassTag(userClass)

  /**
   * Whether this UDT supports null values.
   * Default is true (reference types can be null).
   */
  def nullable: Boolean = true

  /**
   * The ProtoType representation of this UDT.
   * Wraps the sqlType with UDT metadata.
   */
  final def protoType: ProtoType = ProtoType.UDTType(getClass.getName, sqlType)

  /**
   * Optional: Friendly type name for display purposes.
   * Defaults to the simple class name of the user type.
   */
  def typeName: String = userClass.getSimpleName

object ProtoUDT:
  /**
   * Registry of UDT instances by class name.
   * Allows looking up UDTs at runtime for deserialization.
   */
  private val registry = scala.collection.mutable.Map[String, ProtoUDT[?]]()

  /**
   * Register a UDT instance for later lookup.
   * Called automatically when creating UDT encoders.
   */
  def register(udt: ProtoUDT[?]): Unit =
    registry.synchronized:
      registry(udt.getClass.getName) = udt
      // Also register by user class name for convenience
      registry(udt.userClass.getName) = udt

  /**
   * Look up a registered UDT by its class name.
   */
  def lookup(className: String): Option[ProtoUDT[?]] =
    registry.synchronized:
      registry.get(className)

  /**
   * Look up a registered UDT by user class.
   */
  def lookupByUserClass[T >: Null](userClass: Class[T]): Option[ProtoUDT[T]] =
    registry.synchronized:
      registry.get(userClass.getName).map(_.asInstanceOf[ProtoUDT[T]])

  /**
   * Clear the registry (mainly for testing).
   */
  def clearRegistry(): Unit =
    registry.synchronized:
      registry.clear()
