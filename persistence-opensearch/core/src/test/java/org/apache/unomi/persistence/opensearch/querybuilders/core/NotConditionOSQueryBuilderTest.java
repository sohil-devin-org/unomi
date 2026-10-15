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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.stream.JsonGenerator;
import org.opensearch.client.json.jackson.JacksonJsonpMapper;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.persistence.opensearch.ConditionOSQueryBuilderDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Checks the query built for notCondition. The expected JSON is the same in the Elasticsearch and the
 * OpenSearch version of this test, so both builders must produce the same query.
 */
public class NotConditionOSQueryBuilderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final NotConditionOSQueryBuilder builder = new NotConditionOSQueryBuilder();

    /** Builds "term" for leaf conditions and recurses into nested notConditions. */
    private final ConditionOSQueryBuilderDispatcher dispatcher = new ConditionOSQueryBuilderDispatcher() {
        @Override
        public Query buildFilter(Condition condition, Map<String, Object> context) {
            if (condition.getParameter("subCondition") != null) {
                return builder.buildQuery(condition, context, this);
            }
            String kind = (String) condition.getParameter("kind");
            if ("matchNone".equals(kind)) {
                return Query.of(q -> q.matchNone(m -> m));
            }
            if ("matchAll".equals(kind)) {
                return Query.of(q -> q.matchAll(m -> m));
            }
            return Query.of(q -> q.term(t -> t.field("properties.gender").value(v -> v.stringValue("female"))));
        }
    };

    @Test
    public void negatesLeafCondition() throws Exception {
        assertJson("{\"bool\":{\"must_not\":[{\"term\":{\"properties.gender\":{\"value\":\"female\"}}}]}}",
                build(not(leaf(null))));
    }

    @Test
    public void negatesMatchNone() throws Exception {
        assertJson("{\"bool\":{\"must_not\":[{\"match_none\":{}}]}}", build(not(leaf("matchNone"))));
    }

    @Test
    public void negatesMatchAll() throws Exception {
        assertJson("{\"bool\":{\"must_not\":[{\"match_all\":{}}]}}", build(not(leaf("matchAll"))));
    }

    @Test
    public void nestsDoubleNegation() throws Exception {
        assertJson("{\"bool\":{\"must_not\":[{\"bool\":{\"must_not\":[{\"term\":{\"properties.gender\":{\"value\":\"female\"}}}]}}]}}",
                build(not(not(leaf(null)))));
    }

    private Query build(Condition condition) {
        return builder.buildQuery(condition, new HashMap<>(), dispatcher);
    }

    private static Condition not(Condition subCondition) {
        Condition condition = new Condition();
        condition.setConditionTypeId("notCondition");
        condition.setParameter("subCondition", subCondition);
        return condition;
    }

    private static Condition leaf(String kind) {
        Condition condition = new Condition();
        condition.setConditionTypeId("profilePropertyCondition");
        if (kind != null) {
            condition.setParameter("kind", kind);
        }
        return condition;
    }

    private static void assertJson(String expected, Query query) throws Exception {
        JacksonJsonpMapper mapper = new JacksonJsonpMapper();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        JsonGenerator generator = mapper.jsonProvider().createGenerator(out);
        query.serialize(generator, mapper);
        generator.close();
        JsonNode actual = OBJECT_MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        assertEquals(OBJECT_MAPPER.readTree(expected), actual);
    }
}
