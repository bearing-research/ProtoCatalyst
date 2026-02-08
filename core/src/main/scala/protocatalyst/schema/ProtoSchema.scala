package protocatalyst.schema

import java.io.Serializable

import protocatalyst.types._

case class ProtoSchema(
    fields: Vector[ProtoStructField],
    fingerprint: SchemaFingerprint
) extends Serializable:
  @transient lazy val byName: Map[String, ProtoStructField] = fields.map(f => f.name -> f).toMap
  @transient lazy val fieldNames: Set[String] = byName.keySet
  def apply(name: String): Option[ProtoStructField] = byName.get(name)

object ProtoSchema:
  def apply(fields: Vector[ProtoStructField]): ProtoSchema =
    ProtoSchema(fields, SchemaFingerprint.compute(fields))

opaque type SchemaFingerprint = Long

object SchemaFingerprint:

  /** Compute a full fingerprint over field name, type, nullability, and metadata.
    *
    * Fields are sorted by name before hashing so field order does not affect the result. Metadata
    * is included (sorted by key) because it may carry behavioral semantics such as encoding hints
    * or provenance tags. Two schemas that differ only in metadata will produce different
    * fingerprints.
    */
  def compute(fields: Vector[ProtoStructField]): SchemaFingerprint =
    val canonical = fields.sortBy(_.name).map(canonicalize)
    val hi = scala.util.hashing.MurmurHash3.orderedHash(canonical, 0x9e3779b1)
    val lo = scala.util.hashing.MurmurHash3.orderedHash(canonical, 0x517cc1b7)
    (hi.toLong << 32) | (lo.toLong & 0xffffffffL)

  def fromLong(value: Long): SchemaFingerprint = value

  private def canonicalize(f: ProtoStructField): String =
    val meta =
      if f.metadata.isEmpty then ""
      else
        val sorted = f.metadata.toVector.sortBy(_._1).map((k, v) => s"$k=$v").mkString(",")
        s",metadata<$sorted>"
    s"${f.name}:${typeString(f.dataType)}:${f.nullable}$meta"

  private def typeString(t: ProtoType): String = t match
    case ProtoType.BooleanType           => "boolean"
    case ProtoType.ByteType              => "byte"
    case ProtoType.ShortType             => "short"
    case ProtoType.IntegerType           => "integer"
    case ProtoType.LongType              => "long"
    case ProtoType.FloatType             => "float"
    case ProtoType.DoubleType            => "double"
    case ProtoType.StringType            => "string"
    case ProtoType.BinaryType            => "binary"
    case ProtoType.DateType              => "date"
    case ProtoType.TimestampType         => "timestamp"
    case ProtoType.TimestampNTZType      => "timestamp_ntz"
    case ProtoType.DayTimeIntervalType   => "daytimeinterval"
    case ProtoType.YearMonthIntervalType => "yearmonthinterval"
    case ProtoType.CalendarIntervalType  => "calendarinterval"
    case ProtoType.VariantType           => "variant"
    case ProtoType.NullType              => "null"
    case ProtoType.TimeType(p)           => s"time($p)"
    case ProtoType.CharType(n)           => s"char($n)"
    case ProtoType.VarcharType(n)        => s"varchar($n)"
    case ProtoType.DecimalType(p, s)     => s"decimal($p,$s)"
    case ProtoType.ArrayType(e, n)       => s"array<elem=${typeString(e)},containsNull=$n>"
    case ProtoType.MapType(k, v, n)      =>
      s"map<key=${typeString(k)},value=${typeString(v)},valueContainsNull=$n>"
    case ProtoType.StructType(fs)       => s"struct<${fs.map(canonicalize).mkString(",")}>"
    case ProtoType.UDTType(cls, sql)    => s"udt<class=$cls,sql=${typeString(sql)}>"
    case ProtoType.UnresolvedType(hint) => s"unresolved<$hint>"
    case ProtoType.SumType(disc, vs)    =>
      val variants =
        vs.map(v => s"${v.name}:${v.ordinal}:${v.dataType.fold("none")(typeString)}").mkString(",")
      s"sum<discriminator=$disc,variants<$variants>>"

  extension (fp: SchemaFingerprint)
    def toLong: Long = fp
    def toHex: String = f"$fp%016x"

case class SchemaContract(
    relationName: String,
    requiredFields: Vector[FieldContract],
    fingerprint: SchemaFingerprint
) extends Serializable

case class FieldContract(
    name: String,
    expectedType: ProtoType,
    expectedNullable: Boolean
) extends Serializable
