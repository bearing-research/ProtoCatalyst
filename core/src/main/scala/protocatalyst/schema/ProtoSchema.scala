package protocatalyst.schema

import protocatalyst.types.*
import java.io.Serializable

case class ProtoSchema(
    fields: Vector[ProtoStructField],
    fingerprint: SchemaFingerprint
) extends Serializable:
  def fieldNames: Set[String] = fields.map(_.name).toSet
  def apply(name: String): Option[ProtoStructField] = fields.find(_.name == name)

object ProtoSchema:
  def apply(fields: Vector[ProtoStructField]): ProtoSchema =
    ProtoSchema(fields, SchemaFingerprint.compute(fields))

opaque type SchemaFingerprint = Long

object SchemaFingerprint:
  def compute(fields: Vector[ProtoStructField]): SchemaFingerprint =
    val canonical = fields.sortBy(_.name).map(canonicalize)
    scala.util.hashing.MurmurHash3.orderedHash(canonical)

  def fromLong(value: Long): SchemaFingerprint = value

  private def canonicalize(f: ProtoStructField): String =
    s"${f.name}:${typeString(f.dataType)}:${f.nullable}"

  private def typeString(t: ProtoType): String = t match
    case ProtoType.StructType(fs) => s"struct<${fs.map(canonicalize).mkString(",")}>"
    case ProtoType.ArrayType(e, n) => s"array<${typeString(e)},$n>"
    case ProtoType.MapType(k, v, n) => s"map<${typeString(k)},${typeString(v)},$n>"
    case ProtoType.DecimalType(p, s) => s"decimal($p,$s)"
    case other => other.toString.toLowerCase

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
    expectedNullable: Boolean,
    position: Int
) extends Serializable
