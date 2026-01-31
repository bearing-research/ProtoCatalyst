package protocatalyst.encoder

import protocatalyst.schema.*
import protocatalyst.types.*
import scala.reflect.ClassTag
import java.beans.{Introspector, PropertyDescriptor}
import java.lang.reflect.Method

/**
 * Runtime encoder for Java Beans.
 *
 * Unlike ProtoEncoder which uses compile-time Mirror derivation,
 * JavaBeanEncoder uses runtime reflection to introspect Java Bean classes.
 * This matches Spark's Encoders.bean(Class[T]) behavior.
 *
 * Java Bean conventions:
 * - Public no-arg constructor
 * - Properties with getter/setter pairs (getX/setX or isX/setX for booleans)
 */
object JavaBeanEncoder:

  /**
   * Create an encoder for a Java Bean class at runtime.
   *
   * @param beanClass The Java Bean class to encode
   * @return A ProtoEncoder for the bean type
   */
  def apply[T](beanClass: Class[T]): ProtoEncoder[T] =
    val properties = discoverProperties(beanClass)
    val fieldEncoders = properties.map { prop =>
      val propEncoder = encoderForType(prop.getPropertyType)
      FieldEncoder(prop.getName, propEncoder, propEncoder.nullable)
    }
    val structFields = fieldEncoders.map { fe =>
      ProtoStructField(fe.name, fe.encoder.catalystType, fe.nullable)
    }
    BeanEncoder(fieldEncoders, ProtoSchema(structFields), ClassTag(beanClass))

  /**
   * Discover bean properties using Java Introspector.
   * Filters out the "class" property which is inherited from Object.
   */
  private def discoverProperties(beanClass: Class[?]): Vector[PropertyDescriptor] =
    val beanInfo = Introspector.getBeanInfo(beanClass)
    beanInfo.getPropertyDescriptors
      .filter(_.getName != "class") // Exclude Object.getClass()
      .filter(p => p.getReadMethod != null && p.getWriteMethod != null) // Must have both getter and setter
      .toVector
      .sortBy(_.getName) // Consistent ordering

  /**
   * Get an encoder for a Java type at runtime.
   * This maps Java types to ProtoType similar to Spark's type mapping.
   */
  private def encoderForType(javaType: Class[?]): ProtoEncoder[?] =
    javaType match
      // Primitives
      case c if c == classOf[Boolean] || c == classOf[java.lang.Boolean] =>
        PrimitiveRuntimeEncoder(ProtoType.BooleanType, ClassTag(c), c == classOf[java.lang.Boolean])
      case c if c == classOf[Byte] || c == classOf[java.lang.Byte] =>
        PrimitiveRuntimeEncoder(ProtoType.ByteType, ClassTag(c), c == classOf[java.lang.Byte])
      case c if c == classOf[Short] || c == classOf[java.lang.Short] =>
        PrimitiveRuntimeEncoder(ProtoType.ShortType, ClassTag(c), c == classOf[java.lang.Short])
      case c if c == classOf[Int] || c == classOf[java.lang.Integer] =>
        PrimitiveRuntimeEncoder(ProtoType.IntType, ClassTag(c), c == classOf[java.lang.Integer])
      case c if c == classOf[Long] || c == classOf[java.lang.Long] =>
        PrimitiveRuntimeEncoder(ProtoType.LongType, ClassTag(c), c == classOf[java.lang.Long])
      case c if c == classOf[Float] || c == classOf[java.lang.Float] =>
        PrimitiveRuntimeEncoder(ProtoType.FloatType, ClassTag(c), c == classOf[java.lang.Float])
      case c if c == classOf[Double] || c == classOf[java.lang.Double] =>
        PrimitiveRuntimeEncoder(ProtoType.DoubleType, ClassTag(c), c == classOf[java.lang.Double])
      case c if c == classOf[Char] || c == classOf[java.lang.Character] =>
        PrimitiveRuntimeEncoder(ProtoType.StringType, ClassTag(c), c == classOf[java.lang.Character])

      // String and Binary
      case c if c == classOf[String] =>
        PrimitiveRuntimeEncoder(ProtoType.StringType, ClassTag(c), nullable = false)
      case c if c == classOf[Array[Byte]] =>
        PrimitiveRuntimeEncoder(ProtoType.BinaryType, ClassTag(c), nullable = false)

      // BigDecimal/BigInteger
      case c if c == classOf[java.math.BigDecimal] =>
        PrimitiveRuntimeEncoder(ProtoType.DecimalType(38, 18), ClassTag(c), nullable = true)
      case c if c == classOf[java.math.BigInteger] =>
        PrimitiveRuntimeEncoder(ProtoType.DecimalType(38, 0), ClassTag(c), nullable = true)
      case c if c == classOf[BigDecimal] =>
        PrimitiveRuntimeEncoder(ProtoType.DecimalType(38, 18), ClassTag(c), nullable = false)

      // Temporal types
      case c if c == classOf[java.time.LocalDate] =>
        PrimitiveRuntimeEncoder(ProtoType.DateType, ClassTag(c), nullable = false)
      case c if c == classOf[java.time.Instant] =>
        PrimitiveRuntimeEncoder(ProtoType.TimestampType, ClassTag(c), nullable = false)
      case c if c == classOf[java.time.LocalDateTime] =>
        PrimitiveRuntimeEncoder(ProtoType.TimestampNTZType, ClassTag(c), nullable = false)
      case c if c == classOf[java.time.Duration] =>
        PrimitiveRuntimeEncoder(ProtoType.DayTimeIntervalType, ClassTag(c), nullable = false)
      case c if c == classOf[java.time.Period] =>
        PrimitiveRuntimeEncoder(ProtoType.YearMonthIntervalType, ClassTag(c), nullable = false)
      case c if c == classOf[java.sql.Date] =>
        PrimitiveRuntimeEncoder(ProtoType.DateType, ClassTag(c), nullable = false)
      case c if c == classOf[java.sql.Timestamp] =>
        PrimitiveRuntimeEncoder(ProtoType.TimestampType, ClassTag(c), nullable = false)

      // Java enums
      case c if c.isEnum =>
        PrimitiveRuntimeEncoder(ProtoType.StringType, ClassTag(c), nullable = false)

      // Nested bean (recursive)
      case c if isJavaBean(c) =>
        apply(c)

      // Unsupported type
      case c =>
        throw new UnsupportedOperationException(
          s"Unsupported type in Java Bean: ${c.getName}. " +
            "Supported types: primitives, String, BigDecimal, temporal types, enums, and nested beans."
        )

  /**
   * Check if a class looks like a Java Bean.
   * A class is considered a bean if it has a public no-arg constructor
   * and at least one property with both getter and setter.
   */
  private def isJavaBean(clazz: Class[?]): Boolean =
    try
      // Must have public no-arg constructor
      clazz.getConstructor()
      // Must have at least one property (other than "class")
      val properties = discoverProperties(clazz)
      properties.nonEmpty
    catch
      case _: NoSuchMethodException => false

  /** Runtime encoder for primitive/leaf types */
  private class PrimitiveRuntimeEncoder[T](
      val catalystType: ProtoType,
      val clsTag: ClassTag[T],
      val nullable: Boolean
  ) extends ProtoEncoder[T]:
    val schema: ProtoSchema = ProtoSchema(Vector.empty)

  /** Runtime encoder for bean types */
  private class BeanEncoder[T](
      val fieldEncoders: Vector[FieldEncoder[?]],
      val theSchema: ProtoSchema,
      val clsTag: ClassTag[T]
  ) extends ProtoEncoder[T]:
    def schema: ProtoSchema = theSchema
    val catalystType: ProtoType = ProtoType.StructType(theSchema.fields)
    val nullable: Boolean = false
    override def fields: Vector[FieldEncoder[?]] = fieldEncoders
