package protocatalyst.artifact

import protocatalyst.plan.*
import protocatalyst.schema.*
import java.io.Serializable

case class CompiledArtifact(
    formatVersion: ArtifactVersion,
    protocatalystVersion: String,
    compiledAt: Long,
    contentHash: Long,
    schemaContracts: Vector[SchemaContract],
    plan: ProtoLogicalPlan,
    outputSchema: ProtoSchema,
    sourceInfo: Option[SourceInfo]
) extends Serializable

case class ArtifactVersion(major: Int, minor: Int, patch: Int) extends Serializable:
  def isCompatibleWith(other: ArtifactVersion): Boolean =
    this.major == other.major && this.minor >= other.minor

  override def toString: String = s"$major.$minor.$patch"

object ArtifactVersion:
  val current: ArtifactVersion = ArtifactVersion(1, 0, 0)

case class SourceInfo(
    sourceFile: String,
    lineNumber: Int,
    originalSql: Option[String]
) extends Serializable

object ArtifactCodec:
  private val Magic: Array[Byte] = "PCAT".getBytes("UTF-8")

  def serialize(artifact: CompiledArtifact): Array[Byte] =
    import java.io.*
    val baos = new ByteArrayOutputStream()
    baos.write(Magic)
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(artifact)
    oos.close()
    baos.toByteArray

  def deserialize(bytes: Array[Byte]): Either[String, CompiledArtifact] =
    if bytes.length < Magic.length then Left("Invalid artifact: too short")
    else if !bytes.take(Magic.length).sameElements(Magic) then Left("Invalid magic header")
    else
      try
        import java.io.*
        val bais = new ByteArrayInputStream(bytes.drop(Magic.length))
        val ois = new ObjectInputStream(bais)
        Right(ois.readObject().asInstanceOf[CompiledArtifact])
      catch case e: Exception => Left(s"Deserialization failed: ${e.getMessage}")
