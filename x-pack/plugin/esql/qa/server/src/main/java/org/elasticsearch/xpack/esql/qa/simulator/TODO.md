# Simulator TODO

## 1. jqwik integration
- [x] Use jqwik's `Arbitrary` API for generation (`LogicalPlanGenerator`)
- [ ] Replace custom minimal facades (`jqwik/` package) with real `jqwik-engine` dependency (add to `verification-metadata.xml`)
- [ ] Decouple generating/shrinking code from jqwik so it isn't overly-coupled to the framework

## 2. LogicalPlan generator
- [x] Generate `FROM` (via `UnresolvedRelation`)
- [x] Generate `KEEP` (random non-empty subset of columns)
- [x] Generate `DROP` (random non-empty subset of columns)
- [ ] Generate `EVAL`
- [ ] Generate `WHERE` / `FILTER`
- [ ] Generate deeper plans (currently depth=1; increase recursive depth)

## 3. LogicalPlan shrinker
- [ ] Implement shrinking (remove top-level node(s) to find minimal failing plan)
- [ ] Currently all `Shrinkable` instances return `Stream.empty()` from `shrink()` — needs real implementation

## 4. Data generator
- [x] Basic data: 2 int columns (`x`, `y`), 3 rows (`sim_data.csv` + ES index)
- [ ] Make column names generatable
- [ ] Make column types generatable (not just integer)
- [ ] Make number of rows generatable
- [ ] Make number of columns generatable

## 5. Data shrinker
- [ ] Shrink data (fewer rows, fewer columns, simpler values)

## 6. Simulator
- [x] `FROM` — loads CSV data by index pattern
- [x] `KEEP` — projects to named columns
- [x] `DROP` — removes named columns
- [x] `EVAL` — evaluates expressions (Add, Div, literals, column refs)
- [x] `WHERE` / `FILTER` — filters rows by condition (GreaterThan)
- [x] `ROW` — literal row construction
- [ ] Support more expression types beyond Add, Div, GreaterThan
- [ ] Support more commands (SORT, LIMIT, STATS, etc.)

## 7. End-to-end property test
- [x] `SimulatorPropertyIT`: generates plan, runs through simulator and ES REST API, compares results
- [x] `LogicalPlanPrinter`: serializes `UnresolvedRelation`, `Keep`, `Drop`, `Eval` to ES|QL query strings
- [x] Result comparison with type normalization (Integer/Long → Long)
- [ ] Increase trial count beyond 1
- [ ] On mismatch, rely on jqwik shrinking to find minimal failing case (requires items 1 and 3)
