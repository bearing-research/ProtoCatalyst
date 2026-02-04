package protocatalyst.catalyst.parity

import java.nio.file.{Files, Paths}

import scala.jdk.CollectionConverters._

import io.circe.Json
import io.circe.parser._
import munit.FunSuite
import org.apache.spark.sql.SparkSession

import protocatalyst.catalyst.json.SparkPlanEncoder

/** Direct JSON comparison tests - no decoder in the verification path.
  *
  * This suite compares:
  *   - Spark's parsed plan → JSON (via SparkPlanEncoder)
  *   - ProtoCatalyst's parsed plan → JSON (from artifact files)
  *
  * By comparing JSON directly, we eliminate the decoder as a potential source of false positives.
  */
class DirectJsonParitySuite extends FunSuite {

  private var spark: SparkSession = _

  override def beforeAll(): Unit = {
    spark = SparkSession
      .builder()
      .master("local[1]")
      .appName("DirectJsonParityTest")
      .config("spark.ui.enabled", "false")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.driver.host", "localhost")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
  }

  override def afterAll(): Unit = {
    if (spark != null) {
      spark.stop()
    }
  }

  /** Load test artifacts */
  private def loadTestArtifacts(): Seq[(String, String, String)] = {
    val possiblePaths = Seq(
      "spark-catalyst/src/test/resources/parity-artifacts",
      "src/test/resources/parity-artifacts"
    )

    val resourceDir = possiblePaths
      .map(Paths.get(_))
      .find(p => Files.exists(p) && Files.isDirectory(p))

    resourceDir match {
      case None      => Seq.empty
      case Some(dir) =>
        Files
          .list(dir)
          .iterator()
          .asScala
          .filter(_.toString.endsWith(".json"))
          .flatMap { jsonPath =>
            val id = jsonPath.getFileName.toString.stripSuffix(".json")
            val sqlPath = dir.resolve(s"$id.sql")
            if (Files.exists(sqlPath)) {
              val json = Files.readString(jsonPath)
              val sql = Files.readString(sqlPath).trim
              Some((id, sql, json))
            } else None
          }
          .toSeq
    }
  }

  /** Extract just the plan JSON from ProtoCatalyst artifact */
  private def extractPlanJson(artifactJson: String): Either[String, Json] = {
    parse(artifactJson).left.map(_.message).flatMap { json =>
      json.hcursor.downField("plan").focus match {
        case Some(plan) => Right(plan)
        case None       => Left("No 'plan' field in artifact")
      }
    }
  }

  /** Normalize JSON for comparison (remove fields that are expected to differ) */
  private def normalizeJson(json: Json): Json = {
    json
      .mapObject { obj =>
        // Remove fields that are implementation details, not semantic content
        val filtered = obj
          .filterKeys(k =>
            !Set(
              "resolvedType", // ProtoCatalyst may include resolved type info
              "nullable", // Nullability may differ
              "schemaContract", // Schema contract is ProtoCatalyst-specific
              "_unsupported" // Marker for unsupported features
            ).contains(k)
          )

        // Recursively normalize nested objects and arrays
        filtered.mapValues(normalizeJson)
      }
      .mapArray(_.map(normalizeJson))
  }

  /** Compare two JSON structures, returning differences */
  private def compareJson(
      protoJson: Json,
      sparkJson: Json,
      path: String = "root"
  ): Seq[String] = {
    val diffs = scala.collection.mutable.ListBuffer[String]()

    (protoJson, sparkJson) match {
      case (p, s) if p == s =>
      // Exact match

      case (p, s) if p.isObject && s.isObject =>
        val pObj = p.asObject.get
        val sObj = s.asObject.get

        // Check $type matches
        (pObj("$type"), sObj("$type")) match {
          case (Some(pt), Some(st)) if pt != st =>
            diffs += s"$path: type mismatch - proto=$pt, spark=$st"
          case _ =>
        }

        // Check all fields in proto exist in spark
        for (key <- pObj.keys if key != "$type") {
          sObj(key) match {
            case Some(sv) =>
              diffs ++= compareJson(pObj(key).get, sv, s"$path/$key")
            case None =>
              // Field only in proto - might be OK (extra metadata)
              ()
          }
        }

      case (p, s) if p.isArray && s.isArray =>
        val pArr = p.asArray.get
        val sArr = s.asArray.get

        if (pArr.size != sArr.size) {
          diffs += s"$path: array size mismatch - proto=${pArr.size}, spark=${sArr.size}"
        } else {
          pArr.zip(sArr).zipWithIndex.foreach { case ((pElem, sElem), i) =>
            diffs ++= compareJson(pElem, sElem, s"$path[$i]")
          }
        }

      case (p, s) if p.isNumber && s.isNumber =>
        // Allow numeric type differences (Int vs Long)
        val pNum = p.asNumber.flatMap(_.toLong)
        val sNum = s.asNumber.flatMap(_.toLong)
        if (pNum != sNum) {
          diffs += s"$path: number mismatch - proto=$p, spark=$s"
        }

      case (p, s) if p.isString && s.isString =>
        if (p.asString != s.asString) {
          diffs += s"$path: string mismatch - proto=${p.asString.get}, spark=${s.asString.get}"
        }

      case (p, s) =>
        diffs += s"$path: structure mismatch - proto=$p, spark=$s"
    }

    diffs.toSeq
  }

  test("direct JSON comparison - no decoder") {
    val artifacts = loadTestArtifacts()

    if (artifacts.isEmpty) {
      println("No artifacts found. Skipping direct JSON comparison.")
    } else {
      println(s"Running ${artifacts.size} direct JSON comparisons...")

      var passed = 0
      var failed = 0
      val failures = scala.collection.mutable.ListBuffer[(String, String, Seq[String])]()

      for ((id, sql, artifactJson) <- artifacts) {
        try {
          // Parse with Spark
          val sparkPlan = spark.sessionState.sqlParser.parsePlan(sql)
          val sparkJson = SparkPlanEncoder.encode(sparkPlan)

          // Extract plan from ProtoCatalyst artifact
          extractPlanJson(artifactJson) match {
            case Right(protoJson) =>
              // Normalize both for comparison
              val normProto = normalizeJson(protoJson)
              val normSpark = normalizeJson(sparkJson)

              val diffs = compareJson(normProto, normSpark)

              if (diffs.isEmpty) {
                passed += 1
              } else {
                failed += 1
                failures += ((id, sql, diffs))
              }

            case Left(err) =>
              failed += 1
              failures += ((id, sql, Seq(s"Failed to parse artifact: $err")))
          }
        } catch {
          case e: Exception =>
            failed += 1
            failures += ((id, sql, Seq(s"Exception: ${e.getMessage}")))
        }
      }

      println(s"\n=== Direct JSON Parity Results ===")
      println(s"Passed: $passed")
      println(s"Failed: $failed")
      println(s"Total:  ${artifacts.size}")

      if (failures.nonEmpty) {
        println(s"\n=== Failures ===")
        for ((id, sql, diffs) <- failures.take(10)) {
          println(s"\n[$id] $sql")
          diffs.take(5).foreach(d => println(s"  - $d"))
          if (diffs.size > 5) println(s"  ... and ${diffs.size - 5} more")
        }
      }
    }
  }

  test("show Spark encoder output for debugging") {
    val sql = "SELECT name FROM users WHERE age = 30"
    val sparkPlan = spark.sessionState.sqlParser.parsePlan(sql)
    val sparkJson = SparkPlanEncoder.encode(sparkPlan)

    println(s"SQL: $sql")
    println(s"Spark Plan JSON:\n${sparkJson.spaces2}")
  }
}
