# Extending the ES|QL Simulator

So you want to teach the simulator about a new function, command, or data type. This is the contributor checklist.

If you just want to **run** the tests, see [`README.md`](README.md).

## Mental model

The simulator finds bugs by closing this loop:

```
SimSchemaGenerator   ─┐
                      ├──▶ random schema + data
SimDataGenerator     ─┘                              ┌─▶ Simulator.simulate(plan)         ─┐
                                                     │                                     ├─▶ compare
LogicalPlanGenerator ────▶ random LogicalPlan tree ──┤                                     │
                                                     └─▶ LogicalPlanPrinter.print(plan) ───┘
                                                                       │
                                                                       ▼
                                                                  real ES cluster
```

For coverage of a new feature to be meaningful, **every** arrow has to know about it:

- the **generator** has to be able to produce it,
- the **simulator** has to be able to evaluate it,
- the **printer** has to round-trip it back into a parseable ES|QL string.

If any one is missing, the property test silently skips your feature.

## File map

The framework lives in two places:

```
x-pack/plugin/esql/qa/server/
├── src/main/java/org/elasticsearch/xpack/esql/qa/simulator/
│   ├── Simulator.java           ← evaluates a LogicalPlan over data
│   ├── Result.java              ← columnar result type + expression evaluator
│   ├── SimSchema.java           ← (indexName, columns)
│   ├── SimSchemaGenerator.java  ← random schema (column names + types)
│   ├── SimDataGenerator.java    ← random rows (with NULL_PROBABILITY = 0.2)
│   ├── LogicalPlanGenerator.java ← random plan tree
│   ├── LogicalPlanPrinter.java  ← LogicalPlan → ES|QL string
│   ├── SimBug.java              ← named bugs that can be injected for meta-testing
│   └── GenUtils.java            ← small generation helpers
├── src/test/java/.../qa/simulator/
│   └── SimulatorTests.java      ← unit tests for the simulator (no cluster)
└── simulator/src/javaRestTest/java/.../qa/simulator/
    ├── SimulatorPropertyIT.java   ← the property test + shrinker
    ├── ForcedShrinkerIT.java      ← deterministic shrinker
    ├── GeneratorInvariantTests.java
    ├── ShrinkingValidityTests.java
    ├── SimulatorBugTests.java     ← meta-tests using SimBug
    └── SimulatorTestUtils.java
```

The split is because `Simulator`, `LogicalPlanGenerator`, etc. are reused by `SimulatorTests` (unit tests, in `qa/server/src/test`), so they have to live in `qa/server/src/main`. The integration tests live in their own subproject (`qa/server/simulator`) so the long cluster-bringup is only paid when you ask for it.

## Recipe 1 — adding a scalar function or operator

Example: you want coverage for `COALESCE`, or a new arithmetic operator like `**`.

- [ ] **Generator** — `LogicalPlanGenerator.java`
  - For arithmetic/comparison operators, extend the operator pool used in `generateExpression` / `generateNumericLiteral`.
  - For string functions, extend `generateKeywordExpression`.
  - For functions over a new type pair (e.g., a function on dates), you may need a new `generate*Expression` helper.
- [ ] **Simulator** — `Result.java::evaluate`
  - This is where expression evaluation lives. Add a new `case` to the `switch (expr)` that handles the new `Expression` subclass. For binary operators, follow the `ArithmeticOperation` pattern — promote types, propagate nulls, return `Long` / `Double` / `BytesRef` consistent with ES.
- [ ] **Printer** — `LogicalPlanPrinter.java`
  - Add a branch that emits the function as `FOO(arg1, arg2)` (or as `arg1 OP arg2` for operators, with proper parenthesization — `LogicalPlanPrinter` parens any non-trivial subexpression to avoid precedence bugs; do the same).
- [ ] **Unit tests** — `SimulatorTests.java`
  - Add a focused test that builds a plan with the new expression and asserts the result columns.
- [ ] **(Optional) bug injection** — `SimBug.java` + `SimulatorBugTests.java`
  - For the more interesting operators, add a named bug variant (e.g. `MY_OP_INVERTED`) and a deterministic test in `SimulatorBugTests` that verifies the bugged simulator diverges from the correct one. This is your safety net: it exercises the full plan → simulate → compare path even before the property test catches anything.

## Recipe 2 — adding a command

Example: `RENAME`, `DISSECT`, or any new top-level pipeline command.

- [ ] **Generator** — `LogicalPlanGenerator.java`
  - In `generateRaw`, add the command to the `options` list as another `Supplier<LogicalPlan>`. Make sure it can only be inserted in valid positions (e.g. some commands require a non-empty schema; some can't follow others).
  - If your command introduces new attributes (like `EVAL` does), `resolveReferences` may need to know about it.
- [ ] **Simulator** — `Simulator.java`
  - Add a `case YourCommand cmd -> visit(cmd);` to the top-level `switch` in `simulate()`.
  - Implement `visit(YourCommand)`: call `simulate(cmd.child())` first to get the child `Result`, then transform it.
- [ ] **Printer** — `LogicalPlanPrinter.java`
  - Print the new command as `| YOUR_COMMAND ...`.
- [ ] **Unit tests** — `SimulatorTests.java`
  - At minimum: a test with the command alone over a known input, and a test stacking it with another command.
- [ ] **Shrinkers** — `SimulatorPropertyIT.java::doShrink` and `ForcedShrinkerIT.java`
  - Decide whether the new command is **safe to remove from the middle** of a pipeline (`isSafeToRemoveFromMiddle`). Most projection/filter-style commands are; commands that change row count (`LIMIT`, `STATS`) are not.
  - If your command has an internal list (like `EVAL`'s fields or `STATS`'s aggregates), add a "remove individual sub-element" shrink strategy — see how `Eval` and `Aggregate` are handled.
- [ ] **(Optional) bug injection** — see Recipe 1.

## Recipe 3 — adding a data type

Example: support for `BOOLEAN`, `DATETIME`, `IP`, etc.

- [ ] **Schema generator** — `SimSchemaGenerator.java`
  - Add the type to `TYPE_POOL`.
  - If it's numeric-ish, add it to `NUMERIC_TYPES` here and in `LogicalPlanGenerator`.
- [ ] **Data generator** — `SimDataGenerator.java`
  - Add a `case` to `generateValue` returning a Java value matching ES's wire type (e.g., `Long` for `DATETIME`, `String` for `IP`).
  - Update the null-fill defaults in `SimulatorPropertyIT::doShrink` (Strategy 7).
- [ ] **Plan generator** — `LogicalPlanGenerator.java`
  - Extend `generateLeaf` and `generateLiteralValue` to know about the new type.
  - Decide which expressions/functions over the type the generator should produce (e.g., comparisons on `BOOLEAN` are weird).
- [ ] **Simulator / evaluator** — `Result.java::evaluate`
  - Update arithmetic / comparison branches (`toLong`, `toDouble`, ...) so the new type is normalized correctly. Match ES's type-coercion rules.
  - Update `normalizeObject` if the new type needs canonicalization (e.g., `BytesRef` ↔ `String`).
- [ ] **Printer** — `LogicalPlanPrinter.java`
  - Make sure literals of the new type print as valid ES|QL syntax (e.g., `true`, `"2024-01-01T00:00:00Z"::DATETIME`, ...).
- [ ] **Index mapping** — `SimulatorPropertyIT.java`
  - The test creates an ES index from `SimSchema`. The `createIndex` / mapping code has to map the new `DataType` to the right ES field type.
- [ ] **JSON serialization** — `SimulatorPropertyIT.java`
  - The bulk indexing path needs to serialize values of the new type to JSON in a form ES accepts.
- [ ] **Result comparison** — `SimulatorPropertyIT.java`
  - The comparison logic normalizes types (e.g., Integer/Long → Long). If your new type can come back from ES in multiple representations, normalize them too.

## Don't forget

- **Run `GeneratorInvariantTests` first.** It's cluster-free and catches structural bugs (missing references, non-idempotent `resolveReferences`, unparseable printed plans) in seconds.
- **Add a `SimBug` for new commands or non-trivial operators.** It pays for itself the first time the printer regresses or the simulator drifts.
- **Mirror new shrink strategies into `ForcedShrinkerIT`.** The forced shrinker is what reviewers will run on a captured failure; if a strategy only exists in `SimulatorPropertyIT::doShrink`, the captured case won't shrink as far.
- **Keep `Simulator` and `Result` short.** They're the reference implementation. If you find yourself reaching for clever optimizations or large helper classes, you've probably crossed into territory where the simulator is no longer obviously correct, which defeats the purpose.

## How to verify

In rough order of cost / signal:

```bash
./gradlew :x-pack:plugin:esql:qa:server:simulator:compileJavaRestTestJava
./gradlew :x-pack:plugin:esql:qa:server:test \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.SimulatorTests"
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.GeneratorInvariantTests"
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.SimulatorBugTests"
./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest \
  --tests "org.elasticsearch.xpack.esql.qa.simulator.SimulatorPropertyIT"
```

The first three are quick. `SimulatorPropertyIT` brings up a real cluster — only run it once the others are green, otherwise you'll spend most of your iteration time waiting for cluster bootstrap.
