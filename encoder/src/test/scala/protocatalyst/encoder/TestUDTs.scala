package protocatalyst.encoder

import protocatalyst.types.{ProtoStructField, ProtoType}

/** Test UDTs for ProtoEncoder UDT support testing.
  */
object TestUDTs:

  // === Point UDT ===
  // A 2D point stored as a struct with x and y coordinates

  case class Point(x: Double, y: Double)

  object PointUDT extends ProtoUDT[Point]:
    def sqlType: ProtoType = ProtoType.StructType(
      Vector(
        ProtoStructField("x", ProtoType.DoubleType, false),
        ProtoStructField("y", ProtoType.DoubleType, false)
      )
    )

    def serialize(point: Point): Any =
      if point == null then null
      else Array[Any](point.x, point.y)

    def deserialize(datum: Any): Point = datum match
      case null            => null
      case arr: Array[Any] =>
        Point(arr(0).asInstanceOf[Double], arr(1).asInstanceOf[Double])
      case row: Product =>
        Point(
          row.productElement(0).asInstanceOf[Double],
          row.productElement(1).asInstanceOf[Double]
        )
      case _ =>
        throw new IllegalArgumentException(s"Cannot deserialize $datum to Point")

    def userClass: Class[Point] = classOf[Point]

  // === RGB Color UDT ===
  // A color stored as a 3-byte binary (compact representation)

  case class RGBColor(red: Int, green: Int, blue: Int):
    require(red >= 0 && red <= 255, s"Red must be 0-255, got $red")
    require(green >= 0 && green <= 255, s"Green must be 0-255, got $green")
    require(blue >= 0 && blue <= 255, s"Blue must be 0-255, got $blue")

  object RGBColorUDT extends ProtoUDT[RGBColor]:
    def sqlType: ProtoType = ProtoType.BinaryType

    def serialize(color: RGBColor): Any =
      if color == null then null
      else Array[Byte](color.red.toByte, color.green.toByte, color.blue.toByte)

    def deserialize(datum: Any): RGBColor = datum match
      case null                                    => null
      case bytes: Array[Byte] if bytes.length == 3 =>
        RGBColor(
          bytes(0) & 0xff, // Convert signed byte to unsigned int
          bytes(1) & 0xff,
          bytes(2) & 0xff
        )
      case _ =>
        throw new IllegalArgumentException(s"Cannot deserialize $datum to RGBColor")

    def userClass: Class[RGBColor] = classOf[RGBColor]

    override def typeName: String = "RGB"

  // === ComplexNumber UDT ===
  // A complex number stored as an array of two doubles

  case class ComplexNumber(real: Double, imaginary: Double):
    def +(other: ComplexNumber): ComplexNumber =
      ComplexNumber(real + other.real, imaginary + other.imaginary)
    def magnitude: Double = math.sqrt(real * real + imaginary * imaginary)

  object ComplexNumberUDT extends ProtoUDT[ComplexNumber]:
    def sqlType: ProtoType = ProtoType.ArrayType(ProtoType.DoubleType, containsNull = false)

    def serialize(c: ComplexNumber): Any =
      if c == null then null
      else Array[Double](c.real, c.imaginary)

    def deserialize(datum: Any): ComplexNumber = datum match
      case null                                  => null
      case arr: Array[Double] if arr.length == 2 =>
        ComplexNumber(arr(0), arr(1))
      case arr: Array[Any] if arr.length == 2 =>
        ComplexNumber(arr(0).asInstanceOf[Double], arr(1).asInstanceOf[Double])
      case seq: Seq[?] if seq.length == 2 =>
        ComplexNumber(seq(0).asInstanceOf[Double], seq(1).asInstanceOf[Double])
      case _ =>
        throw new IllegalArgumentException(s"Cannot deserialize $datum to ComplexNumber")

    def userClass: Class[ComplexNumber] = classOf[ComplexNumber]

  // === IPAddress UDT ===
  // An IPv4 address stored as an integer

  case class IPAddress(a: Int, b: Int, c: Int, d: Int):
    require(
      a >= 0 && a <= 255 && b >= 0 && b <= 255 &&
        c >= 0 && c <= 255 && d >= 0 && d <= 255,
      "IP octets must be 0-255"
    )

    def toInt: Int = (a << 24) | (b << 16) | (c << 8) | d
    override def toString: String = s"$a.$b.$c.$d"

  object IPAddress:
    def fromInt(ip: Int): IPAddress =
      IPAddress(
        (ip >> 24) & 0xff,
        (ip >> 16) & 0xff,
        (ip >> 8) & 0xff,
        ip & 0xff
      )

  object IPAddressUDT extends ProtoUDT[IPAddress]:
    def sqlType: ProtoType = ProtoType.IntType

    def serialize(ip: IPAddress): Any =
      if ip == null then null
      else ip.toInt

    def deserialize(datum: Any): IPAddress = datum match
      case null    => null
      case i: Int  => IPAddress.fromInt(i)
      case l: Long => IPAddress.fromInt(l.toInt)
      case _       =>
        throw new IllegalArgumentException(s"Cannot deserialize $datum to IPAddress")

    def userClass: Class[IPAddress] = classOf[IPAddress]

    override def typeName: String = "IPv4"

  // === NonNullable UDT ===
  // A UDT that doesn't support null values

  case class NonEmptyString(value: String):
    require(value != null && value.nonEmpty, "Value cannot be null or empty")

  object NonEmptyStringUDT extends ProtoUDT[NonEmptyString]:
    def sqlType: ProtoType = ProtoType.StringType

    def serialize(s: NonEmptyString): Any = s.value

    def deserialize(datum: Any): NonEmptyString = datum match
      case null => throw new IllegalArgumentException("NonEmptyString cannot be null")
      case str: String if str.nonEmpty => NonEmptyString(str)
      case _                           =>
        throw new IllegalArgumentException(s"Cannot deserialize $datum to NonEmptyString")

    def userClass: Class[NonEmptyString] = classOf[NonEmptyString]

    override def nullable: Boolean = false
