# Upstreaming brief — `SparkSession.builder()` on Scala 3

Working doc for taking the session-builder problem to Apache Spark. Written after verifying the
current upstream state, which changed materially on **2026-08-23**: the de-reflection itself has
already landed on master, so most of what this doc was going to ask for is done.

**Bottom line:** we target master; nothing needs backporting; exactly one small issue is still worth
filing (§4). Read §1 before filing anything.

---

## §1. Upstream state (verified 2026-08-23)

| Piece | master (5.0.0-SNAPSHOT) | branch-4.1 (4.1.4-SNAPSHOT) |
|---|---|---|
| `SparkSession.lookupCompanion` uses `scala.reflect.runtime.currentMirror` | **fixed** — SPARK-58169 | **still reflective** |
| `ScalaReflection` eager `val universe` + `TermName` mangling | **fixed** — SPARK-57548 | **still eager** |
| `DEFAULT_COMPANION` swallows the failure cause | **unfixed** | unfixed |

Landed on master, both on 2026-08-23, both by Emil Ejbyfeldt:

- **[SPARK-58169]** *Use java reflection for finding the SparkSession companions*
  ([PR #57302](https://github.com/apache/spark/pull/57302), `3c9ffb687997`). `lookupCompanion`
  becomes `SparkClassUtils.getCompanionObject(name)`, whose body is
  `classForName(name + "$").getField("MODULE$").get(null)` — the same fix this repo implemented
  independently in `spark-reflection-patch`. Explicitly motivated by scala/scala3#25896.
- **[SPARK-57548]** *Avoid eager `scala.reflect.runtime.universe` initialization in ScalaReflection*
  ([PR #57303](https://github.com/apache/spark/pull/57303), `7df3ac405cf7`). This is the 2-line
  down-payment from [MIGRATION.md](MIGRATION.md) Step 3, line for line: `val universe` → `lazy val`,
  and `TermName(fieldName).encodedName.toString` → `scala.reflect.NameTransformer.encode(fieldName)`.

**Do not file the de-reflection as new work.** It is done. One issue remains worth filing (§4).

Related Scala-3 work now in flight upstream, useful for context when writing to dev@:
[SPARK-58166] `TypeTag` → `ClassTag` on `PhysicalDataType` (merged), [SPARK-58168] `Array[Nothing]`
in `Flatten` (merged), [SPARK-58200] *Add a scala-3 build profile* ([PR #57343](https://github.com/apache/spark/pull/57343), **open**).

---

## §2. The problem, for the record and for the ticket

### Reproduction

From a **Scala 3.8+** process against Spark **4.1.x** (Scala 2.13 jars, `CrossVersion.for3Use2_13`):

```scala
val spark = SparkSession.builder().master("local[2]").getOrCreate()
```

Observed:

```
java.lang.IllegalStateException: Cannot find a SparkSession implementation on the Classpath.
  at org.apache.spark.sql.SparkSession$.$anonfun$DEFAULT_COMPANION$4(SparkSession.scala:822)
  at scala.util.Failure.getOrElse(Try.scala:229)
  at org.apache.spark.sql.SparkSession$.org$apache$spark$sql$SparkSession$$DEFAULT_COMPANION(...)
  at org.apache.spark.sql.SparkSession$Builder.<init>(SparkSession.scala:854)
  at org.apache.spark.sql.SparkSession$.builder(SparkSession.scala:833)
```

The message is false: `org.apache.spark.sql.classic.SparkSession` is on the classpath and loads
fine. Verified directly — `Class.forName` succeeds for both the class and its `$` companion, and
reading `MODULE$` returns a valid `SparkSessionCompanion`.

### Root cause

`sql/api/.../SparkSession.scala` (4.1.x):

```scala
private[this] def lookupCompanion(name: String): SparkSessionCompanion = {
  val cls = SparkClassUtils.classForName(name)
  val mirror = scala.reflect.runtime.currentMirror   // <-- fails on Scala 3.8
  val module = mirror.classSymbol(cls).companion.asModule
  mirror.reflectModule(module).instance.asInstanceOf[SparkSessionCompanion]
}
```

Initializing the Scala 2 runtime universe against the Scala 3 standard library throws
`scala.reflect.internal.FatalError: class Array does not have a member apply`
([scala/scala3#25896](https://github.com/scala/scala3/issues/25896), open, high priority, names
Spark). Reproduced directly:

```
FAILED scala.reflect.internal.FatalError: class Array does not have a member apply
  at scala.reflect.internal.Definitions$DefinitionsClass.fatalMissingSymbol(Definitions.scala:1426)
  at ...DefinitionsClass.ArrayModule_overloadedApply$lzycompute(Definitions.scala:512)
```

Both candidates (Classic and Connect) fail the same way, so `DEFAULT_COMPANION` exhausts its
`orElse` and throws the misleading message.

This is upstream of anything encoder-related: no `Dataset`, no typed operation, no `Encoder` is
involved. Any Scala 3 program calling `SparkSession.builder()` fails here first.

---

## §3. Position — target master, do not ask for backports

**Decision: this project targets the latest Spark (master, 5.0.0-SNAPSHOT), not 4.1.x.**

Both fixes are already there. Asking for a 4.1.x backport would mean spending credibility with
maintainers on a release-management argument — for changes that are, from their side, already done —
right before asking them to consider something much larger (compile-time encoder derivation). The
scarce resource is reviewer attention on the derivation proposal, not access to a released artifact.

What follows from targeting master:

- **The de-reflection is off the ask entirely.** [REPORT §11](REPORT.md) is rescoped: what remains is
  the derivation (one file, plus its call site in `ExpressionEncoder.apply[T]()`) and the `TypeTag`
  bounds that Scala 3 forces regardless. The walls that used to precede both are gone.
- **`spark-reflection-patch` becomes a local build accommodation, not a proposal.** It exists so this
  repo can drive *released* 4.1.2 from Scala 3. It should be described that way from now on — not as
  "the patch Spark should take", which is what it was before 2026-08-23.
- **Open follow-up worth doing:** build this project against a master snapshot and see whether a
  Scala 3 driver runs end-to-end with **no** local patch. That is the natural validation of the two
  upstream fixes and would let the patch module be retired. It is untested here — the two documented
  walls are fixed, but [SPARK-58166] and [SPARK-58168] landing alongside them suggests others were
  found, and this project has not swept for more. Do not claim master is clear until it is run.

## §4. The one thing left to file — `DEFAULT_COMPANION` discards the cause

The only genuinely open issue here, and a good one: **not** Scala-3-specific, still present on
master after SPARK-58169, and independently useful to anyone debugging a session that will not
start.

```scala
private def DEFAULT_COMPANION =
  Try(CLASSIC_COMPANION).orElse(Try(CONNECT_COMPANION)).getOrElse {
    throw new IllegalStateException(
      "Cannot find a SparkSession implementation on the Classpath.")
  }
```

`Try` catches everything `NonFatal` — and `scala.reflect.internal.FatalError` extends `Exception`, so
despite the name it *is* `NonFatal` and gets swallowed. Any failure inside the lookup is reported as
"not on the classpath": a `LinkageError`-adjacent `ExceptionInInitializerError`, a
`NoClassDefFoundError` from a partial shading job, a security-manager denial, a class-loader
visibility problem, or (pre-58169) the Scala 3 mirror failure. All of them produce the same message,
which sends the reporter to inspect a classpath that is fine.

**Proposed change** — keep the message, attach the cause:

```scala
private def DEFAULT_COMPANION =
  Try(CLASSIC_COMPANION).orElse(Try(CONNECT_COMPANION)).recover { case cause =>
    throw new IllegalStateException(
      "Cannot find a SparkSession implementation on the Classpath.", cause)
  }.get
```

(Or keep `getOrElse` and carry the `Failure`'s exception through; the point is only that the cause
survives. A reviewer may prefer chaining both failures, since the Connect attempt's cause is the one
retained above — worth raising in the PR description rather than deciding unilaterally.)

**Why it is easy to accept.** No behaviour change on the success path, no new dependency, the
existing message is preserved, and it converts a whole class of misdiagnosed reports into
self-service ones. It also has a genuine bug report attached: this is exactly how the Scala 3 failure
presented, and the misdirection cost real debugging time.

**JIRA-ready summary:** *`SparkSession.builder()` reports "Cannot find a SparkSession implementation
on the Classpath" for any lookup failure, discarding the actual cause.*

---

## §5. Scope discipline

Things to keep out of this ticket, because mixing them in is what turns a three-line fix into a
6-month thread:

- **The encoder derivation replacement.** That is the substantial proposal
  ([REPORT.md](REPORT.md) §11, [MIGRATION.md](MIGRATION.md)) and it is unrelated to session
  creation. Nothing upstream currently does compile-time encoder derivation — verified — so it
  remains this project's contribution to make, on its own thread.
- **Anything about ProtoCatalyst.** The fix in §4 requires adopting nothing from this repo; it is a
  three-line diagnostics change that stands on its own.
- **Performance.** These changes are not performance work. See [REPORT §10b](REPORT.md) for why the
  encoder story should not be pitched on runtime either.

## §6. Coordination note

Emil Ejbyfeldt ([@eejbyfeldt](https://github.com/eejbyfeldt)) is actively working this exact track —
four Scala-3-enablement PRs merged on 2026-08-23, plus the open scala-3 build profile. He arrived at
the same `MODULE$` fix and the same 2-line `ScalaReflection` change this project did, independently,
which is a useful corroboration of the analysis in [REPORT §3](REPORT.md)/§3b.

The practical implication: the plumbing half of this project's migration path is being cleared by
someone already inside the review process. Reaching out before filing is likely worth more than
filing — both to avoid duplicate tickets and because the derivation work needs exactly the kind of
upstream advocate who has already landed four related patches.

---

## Appendix — verification commands

```bash
# Which upstream versions carry the fix
gh api -X GET repos/apache/spark/commits \
  -f path='sql/api/src/main/scala/org/apache/spark/sql/SparkSession.scala' \
  --jq '.[] | "\(.sha[0:12]) \(.commit.author.date[0:10]) \(.commit.message | split("\n")[0])"'

# The two commits referenced above
gh api repos/apache/spark/commits/3c9ffb687997 --jq '.files[].patch'   # SPARK-58169
gh api repos/apache/spark/commits/7df3ac405cf7 --jq '.files[].patch'   # SPARK-57548

# Confirm branch-4.1 is still affected
curl -s https://raw.githubusercontent.com/apache/spark/branch-4.1/sql/api/src/main/scala/org/apache/spark/sql/SparkSession.scala \
  | grep -A4 'def lookupCompanion'
```

Local reproduction of both the failure and the fix:

```bash
sbt 'encoderSpark/testOnly *SparkSessionWallSpec'   # stock builder() works, Scala 3, with the patch
```

[SPARK-57548]: https://github.com/apache/spark/pull/57303
[SPARK-58166]: https://github.com/apache/spark/pull/57296
[SPARK-58168]: https://github.com/apache/spark/pull/57299
[SPARK-58169]: https://github.com/apache/spark/pull/57302
[SPARK-58200]: https://github.com/apache/spark/pull/57343
