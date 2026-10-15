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
package org.apache.unomi.itests;

import org.apache.unomi.api.Event;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.conditions.Condition;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Runs the same table of {@code pastEventCondition} queries against a fixed data set and asserts the exact matched
 * profile IDs. The expected IDs are constants shared by both engines, so this test passing on Elasticsearch and on
 * OpenSearch proves both query builders return identical results. The in-memory condition evaluator is used as the
 * semantic oracle. Per-case results are written to {@code itests/target/parity/pastevent-<engine>.json}.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class PastEventConditionBackendParityIT extends BaseIT {

    private static final Logger LOGGER = LoggerFactory.getLogger(PastEventConditionBackendParityIT.class);

    private static final String VIEW = "peParityView";
    private static final String CLICK = "peParityClick";
    private static final String A = "pe-parity-A";
    private static final String B = "pe-parity-B";
    private static final String C = "pe-parity-C";
    private static final String D = "pe-parity-D";
    private static final String E = "pe-parity-E";
    private static final String F = "pe-parity-F";
    private static final long DAY = 24L * 60 * 60 * 1000;

    private final long now = System.currentTimeMillis();
    private final Map<String, Profile> profiles = new LinkedHashMap<>();
    private int eventCount;

    @Before
    public void setUp() throws InterruptedException {
        removeItems(Profile.class, Event.class);

        // Recent = within the last 10 days, old = more than 30 days ago. Offsets are x.5 days so no event sits on a boundary.
        createProfile(A);
        createProfile(B);
        createProfile(C);
        createProfile(D);
        createProfile(E);
        createProfile(F);
        sendEvents(A, VIEW, 2.5, 3.5, 4.5);
        sendEvents(B, VIEW, 1.5);
        sendEvents(C, VIEW, 1.5, 2.5, 40.5, 41.5, 42.5);
        sendEvents(D, VIEW, 60.5, 61.5);
        sendEvents(F, CLICK, 1.5, 2.5);

        // Precomputed counters used by the generatedPropertyKey fast path (key of "VIEW in the last 10 days").
        String key = generatedKey(occurred(VIEW, c -> c.setParameter("numberOfDays", 10)));
        setPastEvents(A, key, 3);
        setPastEvents(B, key, 1);
        setPastEvents(C, key, 2);
        setPastEvents(D, key, 0);
        setPastEvents(F, "eventTriggeredSomeOtherKey", 4);
        for (Profile profile : profiles.values()) {
            persistenceService.save(profile);
        }

        persistenceService.refreshIndex(Profile.class);
        persistenceService.refreshIndex(Event.class);
        keepTrying("Seeded events should be searchable",
                () -> persistenceService.queryCount(definitionsService.getConditionBuilder().matchAll().build(), Event.ITEM_TYPE),
                count -> count == eventCount, 1000, 30);
        keepTrying("Seeded profiles should be searchable",
                () -> persistenceService.queryCount(definitionsService.getConditionBuilder().matchAll().build(), Profile.ITEM_TYPE),
                count -> count == profiles.size(), 1000, 30);
    }

    @After
    public void tearDown() throws InterruptedException {
        removeItems(Profile.class, Event.class);
    }

    @Test
    public void testPastEventConditionParity() throws Exception {
        Map<String, Condition> cases = new LinkedHashMap<>();
        Map<String, Set<String>> expected = new LinkedHashMap<>();

        // Aggregation over events (no generatedPropertyKey)
        add(cases, expected, "occurred", occurred(VIEW, c -> {}), A, B, C, D);
        add(cases, expected, "occurred_min2", occurred(VIEW, c -> c.setParameter("minimumEventCount", 2)), A, C, D);
        add(cases, expected, "occurred_min2_string", occurred(VIEW, c -> c.setParameter("minimumEventCount", "2")), A, C, D);
        add(cases, expected, "occurred_max2", occurred(VIEW, c -> c.setParameter("maximumEventCount", 2)), B, D);
        add(cases, expected, "occurred_min2_max3", occurred(VIEW, c -> {
            c.setParameter("minimumEventCount", 2);
            c.setParameter("maximumEventCount", 3);
        }), A, D);
        add(cases, expected, "occurred_min5", occurred(VIEW, c -> c.setParameter("minimumEventCount", 5)), C);
        add(cases, expected, "occurred_min6", occurred(VIEW, c -> c.setParameter("minimumEventCount", 6)));
        add(cases, expected, "occurred_days10", occurred(VIEW, c -> c.setParameter("numberOfDays", 10)), A, B, C);
        add(cases, expected, "occurred_days10_min2", occurred(VIEW, c -> {
            c.setParameter("numberOfDays", 10);
            c.setParameter("minimumEventCount", 2);
        }), A, C);
        add(cases, expected, "occurred_days10_max1", occurred(VIEW, c -> {
            c.setParameter("numberOfDays", 10);
            c.setParameter("maximumEventCount", 1);
        }), B);
        add(cases, expected, "occurred_from50d_to30d", occurred(VIEW, c -> {
            c.setParameter("fromDate", daysAgo(50));
            c.setParameter("toDate", daysAgo(30));
        }), C);
        add(cases, expected, "occurred_from3d", occurred(VIEW, c -> c.setParameter("fromDate", daysAgo(3))), A, B, C);
        add(cases, expected, "occurred_from3d_min2", occurred(VIEW, c -> {
            c.setParameter("fromDate", daysAgo(3));
            c.setParameter("minimumEventCount", 2);
        }), C);
        add(cases, expected, "occurred_to30d", occurred(VIEW, c -> c.setParameter("toDate", daysAgo(30))), C, D);
        add(cases, expected, "occurred_other_event_type", occurred(CLICK, c -> {}), F);
        add(cases, expected, "notOccurred", notOccurred(VIEW, c -> {}), E, F);
        add(cases, expected, "notOccurred_days10", notOccurred(VIEW, c -> c.setParameter("numberOfDays", 10)), D, E, F);
        add(cases, expected, "notOccurred_days10_min3_ignored", notOccurred(VIEW, c -> {
            c.setParameter("numberOfDays", 10);
            c.setParameter("minimumEventCount", 3);
        }), D, E, F);
        add(cases, expected, "notOccurred_from50d_to30d", notOccurred(VIEW, c -> {
            c.setParameter("fromDate", daysAgo(50));
            c.setParameter("toDate", daysAgo(30));
        }), A, B, D, E, F);

        // Fast path: counters stored on profiles (systemProperties.pastEvents) via generatedPropertyKey
        add(cases, expected, "fastpath_occurred", withKey(occurred(VIEW, c -> c.setParameter("numberOfDays", 10))), A, B, C);
        add(cases, expected, "fastpath_occurred_min2", withKey(occurred(VIEW, c -> {
            c.setParameter("numberOfDays", 10);
            c.setParameter("minimumEventCount", 2);
        })), A, C);
        add(cases, expected, "fastpath_occurred_max2", withKey(occurred(VIEW, c -> {
            c.setParameter("numberOfDays", 10);
            c.setParameter("maximumEventCount", 2);
        })), B, C);
        add(cases, expected, "fastpath_notOccurred", withKey(notOccurred(VIEW, c -> c.setParameter("numberOfDays", 10))), D, E, F);

        Map<String, Object> report = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Condition> entry : cases.entrySet()) {
            String name = entry.getKey();
            Condition condition = entry.getValue();
            Set<String> want = expected.get(name);

            Set<String> queryIds = runQuery(condition);
            long count = persistenceService.queryCount(condition, Profile.ITEM_TYPE);
            Set<String> oracleIds = profiles.values().stream()
                    .filter(p -> persistenceService.testMatch(condition, p))
                    .map(Profile::getItemId)
                    .collect(Collectors.toCollection(TreeSet::new));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("expected", want);
            row.put("query", queryIds);
            row.put("queryCount", count);
            row.put("evaluator", oracleIds);
            report.put(name, row);

            if (!want.equals(queryIds)) {
                failures.add(name + ": query expected " + want + " but was " + queryIds);
            }
            if (count != want.size()) {
                failures.add(name + ": queryCount expected " + want.size() + " but was " + count);
            }
            if (!want.equals(oracleIds)) {
                failures.add(name + ": evaluator expected " + want + " but was " + oracleIds);
            }
        }

        writeReport(report);
        Assert.assertTrue("pastEventCondition parity failures on " + searchEngine + ":\n" + String.join("\n", failures),
                failures.isEmpty());
    }

    private Set<String> runQuery(Condition condition) {
        try {
            return persistenceService.query(condition, null, Profile.class).stream()
                    .map(Profile::getItemId)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (RuntimeException e) {
            LOGGER.error("Query failed for condition {}", condition, e);
            return new TreeSet<>(Collections.singleton("ERROR: " + e.getMessage()));
        }
    }

    private void add(Map<String, Condition> cases, Map<String, Set<String>> expected, String name, Condition condition, String... ids) {
        cases.put(name, condition);
        expected.put(name, new TreeSet<>(Arrays.asList(ids)));
    }

    private Condition occurred(String eventType, Consumer<Condition> customizer) {
        return pastEvent(eventType, "eventsOccurred", customizer);
    }

    private Condition notOccurred(String eventType, Consumer<Condition> customizer) {
        return pastEvent(eventType, "eventsNotOccurred", customizer);
    }

    private Condition pastEvent(String eventType, String operator, Consumer<Condition> customizer) {
        Condition eventCondition = new Condition(definitionsService.getConditionType("eventTypeCondition"));
        eventCondition.setParameter("eventTypeId", eventType);
        Condition condition = new Condition(definitionsService.getConditionType("pastEventCondition"));
        condition.setParameter("eventCondition", eventCondition);
        condition.setParameter("operator", operator);
        customizer.accept(condition);
        return condition;
    }

    private Condition withKey(Condition condition) {
        condition.setParameter("generatedPropertyKey", generatedKey(condition));
        return condition;
    }

    private String generatedKey(Condition condition) {
        return segmentService.getGeneratedPropertyKey((Condition) condition.getParameter("eventCondition"), condition);
    }

    private void createProfile(String id) {
        Profile profile = new Profile(id);
        profiles.put(id, profile);
    }

    private void setPastEvents(String profileId, String key, int count) {
        Map<String, Object> pastEvent = new HashMap<>();
        pastEvent.put("key", key);
        pastEvent.put("count", count);
        List<Map<String, Object>> pastEvents = new ArrayList<>();
        pastEvents.add(pastEvent);
        profiles.get(profileId).getSystemProperties().put("pastEvents", pastEvents);
    }

    private void sendEvents(String profileId, String eventType, double... daysAgo) {
        Profile profile = profiles.get(profileId);
        for (double d : daysAgo) {
            Event event = new Event(eventType, null, profile, "pe-parity", null, profile, daysAgo(d));
            event.setPersistent(true);
            persistenceService.save(event);
            eventCount++;
        }
    }

    private Date daysAgo(double days) {
        return new Date(now - (long) (days * DAY));
    }

    private void writeReport(Map<String, Object> report) {
        try {
            String karafBase = System.getProperty("karaf.base");
            Path targetDir = karafBase != null ? Paths.get(karafBase).getParent().getParent() : Paths.get("target");
            File out = targetDir.resolve("parity").resolve("pastevent-" + searchEngine + ".json").toFile();
            Files.createDirectories(out.getParentFile().toPath());
            getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, report);
            LOGGER.info("Wrote pastEventCondition parity report to {}", out.getAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("Could not write parity report", e);
        }
    }
}
