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
 * limitations under the License
 */

package org.apache.unomi.itests;

import org.apache.unomi.api.CustomItem;
import org.apache.unomi.api.Event;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.utils.ConditionBuilder;
import org.apache.unomi.persistence.spi.CustomObjectMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Checks that the Elasticsearch and OpenSearch query builders for {@code sourceEventPropertyCondition} return the
 * same events. The expected event ids are constants shared by both engines, so this test passing on both engines
 * proves the two backends agree. The in-memory condition evaluator is checked against the same constants, so the
 * persisted query and the in-memory evaluation also agree.
 * <p>
 * The actual id sets of each case are written to {@code itests/target/parity/sourceeventproperty-<engine>.json}
 * so the Elasticsearch and OpenSearch runs can be compared with a plain diff.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class SourceEventPropertyConditionBackendParityIT extends BaseIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(SourceEventPropertyConditionBackendParityIT.class);

    private static final String EVENT_TYPE = "sepcParityTest";
    private static final String ID_PREFIX = "sepc-parity-";
    private static final List<String> ALL = Arrays.asList("e1", "e2", "e3", "e4", "e5", "e6", "e7");

    private ConditionBuilder builder;
    private final List<Event> events = new ArrayList<>();

    private static final class ParityCase {
        final String name;
        final Map<String, Object> parameters;
        final Set<String> expected;

        ParityCase(String name, Map<String, Object> parameters, String... expected) {
            this.name = name;
            this.parameters = parameters;
            this.expected = new TreeSet<>(Arrays.asList(expected));
        }
    }

    private static Map<String, Object> params(String... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private static List<ParityCase> cases() {
        return Arrays.asList(
                new ParityCase("id", params("id", "page-home"), "e1", "e3", "e5"),
                new ParityCase("path", params("path", "/home"), "e1", "e3", "e5"),
                new ParityCase("scope", params("scope", "sitea"), "e1", "e2", "e4", "e5"),
                new ParityCase("type", params("type", "page"), "e1", "e2", "e3", "e7"),
                new ParityCase("id+scope", params("id", "page-home", "scope", "sitea"), "e1", "e5"),
                new ParityCase("id+type", params("id", "page-home", "type", "page"), "e1", "e3"),
                new ParityCase("id+path+scope+type", params("id", "page-home", "path", "/home", "scope", "sitea", "type", "page"), "e1"),
                new ParityCase("path+type", params("path", "/about", "type", "page"), "e2"),
                new ParityCase("unknown id", params("id", "does-not-exist")),
                new ParityCase("empty id only", params("id", ""), ALL.toArray(new String[0])),
                new ParityCase("no parameters", params(), ALL.toArray(new String[0])),
                new ParityCase("empty id + scope", params("id", "", "scope", "siteb"), "e3"),
                new ParityCase("type without path", params("type", "form"), "e4"),
                new ParityCase("mixed-case id", params("id", "Page-Mixed"), "e7"),
                new ParityCase("lower-case id on mixed-case data", params("id", "page-mixed"), "e7"),
                new ParityCase("mixed-case scope", params("scope", "SiteMixed"), "e7"),
                new ParityCase("accented scope", params("scope", "SitéMixed"), "e7")
        );
    }

    private static CustomItem source(String itemId, String itemType, String scope, String pagePath) {
        CustomItem item = new CustomItem(itemId, itemType);
        item.setScope(scope);
        Map<String, Object> properties = new HashMap<>();
        if (pagePath != null) {
            Map<String, Object> pageInfo = new HashMap<>();
            pageInfo.put("pagePath", pagePath);
            properties.put("pageInfo", pageInfo);
        }
        item.setProperties(properties);
        return item;
    }

    @Before
    public void setUp() throws Exception {
        builder = definitionsService.getConditionBuilder();
        Profile profile = new Profile(ID_PREFIX + "profile");
        Date timestamp = new GregorianCalendar(2026, Calendar.JANUARY, 15, 12, 0, 0).getTime();
        Map<String, CustomItem> sources = new LinkedHashMap<>();
        sources.put("e1", source("page-home", "page", "sitea", "/home"));
        sources.put("e2", source("page-about", "page", "sitea", "/about"));
        sources.put("e3", source("page-home", "page", "siteb", "/home"));
        sources.put("e4", source("form-1", "form", "sitea", null));
        sources.put("e5", source("page-home", "article", "sitea", "/home"));
        sources.put("e6", null);
        sources.put("e7", source("Page-Mixed", "page", "SiteMixed", "/Mixed"));
        for (Map.Entry<String, CustomItem> entry : sources.entrySet()) {
            Event event = new Event(ID_PREFIX + entry.getKey(), EVENT_TYPE, null, profile, "parityscope", entry.getValue(), null,
                    timestamp);
            event.setPersistent(true);
            events.add(event);
            persistenceService.save(event);
        }
        LOGGER.info("Seeded source of e1 as stored: {}", CustomObjectMapper.getObjectMapper().writeValueAsString(events.get(0).getSource()));
        persistenceService.refreshIndex(Event.class, timestamp);
        keepTrying("Seeded events should be searchable",
                () -> persistenceService.queryCount(eventTypeCondition(), Event.ITEM_TYPE),
                count -> count == ALL.size(), DEFAULT_TRYING_TIMEOUT, DEFAULT_TRYING_TRIES);
    }

    @After
    public void tearDown() throws Exception {
        for (Event event : events) {
            persistenceService.remove(event.getItemId(), Event.class);
        }
        persistenceService.refreshIndex(Event.class, null);
        events.clear();
    }

    private Condition eventTypeCondition() {
        return builder.condition("eventTypeCondition").parameter("eventTypeId", EVENT_TYPE).build();
    }

    private Condition sourceCondition(Map<String, Object> parameters) {
        ConditionBuilder.ConditionItem item = builder.condition("sourceEventPropertyCondition");
        parameters.forEach((name, value) -> item.parameter(name, value));
        return item.build();
    }

    private static String shortId(String itemId) {
        return itemId.substring(ID_PREFIX.length());
    }

    @Test
    public void testBackendsReturnSameEvents() throws Exception {
        String engine = getPersistenceBackend().providerId();
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("engine", engine);
        Map<String, Object> results = new LinkedHashMap<>();
        report.put("cases", results);
        List<String> failures = new ArrayList<>();

        for (ParityCase parityCase : cases()) {
            Condition source = sourceCondition(parityCase.parameters);
            // The event type filter keeps the query on the seeded events only.
            Condition query = new Condition(definitionsService.getConditionType("booleanCondition"));
            query.setParameter("operator", "and");
            query.setParameter("subConditions", Arrays.asList(eventTypeCondition(), source));

            Set<String> queried = persistenceService.query(query, null, Event.class, 0, 100).getList().stream()
                    .map(e -> shortId(e.getItemId())).collect(Collectors.toCollection(TreeSet::new));
            long count = persistenceService.queryCount(query, Event.ITEM_TYPE);
            Set<String> evaluated = events.stream().filter(e -> persistenceService.testMatch(source, e))
                    .map(e -> shortId(e.getItemId())).collect(Collectors.toCollection(TreeSet::new));

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("parameters", parityCase.parameters);
            result.put("expected", parityCase.expected);
            result.put("query", queried);
            result.put("queryCount", count);
            result.put("evaluator", evaluated);
            results.put(parityCase.name, result);
            LOGGER.info("[{}] case '{}' params={} expected={} query={} count={} evaluator={}", engine, parityCase.name,
                    parityCase.parameters, parityCase.expected, queried, count, evaluated);

            if (!parityCase.expected.equals(queried)) {
                failures.add(String.format("case '%s' %s: query expected %s but got %s", parityCase.name, parityCase.parameters,
                        parityCase.expected, queried));
            }
            if (count != parityCase.expected.size()) {
                failures.add(String.format("case '%s' %s: queryCount expected %d but got %d", parityCase.name, parityCase.parameters,
                        parityCase.expected.size(), count));
            }
            if (!parityCase.expected.equals(evaluated)) {
                failures.add(String.format("case '%s' %s: evaluator expected %s but got %s", parityCase.name, parityCase.parameters,
                        parityCase.expected, evaluated));
            }
        }

        // Used on its own, a condition without usable parameters must not constrain the query (the evaluator
        // treats it as "always a match").
        Condition topLevel = sourceCondition(params());
        Set<String> topLevelIds = new TreeSet<>();
        String topLevelError = null;
        long topLevelCount = -1;
        try {
            topLevelIds = persistenceService.query(topLevel, null, Event.class, 0, 1000).getList().stream()
                    .map(Event::getItemId).filter(id -> id.startsWith(ID_PREFIX)).map(SourceEventPropertyConditionBackendParityIT::shortId)
                    .collect(Collectors.toCollection(TreeSet::new));
            topLevelCount = persistenceService.queryCount(topLevel, Event.ITEM_TYPE);
        } catch (RuntimeException e) {
            topLevelError = e.toString();
        }
        Map<String, Object> topLevelResult = new LinkedHashMap<>();
        topLevelResult.put("query", topLevelIds);
        topLevelResult.put("queryCountAtLeastSeeded", topLevelCount >= ALL.size());
        topLevelResult.put("error", topLevelError);
        report.put("topLevelNoParameters", topLevelResult);
        LOGGER.info("[{}] top-level no parameters: query={} count={} error={}", engine, topLevelIds, topLevelCount, topLevelError);
        if (topLevelError != null || !new TreeSet<>(ALL).equals(topLevelIds) || topLevelCount < ALL.size()) {
            failures.add(String.format("top-level no parameters: expected all seeded events and count >= %d but got %s, count %d, error %s",
                    ALL.size(), topLevelIds, topLevelCount, topLevelError));
        }

        writeReport(engine, report);
        assertTrue("Mismatches on " + engine + ":\n" + String.join("\n", failures), failures.isEmpty());
        assertEquals(cases().size(), results.size());
    }

    private void writeReport(String engine, Map<String, Object> report) {
        try {
            Path dir = parityDirectory();
            Files.createDirectories(dir);
            Path file = dir.resolve("sourceeventproperty-" + engine + ".json");
            Files.write(file, CustomObjectMapper.getObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report)
                    .getBytes(StandardCharsets.UTF_8));
            LOGGER.info("Wrote parity report to {}", file.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("Could not write parity report", e);
        }
    }

    /** Resolves {@code itests/target/parity}; the test runs inside the Karaf container under {@code itests/target/exam}. */
    private static Path parityDirectory() {
        String karafHome = System.getProperty("karaf.home");
        File dir = karafHome != null ? new File(karafHome).getAbsoluteFile() : new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File current = dir; current != null; current = current.getParentFile()) {
            if ("target".equals(current.getName())) {
                return current.toPath().resolve("parity");
            }
        }
        return Paths.get(System.getProperty("user.dir"), "target", "parity");
    }
}
