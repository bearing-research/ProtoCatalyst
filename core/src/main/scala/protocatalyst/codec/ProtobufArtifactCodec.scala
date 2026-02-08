package protocatalyst.codec

import io.protocatalyst.proto.{v1 => pb}

import protocatalyst.artifact.CompiledArtifact

/** Protobuf serialization codec using generated Java classes from the proto module. */
object ProtobufArtifactCodec extends ArtifactCodec:

  def format: String = "protobuf"

  def serialize(artifact: CompiledArtifact): Array[Byte] =
    ProtoConverter.toProto(artifact).toByteArray

  def deserialize(bytes: Array[Byte]): Either[String, CompiledArtifact] =
    try Right(ProtoConverter.fromProto(pb.CompiledArtifactMsg.parseFrom(bytes)))
    catch case e: Exception => Left(s"Protobuf deserialization failed: ${e.getMessage}")
