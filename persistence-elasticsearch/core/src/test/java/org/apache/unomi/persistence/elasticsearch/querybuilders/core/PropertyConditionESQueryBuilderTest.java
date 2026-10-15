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

import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.json.stream.JsonGenerator;
import org.apache.unomi.api.conditions.Condition;
import org.joda.time.DateTime;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PropertyConditionESQueryBuilderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PropertyConditionESQueryBuilder builder = new PropertyConditionESQueryBuilder();

    @Test
    public void integerRangeValueIsSentAsInteger() throws Exception {
        JsonNode range = range(build("greaterThanOrEqualTo", "properties.level", "propertyValueInteger", 10), "properties.level");

        assertTrue("Integer must not be turned into a double: " + range, range.path("gte").isIntegralNumber());
        assertEquals(10, range.path("gte").asLong());
    }

    @Test
    public void longRangeValueKeepsFullPrecision() throws Exception {
        long value = 9007199254740993L;
        JsonNode range = range(build("lessThan", "properties.bigCounter", "propertyValueInteger", value), "properties.bigCounter");

        assertEquals(value, range.path("lt").asLong());
    }

    @Test
    public void betweenLongValuesKeepFullPrecision() throws Exception {
        JsonNode range = range(build("between", "properties.bigCounter", "propertyValuesInteger",
                Arrays.asList(9007199254740993L, 9007199254740995L)), "properties.bigCounter");

        assertEquals(9007199254740993L, range.path("gte").asLong());
        assertEquals(9007199254740995L, range.path("lte").asLong());
    }

    @Test
    public void doubleRangeValueIsUnchanged() throws Exception {
        JsonNode range = range(build("greaterThan", "properties.score", "propertyValueDouble", 0.15), "properties.score");

        assertEquals(0.15, range.path("gt").asDouble(), 0.0);
    }

    @Test
    public void isDayExcludesStartOfNextDay() throws Exception {
        DateTime noon = new DateTime(2015, 2, 1, 12, 0);
        JsonNode range = range(build("isDay", "properties.lastVisit", "propertyValueDate", noon.toDate()), "properties.lastVisit");

        assertTrue("isDay must start at the start of the day: " + range, range.has("gte"));
        assertTrue("isDay must use an exclusive upper bound: " + range, range.has("lt"));
        assertFalse("isDay must not include the start of the next day: " + range, range.has("lte"));
    }

    private Query build(String operator, String propertyName, String valueParameter, Object value) {
        Condition condition = new Condition();
        condition.setParameter("comparisonOperator", operator);
        condition.setParameter("propertyName", propertyName);
        condition.setParameter(valueParameter, value);
        return builder.buildQuery(condition, Collections.emptyMap(), null);
    }

    private static JsonNode range(Query query, String field) throws Exception {
        JacksonJsonpMapper mapper = new JacksonJsonpMapper();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator generator = mapper.jsonProvider().createGenerator(out)) {
            query.serialize(generator, mapper);
        }
        JsonNode json = OBJECT_MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
        JsonNode range = json.path("range").path(field);
        assertTrue("Expected a range query on " + field + ": " + json, range.isObject());
        return range;
    }
}
