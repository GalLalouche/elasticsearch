/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.optimizer.rules.physical.local;

import org.elasticsearch.core.Nullable;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilder;
import org.elasticsearch.xpack.esql.capabilities.TranslationAware;
import org.elasticsearch.xpack.esql.core.expression.Alias;
import org.elasticsearch.xpack.esql.core.expression.Attribute;
import org.elasticsearch.xpack.esql.core.expression.Expression;
import org.elasticsearch.xpack.esql.core.expression.FieldAttribute;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;
import org.elasticsearch.xpack.esql.core.util.Queries;
import org.elasticsearch.xpack.esql.expression.function.aggregate.Count;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.EsqlBinaryComparison;
import org.elasticsearch.xpack.esql.expression.predicate.operator.comparison.GreaterThan;
import org.elasticsearch.xpack.esql.optimizer.LocalPhysicalOptimizerContext;
import org.elasticsearch.xpack.esql.optimizer.PhysicalOptimizerRules;
import org.elasticsearch.xpack.esql.plan.physical.AggregateExec;
import org.elasticsearch.xpack.esql.plan.physical.EsQueryExec;
import org.elasticsearch.xpack.esql.plan.physical.EsStatsQueryExec;
import org.elasticsearch.xpack.esql.plan.physical.EvalExec;
import org.elasticsearch.xpack.esql.plan.physical.FilterExec;
import org.elasticsearch.xpack.esql.plan.physical.PhysicalPlan;
import org.elasticsearch.xpack.esql.querydsl.query.SingleValueQuery;

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
            var withFilter = applyFilter(queryExec.queryBuilderAndTags(), count);
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

    /**
     * Returns an empty list if no pushdown is possible due to incompatible filters.
     * Otherwise, returns an updated list with the filter (if there is no filter, it will just return the original query and tags). */
    private List<EsQueryExec.QueryBuilderAndTags> applyFilter(List<EsQueryExec.QueryBuilderAndTags> queryBuilderAndTags, Count count) {
        if (queryBuilderAndTags.size() <= 1) {
            return List.of();
        }
        if (count.hasFilter() == false) {
            return queryBuilderAndTags;
        }
        var fieldName = getFieldName(queryBuilderAndTags.getFirst().query());
        // FIXME(gal, NOCOMMIT) Verify all query builders have the same field name
        if (fieldName == null) {
            return List.of();
        }
        Expression filterExpr = count.filter();
        // Only apply the filter if it applies to the same field as the grouping.
        if (filterExpr instanceof EsqlBinaryComparison binaryComparison
            && binaryComparison.left() instanceof FieldAttribute filterField
            && filterField.name().equals(fieldName) == false) {
            return List.of();
        }
        if (translatable(filterExpr, LucenePushdownPredicates.DEFAULT) != TranslationAware.Translatable.YES) {
            return List.of();
        }
        QueryBuilder filterQueryBuilder = TRANSLATOR_HANDLER.asQuery(LucenePushdownPredicates.DEFAULT, filterExpr).toQueryBuilder();
        return queryBuilderAndTags.stream()
            .map(qbt -> new EsQueryExec.QueryBuilderAndTags(combine(qbt, filterQueryBuilder), qbt.tags()))
            .toList();
    }

    private static @Nullable String getFieldName(QueryBuilder queryBuilder) {
        return switch (queryBuilder) {
            case SingleValueQuery.Builder svq -> svq.field();
            case BoolQueryBuilder bq -> {
                var fieldNames = bq.filter().stream().map(PushCountQueryAndTagsToSource::getFieldName).distinct().toList();
                yield fieldNames.size() == 1 ? fieldNames.getFirst() : null;
            }
            default -> null;
        };
    }

    private static QueryBuilder combine(EsQueryExec.QueryBuilderAndTags qbt, QueryBuilder filterQueryBuilder) {
        return Queries.combine(Queries.Clause.FILTER, asList(qbt.query(), filterQueryBuilder));
    }

    private static final Literal ZERO = new Literal(Source.EMPTY, 0L, DataType.LONG);
}
