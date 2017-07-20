// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache license. Please see LICENSE.md file distributed with this work for terms.
package com.yahoo.bard.webservice.data;

import com.yahoo.bard.webservice.data.metric.LogicalMetric;
import com.yahoo.bard.webservice.druid.model.having.AndHaving;
import com.yahoo.bard.webservice.druid.model.having.Having;
import com.yahoo.bard.webservice.druid.model.having.NotHaving;
import com.yahoo.bard.webservice.druid.model.having.NumericHaving;
import com.yahoo.bard.webservice.druid.model.having.OrHaving;
import com.yahoo.bard.webservice.web.ApiHaving;
import com.yahoo.bard.webservice.web.HavingOperation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Map;
import java.util.Arrays;

import java.util.stream.Collectors;

import static com.yahoo.bard.webservice.web.ErrorMessageFormat.HAVING_CUSTOM_OPERATOR_IMPROPER_RANGE;
import static com.yahoo.bard.webservice.web.ErrorMessageFormat.HAVING_CUSTOM_OPERATOR_TOO_FEW_PARAMETERS;

/**
 * Class to hold static methods to build druid query model objects from ApiHaving.
 */
public class DruidHavingBuilder {
    private static final Logger LOG = LoggerFactory.getLogger(DruidHavingBuilder.class);

    /**
     * Build a having model that ANDs together having queries for each of the metrics.
     *
     * @param metricMap  A map of logical metric to the set of havings for that metric
     * @return The having clause to appear in the Druid query. Returns null if the metricMap is empty or null.
     */
    public static Having buildHavings(Map<LogicalMetric, Set<ApiHaving>> metricMap) {
        // return null when no metrics are specified in the API
        if (metricMap == null || metricMap.isEmpty()) {
            return null;
        }

        List<Having> havings = metricMap.entrySet().stream()
                .map(entry -> buildMetricHaving(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        // for a single having just return the entry
        Having newHaving = havings.size() == 1 ? havings.get(0) : new AndHaving(havings);
        LOG.trace("Having: {}", newHaving);
        return newHaving;
    }

    /**
     * Build a Having for all the having queries for a single metric, ANDing them together.
     *
     * @param metric  Metric for the having query
     * @param havings  All having queries belonging to that metric
     * @return A druid query having object representing the having clause on a given metric
     */
    public static Having buildMetricHaving(LogicalMetric metric, Set<ApiHaving> havings) {
        LOG.trace("Building metric having using metric: {} \n\n and set of queries: {}", metric, havings);
        List<Having> orHavings = havings.stream()
                .map(having -> buildHaving(metric, having))
                .collect(Collectors.toList());
        Having newHaving = orHavings.size() == 1 ? orHavings.get(0) : new AndHaving(orHavings);
        LOG.trace("Filter: {}", newHaving);
        return newHaving;
    }

    /**
     * Create a list of NumericHavings for the values specified and OR them together.
     *
     * @param metric  The metric that the operation applied to.
     * @param having  The ApiHaving object
     * @return A single having representing the API Filter
     */
    public static Having buildHaving(LogicalMetric metric, ApiHaving having) {
        LOG.trace("Building having using metric: {} and API Having: {}", metric, having);

        HavingOperation operation = having.getOperation();
        List<Double> values = having.getValues();
        List<Having> havings = new ArrayList<>();
        AndHaving andHaving = null;
        if (operation.equals(HavingOperation.between) || operation.equals(HavingOperation.notBetween)) {
            final int SIZE = 2;
            if (values.size() != SIZE) {
                throw new UnsupportedOperationException(HAVING_CUSTOM_OPERATOR_TOO_FEW_PARAMETERS.format
                        (operation.name(), operation.name(), SIZE));
            }
            else {
                double firstValue = values.get(0);
                double secondValue = values.get(1);
                if (secondValue < firstValue) {
                    throw new IllegalArgumentException(HAVING_CUSTOM_OPERATOR_IMPROPER_RANGE.format(operation.name()));
                }
                else {
                    List<Having> orHavings = new ArrayList<>();
                    orHavings.add(new NumericHaving(Having.DefaultHavingType.GREATER_THAN, metric.getName(),
                            firstValue));
                    orHavings.add(new NumericHaving(Having.DefaultHavingType.EQUAL_TO, metric.getName(), firstValue));
                    OrHaving orHavingLower = new OrHaving(orHavings);
                    orHavings = new ArrayList<>();
                    orHavings.add(new NumericHaving(Having.DefaultHavingType.LESS_THAN, metric.getName(), secondValue));
                    orHavings.add(new NumericHaving(Having.DefaultHavingType.EQUAL_TO, metric.getName(), secondValue));
                    OrHaving orHavingUpper = new OrHaving(orHavings);
                    andHaving = new AndHaving(Arrays.asList(orHavingLower, orHavingUpper));
                }
            }
        }
        else {
            havings = having.getValues().stream()
                    .map(value -> new NumericHaving(operation.getType(), metric.getName(), value))
                    .collect(Collectors.toList());
        }

        // Negate the outer having to preserve expected semantics of negated queries.
        // For example, the having
        //     metric1-notEqualTo[2,4,8]
        // can either be parsed as
        //     !(metric1 == 2) || !(metric1 == 4) || !(metric1 == 8)
        // or
        //     !(metric1 == 2 || metric1 == 4 || metric1 == 8)
        // . The having
        //     metric1-notGreaterThan[2,4,8]
        // can either be parsed as
        //     !(metric1 > 2) || !(metric1 > 4) || !(metric1 > 8)
        // or
        //     !(metric1 > 2 || metric1 > 4 || metric1 > 8)
        // . In both cases, the second method is correct.

        Having newHaving;
        if (operation.equals(HavingOperation.between)) {
            newHaving = andHaving;
        }
        else if (operation.equals(HavingOperation.notBetween)) {
            newHaving = new NotHaving(andHaving);
        }
        else {
            newHaving = havings.size() == 1 ? havings.get(0) : new OrHaving(havings);
            newHaving = operation.isNegated() ? new NotHaving(newHaving) : newHaving;
        }
        return newHaving;
    }
}
