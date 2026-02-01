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
  .aggregate(core, encoder, arrow, query, sqlParser, mockRuntime, benchmarks, benchmarkSpark)
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

// Arrow integration module: compile-time Arrow columnar format support
lazy val arrow = project
  .in(file("arrow"))
  .dependsOn(core, encoder)
  .settings(
    name := "protocatalyst-arrow",
    commonSettings,
    libraryDependencies ++= Seq(
      "org.apache.arrow" % "arrow-memory-core" % "18.1.0",
      "org.apache.arrow" % "arrow-memory-unsafe" % "18.1.0",
      "org.apache.arrow" % "arrow-vector" % "18.1.0"
    ),
    // Arrow needs access to internal JVM modules for off-heap memory
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
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

// Benchmarks module (Scala 3) - JMH benchmarks for ProtoCatalyst encoders
lazy val benchmarks = project
  .in(file("benchmarks"))
  .dependsOn(core, encoder, arrow, mockRuntime)
  .enablePlugins(JmhPlugin)
  .settings(
    name := "protocatalyst-benchmarks",
    commonSettings,
    publish / skip := true,
    // Fury and Arrow need access to internal JVM modules
    Jmh / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    Jmh / fork := true
  )

// Benchmark Spark comparison module (Scala 2.13) - Compare with Spark's ExpressionEncoder
// Note: This module is standalone (no dependency on Scala 3 modules) to avoid version conflicts
lazy val benchmarkSpark = project
  .in(file("benchmark-spark"))
  .enablePlugins(JmhPlugin)
  .settings(
    name := "protocatalyst-benchmark-spark",
    scalaVersion := "2.13.16",
    publish / skip := true,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked"
      // Note: -Werror removed for Scala 2.13 compatibility with Spark
    ),
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % "4.0.0",
      "org.apache.spark" %% "spark-catalyst" % "4.0.0",
      "org.scala-lang" % "scala-reflect" % "2.13.16",  // Required for TypeTag
      // Arrow dependencies for Arrow benchmarks
      "org.apache.arrow" % "arrow-memory-core" % "18.1.0",
      "org.apache.arrow" % "arrow-memory-unsafe" % "18.1.0",
      "org.apache.arrow" % "arrow-vector" % "18.1.0"
    ),
    Jmh / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    Jmh / fork := true
  )
