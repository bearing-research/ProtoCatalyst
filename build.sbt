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
  .aggregate(proto, core, encoder, arrow, executor, query, sqlParser, benchmarks, benchmarkSpark, sparkCatalyst, encoderSpark, mlCore, mlQuery)
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
      "org.apache.arrow" % "arrow-memory-core" % "18.3.0",
      "org.apache.arrow" % "arrow-memory-unsafe" % "18.3.0",
      "org.apache.arrow" % "arrow-vector" % "18.3.0",
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
  .dependsOn(core, arrow, sqlParser % Test)
  .settings(
    name := "protocatalyst-executor",
    commonSettings,
    libraryDependencies ++= Seq(
      // ADBC Core API
      "org.apache.arrow.adbc" % "adbc-core" % "0.23.0",
      // ADBC Driver Manager
      "org.apache.arrow.adbc" % "adbc-driver-manager" % "0.23.0",
      // ADBC Flight SQL Driver (connects to DataFusion Flight SQL server)
      "org.apache.arrow.adbc" % "adbc-driver-flight-sql" % "0.23.0"
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


// Benchmarks module (Scala 3) - JMH benchmarks for ProtoCatalyst encoders
lazy val benchmarks = project
  .in(file("benchmarks"))
  .dependsOn(core, encoder, arrow, encoderSpark)
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
    Jmh / fork := true,
    // TPC-H benchmarks load data/tpch/<sf>/*.tbl from project root.
    Jmh / baseDirectory := (ThisBuild / baseDirectory).value
  )

// Benchmark Spark comparison module (Scala 2.13) - Compare with Spark's ExpressionEncoder
// Note: This module is standalone (no dependency on Scala 3 modules) to avoid version conflicts
lazy val benchmarkSpark = project
  .in(file("benchmark-spark"))
  .enablePlugins(JmhPlugin)
  .settings(
    name := "protocatalyst-benchmark-spark",
    scalaVersion := "2.13.16",
    // SIP-51: Spark 4.1.2 transitively requires scala-library >= 2.13.18, but semanticdb-scalac
    // for 2.13.18 isn't published yet. Demote the upgrade check; safe because we don't link
    // against newer-stdlib symbols ourselves.
    allowUnsafeScalaLibUpgrade := true,
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
      "org.apache.spark" %% "spark-sql" % "4.1.2",
      "org.apache.spark" %% "spark-catalyst" % "4.1.2",
      "org.scala-lang" % "scala-reflect" % "2.13.16",  // Required for TypeTag
      // Arrow dependencies for Arrow benchmarks (aligned with Spark 4.1.2)
      "org.apache.arrow" % "arrow-memory-core" % "18.3.0",
      "org.apache.arrow" % "arrow-memory-unsafe" % "18.3.0",
      "org.apache.arrow" % "arrow-memory-netty" % "18.3.0",
      "org.apache.arrow" % "arrow-vector" % "18.3.0",
      // Spark Connect's ArrowSerializer/ArrowDeserializer — reference impl for parity fixtures
      "org.apache.spark" %% "spark-connect-common" % "4.1.2"
    ),
    Jmh / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    Jmh / fork := true,
    // TPC-H benchmarks load data/tpch/<sf>/*.tbl from project root.
    Jmh / baseDirectory := (ThisBuild / baseDirectory).value,
    // Task to generate golden files for Spark parity testing
    // Run with: sbt "benchmarkSpark/runMain protocatalyst.benchmark.GoldenFileGenerator"
    // The fork cwd is the project root so relative paths to e.g. encoder-spark resolve.
    run / baseDirectory := (ThisBuild / baseDirectory).value,
    run / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      // Spark 4.1 catalyst's SparkDateTimeUtils reflects into sun.util.calendar.ZoneInfo on
      // JDK 17+; required for queries that touch date columns under whole-stage codegen.
      "--add-opens=java.base/sun.util.calendar=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.action=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
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

// Encoder-Spark bridge module (Scala 3, uses Spark 4.1.x Scala 2.13 jars via For3Use2_13).
// Provides UnsafeRowSerializer — compile-time Mirror-derived serializer that writes the same
// packed UnsafeRow byte layout Spark's whole-stage codegen produces. The apples-to-apples
// comparison target for the Phase A encoder benchmark. See docs/scala3-encoder/ENCODER_PARITY.md.
lazy val encoderSpark = project
  .in(file("encoder-spark"))
  .dependsOn(core, encoder)
  .settings(
    name := "protocatalyst-encoder-spark",
    commonSettings,
    libraryDependencies ++= Seq(
      ("org.apache.spark" %% "spark-sql" % "4.1.2")
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang.modules", "scala-xml_2.13"),
      ("org.apache.spark" %% "spark-catalyst" % "4.1.2")
        .cross(CrossVersion.for3Use2_13)
        .exclude("org.scala-lang.modules", "scala-xml_2.13"),
      // Arrow path: macro emits Arrow Schema/Field/FieldVector usage directly, no Spark runtime dep
      "org.apache.arrow" % "arrow-memory-core" % "18.3.0",
      "org.apache.arrow" % "arrow-memory-netty" % "18.3.0",
      "org.apache.arrow" % "arrow-vector" % "18.3.0"
    ),
    Test / javaOptions ++= Seq(
      "--add-opens=java.base/sun.nio.fs=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    ),
    Test / fork := true,
    // TpchDbgenIntegrationSpec reads data/tpch/<sf>/*.tbl with project-root-relative paths.
    Test / baseDirectory := (ThisBuild / baseDirectory).value,
    // Execution-wall demonstrator: prepend the patched (Scala 2.13) ScalaReflection ahead of
    // spark-catalyst on the test classpath so it shadows Spark's copy. This lets WallReproSpec run
    // Spark's real ser/deser end-to-end from this Scala 3 process. See spark-reflection-patch.
    Test / fullClasspath := {
      val patched = (sparkReflectionPatch / Compile / products).value.map(Attributed.blank)
      patched ++ (Test / fullClasspath).value
    }
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
    // SIP-51 — same rationale as benchmarkSpark.
    allowUnsafeScalaLibUpgrade := true,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:imports"
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-sql" % "4.1.2",
      "org.apache.spark" %% "spark-catalyst" % "4.1.2",
      "io.circe" %% "circe-core" % "0.14.6",
      "io.circe" %% "circe-parser" % "0.14.6",
      "org.scalameta" %% "munit" % "1.0.0" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework"),
    Test / fork := true
  )

// Truffle execution backend (Java) — exploratory AOT-clean executor.
// See docs/compiler/TRUFFLE_EXPLORATION.md. Java-only on purpose: the Truffle DSL is a Java
// annotation processor (@Specialization → generated *NodeGen classes) and cannot be hosted from
// Scala (§2 of the plan). The ProtoPhysicalPlan→AST builder + harness integration will stay Scala
// and call into this module's generated Java nodes. Deliberately NOT in the root aggregate while it
// is a Phase-0 skeleton, so `sbt compile`/`sbt test` for the rest of the build are unaffected.
lazy val truffleExec = project
  .in(file("truffle-exec"))
  .enablePlugins(JmhPlugin)
  .settings(
    name := "protocatalyst-truffle-exec",
    crossPaths := false,
    autoScalaLibrary := false,
    libraryDependencies ++= Seq(
      "org.graalvm.truffle" % "truffle-api" % "25.0.2",
      // The DSL annotation processor — compile-time only; javac auto-discovers it on the classpath.
      "org.graalvm.truffle" % "truffle-dsl-processor" % "25.0.2" % Provided,
      // jargraal optimizing runtime as a library (Truffle "unchained"); on a non-GraalVM JDK this is
      // what could enable partial evaluation. Runtime-scope so it doesn't leak onto the compile path.
      "org.graalvm.truffle" % "truffle-runtime" % "25.0.2" % Runtime
    ),
    // This module runs on GraalVM (not the build's homebrew openjdk@21) so the optimizing Truffle
    // runtime + partial evaluation are available, and native-image is reachable later (Phase 4).
    // Isolated to this module; the rest of the build stays on openjdk@21 via .sbtopts.
    javaHome := Some(file("/Library/Java/JavaVirtualMachines/graalvm-21.jdk/Contents/Home")),
    run / fork := true,
    Jmh / fork := true
  )

// Execution-wall demonstrator (Scala 2.13). A verbatim copy of Spark 4.1.2's
// `org.apache.spark.sql.catalyst.ScalaReflection` with two lines changed (lazy `universe`;
// `encodeFieldNameToIdentifier` via NameTransformer). Compiled here against spark-catalyst 4.1.2 +
// scala-reflect, its output is prepended to encoderSpark's test classpath to shadow Spark's copy,
// proving the Scala-3 ser/deser execution wall is removable. NOT a Scala 3 module on purpose:
// the patch is the would-be upstream diff and must compile/run as 2.13 bytecode.
lazy val sparkReflectionPatch = project
  .in(file("spark-reflection-patch"))
  .settings(
    name := "protocatalyst-spark-reflection-patch",
    scalaVersion := "2.13.16",
    allowUnsafeScalaLibUpgrade := true,
    publish / skip := true,
    libraryDependencies ++= Seq(
      "org.apache.spark" %% "spark-catalyst" % "4.1.2",
      "org.apache.spark" %% "spark-sql-api" % "4.1.2",
      "org.scala-lang" % "scala-reflect" % "2.13.16"
    )
  )
