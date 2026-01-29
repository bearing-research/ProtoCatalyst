package protocatalyst.codec

import protocatalyst.artifact.*
import protocatalyst.expr.*
import protocatalyst.plan.*
import protocatalyst.schema.*
import protocatalyst.types.*
import upickle.default.*

/**
 * JSON serialization codec using uPickle.
 *
 * All ReadWriter instances are derived here to keep serialization
 * concerns separate from the domain types.
 */
object JsonArtifactCodec extends ArtifactCodec:

  def format: String = "json"

  def serialize(artifact: CompiledArtifact): Array[Byte] =
    write(artifact).getBytes("UTF-8")

  def deserialize(bytes: Array[Byte]): Either[String, CompiledArtifact] =
    try
      Right(read[CompiledArtifact](new String(bytes, "UTF-8")))
    catch
      case e: Exception => Left(s"JSON deserialization failed: ${e.getMessage}")

  // === Type ReadWriters ===

  given ReadWriter[ProtoType] = ReadWriter.merge(
    macroRW[ProtoType.BooleanType.type],
    macroRW[ProtoType.ByteType.type],
    macroRW[ProtoType.ShortType.type],
    macroRW[ProtoType.IntType.type],
    macroRW[ProtoType.LongType.type],
    macroRW[ProtoType.FloatType.type],
    macroRW[ProtoType.DoubleType.type],
    macroRW[ProtoType.StringType.type],
    macroRW[ProtoType.BinaryType.type],
    macroRW[ProtoType.DateType.type],
    macroRW[ProtoType.TimestampType.type],
    macroRW[ProtoType.TimestampNTZType.type],
    macroRW[ProtoType.DecimalType],
    macroRW[ProtoType.ArrayType],
    macroRW[ProtoType.MapType],
    macroRW[ProtoType.StructType],
    macroRW[ProtoType.UnresolvedType]
  )

  given ReadWriter[ProtoStructField] = macroRW

  // === Literal Value ReadWriters ===

  given ReadWriter[LiteralValue] = ReadWriter.merge(
    macroRW[LiteralValue.BooleanValue],
    macroRW[LiteralValue.ByteValue],
    macroRW[LiteralValue.ShortValue],
    macroRW[LiteralValue.IntValue],
    macroRW[LiteralValue.LongValue],
    macroRW[LiteralValue.FloatValue],
    macroRW[LiteralValue.DoubleValue],
    macroRW[LiteralValue.StringValue],
    macroRW[LiteralValue.BinaryValue],
    macroRW[LiteralValue.DecimalValue],
    macroRW[LiteralValue.DateValue],
    macroRW[LiteralValue.TimestampValue],
    macroRW[LiteralValue.NullValue]
  )

  // === Expression ReadWriters ===

  given ReadWriter[ProtoExpr] = ReadWriter.merge(
    macroRW[ProtoExpr.Literal],
    macroRW[ProtoExpr.ColumnRef],
    macroRW[ProtoExpr.BoundRef],
    macroRW[ProtoExpr.Eq],
    macroRW[ProtoExpr.NotEq],
    macroRW[ProtoExpr.Lt],
    macroRW[ProtoExpr.LtEq],
    macroRW[ProtoExpr.Gt],
    macroRW[ProtoExpr.GtEq],
    macroRW[ProtoExpr.And],
    macroRW[ProtoExpr.Or],
    macroRW[ProtoExpr.Not],
    macroRW[ProtoExpr.IsNull],
    macroRW[ProtoExpr.IsNotNull],
    macroRW[ProtoExpr.Coalesce],
    macroRW[ProtoExpr.Add],
    macroRW[ProtoExpr.Subtract],
    macroRW[ProtoExpr.Multiply],
    macroRW[ProtoExpr.Divide],
    macroRW[ProtoExpr.Concat],
    macroRW[ProtoExpr.Substring],
    macroRW[ProtoExpr.Upper],
    macroRW[ProtoExpr.Lower],
    macroRW[ProtoExpr.Count],
    macroRW[ProtoExpr.Sum],
    macroRW[ProtoExpr.Avg],
    macroRW[ProtoExpr.Min],
    macroRW[ProtoExpr.Max],
    macroRW[ProtoExpr.CaseWhen],
    macroRW[ProtoExpr.If],
    macroRW[ProtoExpr.In],
    macroRW[ProtoExpr.Cast],
    macroRW[ProtoExpr.Alias],
    macroRW[ProtoExpr.OpaqueCall]
  )

  // === Plan ReadWriters ===

  // Simple enums need string-based serialization
  given ReadWriter[JoinType] = readwriter[String].bimap(
    _.toString,
    s => JoinType.valueOf(s)
  )

  given ReadWriter[SortDirection] = readwriter[String].bimap(
    _.toString,
    s => SortDirection.valueOf(s)
  )

  given ReadWriter[NullOrdering] = readwriter[String].bimap(
    _.toString,
    s => NullOrdering.valueOf(s)
  )

  given ReadWriter[SortOrder] = macroRW

  given ReadWriter[ProtoLogicalPlan] = ReadWriter.merge(
    macroRW[ProtoLogicalPlan.RelationRef],
    macroRW[ProtoLogicalPlan.Values],
    macroRW[ProtoLogicalPlan.Project],
    macroRW[ProtoLogicalPlan.Filter],
    macroRW[ProtoLogicalPlan.Aggregate],
    macroRW[ProtoLogicalPlan.Sort],
    macroRW[ProtoLogicalPlan.Limit],
    macroRW[ProtoLogicalPlan.Distinct],
    macroRW[ProtoLogicalPlan.SubqueryAlias],
    macroRW[ProtoLogicalPlan.Join],
    macroRW[ProtoLogicalPlan.Union],
    macroRW[ProtoLogicalPlan.Intersect],
    macroRW[ProtoLogicalPlan.Except],
    macroRW[ProtoLogicalPlan.Window]
  )

  // === Schema ReadWriters ===

  // SchemaFingerprint is opaque type = Long
  given ReadWriter[SchemaFingerprint] =
    readwriter[Long].bimap(
      fp => fp.toLong,
      l => SchemaFingerprint.fromLong(l)
    )

  given ReadWriter[FieldContract] = macroRW
  given ReadWriter[SchemaContract] = macroRW
  given ReadWriter[ProtoSchema] = macroRW

  // === Artifact ReadWriters ===

  given ReadWriter[ArtifactVersion] = macroRW
  given ReadWriter[SourceInfo] = macroRW
  given ReadWriter[CompiledArtifact] = macroRW
