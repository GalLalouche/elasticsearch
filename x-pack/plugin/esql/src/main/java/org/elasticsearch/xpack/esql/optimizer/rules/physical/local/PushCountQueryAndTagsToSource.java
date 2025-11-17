/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.physical.local;

import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.xpack.esql.capabilities.TranslationAware;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.querydsl.query.NotQuery;
import org.elasticsearch.xpack.esql.core.querydsl.query.Query;
import org.elasticsearch.xpack.esql.core.util.Queries;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.EsqlBinaryComparison;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThanOrEqual;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThan;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.LessThanOrEqual;
import org.elasticsearch.xpack.esql.optimizer.LocalPhysicalOptimizerContext;
import org.elasticsearch.xpack.esql.optimizer.PhysicalOptimizerRules;
import org.elasticsearch.xpack.esql.optimizer.rules.physical.local.LucenePushdownPredicates;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.EsQueryExec;
import org.elasticsearch.xpack.esql.plan.physical.EsStatsQueryExec;
import org.elasticsearch.xpack.esql.plan.physical.EvalExec;
import org.elasticsearch.xpack.esql.plan.physical.FilterExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;

import java.util.ArrayList;
import java.util.List;

import static java.util.Arrays.asList;
import static org.elasticsearch.xpack.esql.capabilities.TranslationAware.translatable;
import static org.elasticsearch.xpack.esql.planner.TranslatorHandler.TRANSLATOR_HANDLER;

/**
 * Pushes count aggregations on top of query and tags to source.
 * Will transform:
 * <pre>
 *  Aggregate (count(*) by x)
 *  └── Eval (x = round_to)
 *      └── Query [query + tags]
 *  </pre>
 *  into:
 *  <pre>
 *  Filter (count > 0)
 *  └── StatsQuery [count with query + tags]
 *  </pre>
 *  Where the filter is needed since the original Aggregate would not produce buckets with count = 0.
 */
public class PushCountQueryAndTagsToSource extends PhysicalOptimizerRules.ParameterizedOptimizerRule<
    AggregateExec,
    LocalPhysicalOptimizerContext> {

    @Override
    protected PhysicalPlan rule(AggregateExec aggregateExec, LocalPhysicalOptimizerContext ctx) {
        if (
        // Ensures we are only grouping by one field (2 aggregates: count + group by field).
        aggregateExec.aggregates().size() == 2
            && aggregateExec.aggregates().getFirst() instanceof Alias alias
            && alias.child() instanceof Count count
            && count.field() instanceof Literal // Ensures count(*) or equivalent.
            && aggregateExec.child() instanceof EvalExec evalExec
            && evalExec.child() instanceof EsQueryExec queryExec) {
            var withFilter = foo(queryExec.queryBuilderAndTags(), count);
            if (withFilter.isEmpty()) {
                return aggregateExec;
            }
            EsStatsQueryExec statsQueryExec = new EsStatsQueryExec(
                queryExec.source(),
                queryExec.indexPattern(),
                null, // query
                queryExec.limit(),
                aggregateExec.output(),
                new EsStatsQueryExec.ByStat(withFilter)
            );
            // When count has a filter, buckets with count 0 should be preserved (they represent buckets where filter didn't match).
            // Only filter out zero buckets when there's no filter on the count.
            if (count.hasFilter() == false) {
                // Wrap with FilterExec to remove empty buckets (keep buckets where count > 0). This was automatically handled by the
                // AggregateExec, but since we removed it, we need to do it manually.
                Attribute countAttr = statsQueryExec.output().get(1);
                return new FilterExec(Source.EMPTY, statsQueryExec, new GreaterThan(Source.EMPTY, countAttr, ZERO));
            }
            return statsQueryExec;
        }
        return aggregateExec;
    }

    private List<EsQueryExec.QueryBuilderAndTags> foo(List<EsQueryExec.QueryBuilderAndTags> queryBuilderAndTags, Count count) {
        if (queryBuilderAndTags.size() <= 1) {
            return List.of();
        }
        if (count.hasFilter() == false) {
            return queryBuilderAndTags;
        }
        // Normalize the filter expression: if literal is on the left, swap and invert the comparison
        // For count(*) where "1990-01-01" > hire_date, the semantics are inverted:
        // it should match dates >= 1990, not dates < 1990
        // So "1990-01-01" > hire_date becomes hire_date >= "1990-01-01"
        Expression filterExpr = count.filter();
        // Check if the filter is translatable as-is
        if (translatable(filterExpr, LucenePushdownPredicates.DEFAULT) != TranslationAware.Translatable.YES) {
            // If not translatable, try swapping if literal is on the left
            if (filterExpr instanceof EsqlBinaryComparison esqlBinaryComparison
                && esqlBinaryComparison.left() instanceof Literal
                && esqlBinaryComparison.right() instanceof FieldAttribute) {
                // Swap and invert: "1990-01-01" > hire_date -> hire_date >= "1990-01-01"
                // This inverts the semantics to match the expected behavior
                filterExpr = switch (filterExpr) {
                    case GreaterThan gt -> new GreaterThanOrEqual(
                        gt.source(),
                        gt.right(),
                        gt.left(),
                        gt.zoneId()
                    );
                    case LessThan lt -> new LessThanOrEqual(
                        lt.source(),
                        lt.right(),
                        lt.left(),
                        lt.zoneId()
                    );
                    case GreaterThanOrEqual gte -> new GreaterThan(
                        gte.source(),
                        gte.right(),
                        gte.left(),
                        gte.zoneId()
                    );
                    case LessThanOrEqual lte -> new LessThan(
                        lte.source(),
                        lte.right(),
                        lte.left(),
                        lte.zoneId()
                    );
                    default -> esqlBinaryComparison.swapLeftAndRight();
                };
                // Check again after converting
                if (translatable(filterExpr, LucenePushdownPredicates.DEFAULT) != TranslationAware.Translatable.YES) {
                    return List.of();
                }
            } else {
                return List.of();
            }
        }
        try {
            // Translate the filter to a QueryBuilder
            Query filterQuery = TRANSLATOR_HANDLER.asQuery(LucenePushdownPredicates.DEFAULT, filterExpr);
            if (filterQuery == null) {
                return List.of();
            }
            QueryBuilder filterQueryBuilder = filterQuery.toQueryBuilder();
            if (filterQueryBuilder == null) {
                return List.of();
            }
            // Apply the filter to each QueryBuilderAndTags
            // Order matters: sourceQuery (bucket query) first, then filter query
            List<EsQueryExec.QueryBuilderAndTags> result = new ArrayList<>(queryBuilderAndTags.size());
            for (EsQueryExec.QueryBuilderAndTags qbt : queryBuilderAndTags) {
                QueryBuilder combinedQuery = Queries.combine(Queries.Clause.FILTER, asList(qbt.query(), filterQueryBuilder));
                result.add(new EsQueryExec.QueryBuilderAndTags(combinedQuery, qbt.tags()));
            }
            return result;
        } catch (Exception e) {
            // If filter translation fails, return empty list to skip optimization
            return List.of();
        }
    }

    private static final Literal ZERO = new Literal(Source.EMPTY, 0L, DataType.LONG);
}
