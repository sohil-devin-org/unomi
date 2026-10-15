/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.unomi.persistence.opensearch.querybuilders.core;

import org.opensearch.client.opensearch._types.query_dsl.Query;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import jakarta.json.stream.JsonGenerator;
import org.apache.unomi.api.Metadata;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.conditions.ConditionType;
import org.apache.unomi.persistence.opensearch.ConditionOSQueryBuilderDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the JSON of matchAllCondition queries. MatchAllConditionESQueryBuilderTest asserts the same JSON strings
 * for Elasticsearch, so both backends send the same query for the same condition.
 */
class MatchAllConditionOSQueryBuilderTest {

    private ConditionOSQueryBuilderDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new ConditionOSQueryBuilderDispatcher();
        dispatcher.addQueryBuilder("matchAllConditionQueryBuilder", new MatchAllConditionOSQueryBuilder());
        dispatcher.addQueryBuilder("booleanConditionQueryBuilder", new BooleanConditionOSQueryBuilder());
        dispatcher.addQueryBuilder("notConditionQueryBuilder", new NotConditionOSQueryBuilder());
    }

    @Test
    void matchAllBuildsMatchAllQuery() {
        assertEquals("{\"match_all\":{}}", toJson(dispatcher.buildFilter(matchAll())));
    }

    @Test
    void matchAllFullQueryKeepsMatchAllFilter() {
        assertEquals("{\"bool\":{\"filter\":[{\"match_all\":{}}],\"must\":[{\"match_all\":{}}]}}",
                toJson(dispatcher.getQueryBuilder(matchAll())));
    }

    @Test
    void matchAllInsideBooleanAndNot() {
        assertEquals("{\"match_all\":{}}", toJson(dispatcher.buildFilter(bool("and", matchAll()))));
        assertEquals("{\"bool\":{\"must\":[{\"match_all\":{}},{\"match_all\":{}}]}}",
                toJson(dispatcher.buildFilter(bool("and", matchAll(), matchAll()))));
        assertEquals("{\"bool\":{\"should\":[{\"match_all\":{}},{\"bool\":{\"must_not\":[{\"match_all\":{}}]}}]}}",
                toJson(dispatcher.buildFilter(bool("or", matchAll(), not(matchAll())))));
        assertEquals("{\"bool\":{\"must_not\":[{\"match_all\":{}}]}}", toJson(dispatcher.buildFilter(not(matchAll()))));
    }

    private static Condition matchAll() {
        return new Condition(conditionType("matchAllCondition", "matchAllConditionQueryBuilder"));
    }

    private static Condition bool(String operator, Condition... subConditions) {
        Condition condition = new Condition(conditionType("booleanCondition", "booleanConditionQueryBuilder"));
        condition.setParameter("operator", operator);
        condition.setParameter("subConditions", Arrays.asList(subConditions));
        return condition;
    }

    private static Condition not(Condition subCondition) {
        Condition condition = new Condition(conditionType("notCondition", "notConditionQueryBuilder"));
        condition.setParameter("subCondition", subCondition);
        return condition;
    }

    private static ConditionType conditionType(String id, String queryBuilder) {
        ConditionType type = new ConditionType(new Metadata(id));
        type.setQueryBuilder(queryBuilder);
        return type;
    }

    private static String toJson(Query query) {
        JacksonJsonpMapper mapper = new JacksonJsonpMapper();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator generator = mapper.jsonProvider().createGenerator(out);
        query.serialize(generator, mapper);
        generator.close();
        return out.toString(StandardCharsets.UTF_8);
    }
}
