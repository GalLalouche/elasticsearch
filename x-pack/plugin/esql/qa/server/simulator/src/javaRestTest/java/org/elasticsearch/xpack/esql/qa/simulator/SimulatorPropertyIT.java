/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.qa.simulator;

import com.pholser.junit.quickcheck.From;
import com.pholser.junit.quickcheck.Property;
import com.pholser.junit.quickcheck.generator.GenerationStatus;
import com.pholser.junit.quickcheck.generator.Generator;
import com.pholser.junit.quickcheck.random.SourceOfRandomness;
import com.pholser.junit.quickcheck.runner.JUnitQuickcheck;

import org.apache.http.HttpHost;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.WarningsHandler;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.xcontent.XContentHelper;
import org.elasticsearch.core.Nullable;
import org.elasticsearch.test.cluster.ElasticsearchCluster;
import org.elasticsearch.test.cluster.local.distribution.DistributionType;
import org.elasticsearch.xcontent.json.JsonXContent;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.expression.Order;
import org.elasticsearch.xpack.esql.plan.logical.Aggregate;
import org.elasticsearch.xpack.esql.plan.logical.LogicalPlan;
import org.elasticsearch.xpack.esql.plan.logical.OrderBy;
import org.elasticsearch.xpack.esql.plan.logical.Row;
import org.elasticsearch.xpack.esql.plan.logical.UnaryPlan;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Integration property test: generates random plans and data, then compares simulator results against a real ES cluster. */
@RunWith(JUnitQuickcheck.class)
public class SimulatorPropertyIT {
    static {
        SimulatorTestUtils.initLogging();
    }

    @ClassRule
    public static ElasticsearchCluster cluster = ElasticsearchCluster.local()
        .distribution(DistributionType.DEFAULT)
        .setting("xpack.security.enabled", "false")
        .setting("xpack.license.self_generated.type", "trial")
        .shared(true)
        .build();

    private static RestClient restClient;

    @BeforeClass
    public static void connectClient() throws Throwable {
        String[] addresses = cluster.getHttpAddresses().split(",");
        HttpHost[] hosts = new HttpHost[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            String addr = addresses[i].trim();
            int lastColon = addr.lastIndexOf(':');
            String host = addr.substring(0, lastColon);
            int port = Integer.parseInt(addr.substring(lastColon + 1));
            hosts[i] = new HttpHost(host, port);
        }
        restClient = RestClient.builder(hosts).build();
    }

    @AfterClass
    public static void disconnectClient() throws Exception {
        if (restClient != null) {
            restClient.close();
            restClient = null;
        }
    }

    record TestCase(@Nullable SimSchema schema, @Nullable List<Map<String, Object>> data, LogicalPlan plan, String query) {
        TestCase {
            assert (schema == null) == (data == null);
        }

        @Override
        public String toString() {
            if (schema == null) {
                return Strings.format("%s [ROW plan]", query);
            }
            return Strings.format("%s [%s, %s rows]", query, schema, data.size());
        }
    }

    @Property(trials = 50)
    public void simulatorMatchesEs(@From(TestCaseGenerator.class) TestCase tc) throws Exception {
        boolean needsIndex = tc.schema() != null;

        if (needsIndex) {
            // Clean up any stale index from a previous run, then set up fresh
            deleteIndex(tc.schema().indexName());
            createIndex(tc.schema());
        }
        try {
            if (needsIndex) {
                indexData(tc.schema(), tc.data());
            }

            Simulator simulator = needsIndex ? new Simulator(tc.schema(), tc.data()) : new Simulator();
            Simulator.Result simResult = simulator.simulate(tc.plan());

            Map<String, Object> esResponse = runEsqlQuery(tc.query());
            Simulator.Result esResult = responseToResult(esResponse);

            // Compare (sort columns by name for consistent ordering)
            List<Simulator.Column> simColumns = sortedColumns(simResult.columns());
            List<Simulator.Column> esCols = sortedColumns(esResult.columns());

            if (simColumns.size() != esCols.size()) {
                throw new AssertionError(
                    Strings.format(
                        "Column count mismatch for query [%s]: simulator=%s es=%s\nSimulator columns: %s\nES columns: %s",
                        tc.query(),
                        simColumns.size(),
                        esCols.size(),
                        columnNames(simColumns),
                        columnNames(esCols)
                    )
                );
            }

            for (int c = 0; c < simColumns.size(); c++) {
                Simulator.Column simCol = simColumns.get(c);
                Simulator.Column esCol = esCols.get(c);

                if (simCol.name().equals(esCol.name()) == false) {
                    throw new AssertionError(
                        Strings.format(
                            "Column name mismatch at index %s for query [%s]: simulator=%s es=%s",
                            c,
                            tc.query(),
                            simCol.name(),
                            esCol.name()
                        )
                    );
                }
            }

            List<Order> effectiveSort = findEffectiveSort(tc.plan());
            if (effectiveSort.isEmpty() == false) {
                try {
                    verifySortOrder(effectiveSort, simResult, "simulator", tc.query());
                    verifySortOrder(effectiveSort, esResult, "ES", tc.query());
                } catch (IllegalArgumentException e) {
                    // Sort key column not in final output (dropped by KEEP/DROP after SORT) — can't verify order
                    if (e.getMessage() == null || e.getMessage().startsWith("Column not found:") == false) {
                        throw e;
                    }
                }
            }

            // Compare rows as multisets: sort both sides by all columns to handle
            // tied sort keys and no-SORT cases where row order is nondeterministic.
            List<List<Object>> simRows = extractRows(simColumns);
            List<List<Object>> esRows = extractRows(esCols);
            simRows.sort(SimulatorPropertyIT::compareRows);
            esRows.sort(SimulatorPropertyIT::compareRows);

            if (simRows.equals(esRows) == false) {
                throw new AssertionError(
                    Strings.format(
                        "Row mismatch for query [%s] with %s and data %s:\nsimulator=%s\nes=%s",
                        tc.query(),
                        tc.schema(),
                        tc.data(),
                        simRows,
                        esRows
                    )
                );
            }
        } finally {
            if (needsIndex) {
                deleteIndex(tc.schema().indexName());
            }
        }
    }

    public static class TestCaseGenerator extends Generator<TestCase> {
        public TestCaseGenerator() {
            super(TestCase.class);
        }

        @Override
        public TestCase generate(SourceOfRandomness random, GenerationStatus status) {
            SimSchema schema = SimSchemaGenerator.generate(random, status);
            LogicalPlan plan = LogicalPlanGenerator.generate(schema, random, status);
            if (isRowPlan(plan)) {
                return new TestCase(null, null, plan, LogicalPlanPrinter.print(plan));
            }
            List<Map<String, Object>> data = SimDataGenerator.generate(schema, random, status);
            return new TestCase(schema, data, plan, LogicalPlanPrinter.print(plan));
        }

        @Override
        public List<TestCase> doShrink(SourceOfRandomness random, TestCase larger) {
            List<TestCase> candidates = new ArrayList<>();
            // Shrink plan: drop outermost layer
            if (larger.plan() instanceof UnaryPlan unary) {
                LogicalPlan simpler = LogicalPlanGenerator.resolveReferences(unary.child());
                candidates.add(new TestCase(larger.schema(), larger.data(), simpler, LogicalPlanPrinter.print(simpler)));
            }
            // Shrink data: remove last row (only for FROM plans with data)
            if (larger.data() != null && larger.data().size() > 1) {
                List<Map<String, Object>> smaller = new ArrayList<>(larger.data());
                smaller.remove(smaller.size() - 1);
                candidates.add(new TestCase(larger.schema(), smaller, larger.plan(), larger.query()));
            }
            // Shrink data: fill in a null (absent) field with a non-null value
            if (larger.data() != null) {
                for (int r = 0; r < larger.data().size(); r++) {
                    Map<String, Object> row = larger.data().get(r);
                    for (SimSchema.SimColumn col : larger.schema().columns()) {
                        if (row.containsKey(col.name()) == false) {
                            List<Map<String, Object>> filledData = larger.data()
                                .stream()
                                .<Map<String, Object>>map(LinkedHashMap::new)
                                .toList();
                            Object fillValue = switch (col.type()) {
                                case INTEGER -> 1;
                                case KEYWORD -> "foo";
                                default -> throw new UnsupportedOperationException("Unsupported type: " + col.type());
                            };
                            filledData.get(r).put(col.name(), fillValue);
                            candidates.add(new TestCase(larger.schema(), filledData, larger.plan(), larger.query()));
                            break; // Only try the first null per row to limit candidate explosion
                        }
                    }
                }
            }
            return candidates;
        }
    }

    private static boolean isRowPlan(LogicalPlan plan) {
        LogicalPlan current = plan;
        while (current instanceof UnaryPlan unary) {
            current = unary.child();
        }
        return current instanceof Row;
    }

    private static void createIndex(SimSchema schema) throws IOException {
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
        restClient.performRequest(createIndex);
    }

    private static void indexData(SimSchema schema, List<Map<String, Object>> rows) throws IOException {
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
            for (var entry : row.entrySet()) {
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
        Response bulkResponse = restClient.performRequest(bulk);
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

    private static void deleteIndex(String indexName) throws IOException {
        try {
            restClient.performRequest(new Request("DELETE", "/" + indexName));
        } catch (Exception e) {
            // Best-effort cleanup
        }
    }

    private static Map<String, Object> runEsqlQuery(String query) throws IOException {
        Request request = new Request("POST", "/_query");
        request.setJsonEntity("{\"query\": \"" + query.replace("\"", "\\\"") + "\"}");
        request.setOptions(request.getOptions().toBuilder().setWarningsHandler(WarningsHandler.PERMISSIVE));
        Response response = restClient.performRequest(request);
        assertStatusCode(200, response);
        return XContentHelper.convertToMap(JsonXContent.jsonXContent, response.getEntity().getContent(), false);
    }

    @SuppressWarnings("unchecked")
    private static Simulator.Result responseToResult(Map<String, Object> response) {
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
        return new Simulator.Result(resultColumns);
    }

    private static List<Simulator.Column> sortedColumns(List<Simulator.Column> columns) {
        return columns.stream().sorted(Comparator.comparing(Simulator.Column::name)).toList();
    }

    private static List<String> columnNames(List<Simulator.Column> columns) {
        return columns.stream().map(Simulator.Column::name).toList();
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

    /**
     * Finds the effective sort order by walking the plan from root toward leaves.
     * Returns the sort keys of the first {@link OrderBy} encountered, or empty if
     * an {@link Aggregate} (STATS) is reached first (which destroys row order).
     */
    private static List<Order> findEffectiveSort(LogicalPlan plan) {
        LogicalPlan current = plan;
        while (current instanceof UnaryPlan unary) {
            if (current instanceof OrderBy orderBy) {
                return orderBy.order();
            }
            if (current instanceof Aggregate) {
                return List.of();
            }
            current = unary.child();
        }
        return List.of();
    }

    /**
     * Verifies that the result rows are sorted according to the given sort keys.
     * Rows with equal sort key values (ties) may appear in any relative order.
     * Assumes ES default null ordering (nulls last for ASC, first for DESC);
     * the generator always uses {@link Order.NullsPosition#ANY}.
     */
    private static void verifySortOrder(List<Order> orders, Simulator.Result result, String label, String query) {
        int numRows = result.numRows();
        if (numRows <= 1) {
            return;
        }
        List<List<Object>> sortKeyValues = orders.stream().map(o -> result.evaluate(o.child(), SimBug.BUG_FREE).values()).toList();
        for (int r = 0; r < numRows - 1; r++) {
            for (int k = 0; k < orders.size(); k++) {
                Object curr = normalizeValue(sortKeyValues.get(k).get(r));
                Object next = normalizeValue(sortKeyValues.get(k).get(r + 1));
                boolean asc = orders.get(k).direction() == Order.OrderDirection.ASC;
                int cmp = compareSortValues(curr, next, asc);
                if (cmp > 0) {
                    throw new AssertionError(
                        Strings.format(
                            "%s result not sorted correctly at rows %d-%d for query [%s]: values [%s, %s] (expected %s)",
                            label,
                            r,
                            r + 1,
                            query,
                            curr,
                            next,
                            asc ? "ASC" : "DESC"
                        )
                    );
                }
                if (cmp != 0) {
                    break; // This key determined the order, skip remaining keys
                }
            }
        }
    }

    /**
     * Compares two sort key values respecting null ordering: nulls last for ASC, nulls first for DESC.
     * Returns negative if a should come before b, positive if after, zero if equal.
     */
    private static int compareSortValues(Object a, Object b, boolean asc) {
        if (a == null && b == null) {
            return 0;
        }
        if (a == null) {
            return asc ? 1 : -1;  // null last for ASC, first for DESC
        }
        if (b == null) {
            return asc ? -1 : 1;
        }
        int cmp = compareValues(a, b);
        return asc ? cmp : -cmp;
    }

    /** Builds rows from columnar data, normalizing values (Integer → Long). */
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
