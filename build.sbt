ThisBuild / organization := "io.protocatalyst"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.1"

// Common settings for all modules
lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Werror"
  ),
  libraryDependencies += "org.scalameta" %% "munit" % "1.2.2" % Test,
  testFrameworks += new TestFramework("munit.Framework")
)

// Root project (aggregates all modules)
// Note: spark module excluded until Spark 4.0 Scala 3 artifacts are published
lazy val root = project
  .in(file("."))
  .aggregate(core, encoder, query, sqlParser, mockRuntime)
  .settings(
    name := "protocatalyst",
    publish / skip := true
  )

// Mock runtime module for testing without Spark dependency
lazy val mockRuntime = project
  .in(file("mock-runtime"))
  .dependsOn(core, encoder, query)
  .settings(
    name := "protocatalyst-mock-runtime",
    commonSettings
  )

// Core module: types, schema, IR
lazy val core = project
  .in(file("core"))
  .settings(
    name := "protocatalyst-core",
    commonSettings,
    libraryDependencies ++= Seq(
      "com.lihaoyi" %% "upickle" % "4.1.0"
    )
  )

// Encoder module: compile-time encoder derivation
lazy val encoder = project
  .in(file("encoder"))
  .dependsOn(core)
  .settings(
    name := "protocatalyst-encoder",
    commonSettings,
    libraryDependencies ++= Seq(
      // Optional serialization backends for TransformingEncoder
      "com.esotericsoftware" % "kryo" % "5.6.0",
      "org.apache.fury" % "fury-core" % "0.10.1",
      "org.apache.fury" %% "fury-scala" % "0.10.1"
    ),
    // Fury needs access to internal JVM modules for code generation
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED"
    ),
    Test / fork := true
  )

// SQL Parser module: compile-time SQL parsing
lazy val sqlParser = project
  .in(file("sql-parser"))
  .dependsOn(core, encoder)
  .settings(
    name := "protocatalyst-sql-parser",
    commonSettings,
    libraryDependencies ++= Seq(
      // JSQLParser for validation testing against a reference SQL parser
      "com.github.jsqlparser" % "jsqlparser" % "5.0" % Test
    )
  )

// Query module: compiled query artifacts
lazy val query = project
  .in(file("query"))
  .dependsOn(core, encoder, sqlParser)
  .settings(
    name := "protocatalyst-query",
    commonSettings
  )

// Spark integration module
// Note: Disabled until Spark 4.0 publishes Scala 3 artifacts
// lazy val spark = project
//   .in(file("spark"))
//   .dependsOn(core, encoder, query)
//   .settings(
//     name := "protocatalyst-spark",
//     commonSettings,
//     libraryDependencies ++= Seq(
//       "org.apache.spark" %% "spark-sql" % "4.0.0" % Provided
//     )
//   )
