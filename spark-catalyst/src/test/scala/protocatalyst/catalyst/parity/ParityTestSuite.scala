package protocatalyst.catalyst.parity

import io.circe.parser.parse
import munit.FunSuite
import org.apache.spark.sql.SparkSession

import protocatalyst.catalyst.json.ArtifactParser

/** Parity tests comparing ProtoCatalyst's parsed plans against Spark's SQL parser.
  *
  * These tests construct JSON in ProtoCatalyst's format, parse it into Spark LogicalPlan, and
  * compare against Spark's native SQL parser output.
  */
class ParityTestSuite extends FunSuite {

  // SparkSession for parsing SQL
  private var spark: SparkSession = _

  override def beforeAll(): Unit = {
    spark = SparkSession
      .builder()
      .master("local[1]")
      .appName("ParityTestSuite")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .getOrCreate()
  }

  override def afterAll(): Unit = {
    if (spark != null) {
      spark.stop()
    }
  }

  // === Helper Methods ===

  /** Parse SQL with Spark's parser and return the plan tree string. */
  private def sparkPlanTree(sql: String): String = {
    spark.sessionState.sqlParser.parsePlan(sql).treeString
  }

  /** Parse ProtoCatalyst JSON and return the plan tree string. */
  private def protoPlanTree(json: String): String = {
    ArtifactParser.parsePlanFromJsonString(json) match {
      case scala.Right(plan) => plan.treeString
      case scala.Left(err)   => fail(s"Failed to parse ProtoCatalyst JSON: $err")
    }
  }

  /** Test parity between Spark SQL parsing and ProtoCatalyst JSON parsing. */
  private def testParity(sql: String, protoJson: String): ParityTester.ParityResult = {
    implicit val s: SparkSession = spark
    ParityTester.testParity(sql, protoJson)
  }

  /** Assert parity, printing details on failure. */
  private def assertParity(sql: String, protoJson: String): Unit = {
    val result = testParity(sql, protoJson)
    if (!result.matches) {
      println(s"\n=== PARITY FAILURE ===")
      println(result.planTrees)
      println(s"Differences:\n${result.differences.map("  - " + _).mkString("\n")}")
      fail(result.summary)
    }
  }

  // === Basic SELECT Tests ===

  test("simple SELECT FROM table") {
    val sql = "SELECT name FROM users"

    // ProtoCatalyst JSON format (upickle with $type discriminator)
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.ColumnRef",
            "name": "name",
            "qualifier": null
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT multiple columns") {
    val sql = "SELECT name, age FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null},
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with table qualifier") {
    val sql = "SELECT u.name FROM users AS u"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": "u"}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
          "alias": "u",
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Filter Tests ===

  test("SELECT with WHERE comparison") {
    val sql = "SELECT name FROM users WHERE age > 18"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Gt",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
            "right": {
              "$type": "protocatalyst.expr.ProtoExpr.Literal",
              "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 18}
            }
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with WHERE AND") {
    val sql = "SELECT name FROM users WHERE age > 18 AND active = true"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.And",
            "children": [
              {
                "$type": "protocatalyst.expr.ProtoExpr.Gt",
                "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
                "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 18}}
              },
              {
                "$type": "protocatalyst.expr.ProtoExpr.Eq",
                "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "active", "qualifier": null},
                "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.BooleanValue", "value": true}}
              }
            ]
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Aggregate Tests ===

  test("SELECT with GROUP BY") {
    val sql = "SELECT department, COUNT(*) FROM users GROUP BY department"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Aggregate",
        "groupingExprs": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "department", "qualifier": null}
        ],
        "aggregateExprs": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "department", "qualifier": null},
          {
            "$type": "protocatalyst.expr.ProtoExpr.Count",
            "child": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 1}},
            "distinct": false
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Sort Tests ===

  test("SELECT with ORDER BY") {
    val sql = "SELECT name FROM users ORDER BY age DESC"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Sort",
        "order": [
          {
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
            "direction": "Descending",
            "nullOrdering": "NullsLast"
          }
        ],
        "global": true,
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Limit Tests ===

  test("SELECT with LIMIT") {
    val sql = "SELECT name FROM users LIMIT 10"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Limit",
        "limit": 10,
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Join Tests ===

  test("INNER JOIN") {
    val sql = "SELECT u.name, o.total FROM users u INNER JOIN orders o ON u.id = o.user_id"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": "u"},
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "total", "qualifier": "o"}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
          "left": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "u",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "users",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "right": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "o",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "orders",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "joinType": "Inner",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Eq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": "u"},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "user_id", "qualifier": "o"}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("LEFT JOIN") {
    val sql = "SELECT u.name, o.total FROM users u LEFT JOIN orders o ON u.id = o.user_id"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": "u"},
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "total", "qualifier": "o"}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
          "left": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "u",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "users",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "right": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "o",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "orders",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "joinType": "LeftOuter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Eq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": "u"},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "user_id", "qualifier": "o"}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Union Tests ===

  test("UNION ALL") {
    val sql = "SELECT name FROM users UNION ALL SELECT name FROM admins"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Union",
        "children": [
          {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
            "projectList": [
              {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
            ],
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "users",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
            "projectList": [
              {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
            ],
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "admins",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          }
        ],
        "byName": false,
        "allowMissingColumns": false
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Distinct Tests ===

  test("SELECT DISTINCT") {
    val sql = "SELECT DISTINCT department FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Distinct",
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "department", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Expression Tests ===

  test("CASE WHEN expression") {
    val sql = "SELECT name, CASE WHEN age > 30 THEN 'senior' ELSE 'junior' END FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null},
          {
            "$type": "protocatalyst.expr.ProtoExpr.CaseWhen",
            "branches": [
              [
                {
                  "$type": "protocatalyst.expr.ProtoExpr.Gt",
                  "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
                  "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 30}}
                },
                {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "senior"}}
              ]
            ],
            "elseValue": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "junior"}}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("arithmetic expressions") {
    val sql = "SELECT name, salary * 1.1 FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null},
          {
            "$type": "protocatalyst.expr.ProtoExpr.Multiply",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "salary", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.DoubleValue", "value": 1.1}}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("CAST expression") {
    val sql = "SELECT CAST(age AS STRING) FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Cast",
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
            "targetType": {"$type": "protocatalyst.types.ProtoType.StringType"}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Decoder Unit Tests ===

  test("TypeDecoder handles all primitive types") {
    val types = List(
      ("""{"$type": "protocatalyst.types.ProtoType.BooleanType"}""", "BooleanType"),
      ("""{"$type": "protocatalyst.types.ProtoType.IntegerType"}""", "IntegerType"),
      ("""{"$type": "protocatalyst.types.ProtoType.LongType"}""", "LongType"),
      ("""{"$type": "protocatalyst.types.ProtoType.DoubleType"}""", "DoubleType"),
      ("""{"$type": "protocatalyst.types.ProtoType.StringType"}""", "StringType"),
      ("""{"$type": "protocatalyst.types.ProtoType.DateType"}""", "DateType"),
      ("""{"$type": "protocatalyst.types.ProtoType.TimestampType"}""", "TimestampType")
    )

    types.foreach { case (json, expectedName) =>
      val result = ArtifactParser.parseType(parse(json).toOption.get)
      assert(result.isRight, s"Failed to parse $json: ${result.left.getOrElse("")}")
      assert(
        result.toOption.get.typeName.contains(expectedName) || result.toOption.get.toString
          .contains(expectedName),
        s"Expected $expectedName but got ${result.toOption.get}"
      )
    }
  }

  test("ExpressionDecoder handles literals") {
    val literals = List(
      (
        """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 42}}""",
        "42"
      ),
      (
        """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "hello"}}""",
        "hello"
      ),
      (
        """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.BooleanValue", "value": true}}""",
        "true"
      )
    )

    literals.foreach { case (json, expectedContains) =>
      val result = ArtifactParser.parseExpression(parse(json).toOption.get)
      assert(result.isRight, s"Failed to parse $json: ${result.left.getOrElse("")}")
      assert(
        result.toOption.get.toString.toLowerCase.contains(expectedContains.toLowerCase),
        s"Expected to contain $expectedContains but got ${result.toOption.get}"
      )
    }
  }

  test("PlanDecoder handles basic plan types") {
    val relationJson = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
      "name": "test_table",
      "alias": null,
      "schemaContract": {"fields": [], "fingerprint": 0}
    }"""

    val result = ArtifactParser.parseRawPlan(parse(relationJson).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.contains("test_table"))
  }

  // === Additional Expression Tests for Coverage ===

  test("SELECT with WHERE OR") {
    val sql = "SELECT name FROM users WHERE age > 18 OR active = true"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Or",
            "children": [
              {
                "$type": "protocatalyst.expr.ProtoExpr.Gt",
                "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
                "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 18}}
              },
              {
                "$type": "protocatalyst.expr.ProtoExpr.Eq",
                "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "active", "qualifier": null},
                "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.BooleanValue", "value": true}}
              }
            ]
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with NOT expression") {
    val sql = "SELECT name FROM users WHERE NOT active"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Not",
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "active", "qualifier": null}
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with IS NULL") {
    val sql = "SELECT name FROM users WHERE email IS NULL"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.IsNull",
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "email", "qualifier": null}
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with IS NOT NULL") {
    val sql = "SELECT name FROM users WHERE email IS NOT NULL"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.IsNotNull",
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "email", "qualifier": null}
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with less than comparison") {
    val sql = "SELECT name FROM users WHERE age < 30"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Lt",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 30}}
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with less than or equal comparison") {
    val sql = "SELECT name FROM users WHERE age <= 30"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.LtEq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 30}}
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with greater than or equal comparison") {
    val sql = "SELECT name FROM users WHERE age >= 18"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.GtEq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 18}}
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with not equal comparison") {
    val sql = "SELECT name FROM users WHERE status <> 'inactive'"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.NotEq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "status", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "inactive"}}
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with IN clause") {
    val sql = "SELECT name FROM users WHERE status IN ('active', 'pending')"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.In",
            "value": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "status", "qualifier": null},
            "list": [
              {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "active"}},
              {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "pending"}}
            ]
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with COALESCE") {
    val sql = "SELECT COALESCE(email, phone) FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Coalesce",
            "children": [
              {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "email", "qualifier": null},
              {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "phone", "qualifier": null}
            ]
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Arithmetic Expression Tests ===

  test("SELECT with addition") {
    val sql = "SELECT salary + bonus FROM employees"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Add",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "salary", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "bonus", "qualifier": null}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "employees",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with subtraction") {
    val sql = "SELECT salary - deductions FROM employees"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Subtract",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "salary", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "deductions", "qualifier": null}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "employees",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with division") {
    val sql = "SELECT total / quantity FROM orders"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Divide",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "total", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "quantity", "qualifier": null}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "orders",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === String Function Tests ===

  test("SELECT with UPPER") {
    val sql = "SELECT UPPER(name) FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Upper",
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with LOWER") {
    val sql = "SELECT LOWER(name) FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Lower",
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with CONCAT") {
    val sql = "SELECT CONCAT(first_name, last_name) FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Concat",
            "children": [
              {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "first_name", "qualifier": null},
              {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "last_name", "qualifier": null}
            ]
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("SELECT with SUBSTRING") {
    val sql = "SELECT SUBSTRING(name, 1, 3) FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Substring",
            "str": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null},
            "pos": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 1}},
            "len": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 3}}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Aggregate Function Tests (Decoder-only, since Spark wraps in Project) ===

  test("ExpressionDecoder handles SUM") {
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Sum", "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "amount", "qualifier": null}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(
      result.toOption.get.toString.toLowerCase
        .contains("sum") || result.toOption.get.toString.toLowerCase.contains("aggregate")
    )
  }

  test("ExpressionDecoder handles AVG") {
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Avg", "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "amount", "qualifier": null}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(
      result.toOption.get.toString.toLowerCase
        .contains("avg") || result.toOption.get.toString.toLowerCase
        .contains("average") || result.toOption.get.toString.toLowerCase.contains("aggregate")
    )
  }

  test("ExpressionDecoder handles MIN") {
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Min", "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "price", "qualifier": null}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(
      result.toOption.get.toString.toLowerCase
        .contains("min") || result.toOption.get.toString.toLowerCase.contains("aggregate")
    )
  }

  test("ExpressionDecoder handles MAX") {
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Max", "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "price", "qualifier": null}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(
      result.toOption.get.toString.toLowerCase
        .contains("max") || result.toOption.get.toString.toLowerCase.contains("aggregate")
    )
  }

  // === IF Expression Test ===

  test("SELECT with IF expression") {
    val sql = "SELECT IF(age >= 18, 'adult', 'minor') FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.If",
            "predicate": {
              "$type": "protocatalyst.expr.ProtoExpr.GtEq",
              "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
              "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 18}}
            },
            "trueValue": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "adult"}},
            "falseValue": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "minor"}}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Alias Test ===

  test("SELECT with alias expression") {
    val sql = "SELECT name AS user_name FROM users"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Alias",
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null},
            "name": "user_name"
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Join Tests ===

  test("RIGHT JOIN") {
    val sql = "SELECT u.name, o.total FROM users u RIGHT JOIN orders o ON u.id = o.user_id"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": "u"},
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "total", "qualifier": "o"}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
          "left": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "u",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "users",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "right": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "o",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "orders",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "joinType": "RightOuter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Eq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": "u"},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "user_id", "qualifier": "o"}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("FULL OUTER JOIN") {
    val sql = "SELECT u.name, o.total FROM users u FULL OUTER JOIN orders o ON u.id = o.user_id"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": "u"},
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "total", "qualifier": "o"}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
          "left": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "u",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "users",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "right": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "o",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "orders",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "joinType": "FullOuter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Eq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": "u"},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "user_id", "qualifier": "o"}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("CROSS JOIN") {
    val sql = "SELECT u.name, r.role FROM users u CROSS JOIN roles r"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": "u"},
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "role", "qualifier": "r"}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
          "left": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "u",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "users",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "right": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "r",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "roles",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "joinType": "Cross",
          "condition": null
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Set Operations Tests ===

  test("INTERSECT") {
    val sql = "SELECT name FROM users INTERSECT SELECT name FROM admins"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Intersect",
        "left": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        },
        "right": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "admins",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        },
        "isAll": false
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("EXCEPT") {
    val sql = "SELECT name FROM users EXCEPT SELECT name FROM banned"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Except",
        "left": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        },
        "right": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "banned",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        },
        "isAll": false
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === ORDER BY with multiple columns ===

  test("ORDER BY ASC") {
    val sql = "SELECT name FROM users ORDER BY age ASC"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Sort",
        "order": [
          {
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
            "direction": "Ascending",
            "nullOrdering": "NullsFirst"
          }
        ],
        "global": true,
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "users",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Type Decoder Tests ===

  test("TypeDecoder handles all additional primitive types") {
    val types = List(
      ("""{"$type": "protocatalyst.types.ProtoType.ByteType"}""", "byte"),
      ("""{"$type": "protocatalyst.types.ProtoType.ShortType"}""", "short"),
      ("""{"$type": "protocatalyst.types.ProtoType.FloatType"}""", "float"),
      ("""{"$type": "protocatalyst.types.ProtoType.BinaryType"}""", "binary"),
      ("""{"$type": "protocatalyst.types.ProtoType.TimestampNTZType"}""", "timestamp_ntz")
    )

    types.foreach { case (json, _) =>
      val result = ArtifactParser.parseType(parse(json).toOption.get)
      assert(result.isRight, s"Failed to parse $json: ${result.left.getOrElse("")}")
    }
  }

  test("TypeDecoder handles DecimalType") {
    val json =
      """{"$type": "protocatalyst.types.ProtoType.DecimalType", "precision": 10, "scale": 2}"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(
      result.toOption.get.toString.contains("decimal") || result.toOption.get.toString
        .contains("Decimal")
    )
  }

  test("TypeDecoder handles ArrayType") {
    val json = """{
      "$type": "protocatalyst.types.ProtoType.ArrayType",
      "elementType": {"$type": "protocatalyst.types.ProtoType.IntegerType"},
      "containsNull": true
    }"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.toLowerCase.contains("array"))
  }

  test("TypeDecoder handles MapType") {
    val json = """{
      "$type": "protocatalyst.types.ProtoType.MapType",
      "keyType": {"$type": "protocatalyst.types.ProtoType.StringType"},
      "valueType": {"$type": "protocatalyst.types.ProtoType.IntegerType"},
      "valueContainsNull": true
    }"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.toLowerCase.contains("map"))
  }

  test("TypeDecoder handles StructType") {
    val json = """{
      "$type": "protocatalyst.types.ProtoType.StructType",
      "fields": [
        {"name": "id", "dataType": {"$type": "protocatalyst.types.ProtoType.IntegerType"}, "nullable": false},
        {"name": "name", "dataType": {"$type": "protocatalyst.types.ProtoType.StringType"}, "nullable": true}
      ]
    }"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.toLowerCase.contains("struct"))
  }

  test("TypeDecoder handles special types") {
    val types = List(
      """{"$type": "protocatalyst.types.ProtoType.NullType"}""",
      """{"$type": "protocatalyst.types.ProtoType.UnresolvedType"}""",
      """{"$type": "protocatalyst.types.ProtoType.CalendarIntervalType"}""",
      """{"$type": "protocatalyst.types.ProtoType.DayTimeIntervalType"}""",
      """{"$type": "protocatalyst.types.ProtoType.YearMonthIntervalType"}"""
    )

    types.foreach { json =>
      val result = ArtifactParser.parseType(parse(json).toOption.get)
      assert(result.isRight, s"Failed to parse $json: ${result.left.getOrElse("")}")
    }
  }

  // === Literal Value Tests ===

  test("ExpressionDecoder handles all literal types") {
    val literals = List(
      (
        """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.ByteValue", "value": 127}}""",
        "127"
      ),
      (
        """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.ShortValue", "value": 32767}}""",
        "32767"
      ),
      (
        """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.LongValue", "value": 9223372036854775807}}""",
        "9223372036854775807"
      ),
      (
        """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.FloatValue", "value": 3.14}}""",
        "3.14"
      ),
      (
        """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.DecimalValue", "value": "123.45"}}""",
        "123.45"
      )
    )

    literals.foreach { case (json, expectedContains) =>
      val result = ArtifactParser.parseExpression(parse(json).toOption.get)
      assert(result.isRight, s"Failed to parse $json: ${result.left.getOrElse("")}")
    }
  }

  test("ExpressionDecoder handles DateValue") {
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.DateValue", "epochDays": 19000}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("ExpressionDecoder handles TimestampValue") {
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.TimestampValue", "epochMicros": 1640000000000000}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("ExpressionDecoder handles NullValue") {
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.NullValue", "dataType": {"$type": "protocatalyst.types.ProtoType.StringType"}}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("ExpressionDecoder handles BinaryValue") {
    // Base64 encoded bytes for "hello"
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.BinaryValue", "value": "aGVsbG8="}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === BoundRef Expression Test ===

  test("ExpressionDecoder handles BoundRef") {
    val json = """{
      "$type": "protocatalyst.expr.ProtoExpr.BoundRef",
      "index": 0,
      "dataType": {"$type": "protocatalyst.types.ProtoType.IntegerType"},
      "nullable": true
    }"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === OpaqueCall Expression Test ===

  test("ExpressionDecoder handles OpaqueCall") {
    val json = """{
      "$type": "protocatalyst.expr.ProtoExpr.OpaqueCall",
      "functionName": "custom_func",
      "arguments": [
        {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 42}}
      ]
    }"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.contains("custom_func"))
  }

  // === Additional Plan Decoder Tests ===

  test("PlanDecoder handles VALUES clause") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Values",
      "rows": [
        [{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 1}},
         {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "a"}}],
        [{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 2}},
         {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "b"}}]
      ],
      "schema": {
        "fields": [
          {"name": "id", "dataType": {"$type": "protocatalyst.types.ProtoType.IntegerType"}, "nullable": false},
          {"name": "name", "dataType": {"$type": "protocatalyst.types.ProtoType.StringType"}, "nullable": true}
        ]
      }
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles Window plan") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Window",
      "windowExprs": [
        {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "row_num", "qualifier": null}
      ],
      "partitionSpec": [
        {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "department", "qualifier": null}
      ],
      "orderSpec": [
        {
          "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "salary", "qualifier": null},
          "direction": "Descending",
          "nullOrdering": "NullsLast"
        }
      ],
      "child": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "employees",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      }
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles CTE (With) plan") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.With",
      "cteRelations": [
        ["cte_name", {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "source_table",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }]
      ],
      "recursive": false,
      "child": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "cte_name",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      }
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles LateralJoin") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.LateralJoin",
      "left": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t1",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      },
      "lateral": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t2",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      },
      "condition": null
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles Pivot (stub)") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Pivot",
      "groupByExprsOpt": [],
      "pivotColumn": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "category", "qualifier": null},
      "pivotValues": [],
      "aggregates": [],
      "child": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "sales",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      }
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles Unpivot (stub)") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Unpivot",
      "ids": [],
      "values": [],
      "variableColumnName": "variable",
      "valueColumnName": "value",
      "child": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "sales",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      }
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles Generate (stub)") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Generate",
      "generator": {"$type": "protocatalyst.expr.ProtoExpr.OpaqueCall", "functionName": "explode", "arguments": []},
      "unrequiredChildIndex": [],
      "outer": false,
      "qualifier": null,
      "generatorOutput": [],
      "child": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "arrays",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      }
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles ResolvedHint") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.ResolvedHint",
      "hints": [],
      "child": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t1",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      }
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === Additional Type Decoder Tests ===

  test("TypeDecoder handles CharType") {
    val json = """{"$type": "protocatalyst.types.ProtoType.CharType", "length": 10}"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("TypeDecoder handles VarcharType") {
    val json = """{"$type": "protocatalyst.types.ProtoType.VarcharType", "length": 255}"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("TypeDecoder handles VariantType") {
    val json = """{"$type": "protocatalyst.types.ProtoType.VariantType"}"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("TypeDecoder handles TimeType (mapped to StringType)") {
    val json = """{"$type": "protocatalyst.types.ProtoType.TimeType"}"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("TypeDecoder handles SumType (mapped to StringType)") {
    val json = """{"$type": "protocatalyst.types.ProtoType.SumType"}"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === Additional Join Types ===

  test("LEFT SEMI JOIN") {
    val sql = "SELECT u.name FROM users u LEFT SEMI JOIN orders o ON u.id = o.user_id"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": "u"}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
          "left": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "u",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "users",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "right": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "o",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "orders",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "joinType": "LeftSemi",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Eq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": "u"},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "user_id", "qualifier": "o"}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  test("LEFT ANTI JOIN") {
    val sql = "SELECT u.name FROM users u LEFT ANTI JOIN orders o ON u.id = o.user_id"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": "u"}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
          "left": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "u",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "users",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "right": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.SubqueryAlias",
            "alias": "o",
            "child": {
              "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
              "name": "orders",
              "alias": null,
              "schemaContract": {"fields": [], "fingerprint": 0}
            }
          },
          "joinType": "LeftAnti",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Eq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": "u"},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "user_id", "qualifier": "o"}
          }
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === RelationRef with alias ===

  test("PlanDecoder handles RelationRef with alias") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
      "name": "users",
      "alias": "u",
      "schemaContract": {"fields": [], "fingerprint": 0}
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.contains("SubqueryAlias"))
  }

  // === INTERSECT ALL / EXCEPT ALL ===

  test("PlanDecoder handles INTERSECT ALL") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Intersect",
      "left": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t1",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      },
      "right": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t2",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      },
      "isAll": true
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles EXCEPT ALL") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Except",
      "left": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t1",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      },
      "right": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t2",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      },
      "isAll": true
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === ParityTester API Tests ===

  test("ParityResult.summary returns PASS for matching plans") {
    implicit val s: SparkSession = spark
    val sql = "SELECT name FROM users"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(result.matches)
    assert(result.summary.startsWith("PASS"))
    assert(result.summary.contains(sql))
  }

  test("ParityResult.planTrees returns formatted plan trees") {
    implicit val s: SparkSession = spark
    val sql = "SELECT name FROM users"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    val trees = result.planTrees
    assert(trees.contains("SQL:"))
    assert(trees.contains("Spark plan:"))
    assert(trees.contains("ProtoCatalyst plan:"))
  }

  test("ParityTester.testParityFromPlanJson works with pre-parsed JSON") {
    implicit val s: SparkSession = spark
    val sql = "SELECT name FROM users"
    val planJson = parse("""{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
      "projectList": [
        {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
      ],
      "child": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "users",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      }
    }""").toOption.get
    val result = ParityTester.testParityFromPlanJson(sql, planJson)
    assert(result.matches)
  }

  test("ParityTester.testParityFromBytes parses PCAT format") {
    implicit val s: SparkSession = spark
    val sql = "SELECT name FROM users"

    // Create PCAT header + JSON payload
    val jsonPayload = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""
    val magicHeader = Array('P'.toByte, 'C'.toByte, 'A'.toByte, 'T'.toByte)
    val formatByte = Array(0x01.toByte) // JSON format
    val artifactBytes = magicHeader ++ formatByte ++ jsonPayload.getBytes("UTF-8")

    val result = ParityTester.testParityFromBytes(sql, artifactBytes)
    assert(result.matches)
  }

  test("ParityTester.runBatch processes multiple test cases") {
    implicit val s: SparkSession = spark
    val testCases = Seq(
      (
        "SELECT a FROM t1",
        """{
        "plan": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [{"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null}],
          "child": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t1", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}}
        }
      }"""
      ),
      (
        "SELECT b FROM t2",
        """{
        "plan": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [{"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "b", "qualifier": null}],
          "child": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t2", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}}
        }
      }"""
      )
    )
    val batchResult = ParityTester.runBatch(testCases)
    assertEquals(batchResult.total, 2)
    assertEquals(batchResult.passed, 2)
    assertEquals(batchResult.failed, 0)
    assert(batchResult.passedResults.size == 2)
    assert(batchResult.failedResults.isEmpty)
  }

  test("BatchResult.summary includes header and details") {
    implicit val s: SparkSession = spark
    val testCases = Seq(
      (
        "SELECT a FROM t1",
        """{
        "plan": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [{"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null}],
          "child": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t1", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}}
        }
      }"""
      )
    )
    val batchResult = ParityTester.runBatch(testCases)
    val summary = batchResult.summary
    assert(summary.contains("1/1 passed"))
    assert(summary.contains("PASS"))
  }

  test("ArtifactParser.parsePlan with valid PCAT bytes") {
    val jsonPayload = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "test_table",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      }
    }"""
    val magicHeader = Array('P'.toByte, 'C'.toByte, 'A'.toByte, 'T'.toByte)
    val formatByte = Array(0x01.toByte)
    val artifactBytes = magicHeader ++ formatByte ++ jsonPayload.getBytes("UTF-8")

    val result = ArtifactParser.parsePlan(artifactBytes)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.contains("test_table"))
  }

  // === Error Handling Tests ===

  test("ArtifactParser.parsePlan fails with too short bytes") {
    val shortBytes = Array[Byte]('P', 'C', 'A')
    val result = ArtifactParser.parsePlan(shortBytes)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("too short"))
  }

  test("ArtifactParser.parsePlan fails with invalid magic header") {
    val badHeader = Array[Byte]('X', 'Y', 'Z', 'W', 0x01) ++ "{}".getBytes("UTF-8")
    val result = ArtifactParser.parsePlan(badHeader)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Invalid magic header"))
  }

  test("ArtifactParser.parsePlan fails with unsupported format") {
    val badFormat = Array[Byte]('P', 'C', 'A', 'T', 0x02) ++ "{}".getBytes("UTF-8")
    val result = ArtifactParser.parsePlan(badFormat)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Unsupported format"))
  }

  test("ArtifactParser.parsePlanFromJsonString fails with invalid JSON") {
    val invalidJson = "{ not valid json"
    val result = ArtifactParser.parsePlanFromJsonString(invalidJson)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("JSON parse error"))
  }

  test("ArtifactParser.parsePlanFromJson fails with missing plan field") {
    val json = parse("""{"notPlan": {}}""").toOption.get
    val result = ArtifactParser.parsePlanFromJson(json)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Missing 'plan' field"))
  }

  test("PlanDecoder fails with unknown plan type") {
    val json = """{"$type": "protocatalyst.plan.ProtoLogicalPlan.UnknownType"}"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Unknown"))
  }

  test("ExpressionDecoder fails with unknown expression type") {
    val json = """{"$type": "protocatalyst.expr.ProtoExpr.UnknownExpr"}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Unknown"))
  }

  test("TypeDecoder fails with unknown type") {
    val json = """{"$type": "protocatalyst.types.ProtoType.UnknownType"}"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Unknown"))
  }

  test("ExpressionDecoder fails with unknown literal type") {
    val json =
      """{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.UnknownValue"}}"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isLeft)
    assert(result.left.getOrElse("").contains("Unknown"))
  }

  // === CaseWhen Expression Test ===

  test("ExpressionDecoder handles CaseWhen") {
    val json = """{
      "$type": "protocatalyst.expr.ProtoExpr.CaseWhen",
      "branches": [
        [{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.BooleanValue", "value": true}},
         {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 1}}]
      ],
      "elseValue": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 0}}
    }"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.toLowerCase.contains("case"))
  }

  test("ExpressionDecoder handles CaseWhen without else") {
    val json = """{
      "$type": "protocatalyst.expr.ProtoExpr.CaseWhen",
      "branches": [
        [{"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.BooleanValue", "value": true}},
         {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 1}}]
      ],
      "elseValue": null
    }"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === Multiply Expression Test ===

  test("SELECT with multiplication") {
    val sql = "SELECT price * quantity FROM orders"

    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {
            "$type": "protocatalyst.expr.ProtoExpr.Multiply",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "price", "qualifier": null},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "quantity", "qualifier": null}
          }
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "orders",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""

    assertParity(sql, protoJson)
  }

  // === Cast Expression Test ===

  test("ExpressionDecoder handles Cast") {
    val json = """{
      "$type": "protocatalyst.expr.ProtoExpr.Cast",
      "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "age", "qualifier": null},
      "targetType": {"$type": "protocatalyst.types.ProtoType.StringType"}
    }"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
    assert(result.toOption.get.toString.toLowerCase.contains("cast"))
  }

  // === COUNT with DISTINCT Test ===

  test("ExpressionDecoder handles COUNT with distinct") {
    val json = """{
      "$type": "protocatalyst.expr.ProtoExpr.Count",
      "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": null},
      "distinct": true
    }"""
    val result = ArtifactParser.parseExpression(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === TypeDecoder UDT Test ===

  test("TypeDecoder handles UDTType") {
    val json = """{
      "$type": "protocatalyst.types.ProtoType.UDTType",
      "sqlType": {"$type": "protocatalyst.types.ProtoType.StringType"}
    }"""
    val result = ArtifactParser.parseType(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === LateralJoin with condition Test ===

  test("PlanDecoder handles LateralJoin with condition") {
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.LateralJoin",
      "left": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t1",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      },
      "lateral": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
        "name": "t2",
        "alias": null,
        "schemaContract": {"fields": [], "fingerprint": 0}
      },
      "condition": {
        "$type": "protocatalyst.expr.ProtoExpr.Eq",
        "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null},
        "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "b", "qualifier": null}
      }
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  // === PlanComparator Mismatch Tests ===

  test("ParityResult.summary returns FAIL for mismatching plans") {
    implicit val s: SparkSession = spark
    val sql = "SELECT name FROM users"
    // Intentionally wrong: different table name
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "name", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "different_table",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    assert(result.summary.startsWith("FAIL"))
    assert(result.differences.nonEmpty)
  }

  test("PlanComparator detects plan type mismatch") {
    implicit val s: SparkSession = spark
    // SQL is Project but JSON is Filter
    val sql = "SELECT name FROM users"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
        "condition": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.BooleanValue", "value": true}},
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "users",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    assert(result.differences.exists(_.contains("plan type mismatch")))
  }

  test("PlanComparator detects children count mismatch") {
    implicit val s: SparkSession = spark
    // SQL is UNION (2 children) but JSON only has one
    val sql = "SELECT a FROM t1 UNION SELECT a FROM t2"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Union",
        "children": [
          {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
            "projectList": [{"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null}],
            "child": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t1", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}}
          }
        ],
        "byName": false,
        "allowMissingColumns": false
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    // UNION with different children count will be detected
    assert(result.differences.nonEmpty)
  }

  test("PlanComparator detects expression list size mismatch") {
    implicit val s: SparkSession = spark
    // SQL has 2 columns but JSON has 1
    val sql = "SELECT a, b FROM t"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "t",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    assert(result.differences.exists(_.contains("expression list size mismatch")))
  }

  test("PlanComparator detects attribute name mismatch") {
    implicit val s: SparkSession = spark
    val sql = "SELECT col_a FROM t"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "different_col", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "t",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    assert(result.differences.exists(_.contains("Attribute mismatch")))
  }

  test("PlanComparator detects literal value mismatch") {
    implicit val s: SparkSession = spark
    val sql = "SELECT 1 FROM t"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 2}}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
          "name": "t",
          "alias": null,
          "schemaContract": {"fields": [], "fingerprint": 0}
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    assert(result.differences.exists(_.contains("Literal mismatch")))
  }

  test("PlanComparator detects sort direction mismatch") {
    implicit val s: SparkSession = spark
    // SQL has ASC but JSON has DESC
    val sql = "SELECT a FROM t ORDER BY a ASC"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Sort",
        "order": [
          {
            "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null},
            "direction": "Descending",
            "nullOrdering": "NullsLast"
          }
        ],
        "global": true,
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [
            {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null}
          ],
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "t",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    assert(result.differences.exists(_.contains("sort direction mismatch")))
  }

  test("PlanComparator detects join type mismatch") {
    implicit val s: SparkSession = spark
    // SQL has INNER but JSON has LEFT
    val sql = "SELECT a FROM t1 INNER JOIN t2 ON t1.id = t2.id"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
          "left": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "t1",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          },
          "right": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "t2",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          },
          "joinType": "LeftOuter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Eq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": "t1"},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "id", "qualifier": "t2"}
          }
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    assert(result.differences.exists(_.contains("Join.joinType mismatch")))
  }

  test("PlanComparator detects expression type mismatch") {
    implicit val s: SparkSession = spark
    // SQL has column ref but JSON has literal
    val sql = "SELECT a FROM t WHERE b = 1"
    val protoJson = """{
      "plan": {
        "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
        "projectList": [
          {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null}
        ],
        "child": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Filter",
          "condition": {
            "$type": "protocatalyst.expr.ProtoExpr.Eq",
            "left": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.StringValue", "value": "wrong"}},
            "right": {"$type": "protocatalyst.expr.ProtoExpr.Literal", "value": {"$type": "protocatalyst.types.LiteralValue.IntValue", "value": 1}}
          },
          "child": {
            "$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef",
            "name": "t",
            "alias": null,
            "schemaContract": {"fields": [], "fingerprint": 0}
          }
        }
      }
    }"""
    val result = ParityTester.testParity(sql, protoJson)
    assert(!result.matches)
    assert(result.differences.exists(_.contains("expression type mismatch")))
  }

  test("BatchResult includes failed results") {
    implicit val s: SparkSession = spark
    val testCases = Seq(
      (
        "SELECT a FROM t1",
        """{
        "plan": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [{"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null}],
          "child": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t1", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}}
        }
      }"""
      ),
      (
        "SELECT b FROM t2",
        """{
        "plan": {
          "$type": "protocatalyst.plan.ProtoLogicalPlan.Project",
          "projectList": [{"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "wrong_col", "qualifier": null}],
          "child": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t2", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}}
        }
      }"""
      )
    )
    val batchResult = ParityTester.runBatch(testCases)
    assertEquals(batchResult.total, 2)
    assertEquals(batchResult.passed, 1)
    assertEquals(batchResult.failed, 1)
    assert(batchResult.failedResults.size == 1)
    assert(batchResult.passedResults.size == 1)
    assert(batchResult.summary.contains("1/2 passed"))
    assert(batchResult.summary.contains("1 failed"))
  }

  // === Additional PlanDecoder edge cases ===

  test("PlanDecoder handles default join type") {
    // Join type "Unknown" should default to Inner
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Join",
      "left": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t1", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}},
      "right": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t2", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}},
      "joinType": "UnknownJoinType",
      "condition": null
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }

  test("PlanDecoder handles default sort direction") {
    // Direction "Unknown" should default to Ascending
    val json = """{
      "$type": "protocatalyst.plan.ProtoLogicalPlan.Sort",
      "order": [
        {
          "child": {"$type": "protocatalyst.expr.ProtoExpr.ColumnRef", "name": "a", "qualifier": null},
          "direction": "UnknownDirection",
          "nullOrdering": "UnknownNullOrdering"
        }
      ],
      "global": true,
      "child": {"$type": "protocatalyst.plan.ProtoLogicalPlan.RelationRef", "name": "t", "alias": null, "schemaContract": {"fields": [], "fingerprint": 0}}
    }"""
    val result = ArtifactParser.parseRawPlan(parse(json).toOption.get)
    assert(result.isRight, s"Failed to parse: ${result.left.getOrElse("")}")
  }
}
