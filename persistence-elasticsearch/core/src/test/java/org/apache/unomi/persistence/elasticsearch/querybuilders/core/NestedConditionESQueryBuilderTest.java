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
package org.apache.unomi.persistence.elasticsearch.querybuilders.core;

import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.JsonpUtils;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.persistence.elasticsearch.ConditionESQueryBuilderDispatcher;
import org.junit.Test;

import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

/**
 * Checks the JSON produced by the nested condition builder. The same expected JSON is used in
 * NestedConditionOSQueryBuilderTest so both backends are held to one shape.
 */
public class NestedConditionESQueryBuilderTest {

    static final String EXPECTED_JSON = "{\"nested\":{\"path\":\"properties.interests\","
            + "\"query\":{\"term\":{\"properties.interests.key\":{\"value\":\"cars\"}}},\"score_mode\":\"avg\"}}";

    private final ConditionESQueryBuilderDispatcher dispatcher = new ConditionESQueryBuilderDispatcher() {
        @Override
        public Query buildFilter(Condition condition, Map<String, Object> context) {
            return Query.of(q -> q.term(t -> t.field("properties.interests.key").value(FieldValue.of("cars"))));
        }
    };

    @Test
    public void buildQuery_wrapsSubConditionInNestedQueryWithPathAndAvgScore() throws Exception {
        Condition condition = new Condition();
        condition.setParameter("path", "properties.interests");
        condition.setParameter("subCondition", new Condition());

        Query query = new NestedConditionESQueryBuilder().buildQuery(condition, Collections.emptyMap(), dispatcher);

        ObjectMapper mapper = new ObjectMapper();
        assertEquals(mapper.readTree(EXPECTED_JSON),
                mapper.readTree(JsonpUtils.toJsonString(query, new JacksonJsonpMapper())));
    }

    @Test
    public void buildQuery_missingPath_throwsIllegalArgumentException() {
        Condition condition = new Condition();
        condition.setParameter("subCondition", new Condition());
        assertThrows(IllegalArgumentException.class,
                () -> new NestedConditionESQueryBuilder().buildQuery(condition, Collections.emptyMap(), dispatcher));
    }
}
