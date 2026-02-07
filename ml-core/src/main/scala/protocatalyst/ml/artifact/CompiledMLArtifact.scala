package protocatalyst.ml.artifact

import java.io.Serializable

import protocatalyst.ml.graph.ComputeGraph

/** A compiled ML artifact containing a pre-optimized compute graph.
  *
  * Analogous to CompiledArtifact for SQL. Produced at compile time by `mlquote { }`, serialized to
  * PCAT binary format for runtime execution.
  */
case class CompiledMLArtifact(
    formatVersion: MLArtifactVersion,
    protocatalystVersion: String,
    compiledAt: Long,
    contentHash: Long,
    graph: ComputeGraph,
    sourceInfo: Option[MLSourceInfo]
) extends Serializable

case class MLArtifactVersion(major: Int, minor: Int, patch: Int) extends Serializable:
  def isCompatibleWith(other: MLArtifactVersion): Boolean =
    this.major == other.major && this.minor >= other.minor

  override def toString: String = s"$major.$minor.$patch"

object MLArtifactVersion:
  val current: MLArtifactVersion = MLArtifactVersion(1, 0, 0)

case class MLSourceInfo(
    sourceFile: String,
    lineNumber: Int
) extends Serializable
