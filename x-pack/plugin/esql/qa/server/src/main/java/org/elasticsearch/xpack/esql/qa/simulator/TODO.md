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
- [x] Generate `WHERE` / `FILTER`
- [x] Generate `SORT` + `LIMIT` (paired — ES requires LIMIT after SORT)
- [x] Generate `LIMIT`
- [x] Generate deeper plans (recursive depth configurable via `simulator.planDepth`, default 5)

## 3. LogicalPlan shrinker
- [x] Implement shrinking — handled natively by jqwik-engine (no custom `Shrinkable` needed)

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
- [ ] Support more commands (STATS, etc.)
- [x] Unit tests for simulator (Row, From, Keep, Drop, Where, Eval with Add/Sub/Mul, EsRelation in-memory)

## 7. End-to-end property test
- [x] `SimulatorPropertyIT`: generates plan, runs through simulator and ES REST API, compares results
- [x] `LogicalPlanPrinter`: serializes `UnresolvedRelation`, `Keep`, `Drop`, `Eval` to ES|QL query strings
- [x] Result comparison with type normalization (Integer/Long → Long)
- [x] Fix warning handling (allow "No limit defined" warning from ES|QL)
- [ ] Use binary serialization instead of string serialization when passing LogicalPlan to ES (avoids string round-trip bugs)
- [x] Increase trial count beyond 1 (now 10)
- [x] On mismatch, rely on jqwik shrinking to find minimal failing case (jqwik-engine handles this natively)
