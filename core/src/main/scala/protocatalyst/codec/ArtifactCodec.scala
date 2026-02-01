package protocatalyst.codec

import protocatalyst.artifact.CompiledArtifact

/** Abstraction for artifact serialization. Allows swapping between JSON, protobuf, or other
  * formats.
  */
trait ArtifactCodec:
  /** Unique identifier for this codec (e.g., "json", "protobuf") */
  def format: String

  /** Serialize artifact to bytes */
  def serialize(artifact: CompiledArtifact): Array[Byte]

  /** Deserialize artifact from bytes */
  def deserialize(bytes: Array[Byte]): Either[String, CompiledArtifact]

object ArtifactCodec:
  /** Magic bytes to identify format: "PCAT" + format byte */
  private val MagicPrefix: Array[Byte] = "PCAT".getBytes("UTF-8")

  /** Format identifiers */
  private val FormatJson: Byte = 0x01
  private val FormatProtobuf: Byte = 0x02 // Reserved for future

  /** Default codec - JSON via uPickle */
  val default: ArtifactCodec = JsonArtifactCodec

  /** Serialize with format header */
  def serializeWithHeader(artifact: CompiledArtifact, codec: ArtifactCodec = default): Array[Byte] =
    val formatByte = codec.format match
      case "json"     => FormatJson
      case "protobuf" => FormatProtobuf
      case other      => throw new IllegalArgumentException(s"Unknown codec format: $other")

    val payload = codec.serialize(artifact)
    MagicPrefix ++ Array(formatByte) ++ payload

  /** Deserialize, auto-detecting format from header */
  def deserializeWithHeader(bytes: Array[Byte]): Either[String, CompiledArtifact] =
    if bytes.length < MagicPrefix.length + 1 then Left("Invalid artifact: too short")
    else if !bytes.take(MagicPrefix.length).sameElements(MagicPrefix) then
      Left("Invalid magic header")
    else
      val formatByte = bytes(MagicPrefix.length)
      val payload = bytes.drop(MagicPrefix.length + 1)

      formatByte match
        case FormatJson     => JsonArtifactCodec.deserialize(payload)
        case FormatProtobuf => Left("Protobuf codec not yet implemented")
        case other          => Left(s"Unknown format byte: $other")
