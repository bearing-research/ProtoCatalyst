package protocatalyst.query

import protocatalyst.artifact.*
import protocatalyst.encoder.*
import protocatalyst.schema.*
import scala.compiletime.error

/** Type-safe compiled query wrapper. */
final class CompiledQuery[A] private[query] (
    val artifact: CompiledArtifact,
    val encoder: ProtoEncoder[A]
):
  def requiredSchemas: Vector[SchemaContract] = artifact.schemaContracts
  def outputSchema: ProtoSchema = artifact.outputSchema
  def contentHash: Long = artifact.contentHash

  def toBytes: Array[Byte] = ArtifactCodec.serialize(artifact)

object CompiledQuery:

  /** Compile a SQL string at compile time. */
  inline def sql[A](inline query: String)(using enc: ProtoEncoder[A]): CompiledQuery[A] =
    error("CompiledQuery.sql macro is not yet implemented. See docs/DESIGN.md for planned implementation.")

  /** Load a pre-compiled artifact. */
  def fromBytes[A](bytes: Array[Byte])(using enc: ProtoEncoder[A]): Either[String, CompiledQuery[A]] =
    ArtifactCodec.deserialize(bytes).map(art => new CompiledQuery(art, enc))
