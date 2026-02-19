# Simulator TODO

## 1. jqwik integration
- [x] Use jqwik's `Arbitrary` API for generation (`LogicalPlanGenerator`)
- [x] Replace custom minimal facades (`jqwik/` package) with real `jqwik-engine` dependency (add to `verification-metadata.xml`)
- [ ] Decouple generating/shrinking code from jqwik so it isn't overly-coupled to the framework
- [ ] Replace jqwik with [junit-quickcheck](https://github.com/pholser/junit-quickcheck)

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
- [x] **Chained STATS/INLINE STATS duplicate columns**: when a grouping key shares a name with an aggregate output, ES deduplicates keeping the last entry (grouping key wins). Fix: changed both `visit(Aggregate)` and `visit(InlineStats)` from "keep first" to "keep last" dedup, matching ES's `mergeOutputExpressions` semantics. Also added null propagation for arithmetic/comparison operators and null-safe aggregation. Seeds: `-4461844028516981882`, `1934485686493129541`, `8178407657540719480`, `7261277714885348150`, `-1636325762218452898`.
- [ ] **ES 500 crash on INLINE STATS + STATS column shadowing**: `IllegalStateException: Expected to replace a single StubRelation in the plan, but none found` when INLINE STATS is followed by STATS that shadows columns, with the shadowed column referenced in a BY clause. Seed: `-1912992578914150372`. Example: `FROM sim_aelsb | INLINE STATS s0 = MIN((10 * 1) - (3 - 9)) | SORT y DESC | LIMIT 9 | STATS s1 = MAX((s0 * 10) - (10 * s0)), s0 = SUM((2 + c) - (y - 8)) | STATS s1 = MIN((s1 + s1) * (s1 - 1)) BY s1, s0 | LIMIT 1`.
- [x] **SORT tie-breaking mismatch**: when sort keys are equal, simulator and ES break ties differently, leading to different rows being selected after LIMIT. Seeds: `-1487800040806453354`, `-3578431799500002710`. Fix: two-phase comparison — (1) `findEffectiveSort` walks the plan from root to find the active `OrderBy` (stops at `Aggregate` which destroys order), then `verifySortOrder` checks both results respect the sort key ordering (ties allowed); (2) multiset comparison sorts both sides by all columns to handle tied rows and no-SORT cases.
- [x] **Integer overflow**: simulator uses `long` arithmetic but ES uses `integer` which returns `null` on overflow. Fix: in `evalBinaryLong`, when both operands are `DataType.INTEGER`, check if the `long` result overflows 32-bit range and return `null` if so. Seeds: `1934485686493129541`, `3874064250528934887`.
- [ ] **ES 500 crash on INLINE STATS with constant aggregation**: `IllegalStateException: Expected to replace a single StubRelation in the plan, but none found` (in `InlineJoin.replaceStub` / `PropagateInlineEvals`) when `INLINE STATS` uses a purely constant aggregation expression with no column references. Example: `FROM sim_jgs | INLINE STATS s0 = MIN((6 + 2) + (5 + 2))`. Seed: `3857084894480398319`. Reproduce: `./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest -Dsimulator.seed=3857084894480398319 -Dsimulator.tries=200`.
- [ ] **Constant STATS aggregation with 0 input rows**: `MIN`/`MAX` of a constant expression (no column refs) in STATS returns `null` in simulator but the constant value in ES when there are 0 input rows (after WHERE filters all rows and INLINE STATS BY produces 0 rows). Example: `FROM sim_one | WHERE (7 - 9) * (y + y) > (y - 7) + (6 + 4) | INLINE STATS s0 = MAX((2 * y) * (4 * 5)) BY b | KEEP x, c, s0, y, b | STATS s1 = SUM((s0 + s0) - (3 * 1)), s0 = MIN((5 + 9) - (7 * 10)) | LIMIT 1` → simulator=`[[null, null]]`, ES=`[[-56, null]]`. ES evaluates `MIN(-56)` as `-56` even with 0 rows; simulator returns `null`. Seed: `2342139981827820624`. Reproduce: `./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest -Dsimulator.seed=2342139981827820624 -Dsimulator.tries=200`.
- [ ] **SORT expression parenthesization / wrong row selected**: `SORT c ASC, (10 - 1) - (5 - y) DESC, c ASC | LIMIT 9 | EVAL col_1 = (x - x) + (3 * x) | LIMIT 2 | SORT (1 * col_1) + (1 - x) ASC, (3 + 4) + (y + x) DESC, (10 + y) - (x * 10) DESC | LIMIT 9` with data `sim_brba(c:keyword, d:keyword, y:integer, x:integer)` rows `[{c=baz, d=foo, x=1, y=1}, {c=bar, d=foo, x=1, y=1}, {c=baz, d=foo, x=1, y=2}]` → simulator=`[[bar, 3, foo, 1, 1], [baz, 3, foo, 1, 2]]`, ES=`[[bar, 3, foo, 1, 1], [baz, 3, foo, 1, 1]]`. The two baz rows have DIFFERENT sort keys (`(10-1)-(5-y)` = 5 for y=1, 6 for y=2; DESC should pick y=2 first), so the mismatch is not a tie-breaking issue. Likely a `LogicalPlanPrinter` bug: `(10-1)-(5-y)` may be printed without inner parens as `10-1-5-y = 4-y`, reversing the sort order and causing ES to pick y=1. Seed: `-5740937290962512833`. Reproduce: `./gradlew :x-pack:plugin:esql:qa:server:simulator:javaRestTest -Dsimulator.seed=-5740937290962512833 -Dsimulator.tries=200`.
- [ ] **SORT tie-breaking regression (fix incomplete)**: when LIMIT cuts in the middle of a tied group, simulator and ES pick different rows and subsequent WHERE/KEEP/SORT apply on those different rows, producing genuinely different result sets. The existing `verifySortOrder`+multiset fix doesn't handle this. Seeds: `-4134259006366380559` (INLINE STATS + all-tied b keys), `4100494120868700546` (EVAL + SORT z ASC with 3 tied rows), `-7528730211837372562` (SORT c/a all tied, LIMIT 2 picks different y values). Example (seed `4100494120868700546`): `FROM sim_topg | EVAL z = (4 - y) - (10 + y), w = (6 * y) + (3 - 3) | LIMIT 8 | SORT z ASC | LIMIT 3 | WHERE (1 + y) * (1 * y) > (y + z) + (7 - 3) | KEEP c, y` with data `sim_topg(c:keyword, y:integer)` rows `[{c=foo, y=5}, {c=foo, y=5}, {c=bar, y=5}, {c=foo, y=6}]` → simulator=`[[foo, 5], [foo, 5], [foo, 6]]`, ES=`[[bar, 5], [foo, 5], [foo, 6]]`.
