# GraalVM AOT for Apache Spark — A Roadmap

*Companion to [`REPORT.md`](REPORT.md). The main report establishes that
Spark's `ExpressionEncoder` is the load-bearing structural blocker for
both Scala 3 migration and GraalVM `native-image` compilation, and
demonstrates a Scala 3 macro-derived replacement. This document asks
the follow-up question: **what else?**.*

---

## TL;DR

1. **AOT-clean full Spark is not realistic in any near-term horizon**
   without rewriting Catalyst's runtime bytecode generation (Janino).
   AOT-clean **Spark Connect client** is a realistic 12–18 month
   target.
2. **Project Leyden — not GraalVM — is the more pragmatic path for
   full Spark.** Leyden preserves the open-world assumption (reflection
   still works), targets 30–50% startup wins via an AOT cache, and
   ships through JDK 24–26. Spark could adopt it with zero code
   changes. Native-image gives bigger wins but at a much higher
   engineering cost.
3. **The Spark community has not started.** No SPARK JIRA exists for
   native-image, Leyden, or AOT as of late 2025. Compare to Kafka
   (4 active tickets for native broker work). A SPARK ticket would
   itself be a useful community signal.
4. **The encoder is item 1 of ~6 blockers** for an AOT-clean Connect
   client. The remaining items are individually tractable and the
   ProtoCatalyst macro infrastructure solves several of them as a
   side effect.

This document is structured as the inventory + roadmap that would
accompany a SPARK JIRA on this topic.

---

## §1. Why this is worth investigating

Spark's startup cost on a fresh JVM is currently dominated by:

- `scala.reflect.runtime.universe` initialization (~500 ms,
  measured in [`REPORT.md`](REPORT.md) §13, paid by the first
  `ExpressionEncoder[T]()` call).
- JVM startup + class loading (~300 ms baseline).
- Per-encoder Catalyst codegen via Janino (~100–130 ms first
  call, ~5–20 ms subsequent calls).
- SparkContext initialization (200–500 ms depending on configuration).

Total: **a fresh Spark Connect client takes 1–3 seconds to issue its
first query.** A native-image-compiled Spring Boot REST API of
comparable size starts in ~400 ms; a native-image Quarkus app in
~50 ms. For Spark workloads where the client is short-lived —
serverless executors, ad-hoc CLI tools, IDE test integrations,
edge analytics — this gap is the user experience.

The encoder work in this project removes one specific contribution
to that gap. This document scopes the remaining work.

---

## §2. Two AOT paths: native-image vs Leyden

These are routinely conflated in informal conversation; the
differences are large and affect the migration calculus.

### GraalVM native-image (closed-world AOT)

- **Build-time**: whole-program reachability analysis, emits a
  standalone native executable.
- **Runtime**: no JVM, no class loading, no JIT. Only what was
  reachable at build time runs.
- **Cold start**: order-of-magnitude reduction (Spring Boot 3.2:
  1.42 s → 407 ms; Quarkus apps to ~50 ms).
- **Compatibility**: hostile to runtime reflection, dynamic
  classloading, runtime bytecode generation, native code that
  initializes lazily.
- **Effort**: high — requires per-blocker work (substitutions,
  reachability metadata, sometimes complete rewrites).

### Project Leyden (open-world AOT cache)

- **Build-time**: a training run produces an AOT cache of class
  loads, link resolutions, and method profiles.
- **Runtime**: still on the JVM; the cache front-loads work that
  would otherwise happen lazily.
- **Cold start**: 30–50% reduction without code changes (Spring
  Boot 3.3+ documented this).
- **Compatibility**: preserves the open-world assumption.
  Reflection, dynamic classloading, Janino, `Unsafe`, REPL — **all
  keep working**.
- **Effort**: minimal — add a training-run step and ship the cache
  alongside the application.

| Property | native-image | Leyden |
|---|---|---|
| Output | Native binary | JVM + AOT cache |
| Cold start | ~10× reduction | ~30–50% reduction |
| Open-world | No | Yes |
| Reflection allowed | Only via metadata | Yes |
| Janino-compatible | No | Yes |
| Spark adoption cost | Years | Negligible |

**For Spark specifically, Leyden is dramatically more pragmatic.**
Native-image gives the bigger win but requires solving the entire
inventory in §3. Leyden gives most of the cold-start win at
essentially zero engineering cost. Mature Spring Boot apps already
ship Leyden-friendly training runs. Sources:
[OpenJDK Leyden](https://openjdk.org/projects/leyden/),
[inside.java JVMLS '25](https://inside.java/2025/10/21/jvmls-assembling-project-leyden/),
[inside.java AOT cache Jan 2026](https://inside.java/2026/01/09/run-aot-cache/),
[Spring Boot Leyden integration](https://github.com/spring-projects/spring-tools/wiki/Leyden-AOT-Cache-Usage-and-Configuration).

The rest of this document treats native-image as the more ambitious
goal and Leyden as the easier-to-pitch first step.

---

## §3. The blocker inventory (Spark 4.1.x)

Fifteen distinct mechanisms by which Spark's runtime dynamism
prevents native-image compilation. Classified by component, scope
(client / driver / executor), severity, and whether a documented
workaround exists.

| # | Component | Mechanism | Scope | Workaround? | Severity |
|---|---|---|---|---|---|
| 1 | `ExpressionEncoder` + `ScalaReflection` | Scala 2.13 `TypeTag` + `runtimeMirror` reflection on user case classes | Client + Driver | **Replaced by Scala 3 macro derivation (this project's work)** | Blocks startup of any typed `Dataset[T]` |
| 2 | Janino runtime codegen (`GenerateUnsafeProjection`, `WholeStageCodegenExec`, and 5 sibling generators in `org.apache.spark.sql.catalyst.expressions.codegen`) | `ClassBodyEvaluator` compiles Java strings to bytecode at runtime; `defineClass` into a private classloader | Driver (planner) + Executor (loads & runs) | **None** — Janino is fundamentally incompatible with native-image (same blocker Calcite/Flink face). Can disable codegen via `factoryMode=NO_CODEGEN` but [SPARK-44236](https://issues.apache.org/jira/browse/SPARK-44236) notes WSCG still emits codegen even then | Blocks all SQL execution under native-image |
| 3 | `sun.misc.Unsafe` / Tungsten (`common/unsafe/.../Platform.java`) | Off-heap field offsets, raw memory manipulation | Driver + Executor + Client | Handled automatically by GraalVM when `Unsafe.objectFieldOffset` args are constants (which Tungsten's are). May need a `@TargetClass` substitution for `theUnsafe` lookup. See [Red Hat: Using Unsafe safely in GraalVM Native Image](https://developers.redhat.com/articles/2022/05/09/using-unsafe-safely-graalvm-native-image) | Tractable |
| 4 | Spark REPL (`SparkILoop`, `Main`) — `scala.tools.nsc.interpreter.IMain` | Embeds the full Scala compiler | Driver only (REPL mode) | None — must be excluded from the native image | Feature-only |
| 5 | **Spark Connect Scala REPL** (`ConnectRepl.scala`) — Ammonite | Embeds Scala compiler in the **client** JAR. The client's `pom.xml` declares `scala-compiler` as `compile`-scope | Client | **Excise `ConnectRepl` + drop `scala-compiler` from client distribution**. Split into a separate `spark-connect-repl` artifact | Blocks client compilation as written |
| 6 | Kryo + Java serializers (`KryoSerializer`, `JavaSerializer`) | Reflection-driven serialization; `ObjectInputStream.resolveClass` | Driver + Executor (closure shipping, shuffle, broadcast). Client only when user passes closures | `reflect-config.json` + `serialization-config.json` per user type. Tedious per-app | Tractable per-app |
| 7 | Closure cleaning (`ClosureCleaner`) | ASM bytecode rewriting at runtime to null outer references | Driver | **Never invoked on the Connect client's pure DataFrame/SQL path.** Required only for typed UDFs and `Dataset` map/flatMap | Dead-code-eliminable in pure SQL path |
| 8 | `Utils.classForName` + `MutableURLClassLoader` | Dynamic class loading from user-supplied JARs (`--jars`, `spark.jars`) | Driver + Executor | Fundamentally violates closed-world. Acceptable only if the native image is a fixed application binary with no plugin support | Blocks plugin-JAR feature |
| 9 | Hive UDFs + `IsolatedClientLoader` | Loads Hive metastore client into isolated classloader at runtime; reflects on Hive UDF classes | Driver (Hive integration only) | Exclude `spark-hive` module from native image | Feature-only |
| 10 | `UDFRegistration` + reflective UDF wrapping | Reflects on Scala `Function0..22` to extract argument types via `TypeTag` | Driver, Client | **Solved by same Scala 3 macro path as item 1** | Same as #1 |
| 11 | `ServiceLoader` for `SparkSessionExtensions`, `DataSourceRegister`, etc. | `META-INF/services` lookup | Driver | GraalVM auto-detects `ServiceLoader` since 21+; explicit metadata for non-default cases | Tractable |
| 12 | gRPC + Netty in Connect client | Heavy reflection + native code | Client | Mature: [`oracle/graalvm-reachability-metadata`](https://github.com/oracle/graalvm-reachability-metadata) ships configs for gRPC/Netty (community-maintained, active 2025) | Solved upstream |
| 13 | **Apache Arrow Java** (`arrow-vector`, `arrow-memory-netty`) | `sun.misc.Unsafe`, Netty buffer, reflection on vector types | Client + Executor | **No official Arrow GraalVM metadata exists as of late 2025.** Likely needs `reflect-config.json` per vector type + build-time init for `Unsafe` constants. **Gap to fill** | Tractable, requires effort |
| 14 | Log4j2, SLF4J binders, Jackson, Hadoop FS | Reflection-driven plugin discovery, `META-INF/services` | All | Log4j2 ships metadata since 2.17.x. Jackson has community metadata. Hadoop is a documented native-image pain point (Spring/Quarkus communities haven't fully cracked it) | Heavy lift for Hadoop |
| 15 | `SparkConf` system property scanning + `SparkSubmit` argument parsing | Reflective option discovery | Driver entry point | Tractable; mostly mechanical work | Low-effort |

Severity legend:
- **Blocks compilation**: build fails or runtime errors immediately on entry.
- **Blocks startup**: build succeeds but JVM dies on first method call.
- **Blocks specific feature**: rest of Spark works; specific feature unavailable.
- **Tractable**: workaround documented or solvable with metadata.

---

## §4. Scope reduction — Connect client is the realistic 12–18 month target

The Connect client's blocker surface is **dramatically smaller** than
the full driver. Confirmed by direct inspection of Spark master tree
(commit `146d9ce288b` at time of writing, post-4.1.x):

| Blocker | Affects full Driver | Affects Connect client |
|---|---:|---:|
| #1 Encoder + ScalaReflection | ✓ | ✓ (in `ArrowDeserializer`) |
| #2 Janino codegen | ✓ | — (server-side only) |
| #3 Unsafe / Tungsten | ✓ | ✓ (for some literal paths) |
| #4 Spark REPL | ✓ | — |
| #5 Connect Scala REPL | — | ✓ (excisable) |
| #6 Kryo / Java serializers | ✓ | only with typed closures |
| #7 ClosureCleaner | ✓ | only with typed `Dataset` ops |
| #8 Dynamic JAR loading | ✓ | — |
| #9 Hive UDFs | ✓ | — |
| #10 UDFRegistration reflection | ✓ | ✓ |
| #11 ServiceLoader | ✓ | partial |
| #12 gRPC/Netty | — | ✓ (already solved upstream) |
| #13 Arrow Java | ✓ | ✓ (gap to fill) |
| #14 Log4j2/Jackson/Hadoop | ✓ | partial |
| #15 SparkConf/Submit | ✓ | — |

**Of 15 total blockers, only 6 apply to the Connect client. Three of
those six are either already solved (encoder via this project's
macros), excisable (Ammonite REPL), or solved upstream (gRPC/Netty).**

That leaves three real items for a Connect-client-AOT effort:
- `ScalaReflection` in `ArrowDeserializer.scala` (apply the same
  macro infrastructure).
- Apache Arrow Java GraalVM metadata (author + upstream).
- `Platform`/`Unsafe` build-time initialization (verify call sites,
  add substitutions if needed).

The **full driver** AOT target needs Janino replacement (item 2),
which is essentially what ProtoCatalyst's executor work is doing on
a different axis. That's a multi-year effort. Connect client is
months.

---

## §5. Prior community signals

What exists in 2025–2026:

- **A November 2025 spark-dev thread** proposing a separate-repo
  Scala 3 Spark Connect client:
  [DISCUSS: Spark Connect Client for Scala 3 as Separate Project](http://www.mail-archive.com/dev@spark.apache.org/msg34514.html).
  AOT not yet mentioned, but it's the natural vehicle.
- [`nelvadas/spark-with-graalvm`](https://github.com/nelvadas/spark-with-graalvm)
  — benchmarks **GraalVM JIT** only (no native-image); WordCount only.
- [`radanalyticsio/spark-operator` issue #9](https://github.com/radanalyticsio/spark-operator/issues/9)
  — about native-image for the *operator* (a small Kubernetes
  controller), not Spark itself.

What's notably missing:

- **Zero SPARK-prefixed JIRAs** for native-image, AOT, or Leyden.
  Compared with Kafka (4 active tickets for [KIP-974 Native
  Broker](https://issues.apache.org/jira/browse/KAFKA-15444)) or
  Camel (multiple Quarkus-native-extension JIRAs), this is a
  significant absence.
- **No Quarkus/Micronaut/Helidon "Spark extension".** Those
  frameworks own application lifecycle, which `SparkContext` does
  not cede.
- **No `@TargetClass` substitution PRs or experimental branches**
  for Spark in any public location found during the survey.
- A 2020 spark-user thread asked the same question
  ([lists.apache.org/thread/...](https://lists.apache.org/thread/76qyotbl1pvj7jdspo4jxxryqg3vy02c))
  and got no positive responses.

**The community has not started.** This is both the opportunity and
the risk — opportunity because the design space is wide open, risk
because it means landing this work requires building community
consensus from scratch.

---

## §6. Prioritized roadmap

If the encoder (item 1 of the inventory) is in flight, the rest in
realistic execution order:

### Phase 1: AOT-clean Connect client (~12–18 months)

Six steps, each individually tractable:

1. **(In progress)** Replace `TypeTag`-based encoder derivation with
   Scala 3 macros. Same machinery solves `UDFRegistration` /
   `UserDefinedFunction` argument-type extraction (blockers #1 + #10).
2. **Replace `ScalaReflection` in `ArrowDeserializer.scala`** in
   `sql/connect/common`. The Connect client re-uses encoder
   reflection on the deserialization path. Same macro infrastructure
   applies — straightforward extension.
3. **Excise `scala-compiler` + Ammonite from the Connect client
   distribution.** Split `ConnectRepl.scala` and
   `AmmoniteClassFinder.scala` into a separate
   `spark-connect-repl` artifact. The pure client should depend
   only on `scala-library`, protobuf, gRPC, and Arrow. Verified
   from source: `sql/connect/client/jvm/pom.xml` declares
   `scala-compiler` as `compile` scope.
4. **Author Apache Arrow Java reachability metadata** (vector
   types, `BufferAllocator`, Netty memory pools) and upstream to
   `oracle/graalvm-reachability-metadata`. gRPC, Netty, Log4j2,
   and Jackson already have metadata; Arrow is the gap.
5. **Build-time initialize Spark's `Platform` /
   `UnsafeAlignedOffset`.** Verify `Unsafe.objectFieldOffset` call
   sites use constant fields (they do, on inspection of
   `common/unsafe`). Add a small `@TargetClass` substitution for
   the static initializer that probes `Unsafe.theUnsafe` if needed.
6. **Strip `ClosureCleaner` from the Connect client classpath** for
   the pure SQL/DataFrame path. Already only invoked for typed
   transformations; dead-code elimination should drop it once the
   `Dataset[T].map` Scala-closure path is either macro-rewritten or
   marked unsupported in the native-image build profile.

After phase 1: AOT-compiled `spark-connect-client-jvm` becomes a
shippable artifact. Sub-second cold start for typed query
submission. Lambda-friendly, edge-friendly, embedded-host-friendly.

### Phase 2: AOT-clean Driver (decade horizon — or never)

Requires solving item #2 (Janino → AOT-clean codegen). Options:

- **Replace Janino with a Scala 3 quoted-macro codegen path.** This
  is essentially what ProtoCatalyst's executor module is doing
  experimentally. Requires committing to compile-time codegen for
  all of Catalyst.
- **Accept interpreted-mode-only under native-image builds.**
  Massive perf regression; only viable for development tooling, not
  production execution.
- **Wait for a Catalyst rewrite.** The Spark community may
  eventually re-architect codegen for reasons unrelated to AOT;
  ride that wave.

**This is not a 2026–2027 effort. Probably not a 2028–2029 effort
either.** Realistic alternative: see Phase 3.

### Phase 3: Leyden adoption for full Spark (~6 months)

Independent of native-image work. Steps:

1. Add a Leyden training-run step to Spark's build (run a small
   representative job, emit an AOT cache).
2. Document JDK 24+ requirement for the Leyden-enabled build.
3. Ship the AOT cache alongside Spark distributions.
4. Measure cold-start delta on Spark-on-Kubernetes baseline.

**Expected outcome**: 30–50% Spark driver / executor cold start
reduction. Zero code changes. Lands faster than any native-image
work could.

---

## §7. Recommendations

For an interested Spark contributor:

### Short term — Leyden

Open a SPARK JIRA: **"Investigate Project Leyden adoption for Spark
driver and executor cold start"**. Reference:

- [JEP 483](https://openjdk.org/jeps/483) (shipped in JDK 24)
- [JEP 514/515](https://openjdk.org/projects/leyden/) (JDK 25)
- [Spring Boot Leyden integration](https://github.com/spring-projects/spring-tools/wiki/Leyden-AOT-Cache-Usage-and-Configuration)

A draft training-run Maven plugin + measured cold-start numbers
would make this concrete in 1–2 weeks of work. Spark community
adoption would be a multi-month process but the technical lift is
small.

### Medium term — Connect client AOT

Engage the existing
[Nov 2025 Scala 3 Connect Client dev list thread](http://www.mail-archive.com/dev@spark.apache.org/msg34514.html).
Position the AOT-clean angle as a follow-up benefit of the Scala 3
migration: once the macro-derived encoder lands (this project's
work), the remaining 5 blockers for the Connect client are
tractable in months, not years.

Open a companion SPARK JIRA: **"AOT-clean Spark Connect client
roadmap"**. Use the §6 phase 1 list as the proposed action items.

### Long term — full driver

Park it. The Janino blocker isn't solvable without a Catalyst
rewrite. Pursue alternative directions (Leyden for cold-start,
ProtoCatalyst-like external execution for AOT-native processing)
rather than trying to AOT-compile Catalyst itself.

---

## §8. Caveats and what we couldn't verify

- **The Calcite Janino native-image discussion** referenced in
  secondary sources could not be directly fetched
  (markmail.org connection refused during research). The blocker
  itself is well-documented elsewhere; the specific Calcite-side
  thread is second-hand.
- **No Apache Arrow Java GraalVM metadata** was found in
  `oracle/graalvm-reachability-metadata`. Absence of evidence
  isn't evidence of absence; before committing to item 4 of the
  phase 1 roadmap, the contributor should directly search the
  reachability-metadata repo.
- **No `@TargetClass` substitution PRs or experimental branches**
  for Spark were found in any public location searched. The
  community may have private exploration this survey didn't surface.
- **Leyden's full perf characterization for Spark workloads has
  not been measured.** The 30–50% number is from Spring Boot
  benchmarks; Spark may see more or less. A small Leyden experiment
  would yield real data.

---

## Sources

- [Apache Spark master tree](https://github.com/apache/spark) (post-4.1, commit `146d9ce288b`)
- [GraalVM Native Image Compatibility Guide](https://www.graalvm.org/latest/reference-manual/native-image/metadata/Compatibility/)
- [GraalVM Reachability Metadata Repository](https://github.com/oracle/graalvm-reachability-metadata)
- [Using Unsafe safely in GraalVM Native Image (Red Hat)](https://developers.redhat.com/articles/2022/05/09/using-unsafe-safely-graalvm-native-image)
- [SPARK-44236 — factoryMode=NO_CODEGEN doesn't fully disable WSCG](https://issues.apache.org/jira/browse/SPARK-44236)
- [SPARK-39675 — codegen.factoryMode marked internal](https://issues.apache.org/jira/browse/SPARK-39675)
- [Project Leyden — OpenJDK](https://openjdk.org/projects/leyden/)
- [JEP 483: Ahead-of-Time Class Loading & Linking (JDK 24)](https://openjdk.org/jeps/483)
- [Inside.java: Assembling Project Leyden (Oct 2025)](https://inside.java/2025/10/21/jvmls-assembling-project-leyden/)
- [Inside.java: Run AOT Cache (Jan 2026)](https://inside.java/2026/01/09/run-aot-cache/)
- [Spring Boot Leyden AOT Cache wiki](https://github.com/spring-projects/spring-tools/wiki/Leyden-AOT-Cache-Usage-and-Configuration)
- [KAFKA-15444 — KIP-974 Native Kafka Broker (for contrast)](https://issues.apache.org/jira/browse/KAFKA-15444)
- [DISCUSS: Spark Connect Client for Scala 3 as Separate Project (Nov 2025)](http://www.mail-archive.com/dev@spark.apache.org/msg34514.html)
- [2020 spark-user thread on GraalVM Native Image](https://lists.apache.org/thread/76qyotbl1pvj7jdspo4jxxryqg3vy02c)
- [2020 graalvm-users post (Ivo Knabe)](https://oss.oracle.com/pipermail/graalvm-users/2020-June/000230.html)
- [`oracle/graal` issue #2694 — Unsafe.staticFieldOffset](https://github.com/oracle/graal/issues/2694)

---

*Document prepared as a companion to [`REPORT.md`](REPORT.md). The
encoder work referenced throughout lives in
[`encoder-spark/src/main/scala/protocatalyst/encoder/spark/`](../encoder-spark/src/main/scala/protocatalyst/encoder/spark/).*
