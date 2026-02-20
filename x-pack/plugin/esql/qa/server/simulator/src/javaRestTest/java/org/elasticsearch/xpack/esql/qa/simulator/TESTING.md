# ES|QL Simulator Tests

## Test classes

| Class | Runner | Cluster? | Description |
|---|---|---|---|
| `SimulatorPropertyIT` | junit-quickcheck | Yes | Main property test: generates random plans and data, runs through both the simulator and a real ES cluster, and compares results. |
| `ForcedShrinkerIT` | ESRestTestCase (JUnit 4) | Yes | Deterministic shrinking tool: takes a known-failing query+data via system properties and greedily minimizes them to a minimal reproduction. |
| `GeneratorInvariantTests` | junit-quickcheck | No | Structural invariants: all column references exist, `resolveReferences` is idempotent, every plan prints to parseable ES\|QL, no plan node has zero output columns. |
| `ShrinkingValidityTests` | junit-quickcheck | No | Verifies that generated raw plans have no stale NameId references. |
| `SimulatorBugTests` | JUnit 4 | No | Deterministic meta-tests: each `@Test` injects a specific `SimBug` and verifies the correct vs bugged simulators diverge. |

## Running tests

### All simulator tests

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest
```

### A single test class

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.SimulatorPropertyIT"
```

### Compile-only check (no cluster, fast feedback)

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:compileJavaRestTestJava
```

## System properties

### Generator tuning

| Property | Default | Description |
|---|---|---|
| `simulator.planDepth` | `5` | Maximum number of pipeline stages (EVAL, WHERE, SORT, etc.) layered on top of the source node. |
| `simulator.exprDepth` | `2` | Maximum recursion depth for expression trees (arithmetic, string functions). Not forwarded from Gradle by default; set via `-Dsimulator.exprDepth=N` in the JVM args. |

Example:

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  -Dsimulator.planDepth=10
```

### ForcedShrinkerIT input

The forced shrinker skips silently when no input is provided. Supply a failing query and its dataset via either inline properties or file paths.

| Property | Description |
|---|---|
| `simulator.query` | The ES\|QL query string (e.g., `FROM idx \| EVAL z = x + 1`). |
| `simulator.data` | JSON object with `index`, `schema`, and `rows` fields (see format below). |
| `simulator.queryFile` | Path to a file containing the query (avoids shell quoting issues). |
| `simulator.dataFile` | Path to a file containing the data JSON. |
| `simulator.failureMode` | `crash` (default) checks for HTTP 500; `mismatch` compares ES vs simulator results. |

File-based input is recommended on Windows to avoid JSON quoting problems.

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

Absent keys in a row represent null fields.

#### Example: run the forced shrinker

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.ForcedShrinkerIT" \
  -Dsimulator.queryFile=/tmp/query.txt \
  -Dsimulator.dataFile=/tmp/data.json \
  -Dsimulator.failureMode=mismatch
```

## Seed replay

When `SimulatorPropertyIT` finds a failure, junit-quickcheck prints the seed:

```
Seeds for reproduction: [2434787385628699876]
```

Replay via the `-Dsimulator.seed` system property:

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.SimulatorPropertyIT" \
  -Dsimulator.seed=2434787385628699876
```

This overrides the random source in the generator with a `Random` seeded by the given value, reproducing the same test case on every trial. All 50 trials will generate the identical case, so the failure appears immediately.

## Logging

The simulator tests use Log4j2 loggers with `@TestLogging` annotations. Since junit-quickcheck doesn't use the ES test runner (`RandomizedRunner`), the `@TestLogging` annotation is processed manually by `SimulatorTestUtils.applyTestLogging()`.

To change log levels, edit the annotation on the test class:

```java
@TestLogging(value = "org.elasticsearch.xpack.esql:TRACE", reason = "debug")
```

`SimulatorPropertyIT` logs every generated trial and every shrink step at INFO level.

## Shrinking

### Automatic shrinking (SimulatorPropertyIT)

When a trial fails, junit-quickcheck invokes `doShrink()` repeatedly to find a smaller failing case. The current strategies are:

1. **Drop outermost pipeline layer** — removes the top command (EVAL, WHERE, SORT, etc.).
2. **Remove individual EVAL fields** — if the outermost EVAL has multiple fields, tries keeping N-1.
3. **Simplify expressions** — replaces each expression with its sub-expressions (e.g., `x * (6 + x)` tries `x`, `6 + x`, `6`, `x`).
4. **Remove any data row** — tries removing each row individually.
5. **Fill null fields** — replaces absent (null) fields with a simple non-null value (`1` for INTEGER, `"foo"` for KEYWORD).

Shrinking is configured via `@Property`:

| Parameter | Default | Current value | Description |
|---|---|---|---|
| `maxShrinkDepth` | 20 | 100 | Maximum number of shrink iterations. |
| `maxShrinkTime` | 60000 ms | 120000 ms | Maximum wall-clock time for shrinking. |

### Manual shrinking (ForcedShrinkerIT)

For deeper shrinking beyond what the automatic shrinker achieves, copy the failing query and data into files and run the forced shrinker (see above). It performs five greedy shrinking steps in a loop:

1. Remove pipeline stages (including from the middle).
2. Remove data rows.
3. Remove data columns.
4. Shrink expressions to sub-expressions.
5. Simplify integer literals to `1`.
