package protocatalyst.query

import protocatalyst.artifact.*
import protocatalyst.encoder.*
import protocatalyst.schema.*
import protocatalyst.sql.SqlMacro

/** Type-safe compiled query wrapper. */
final class CompiledQuery[A] private[protocatalyst] (
    val artifact: CompiledArtifact,
    val encoder: ProtoEncoder[A]
):
  def requiredSchemas: Vector[SchemaContract] = artifact.schemaContracts
  def outputSchema: ProtoSchema = artifact.outputSchema
  def contentHash: Long = artifact.contentHash

  def toBytes: Array[Byte] = ArtifactCodec.serialize(artifact)

object CompiledQuery:

  /** Compile a SQL string at compile time.
    *
    * The SQL is parsed at compile time, with parse errors reported as compilation errors.
    * Column validation happens at runtime against the schema from ProtoEncoder[A].
    *
    * Example:
    * {{{
    * case class User(name: String, age: Int, salary: Double) derives ProtoEncoder
    * val query = CompiledQuery.sql[User]("SELECT name, age FROM users WHERE age > 18")
    * }}}
    */
  inline def sql[A](inline query: String)(using enc: ProtoEncoder[A]): CompiledQuery[A] =
    SqlMacro.compileSQL[A](query) match
      case Right(artifact) => fromArtifact(artifact, enc)
      case Left(err) => throw new IllegalArgumentException(err)

  /** Compile SQL and return Either for error handling. */
  inline def sqlEither[A](inline query: String)(using enc: ProtoEncoder[A]): Either[String, CompiledQuery[A]] =
    SqlMacro.compileSQL[A](query).map(artifact => fromArtifact(artifact, enc))

  /** Load a pre-compiled artifact. */
  def fromBytes[A](bytes: Array[Byte])(using enc: ProtoEncoder[A]): Either[String, CompiledQuery[A]] =
    ArtifactCodec.deserialize(bytes).map(art => fromArtifact(art, enc))

  /** Create from artifact - used by DSL and SQL macro. */
  def fromArtifact[A](artifact: CompiledArtifact, enc: ProtoEncoder[A]): CompiledQuery[A] =
    new CompiledQuery(artifact, enc)
