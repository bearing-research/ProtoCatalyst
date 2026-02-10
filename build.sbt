ThisBuild / organization := "io.protocatalyst"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.1"

// Common settings for all modules
lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked",
    "-Wunused:imports", // Required for Scalafix OrganizeImports rule
    "-Werror"
  ),
  // Enable SemanticDB for Scalafix
  semanticdbEnabled := true,
  semanticdbVersion := scalafixSemanticdb.revision,
  libraryDependencies += "org.scalameta" %% "munit" % "1.2.2" % Test,
  testFrameworks += new TestFramework("munit.Framework")
)

// Root project (aggregates all modules)
// Note: spark module excluded until Spark 4.0 Scala 3 artifacts are published
lazy val root = project
  .in(file("."))
  .aggregate(proto, core, encoder, arrow, executor, substrait, query, sqlParser, benchmarks, benchmarkSpark, sparkCatalyst, mlCore, mlQuery)
  .settings(
    name := "protocatalyst",
    publish / skip := true
  )

// Protobuf schema module (Java-only) — shared between Scala 3 and Scala 2.13 modules
lazy val proto = project
  .in(file("proto"))
  .settings(
    name := "protocatalyst-proto",
    crossPaths := false,
    autoScalaLibrary := false,
    libraryDependencies += "com.google.protobuf" % "protobuf-java" % "4.29.3",
    Compile / PB.targets := Seq(
      PB.gens.java -> (Compile / sourceManaged).value
    )
  )

// Core module: types, schema, IR
lazy val core = project
  .in(file("core"))
  .dependsOn(proto, mlCore)
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
      "org.apache.arrow" % "arrow-vector" % "18.1.0",
      // Parquet I/O (uses LocalInputFile/LocalOutputFile + PlainParquetConfiguration, no Hadoop in our code)
      "org.apache.parquet" % "parquet-hadoop" % "1.17.0",
      // Hadoop runtime deps required internally by parquet-hadoop's CodecFactory
      "org.apache.hadoop" % "hadoop-common" % "3.4.1" excludeAll (
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule(organization = "org.apache.curator"),
        ExclusionRule(organization = "org.apache.zookeeper"),
        ExclusionRule(organization = "com.sun.jersey"),
        ExclusionRule(organization = "javax.servlet"),
        ExclusionRule(organization = "io.netty")
      ),
      "org.apache.hadoop" % "hadoop-mapreduce-client-core" % "3.4.1" excludeAll (
        ExclusionRule(organization = "org.eclipse.jetty"),
        ExclusionRule(organization = "org.apache.curator"),
        ExclusionRule(organization = "io.netty")
      )
    ),
    // Arrow needs access to internal JVM modules for off-heap memory
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    Test / fork := true
  )

// Executor module: standalone query execution engine (Arrow-based, no Spark dependency)
lazy val executor = project
  .in(file("executor"))
  .dependsOn(core, arrow)
  .settings(
    name := "protocatalyst-executor",
    commonSettings,
    libraryDependencies ++= Seq(
      // ADBC Core API
      "org.apache.arrow.adbc" % "adbc-core" % "0.15.0",
      // ADBC Driver Manager
      "org.apache.arrow.adbc" % "adbc-driver-manager" % "0.15.0",
      // ADBC Flight SQL Driver (connects to DataFusion Flight SQL server)
      "org.apache.arrow.adbc" % "adbc-driver-flight-sql" % "0.15.0"
    ),
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    Test / fork := true
  )

// Substrait module: converts ProtoCatalyst IR to Substrait (industry-standard IR format)
lazy val substrait = project
  .in(file("substrait"))
  .dependsOn(core, arrow)
  .settings(
    name := "protocatalyst-substrait",
    commonSettings,
    libraryDependencies ++= Seq(
      // Substrait Java bindings (protobuf-based IR format)
      "io.substrait" % "core" % "0.78.0",
      // ADBC for executing Substrait plans via Flight SQL
      "org.apache.arrow.adbc" % "adbc-core" % "0.15.0",
      "org.apache.arrow.adbc" % "adbc-driver-manager" % "0.15.0",
      "org.apache.arrow.adbc" % "adbc-driver-flight-sql" % "0.15.0"
    ),
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
  .dependsOn(core, encoder, arrow)
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
      "-unchecked",
      "-Wunused:imports" // Required for Scalafix OrganizeImports
      // Note: -Werror removed for Scala 2.13 compatibility with Spark
    ),
    // Enable SemanticDB for Scalafix
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
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
    Jmh / fork := true,
    // Task to generate golden files for Spark parity testing
    // Run with: sbt "benchmarkSpark/runMain protocatalyst.benchmark.GoldenFileGenerator"
    run / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED"
    ),
    run / fork := true
  )

// ML Core module: tensor IR, computation graph, optimizer
lazy val mlCore = project
  .in(file("ml-core"))
  .dependsOn(proto)
  .settings(
    name := "protocatalyst-ml-core",
    commonSettings,
    libraryDependencies += "com.jyuzawa" % "onnxruntime-cpu" % "1.23.2" % Test,
    Test / fork := true,
    Test / javaHome := Some(file("/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home")),
    Test / javaOptions += "--enable-native-access=ALL-UNNAMED"
  )

// ML Query module: typed tensor DSL and shape checking
lazy val mlQuery = project
  .in(file("ml-query"))
  .dependsOn(mlCore)
  .settings(
    name := "protocatalyst-ml-query",
    commonSettings
  )

// Spark Catalyst integration module (Scala 2.13) - Convert ProtoLogicalPlan to Spark LogicalPlan
// Note: This module is standalone (no dependency on Scala 3 modules) to avoid version conflicts
// Depends on proto (Java-only) for protobuf deserialization
lazy val sparkCatalyst = project
  .in(file("spark-catalyst"))
  .dependsOn(proto)
  .settings(
    name := "protocatalyst-spark-catalyst",
    scalaVersion := "2.13.16",
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:imports"
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % "4.0.0",
      "org.apache.spark" %% "spark-catalyst" % "4.0.0",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",
      "org.scalameta" %% "munit" % "1.0.0" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Test / fork := true
  )
