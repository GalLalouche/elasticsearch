# Simulator TODO

## 1. jqwik integration
- [x] Use jqwik's `Arbitrary` API for generation (`LogicalPlanGenerator`)
- [x] Replace custom minimal facades (`jqwik/` package) with real `jqwik-engine` dependency (add to `verification-metadata.xml`)
- [x] Decouple generating/shrinking code from jqwik so it isn't overly-coupled to the framework
- [x] Replace jqwik with [junit-quickcheck](https://github.com/pholser/junit-quickcheck)

## 2. LogicalPlan generator
- [x] Generate `FROM` (via `UnresolvedRelation`)
- [x] Generate `KEEP` (random non-empty subset of columns)
- [x] Generate `DROP` (random non-empty subset of columns)
- [x] Generate `EVAL`
- [x] Generate multi-field `EVAL` (e.g., `EVAL z = a + b, w = a * 2`)
- [x] Generate nested arithmetic expressions (e.g., `(a + b) * c`; depth configurable via `simulator.exprDepth`)
- [x] Generate `WHERE` / `FILTER`
- [x] Generate more complex `WHERE` expressions (arithmetic in conditions, e.g., `WHERE a + b > 5`, `WHERE a * 2 < c`)
- [x] Generate `SORT` + `LIMIT` (paired — ES requires LIMIT after SORT)
- [x] Generate expressions in `SORT` (e.g., `SORT a + b ASC`, `SORT a * 2 DESC`) and multiple sort keys
- [x] Generate `LIMIT`
- [x] Generate deeper plans (recursive depth configurable via `simulator.planDepth`, default 5)

## 3. LogicalPlan shrinker
- [x] Implement shrinking — handled natively by jqwik-engine (no custom `Shrinkable` needed)
- [x] Investigate: jqwik shrinking can independently mutate inner plan nodes without re-running outer `flatMap` closures, producing plans with stale NameIds. Currently patched by `resolveReferences` but this may mask deeper issues or cause invalid shrink candidates to be tested.
- [x] Print the jqwik seed prominently on failure so it's easy to find in logs. Support passing a seed via `-Dsimulator.seed=12345` for reproducibility.
- [x] **Configurable shrinking**: `-Dsimulator.shrinkSeconds=30` and `-Dsimulator.shrinking=FULL` now work via generated `junit-platform.properties` (jqwik 1.9+ requires `jqwik.` prefix; `AroundPropertyHook.setShrinking()` is too late). Note: `jqwik.properties` is deprecated since 1.6.
- [x] **Forced shrinking mode 2**: Add a mode that takes a query and data as input and tries to shrink it until it no longer fails.
- [x] **Investigate shrinking quality**: even with 30s (98 shrink steps), shrunk samples are still complex. Each shrink attempt needs an ES round-trip (~130ms), limiting attempts to ~230 in 30s. Consider: (a) running the simulator locally to pre-filter invalid shrink candidates without ES, (b) caching ES responses for identical queries.
- [x] **Loop mode**: continuously run and shrink, automatically logging discovered bugs to a file (and later, filing GitHub issues).
- [ ] **Turn the loop into a proper script**: replace the Claude skill-based loop with a standalone script.
- [ ] **Make shrinks/number of tries/etc. configurable**: figure out how to make these parameters runtime-configurable (e.g., via system properties or settings) rather than compile-time constants.

## Meta-tests
- [x] **Deterministic bug injection**: define named simulator bugs (`ADD_IS_SUB`, `KEEP_DROPS_FIRST`, `WHERE_INVERTED`, etc.) that can be toggled on. Run the property test with a bug active and verify it detects the failure. Assert properties of the shrunk counterexample (e.g., `ADD_IS_SUB` should shrink to a plan containing `Eval` with `Add`).
- [x] **Generator invariant tests**: generate N plans (no cluster needed) and verify structural properties: all column references in KEEP/DROP/EVAL exist in the child's `output()`, `resolveReferences` is idempotent, every plan prints to parseable ES|QL, no plan has zero output columns.
- [x] **Shrinking quality tests**: covered by deterministic bug injection tests — each verifies the exact minimal shrunk query string.

## 4. Data generator
- [x] Basic data: 2 int columns (`x`, `y`), 3 rows (`sim_data.csv` + ES index)
- [x] Make column names generatable (pool: a, b, c, d, x, y)
- [x] Make column types generatable (INTEGER, KEYWORD)
- [x] Make number of rows generatable (1–5)
- [x] Make number of columns generatable (1–4)
- [ ] **Generate missing/null field values**: some fields in rows should occasionally be absent (null), matching real Elasticsearch behavior where documents may have missing fields. The simulator already handles null propagation in arithmetic and aggregates, but test data never contains nulls as inputs.

## 5. Data shrinker
- [x] Shrink data — handled natively by jqwik-engine (Arbitrary-based generation shrinks automatically)

## 6. Simulator
- [x] `FROM` — loads CSV data by index pattern
- [x] `KEEP` — projects to named columns
- [x] `DROP` — removes named columns
- [x] `EVAL` — evaluates expressions (Add, Div, literals, column refs)
- [x] `WHERE` / `FILTER` — filters rows by condition (GreaterThan)
- [x] `ROW` — literal row construction
- [x] Sub, Mul expression support
- [x] Support more expression types beyond Add, Sub, Mul, Div, GreaterThan, LessThan (added Mod, Neg, GreaterThanOrEqual, LessThanOrEqual, Equals, NotEquals)
- [x] `SORT` + `LIMIT` — sort by column(s) ASC/DESC, take first N rows
- [x] `LIMIT` — take first N rows
- [x] Support `STATS` (Aggregate with COUNT, SUM, MIN, MAX; grouping + no-grouping)
- [x] More aggregation variety in STATS/INLINESTATS: multiple aggregations per query (e.g., `STATS s0 = SUM(a), s1 = MAX(b) BY c`), multiple grouping keys, no-grouping variants
- [x] Generate arithmetic expressions in aggregation fields (e.g., `STATS s0 = SUM(a + b) BY c`)
- [x] Generate `ROW` plans (simulator already handles ROW evaluation, but the generator never produces them)
- [ ] Support more commands (RENAME, etc.)
- [x] Support keyword functions (e.g., CONCAT, LENGTH, SUBSTRING, TO_UPPER, TO_LOWER, TRIM, etc.)
- [x] Unit tests for simulator (Row, From, Keep, Drop, Where, Eval with Add/Sub/Mul, EsRelation in-memory)

## 7. End-to-end property test
- [x] `SimulatorPropertyIT`: generates plan, runs through simulator and ES REST API, compares results
- [x] `LogicalPlanPrinter`: serializes `UnresolvedRelation`, `Keep`, `Drop`, `Eval` to ES|QL query strings
- [x] Result comparison with type normalization (Integer/Long → Long)
- [x] Fix warning handling (allow "No limit defined" warning from ES|QL)
- [ ] Use binary serialization instead of string serialization when passing LogicalPlan to ES (avoids string round-trip bugs)
- [ ] **Run csv-spec tests using the simulator**: use the simulator as an oracle for the existing csv-spec test suite, verifying that the simulator agrees with the expected csv-spec results.
- [x] Increase trial count beyond 1 (now 50)
- [x] On mismatch, rely on jqwik shrinking to find minimal failing case (jqwik-engine handles this natively)

## 8. Bugs found by simulator
- [ ] **ES 500 crash on INLINE STATS + STATS column shadowing**: `IllegalStateException: Expected to replace a single StubRelation in the plan, but none found` when INLINE STATS is followed by STATS that shadows columns, with the shadowed column referenced in a BY clause. Seed: `-1912992578914150372`. Example: `FROM sim_aelsb | INLINE STATS s0 = MIN((10 * 1) - (3 - 9)) | SORT y DESC | LIMIT 9 | STATS s1 = MAX((s0 * 10) - (10 * s0)), s0 = SUM((2 + c) - (y - 8)) | STATS s1 = MIN((s1 + s1) * (s1 - 1)) BY s1, s0 | LIMIT 1`.
- [ ] **SORT expression parenthesization / wrong row selected**: `SORT c ASC, (10 - 1) - (5 - y) DESC, c ASC | LIMIT 9 | EVAL col_1 = (x - x) + (3 * x) | LIMIT 2 | SORT (1 * col_1) + (1 - x) ASC, (3 + 4) + (y + x) DESC, (10 + y) - (x * 10) DESC | LIMIT 9` with data `sim_brba(c:keyword, d:keyword, y:integer, x:integer)` rows `[{c=baz, d=foo, x=1, y=1}, {c=bar, d=foo, x=1, y=1}, {c=baz, d=foo, x=1, y=2}]` → simulator=`[[bar, 3, foo, 1, 1], [baz, 3, foo, 1, 2]]`, ES=`[[bar, 3, foo, 1, 1], [baz, 3, foo, 1, 1]]`. The two baz rows have DIFFERENT sort keys (`(10-1)-(5-y)` = 5 for y=1, 6 for y=2; DESC should pick y=2 first), so the mismatch is not a tie-breaking issue. Likely a `LogicalPlanPrinter` bug: `(10-1)-(5-y)` may be printed without inner parens as `10-1-5-y = 4-y`, reversing the sort order and causing ES to pick y=1. Seed: `-5740937290962512833`. Reproduce: `./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest -Dsimulator.seed=-5740937290962512833 -Dsimulator.tries=200`.
- [ ] **Re-enable LIMIT/SORT generation**: requires solving nondeterministic tie-breaking when LIMIT cuts within a tied group. Options: (a) add a unique row-ID column to test data and use as tiebreaker sort key, (b) detect tie-breaking mismatches in the comparison logic, (c) make the simulator's sort match ES's tie-breaking.
- [ ] **ES 500 on LONG overflow in expressions**: ES returns `arithmetic_exception: long overflow` as a 500 Internal Server Error instead of returning null for certain LONG overflow expressions. Inconsistent with other overflow paths that correctly return null. Seed: `-303959633346700987`. Schema: `sim_llzoo(b:double, d:long, x:long, c:integer)`, 3 rows. Query: `FROM sim_llzoo | KEEP b, x | STATS s0 = MIN(-((1.2826302138710548E307 + -6576108968108364447) - x)), s1 = SUM(x) | EVAL col_0 = -6869934479356291010, z = -(1164584042476148557 % (-642243961635164556 / s1)) | WHERE (-3.1934002527241306E307 + s1) % -5412179033518574705 != z | KEEP s0, s1`. Reproduce with seed replay, then feed to ForcedShrinkerIT with `failureMode=crash` to shrink.
