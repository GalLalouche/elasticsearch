/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakFilters;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseException;
import org.elasticsearch.client.WarningsHandler;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.test.TestClustersThreadFilter;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.test.rest.ESRestTestCase;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.NamedExpression;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.expression.function.UnresolvedFunction;
import org.elasticsearch.xpack.esql.expression.function.aggregate.AggregateFunction;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Max;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Min;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Sum;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.Eval;
import org.elasticsearch.xpack.esql.plan.logical.Filter;
import org.elasticsearch.xpack.esql.plan.logical.InlineStats;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;
import org.junit.ClassRule;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Takes a known-failing query + data and shrinks them to the minimal reproduction.
 * Invoked via system properties: {@code -Dsimulator.query=... -Dsimulator.data=...}
 * Skips when properties are absent.
 */
@ThreadLeakFilters(filters = TestClustersThreadFilter.class)
public class ForcedShrinkerIT extends ESRestTestCase {
    private static final Logger logger = LogManager.getLogger(ForcedShrinkerIT.class);

    @ClassRule
    public static final ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .distribution(DistributionType.DEFAULT)
        .setting("xpack.security.enabled", "false")
        .setting("xpack.license.self_generated.type", "trial")
        .build();

    @Override
    protected String getTestRestCluster() {
        return cluster.getHttpAddresses();
    }

    public void testShrink() throws Exception {
        String queryStr = firstNonEmpty("simulator.query", "simulator.queryJson");
        String dataStr = firstNonEmpty("simulator.dataJson");
        // Support reading query/data from files (for platforms where shell quoting of JSON is painful)
        String queryFile = System.getProperty("simulator.queryFile");
        String dataFile = System.getProperty("simulator.dataFile");
        if ((queryStr == null || queryStr.isEmpty()) && queryFile != null) {
            queryStr = Files.readString(Path.of(queryFile)).trim();
        }
        if ((dataStr == null || dataStr.isEmpty()) && dataFile != null) {
            dataStr = Files.readString(Path.of(dataFile)).trim();
        }
        if (queryStr == null || queryStr.isEmpty() || dataStr == null || dataStr.isEmpty()) {
            // Nothing to shrink — skip silently
            return;
        }

        String failureMode = System.getProperty("simulator.failureMode", "crash");

        // Parse data JSON
        SimSchema schema = parseSchema(dataStr);
        List<Map<String, Object>> rows = parseRows(dataStr);

        // Parse query, attach an EsRelation built from the schema, and resolve names + UnresolvedFunctions
        LogicalPlan plan = parseAndResolve(queryStr, schema);

        // Verify initial failure reproduces
        if (stillFails(schema, rows, plan, failureMode) == false) {
            throw new AssertionError("Initial query+data does not reproduce the failure. Query: " + queryStr);
        }
        logger.info("=== Initial failure confirmed ===");
        logger.info("Query: {}", LogicalPlanPrinter.print(plan));
        logger.info("Data: {}", toDataJson(schema, rows));

        // Greedy shrinking loop — restart from step 1 on any successful shrink
        int step = 0;
        boolean progress = true;
        while (progress) {
            progress = false;

            // Step 1: Remove pipeline stages
            List<LogicalPlan> stages = flattenStages(plan);
            for (int i = 0; i < stages.size() - 1; i++) { // skip FROM (last element)
                if ((stages.get(i) instanceof UnaryPlan) == false) {
                    continue;
                }
                LogicalPlan candidate = rebuildWithout(stages, i);
                try {
                    if (stillFails(schema, rows, candidate, failureMode)) {
                        plan = candidate;
                        step++;
                        progress = true;
                        log(step, "removed stage " + stages.get(i).getClass().getSimpleName(), plan, schema, rows);
                        break;
                    }
                } catch (Exception e) {
                    // Candidate produced an error (e.g., invalid plan) — skip
                }
            }
            if (progress) {
                continue;
            }

            // Step 2: Remove data rows
            for (int i = 0; i < rows.size(); i++) {
                if (rows.size() <= 1) {
                    break;
                }
                List<Map<String, Object>> candidateRows = new ArrayList<>(rows);
                candidateRows.remove(i);
                try {
                    if (stillFails(schema, candidateRows, plan, failureMode)) {
                        rows = candidateRows;
                        step++;
                        progress = true;
                        log(step, "removed row " + i, plan, schema, rows);
                        break;
                    }
                } catch (Exception e) {
                    // skip
                }
            }
            if (progress) {
                continue;
            }

            // Step 3: Remove data columns
            for (int i = 0; i < schema.columns().size(); i++) {
                if (schema.columns().size() <= 1) {
                    break;
                }
                SimSchema.SimColumn removedCol = schema.columns().get(i);
                List<SimSchema.SimColumn> newCols = new ArrayList<>(schema.columns());
                newCols.remove(i);
                SimSchema candidateSchema = new SimSchema(schema.indexName(), newCols);
                List<Map<String, Object>> candidateRows = rows.stream().map(row -> {
                    Map<String, Object> newRow = new LinkedHashMap<>(row);
                    newRow.remove(removedCol.name());
                    return newRow;
                }).toList();
                try {
                    if (stillFails(candidateSchema, candidateRows, plan, failureMode)) {
                        schema = candidateSchema;
                        rows = candidateRows;
                        step++;
                        progress = true;
                        log(step, "removed column " + removedCol.name(), plan, schema, rows);
                        break;
                    }
                } catch (Exception e) {
                    // skip
                }
            }
            if (progress) {
                continue;
            }

            // Step 4: Shrink expressions
            stages = flattenStages(plan);
            boolean exprProgress = false;
            for (int i = 0; i < stages.size() - 1 && exprProgress == false; i++) {
                List<LogicalPlan> exprShrinks = shrinkExpressions(stages.get(i));
                for (LogicalPlan shrunkStage : exprShrinks) {
                    List<LogicalPlan> newStages = new ArrayList<>(stages);
                    newStages.set(i, shrunkStage);
                    LogicalPlan candidate = rebuildFromStages(newStages);
                    try {
                        if (stillFails(schema, rows, candidate, failureMode)) {
                            plan = candidate;
                            step++;
                            progress = true;
                            exprProgress = true;
                            log(step, "shrunk expression in " + stages.get(i).getClass().getSimpleName(), plan, schema, rows);
                            break;
                        }
                    } catch (Exception e) {
                        // skip
                    }
                }
            }
            if (progress) {
                continue;
            }

            // Step 5: Simplify integer values in data
            for (int r = 0; r < rows.size() && progress == false; r++) {
                for (Map.Entry<String, Object> entry : rows.get(r).entrySet()) {
                    if (entry.getValue() instanceof Number num && num.intValue() != 1) {
                        List<Map<String, Object>> candidateRows = deepCopyRows(rows);
                        candidateRows.get(r).put(entry.getKey(), 1);
                        try {
                            if (stillFails(schema, candidateRows, plan, failureMode)) {
                                rows = candidateRows;
                                step++;
                                progress = true;
                                log(step, "simplified " + entry.getKey() + " in row " + r + " to 1", plan, schema, rows);
                                break;
                            }
                        } catch (Exception e) {
                            // skip
                        }
                    }
                }
            }
        }

        logger.info("=== SHRUNK RESULT (after {} steps) ===", step);
        logger.info("Query: {}", LogicalPlanPrinter.print(plan));
        logger.info("Data: {}", toDataJson(schema, rows));
    }

    /** Returns the first non-empty system property value among the given property names, or null. */
    private static String firstNonEmpty(String... propertyNames) {
        for (String name : propertyNames) {
            String value = System.getProperty(name);
            if (value != null && value.isEmpty() == false) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static SimSchema parseSchema(String dataJson) {
        Map<String, Object> dataMap = XContentHelper.convertToMap(JsonXContent.jsonXContent, dataJson, false);
        String indexName = (String) dataMap.get("index");
        List<Map<String, String>> schemaList = (List<Map<String, String>>) dataMap.get("schema");
        List<SimSchema.SimColumn> columns = schemaList.stream()
            .map(s -> new SimSchema.SimColumn(s.get("name"), DataType.fromTypeName(s.get("type"))))
            .toList();
        return new SimSchema(indexName, columns);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> parseRows(String dataJson) {
        Map<String, Object> dataMap = XContentHelper.convertToMap(JsonXContent.jsonXContent, dataJson, false);
        List<Map<String, Object>> rowsList = (List<Map<String, Object>>) dataMap.get("rows");
        return rowsList.stream().map(LinkedHashMap::new).collect(Collectors.toCollection(ArrayList::new));
    }

    private static String toDataJson(SimSchema schema, List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("{\"index\":\"");
        sb.append(schema.indexName());
        sb.append("\",\"schema\":[");
        for (int i = 0; i < schema.columns().size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            SimSchema.SimColumn col = schema.columns().get(i);
            sb.append("{\"name\":\"").append(col.name()).append("\",\"type\":\"").append(col.type().typeName()).append("\"}");
        }
        sb.append("],\"rows\":[");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : rows.get(i).entrySet()) {
                if (first == false) {
                    sb.append(",");
                }
                sb.append("\"").append(entry.getKey()).append("\":");
                if (entry.getValue() instanceof String s) {
                    sb.append("\"").append(s).append("\"");
                } else {
                    sb.append(entry.getValue());
                }
                first = false;
            }
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Parses the query, replaces the leaf {@link org.elasticsearch.xpack.esql.plan.logical.UnresolvedRelation}
     * with an {@link org.elasticsearch.xpack.esql.plan.logical.EsRelation} built from {@code schema},
     * resolves {@link UnresolvedFunction} calls to concrete aggregates, and resolves attribute references
     * to the leaf's canonical {@link org.elasticsearch.xpack.esql.core.expression.NameId}s so the simulator
     * can evaluate the plan.
     */
    private static LogicalPlan parseAndResolve(String query, SimSchema schema) {
        LogicalPlan parsed = EsqlTestUtils.TEST_PARSER.parseQuery(query);
        LogicalPlan withRelation = replaceLeafWithEsRelation(parsed, LogicalPlanGenerator.buildEsRelation(schema));
        return LogicalPlanGenerator.resolveReferences(resolveUnresolvedFunctions(withRelation));
    }

    private static LogicalPlan replaceLeafWithEsRelation(LogicalPlan plan, LogicalPlan esRelation) {
        if (plan instanceof InlineStats is) {
            Aggregate agg = is.aggregate();
            return new InlineStats(
                is.source(),
                new Aggregate(agg.source(), replaceLeafWithEsRelation(agg.child(), esRelation), agg.groupings(), agg.aggregates())
            );
        }
        if (plan instanceof UnaryPlan unary) {
            return unary.replaceChild(replaceLeafWithEsRelation(unary.child(), esRelation));
        }
        return esRelation;
    }

    /** Walks the plan tree, replacing {@link UnresolvedFunction} nodes with resolved aggregate functions. */
    private static LogicalPlan resolveUnresolvedFunctions(LogicalPlan plan) {
        return switch (plan) {
            case Eval eval -> {
                List<Alias> resolvedFields = eval.fields()
                    .stream()
                    .map(a -> new Alias(a.source(), a.name(), resolveExpr(a.child())))
                    .toList();
                yield new Eval(eval.source(), resolveUnresolvedFunctions(eval.child()), resolvedFields);
            }
            case Filter filter -> new Filter(filter.source(), resolveUnresolvedFunctions(filter.child()), resolveExpr(filter.condition()));
            case OrderBy orderBy -> new OrderBy(
                orderBy.source(),
                resolveUnresolvedFunctions(orderBy.child()),
                orderBy.order().stream().map(o -> new Order(o.source(), resolveExpr(o.child()), o.direction(), o.nullsPosition())).toList()
            );
            case InlineStats is -> {
                Aggregate resolved = resolveAggregate(is.aggregate());
                yield new InlineStats(is.source(), resolved);
            }
            case Aggregate agg -> resolveAggregate(agg);
            case UnaryPlan unary -> unary.replaceChild(resolveUnresolvedFunctions(unary.child()));
            default -> plan; // UnresolvedRelation, etc.
        };
    }

    private static Aggregate resolveAggregate(Aggregate agg) {
        List<NamedExpression> resolvedAggs = agg.aggregates()
            .stream()
            .map(ne -> ne instanceof Alias alias ? new Alias(alias.source(), alias.name(), resolveExpr(alias.child())) : ne)
            .toList();
        List<Expression> resolvedGroupings = agg.groupings().stream().map(ForcedShrinkerIT::resolveExpr).toList();
        return new Aggregate(agg.source(), resolveUnresolvedFunctions(agg.child()), resolvedGroupings, resolvedAggs);
    }

    /** Resolves {@link UnresolvedFunction} to the corresponding aggregate function (COUNT/SUM/MIN/MAX). */
    private static Expression resolveExpr(Expression expr) {
        if (expr instanceof UnresolvedFunction uf) {
            Expression field = uf.children().getFirst();
            return switch (uf.name().toUpperCase(Locale.ROOT)) {
                case "COUNT" -> new Count(uf.source(), field);
                case "SUM" -> new Sum(uf.source(), field);
                case "MIN" -> new Min(uf.source(), field);
                case "MAX" -> new Max(uf.source(), field);
                default -> throw new UnsupportedOperationException("Unsupported function: " + uf.name());
            };
        }
        return expr;
    }

    /** Flattens the plan into pipeline stages (root first, FROM last). InlineStats skips its inner Aggregate. */
    private static List<LogicalPlan> flattenStages(LogicalPlan plan) {
        List<LogicalPlan> stages = new ArrayList<>();
        LogicalPlan current = plan;
        while (current instanceof UnaryPlan unary) {
            stages.add(current);
            if (current instanceof InlineStats is) {
                current = is.aggregate().child();
            } else {
                current = unary.child();
            }
        }
        stages.add(current); // FROM (UnresolvedRelation)
        return stages;
    }

    /** Rebuilds a plan from bottom (last) to top (first), reconnecting stages. */
    private static LogicalPlan rebuildFromStages(List<LogicalPlan> stages) {
        LogicalPlan rebuilt = stages.getLast(); // FROM
        for (int j = stages.size() - 2; j >= 0; j--) {
            LogicalPlan stage = stages.get(j);
            rebuilt = switch (stage) {
                case InlineStats is -> {
                    Aggregate agg = is.aggregate();
                    Aggregate newAgg = new Aggregate(agg.source(), rebuilt, agg.groupings(), agg.aggregates());
                    yield new InlineStats(is.source(), newAgg);
                }
                case Aggregate agg -> new Aggregate(agg.source(), rebuilt, agg.groupings(), agg.aggregates());
                default -> ((UnaryPlan) stage).replaceChild(rebuilt);
            };
        }
        return rebuilt;
    }

    /** Removes stage at index i and rebuilds the plan. */
    private static LogicalPlan rebuildWithout(List<LogicalPlan> stages, int removeIndex) {
        List<LogicalPlan> newStages = new ArrayList<>(stages);
        newStages.remove(removeIndex);
        return rebuildFromStages(newStages);
    }

    /** Returns all possible single-expression-shrink variants of a plan stage. */
    private static List<LogicalPlan> shrinkExpressions(LogicalPlan stage) {
        List<LogicalPlan> results = new ArrayList<>();
        switch (stage) {
            case Eval eval -> {
                for (int f = 0; f < eval.fields().size(); f++) {
                    Alias alias = eval.fields().get(f);
                    for (Expression sub : collectSubExpressions(alias.child())) {
                        List<Alias> newFields = new ArrayList<>(eval.fields());
                        newFields.set(f, new Alias(alias.source(), alias.name(), sub));
                        results.add(new Eval(eval.source(), eval.child(), newFields));
                    }
                }
            }
            case Filter filter -> {
                for (Expression sub : collectSubExpressions(filter.condition())) {
                    results.add(new Filter(filter.source(), filter.child(), sub));
                }
            }
            case OrderBy orderBy -> {
                for (int k = 0; k < orderBy.order().size(); k++) {
                    Order order = orderBy.order().get(k);
                    for (Expression sub : collectSubExpressions(order.child())) {
                        List<Order> newOrders = new ArrayList<>(orderBy.order());
                        newOrders.set(k, new Order(order.source(), sub, order.direction(), order.nullsPosition()));
                        results.add(new OrderBy(orderBy.source(), orderBy.child(), newOrders));
                    }
                }
            }
            case InlineStats is -> results.addAll(
                shrinkAggregateExprs(is.aggregate()).stream().map(a -> new InlineStats(is.source(), a)).toList()
            );
            case Aggregate agg -> results.addAll(shrinkAggregateExprs(agg));
            default -> {
                /* no expressions to shrink */ }
        }
        return results;
    }

    /** Returns shrunk variants of an Aggregate node by shrinking each aggregate function's field expression. */
    private static List<Aggregate> shrinkAggregateExprs(Aggregate agg) {
        List<Aggregate> results = new ArrayList<>();
        int numAggs = agg.aggregates().size() - agg.groupings().size();
        for (int a = 0; a < numAggs; a++) {
            NamedExpression ne = agg.aggregates().get(a);
            if (ne instanceof Alias alias && Alias.unwrap(alias) instanceof AggregateFunction aggFunc) {
                for (Expression sub : collectSubExpressions(aggFunc.field())) {
                    AggregateFunction newAggFunc = replaceAggField(aggFunc, sub);
                    List<NamedExpression> newAggs = new ArrayList<>(agg.aggregates());
                    newAggs.set(a, new Alias(alias.source(), alias.name(), newAggFunc));
                    results.add(new Aggregate(agg.source(), agg.child(), agg.groupings(), newAggs));
                }
            }
        }
        return results;
    }

    /** Recursively collects all sub-expressions (children and their children). */
    private static List<Expression> collectSubExpressions(Expression expr) {
        List<Expression> subs = new ArrayList<>();
        for (Expression child : expr.children()) {
            subs.add(child);
            subs.addAll(collectSubExpressions(child));
        }
        return subs;
    }

    /** Creates a new aggregate function of the same type with a different field. */
    private static AggregateFunction replaceAggField(AggregateFunction func, Expression newField) {
        return switch (func) {
            case Count c -> new Count(c.source(), newField);
            case Sum s -> new Sum(s.source(), newField);
            case Min m -> new Min(m.source(), newField);
            case Max m -> new Max(m.source(), newField);
            default -> throw new UnsupportedOperationException("Unsupported aggregate function: " + func.getClass());
        };
    }

    private boolean stillFails(SimSchema schema, List<Map<String, Object>> rows, LogicalPlan plan, String failureMode) throws Exception {
        String query = LogicalPlanPrinter.print(plan);
        deleteIndexBestEffort(schema.indexName());
        createIndex(schema);
        try {
            indexData(schema, rows);

            return "mismatch".equals(failureMode) ? queryMismatches(query, schema, rows, plan) : queryCrashes(query);
        } finally {
            deleteIndexBestEffort(schema.indexName());
        }
    }

    private boolean queryCrashes(String query) {
        try {
            Request request = new Request("POST", "/_query");
            request.setJsonEntity("{\"query\": \"" + query.replace("\"", "\\\"") + "\"}");
            request.setOptions(request.getOptions().toBuilder().setWarningsHandler(WarningsHandler.PERMISSIVE));
            client().performRequest(request);
            return false; // 200 OK — no crash
        } catch (ResponseException e) {
            return e.getResponse().getStatusLine().getStatusCode() >= 500;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean queryMismatches(String query, SimSchema schema, List<Map<String, Object>> rows, LogicalPlan plan) {
        try {
            Map<String, Object> esResponse = runEsqlQuery(query);
            Result esResult = responseToResult(esResponse);
            Result simResult = new Simulator(schema, rows).simulate(plan);
            return resultsMatch(simResult, esResult) == false;
        } catch (Exception e) {
            logger.warn("queryMismatches threw for [{}]: {}", query, e.toString());
            return false;
        }
    }

    private static boolean resultsMatch(Result simResult, Result esResult) {
        List<Simulator.Column> simColumns = sortedColumns(simResult.columns());
        List<Simulator.Column> esCols = sortedColumns(esResult.columns());
        if (simColumns.size() != esCols.size()) {
            return false;
        }
        for (int c = 0; c < simColumns.size(); c++) {
            if (simColumns.get(c).name().equals(esCols.get(c).name()) == false) {
                return false;
            }
        }
        List<List<Object>> simRows = extractRows(simColumns);
        List<List<Object>> esRows = extractRows(esCols);
        simRows.sort(ForcedShrinkerIT::compareRows);
        esRows.sort(ForcedShrinkerIT::compareRows);
        return simRows.equals(esRows);
    }

    private static void log(int step, String what, LogicalPlan plan, SimSchema schema, List<Map<String, Object>> rows) {
        logger.info("[Step {}] {}", step, what);
        logger.info("  Query: {}", LogicalPlanPrinter.print(plan));
        logger.info("  Data: {}", toDataJson(schema, rows));
    }

    private static List<Map<String, Object>> deepCopyRows(List<Map<String, Object>> rows) {
        return rows.stream().map(LinkedHashMap::new).collect(Collectors.toCollection(ArrayList::new));
    }

    private void createIndex(SimSchema schema) throws IOException {
        Request createIndex = new Request("PUT", "/" + schema.indexName());
        StringBuilder mappings = new StringBuilder("{\"mappings\":{\"properties\":{");
        List<SimSchema.SimColumn> columns = schema.columns();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                mappings.append(",");
            }
            SimSchema.SimColumn col = columns.get(i);
            mappings.append("\"").append(col.name()).append("\":{\"type\":\"").append(col.type().typeName()).append("\"}");
        }
        mappings.append("}}}");
        createIndex.setJsonEntity(mappings.toString());
        client().performRequest(createIndex);
    }

    private void indexData(SimSchema schema, List<Map<String, Object>> rows) throws IOException {
        if (rows.isEmpty()) {
            return;
        }
        Request bulk = new Request("POST", "/_bulk");
        bulk.addParameter("refresh", "true");
        StringBuilder body = new StringBuilder();
        for (Map<String, Object> row : rows) {
            body.append("{\"index\":{\"_index\":\"").append(schema.indexName()).append("\"}}\n");
            body.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (first == false) {
                    body.append(",");
                }
                body.append("\"").append(entry.getKey()).append("\":");
                Object value = entry.getValue();
                if (value instanceof String s) {
                    body.append("\"").append(s).append("\"");
                } else {
                    body.append(value);
                }
                first = false;
            }
            body.append("}\n");
        }
        bulk.setJsonEntity(body.toString());
        Response bulkResponse = client().performRequest(bulk);
        assertStatusCode(200, bulkResponse);
        Map<String, Object> bulkResult = XContentHelper.convertToMap(
            JsonXContent.jsonXContent,
            bulkResponse.getEntity().getContent(),
            false
        );
        if (Boolean.TRUE.equals(bulkResult.get("errors"))) {
            throw new AssertionError("Bulk indexing had errors: " + bulkResult);
        }
    }

    private void deleteIndexBestEffort(String indexName) {
        try {
            client().performRequest(new Request("DELETE", "/" + indexName));
        } catch (@SuppressWarnings("unused") Exception e) {
            // Best-effort cleanup
        }
    }

    private Map<String, Object> runEsqlQuery(String query) throws IOException {
        Request request = new Request("POST", "/_query");
        request.setJsonEntity("{\"query\": \"" + query.replace("\"", "\\\"") + "\"}");
        request.setOptions(request.getOptions().toBuilder().setWarningsHandler(WarningsHandler.PERMISSIVE));
        Response response = client().performRequest(request);
        assertStatusCode(200, response);
        return XContentHelper.convertToMap(JsonXContent.jsonXContent, response.getEntity().getContent(), false);
    }

    @SuppressWarnings("unchecked")
    private static Result responseToResult(Map<String, Object> response) {
        List<Map<String, String>> columns = (List<Map<String, String>>) response.get("columns");
        List<List<Object>> values = (List<List<Object>>) response.get("values");

        List<Simulator.Column> resultColumns = new ArrayList<>();
        for (int c = 0; c < columns.size(); c++) {
            Map<String, String> colMeta = columns.get(c);
            String name = colMeta.get("name");
            DataType type = DataType.fromTypeName(colMeta.get("type"));
            List<Object> colValues = new ArrayList<>();
            if (values != null) {
                for (List<Object> row : values) {
                    colValues.add(row.get(c));
                }
            }
            resultColumns.add(new Simulator.Column(name, type, colValues));
        }
        return new Result(resultColumns);
    }

    private static List<Simulator.Column> sortedColumns(List<Simulator.Column> columns) {
        return columns.stream().sorted(Comparator.comparing(Simulator.Column::name)).toList();
    }

    private static void assertStatusCode(int expected, Response response) {
        int actual = response.getStatusLine().getStatusCode();
        if (actual != expected) {
            throw new AssertionError(Strings.format("Expected status code %s but got %s", expected, actual));
        }
    }

    private static Object normalizeValue(Object v) {
        return v instanceof Integer n ? n.longValue() : v;
    }

    private static List<List<Object>> extractRows(List<Simulator.Column> columns) {
        int numRows = columns.isEmpty() ? 0 : columns.getFirst().values().size();
        List<List<Object>> rows = new ArrayList<>(numRows);
        for (int r = 0; r < numRows; r++) {
            List<Object> row = new ArrayList<>(columns.size());
            for (Simulator.Column col : columns) {
                row.add(normalizeValue(col.values().get(r)));
            }
            rows.add(row);
        }
        return rows;
    }

    private static int compareRows(List<Object> a, List<Object> b) {
        for (int i = 0; i < a.size(); i++) {
            int cmp = compareValues(a.get(i), b.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static int compareValues(Object a, Object b) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return -1;
        }
        if (b == null) {
            return 1;
        }
        if (a instanceof Comparable<?> ac) {
            return ((Comparable) ac).compareTo(b);
        }
        return a.toString().compareTo(b.toString());
    }
}
