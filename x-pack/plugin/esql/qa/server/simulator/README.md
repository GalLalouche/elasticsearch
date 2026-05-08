# ES|QL Simulator — Property Tests

A property-based test that hunts for ES|QL engine bugs.

The idea: generate a random ES|QL plan and a random dataset, then run them through **two** evaluators —

1. a **simulator** — a reference implementation of ES|QL written in plain Java for-loops (`Simulator.java`),
2. a **real Elasticsearch cluster** — spun up by the test for each suite.

If the results disagree, ES has a bug (or, sometimes, the simulator does — but the simulator is short and easy to audit). When a divergence is found, the framework **shrinks** the failing case down to a minimal plan + minimal data that still reproduces the issue.

If you want to **extend** the framework — add support for a new function, a new command, or a new data type — read [`CONTRIBUTING.md`](CONTRIBUTING.md) instead.

## Test classes

| Class | Runner | Cluster? | What it does |
|---|---|---|---|
| `SimulatorPropertyIT` | `JUnitQuickcheck` | yes | Main property test: random plan + data, simulator vs real ES, with shrinking. |
| `ForcedShrinkerIT` | `ESRestTestCase` (JUnit 4) | yes | Deterministic shrinker: takes a known-failing query+data via system properties and greedily minimizes them. |
| `GeneratorInvariantTests` | `JUnitQuickcheck` | no | Structural invariants on generated plans: all references resolve, `resolveReferences` is idempotent, every plan prints to parseable ES\|QL, no node has zero output columns. |
| `ShrinkingValidityTests` | `JUnitQuickcheck` | no | Verifies generated raw plans don't contain stale `NameId` references. |
| `SimulatorBugTests` | JUnit 4 | no | Deterministic meta-tests: each `@Test` injects a specific `SimBug` and asserts the correct vs bugged simulators diverge. |

The `*IT` classes need a cluster and live in `javaRestTest`. The other three don't need a cluster, run quickly, and are useful for fast local iteration.

## Running

All commands assume you're in the repo root.

### All simulator tests (slow — spins up a real cluster)

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest
```

### A single test class

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.SimulatorPropertyIT"
```

### Compile-only check (fast feedback, no cluster)

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:compileJavaRestTestJava
```

### The cluster-free tests (fastest meaningful signal)

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.GeneratorInvariantTests"
```

## System properties

### Generator tuning

| Property | Default | Description |
|---|---|---|
| `simulator.planDepth` | `5` | Max number of pipeline stages stacked on the source (EVAL, WHERE, SORT, ...). Forwarded by the build script. |
| `simulator.exprDepth` | `2` | Max recursion depth for expression trees (arithmetic, string functions). Set via `-Dsimulator.exprDepth=N` (not forwarded by Gradle by default). |

Example:

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  -Dsimulator.planDepth=10
```

### Seed replay

When `SimulatorPropertyIT` finds a failure, junit-quickcheck prints the seed:

```
Seeds for reproduction: [2434787385628699876]
```

Replay via `-Dsimulator.seed`:

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.SimulatorPropertyIT" \
  -Dsimulator.seed=2434787385628699876
```

This pins the random source so every trial in the run produces the same case — the failure surfaces on trial 1.

### `ForcedShrinkerIT` input

The forced shrinker is a deterministic minimizer. It silently skips when no input is provided. Supply a failing query and its dataset via inline properties or files:

| Property | Description |
|---|---|
| `simulator.query` | The ES\|QL query string. |
| `simulator.queryJson` | Alias for `simulator.query`. |
| `simulator.dataJson` | JSON object with `index`, `schema`, `rows` (format below). |
| `simulator.queryFile` | Path to a file containing the query (avoids shell quoting issues). |
| `simulator.dataFile` | Path to a file containing the data JSON. |
| `simulator.failureMode` | `crash` (default) — checks for HTTP 500. `mismatch` — compares ES vs simulator results. |

File-based input is recommended on Windows to avoid JSON quoting headaches.

#### Data JSON format

```json
{
  "index": "sim_test",
  "schema": [
    {"name": "x", "type": "integer"},
    {"name": "y", "type": "keyword"}
  ],
  "rows": [
    {"x": 1, "y": "foo"},
    {"x": 2}
  ]
}
```

Absent keys in a row mean null fields.

#### Example: run the forced shrinker

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.ForcedShrinkerIT" \
  -Dsimulator.queryFile=/tmp/query.txt \
  -Dsimulator.dataFile=/tmp/data.json \
  -Dsimulator.failureMode=mismatch
```

## Shrinking

### Automatic (in `SimulatorPropertyIT`)

When a trial fails, junit-quickcheck repeatedly invokes `doShrink()` to find a smaller failing case. Strategies, in order:

1. Drop outermost pipeline layer.
2. Remove a safe operator from the middle of the pipeline.
3. Remove individual EVAL fields (keep N−1).
4. Remove individual aggregates from STATS / INLINE STATS.
5. Simplify expressions to sub-expressions (e.g., `x * (6 + x)` tries `x`, `6 + x`, `6`, `x`).
6. Simplify sub-expressions to constant literals.
7. Remove a data row.
8. Fill a null field with a simple non-null value (`1`, `"foo"`, ...).
9. Shrink numeric values (data and literals) toward smaller magnitudes.
10. Remove unused schema columns.

Shrinking budget is configured via `@Property(maxShrinkDepth = ..., maxShrinkTime = ...)` on the test method.

### Manual (`ForcedShrinkerIT`)

For deeper shrinking than the automatic pass achieves, copy the failing query and data into files and run `ForcedShrinkerIT`. It loops over five greedy steps:

1. Remove pipeline stages (including from the middle).
2. Remove data rows.
3. Remove data columns.
4. Shrink expressions to sub-expressions.
5. Simplify integer literals to `1`.

## Logging

The simulator tests use Log4j2 with `@TestLogging`. Because junit-quickcheck doesn't use the ES `RandomizedRunner`, `@TestLogging` is processed manually in `SimulatorTestUtils.applyTestLogging()`.

To change levels, edit the annotation on the class:

```java
@TestLogging(value = "org.elasticsearch.xpack.esql:TRACE", reason = "debug")
```

`SimulatorPropertyIT` logs every generated trial and every shrink step at `INFO`.
