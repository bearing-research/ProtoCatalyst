package protocatalyst.artifact

import protocatalyst.codec.{ArtifactCodec => Codec}
import protocatalyst.plan._
import protocatalyst.schema._

case class CompiledArtifact(
    formatVersion: ArtifactVersion,
    protocatalystVersion: String,
    compiledAt: Long,
    contentHash: Long,
    schemaContracts: Vector[SchemaContract],
    plan: ProtoLogicalPlan,
    outputSchema: ProtoSchema,
    sourceInfo: Option[SourceInfo]
)

case class ArtifactVersion(major: Int, minor: Int, patch: Int):
  def isCompatibleWith(other: ArtifactVersion): Boolean =
    this.major == other.major && this.minor >= other.minor

  override def toString: String = s"$major.$minor.$patch"

object ArtifactVersion:
  val current: ArtifactVersion = ArtifactVersion(1, 0, 0)

case class SourceInfo(
    sourceFile: String,
    lineNumber: Int,
    originalSql: Option[String]
)

/** Convenience object delegating to the codec system. */
object ArtifactCodec:
  def serialize(artifact: CompiledArtifact): Array[Byte] =
    Codec.serializeWithHeader(artifact)

  def deserialize(bytes: Array[Byte]): Either[String, CompiledArtifact] =
    Codec.deserializeWithHeader(bytes)
