package protocatalyst.catalyst.json

import io.circe.Json
import io.circe.parser.parse
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import protocatalyst.catalyst.protobuf.ProtobufPlanDecoder

/** Entry point for parsing ProtoCatalyst CompiledArtifact JSON.
  *
  * Handles the PCAT header format and extracts the plan for conversion. Supports both JSON (format
  * byte 0x01) and Protobuf (format byte 0x02) payloads.
  */
object ArtifactParser {

  /** Magic header bytes for ProtoCatalyst artifacts: "PCAT" */
  private val MagicHeader = Array('P'.toByte, 'C'.toByte, 'A'.toByte, 'T'.toByte)
  private val JsonFormat: Byte = 0x01
  private val ProtobufFormat: Byte = 0x02

  /** Parse a CompiledArtifact from bytes and extract the LogicalPlan.
    *
    * @param bytes
    *   The serialized artifact bytes (with PCAT header)
    * @return
    *   Either an error message or the parsed Spark LogicalPlan
    */
  def parsePlan(bytes: Array[Byte]): Either[String, LogicalPlan] = {
    if (bytes.length < 5) {
      return Left("Artifact too short: expected at least 5 bytes for header")
    }
    if (!bytes.slice(0, 4).sameElements(MagicHeader)) {
      return Left("Invalid magic header: expected PCAT")
    }

    val payload = bytes.slice(5, bytes.length)

    bytes(4) match {
      case JsonFormat =>
        for {
          json <- parse(new String(payload, "UTF-8")).left.map(e =>
            s"JSON parse error: ${e.getMessage}"
          )
          plan <- parsePlanFromJson(json)
        } yield plan

      case ProtobufFormat =>
        ProtobufPlanDecoder.parsePlanFromBytes(payload)

      case other =>
        Left(
          s"Unsupported format: expected JSON (0x01) or Protobuf (0x02), got 0x${String.format("%02x", Byte.box(other))}"
        )
    }
  }

  /** Parse a plan directly from JSON string (without header). */
  def parsePlanFromJsonString(jsonStr: String): Either[String, LogicalPlan] = {
    for {
      json <- parse(jsonStr).left.map(e => s"JSON parse error: ${e.getMessage}")
      plan <- parsePlanFromJson(json)
    } yield plan
  }

  /** Parse a plan from parsed JSON. */
  def parsePlanFromJson(json: Json): Either[String, LogicalPlan] = {
    val c = json.hcursor
    for {
      planJson <- c.get[Json]("plan").left.map(e => s"Missing 'plan' field: ${e.getMessage}")
      plan <- PlanDecoder.decode(planJson).left.map(e => s"Plan decode error: ${e.getMessage}")
    } yield plan
  }

  /** Extract JSON from artifact bytes with header. */
  private def extractJson(bytes: Array[Byte]): Either[String, Json] = {
    if (bytes.length < 5) {
      return Left("Artifact too short: expected at least 5 bytes for header")
    }

    // Check magic header
    if (!bytes.slice(0, 4).sameElements(MagicHeader)) {
      return Left("Invalid magic header: expected PCAT")
    }

    // Check format byte
    if (bytes(4) != JsonFormat) {
      return Left(s"Unsupported format: expected JSON (0x01), got 0x${bytes(4).toHexString}")
    }

    // Parse JSON payload
    val jsonBytes = bytes.slice(5, bytes.length)
    val jsonStr = new String(jsonBytes, "UTF-8")
    parse(jsonStr).left.map(e => s"JSON parse error: ${e.getMessage}")
  }

  /** Parse just the plan JSON (for cases where you have the plan JSON directly). */
  def parseRawPlan(planJson: Json): Either[String, LogicalPlan] = {
    PlanDecoder.decode(planJson).left.map(e => s"Plan decode error: ${e.getMessage}")
  }

  /** Parse an expression from JSON. */
  def parseExpression(
      exprJson: Json
  ): Either[String, org.apache.spark.sql.catalyst.expressions.Expression] = {
    ExpressionDecoder.decode(exprJson).left.map(e => s"Expression decode error: ${e.getMessage}")
  }

  /** Parse a type from JSON. */
  def parseType(typeJson: Json): Either[String, org.apache.spark.sql.types.DataType] = {
    TypeDecoder.decode(typeJson).left.map(e => s"Type decode error: ${e.getMessage}")
  }
}
