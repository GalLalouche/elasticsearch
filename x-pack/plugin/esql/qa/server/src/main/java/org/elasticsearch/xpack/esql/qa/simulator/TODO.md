# Simulator TODO

## 1. jqwik integration
- [x] Use jqwik's `Arbitrary` API for generation (`LogicalPlanGenerator`)
- [x] Replace custom minimal facades (`jqwik/` package) with real `jqwik-engine` dependency (add to `verification-metadata.xml`)
- [ ] Decouple generating/shrinking code from jqwik so it isn't overly-coupled to the framework

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
- [ ] **Forced shrinking mode 2**: Add a mode that takes a query as input and tries to shrink it until it no longer fails.
- [ ] **Investigate shrinking quality**: even with 30s (98 shrink steps), shrunk samples are still complex. Each shrink attempt needs an ES round-trip (~130ms), limiting attempts to ~230 in 30s. Consider: (a) running the simulator locally to pre-filter invalid shrink candidates without ES, (b) caching ES responses for identical queries.
- [ ] **Loop mode**: continuously run and shrink, automatically logging discovered bugs to a file (and later, filing GitHub issues).

## Meta-tests
- [x] **Deterministic bug injection**: define named simulator bugs (`ADD_IS_SUB`, `KEEP_DROPS_FIRST`, `WHERE_INVERTED`, etc.) that can be toggled on. Run the property test with a bug active and verify it detects the failure. Assert properties of the shrunk counterexample (e.g., `ADD_IS_SUB` should shrink to a plan containing `Eval` with `Add`).
- [ ] **Generator invariant tests**: generate N plans (no cluster needed) and verify structural properties: all column references in KEEP/DROP/EVAL exist in the child's `output()`, `resolveReferences` is idempotent, every plan prints to parseable ES|QL, no plan has zero output columns.
- [x] **Shrinking quality tests**: covered by deterministic bug injection tests — each verifies the exact minimal shrunk query string.

## 4. Data generator
- [x] Basic data: 2 int columns (`x`, `y`), 3 rows (`sim_data.csv` + ES index)
- [x] Make column names generatable (pool: a, b, c, d, x, y)
- [x] Make column types generatable (INTEGER, KEYWORD)
- [x] Make number of rows generatable (1–5)
- [x] Make number of columns generatable (1–4)

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
- [ ] Support more expression types beyond Add, Sub, Mul, Div, GreaterThan, LessThan
- [x] `SORT` + `LIMIT` — sort by column(s) ASC/DESC, take first N rows
- [x] `LIMIT` — take first N rows
- [x] Support `STATS` (Aggregate with COUNT, SUM, MIN, MAX; grouping + no-grouping)
- [x] More aggregation variety in STATS/INLINESTATS: multiple aggregations per query (e.g., `STATS s0 = SUM(a), s1 = MAX(b) BY c`), multiple grouping keys, no-grouping variants
- [x] Generate arithmetic expressions in aggregation fields (e.g., `STATS s0 = SUM(a + b) BY c`)
- [ ] Support more commands (RENAME, etc.)
- [x] Unit tests for simulator (Row, From, Keep, Drop, Where, Eval with Add/Sub/Mul, EsRelation in-memory)

## 7. End-to-end property test
- [x] `SimulatorPropertyIT`: generates plan, runs through simulator and ES REST API, compares results
- [x] `LogicalPlanPrinter`: serializes `UnresolvedRelation`, `Keep`, `Drop`, `Eval` to ES|QL query strings
- [x] Result comparison with type normalization (Integer/Long → Long)
- [x] Fix warning handling (allow "No limit defined" warning from ES|QL)
- [ ] Use binary serialization instead of string serialization when passing LogicalPlan to ES (avoids string round-trip bugs)
- [x] Increase trial count beyond 1 (now 50)
- [x] On mismatch, rely on jqwik shrinking to find minimal failing case (jqwik-engine handles this natively)

## 8. Bugs found by simulator
- [x] **INLINE STATS after LIMIT**: generator produced `LIMIT N | INLINE STATS ...` which ES rejects with "INLINE STATS cannot be used after an explicit or implicit LIMIT command". Fix: skip `wrapInlineStats` when plan tree already contains a `Limit` node.
- [x] **Empty aggregation returns wrong value**: `SUM`/`MIN`/`MAX` over zero rows returned `0` in simulator but `null` in ES. Fix: return `null` when `indices.isEmpty()` for all aggregates except `COUNT` (which correctly returns `0`).
- [x] **Shrinking mode not configurable**: `SimulatorSeedHook` only overrode seed, not shrinking mode. Fix: generate `junit-platform.properties` from Gradle with `jqwik.shrinking.bounded.seconds` / `jqwik.shrinking.default` via `-Dsimulator.shrinkSeconds=N` / `-Dsimulator.shrinking=FULL`.
- [ ] **Chained STATS duplicate columns**: `STATS s1 = COUNT(...), s0 = COUNT(...) BY s1` — when a STATS grouping key (`BY s1`) has the same name as an aggregate output (`s1 = COUNT(...)`), ES deduplicates to 2 columns but the simulator keeps 3 (`s0, s1, s1`). Seed: `-4461844028516981882`.
