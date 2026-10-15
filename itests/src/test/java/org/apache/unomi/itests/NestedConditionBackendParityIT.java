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

import org.apache.unomi.api.Profile;
import org.apache.unomi.api.conditions.Condition;
import org.apache.unomi.api.utils.ConditionBuilder;
import org.apache.unomi.api.utils.ConditionBuilder.ConditionItem;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;

import java.io.File;
import java.util.*;
import java.util.function.Supplier;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks that {@code nestedCondition} returns the same profiles on Elasticsearch and on OpenSearch.
 * <p>
 * The test seeds a fixed set of profiles with nested {@code properties.interests} and
 * {@code systemProperties.pastEvents} objects, then runs a table of nested conditions through the
 * persistence query, the persistence count and the in-memory condition evaluator. Expected IDs are
 * the same constants for both engines, so passing on both proves both engines return the same result.
 * The actual ID sets are written to {@code itests/target/parity/nested-&lt;engine&gt;.json} so the two
 * runs can be diffed.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class NestedConditionBackendParityIT extends BaseIT {

    private static final String PREFIX = "nested-parity-";
    private static final String TAG_PROPERTY = "nestedParityTag";
    private static final String TAG_VALUE = "nestedparity";

    private final List<Profile> seeded = new ArrayList<>();
    private ConditionBuilder builder;

    @Before
    public void setUp() throws InterruptedException {
        builder = definitionsService.getConditionBuilder();

        // 01: one interest matches cars > 40
        seed("01", interests(interest("cars", 50), interest("football", 15)), null);
        // 02: cars and value > 40 exist, but on different nested objects (must not match a nested AND)
        seed("02", interests(interest("cars", 10), interest("football", 60)), null);
        seed("03", interests(interest("cars", 45)), null);
        seed("04", interests(interest("tennis", 80)), null);
        // 05: empty nested list, 06: no nested property at all
        seed("05", new ArrayList<>(), null);
        seed("06", null, null);
        // 07: one nested object without a value
        Map<String, Object> carsNoValue = new HashMap<>();
        carsNoValue.put("key", "cars");
        seed("07", interests(carsNoValue, interest("football", 5)), null);
        // 08: boundary value 40 on two objects
        seed("08", interests(interest("music", 40), interest("cars", 40)), null);
        // 09/10: nested systemProperties.pastEvents (used by past event segments)
        seed("09", null, interests(pastEvent("pe-view", 3), pastEvent("pe-buy", 0)));
        seed("10", null, interests(pastEvent("pe-buy", 2)));

        for (Profile profile : seeded) {
            persistenceService.save(profile);
        }
        refreshPersistence(Profile.class);
    }

    @After
    public void tearDown() throws InterruptedException {
        for (Profile profile : seeded) {
            persistenceService.remove(profile.getItemId(), Profile.class);
        }
        seeded.clear();
        refreshPersistence(Profile.class);
    }

    @Test
    public void testNestedConditionParity() throws Exception {
        Map<String, Supplier<ConditionItem>> cases = new LinkedHashMap<>();
        Map<String, Set<String>> expected = new LinkedHashMap<>();

        addCase(cases, expected, "interests key=cars AND value>40 (same nested object)",
                () -> nestedInterests(builder.and(key("cars"), builder.profileProperty("properties.interests.value").greaterThan(40))),
                "01", "03");
        addCase(cases, expected, "interests key=cars AND value>=40",
                () -> nestedInterests(builder.and(key("cars"), builder.profileProperty("properties.interests.value").greaterThanOrEqualTo(40))),
                "01", "03", "08");
        addCase(cases, expected, "interests key=football",
                () -> nestedInterests(key("football")),
                "01", "02", "07");
        addCase(cases, expected, "interests key=football AND value<20",
                () -> nestedInterests(builder.and(key("football"), builder.profileProperty("properties.interests.value").lessThan(20))),
                "01", "07");
        addCase(cases, expected, "interests key=cars AND value missing",
                () -> nestedInterests(builder.and(key("cars"), builder.profileProperty("properties.interests.value").missing())),
                "07");
        addCase(cases, expected, "interests value exists",
                () -> nestedInterests(builder.profileProperty("properties.interests.value").exists()),
                "01", "02", "03", "04", "07", "08");
        addCase(cases, expected, "interests key notEquals cars",
                () -> nestedInterests(builder.profileProperty("properties.interests.key").notEqualTo("cars")),
                "01", "02", "04", "07", "08");
        addCase(cases, expected, "NOT(interests key=cars)",
                () -> builder.not(nestedInterests(key("cars"))),
                "04", "05", "06", "09", "10");
        addCase(cases, expected, "interests key in [tennis, music]",
                () -> nestedInterests(builder.profileProperty("properties.interests.key").in("tennis", "music")),
                "04", "08");
        addCase(cases, expected, "interests value between 40 and 50",
                () -> nestedInterests(builder.profileProperty("properties.interests.value").between(40, 50)),
                "01", "03", "08");
        addCase(cases, expected, "interests key=cars AND value=50",
                () -> nestedInterests(builder.and(key("cars"), builder.profileProperty("properties.interests.value").equalTo(50))),
                "01");
        addCase(cases, expected, "nested(cars AND >40) OR nested(tennis)",
                () -> builder.or(
                        nestedInterests(builder.and(key("cars"), builder.profileProperty("properties.interests.value").greaterThan(40))),
                        nestedInterests(key("tennis"))),
                "01", "03", "04");
        addCase(cases, expected, "nested(key=cars) AND nested(key=football)",
                () -> builder.and(nestedInterests(key("cars")), nestedInterests(key("football"))),
                "01", "02", "07");
        addCase(cases, expected, "pastEvents key=pe-buy AND count>0",
                () -> builder.nested(builder.and(
                        builder.profileProperty("systemProperties.pastEvents.key").equalTo("pe-buy"),
                        builder.profileProperty("systemProperties.pastEvents.count").greaterThan(0)), "systemProperties.pastEvents"),
                "10");
        addCase(cases, expected, "pastEvents count>0",
                () -> builder.nested(builder.profileProperty("systemProperties.pastEvents.count").greaterThan(0), "systemProperties.pastEvents"),
                "09", "10");

        Map<String, Profile> storedById = new HashMap<>();
        for (Profile profile : seeded) {
            storedById.put(profile.getItemId(), persistenceService.load(profile.getItemId(), Profile.class));
        }

        List<String> failures = new ArrayList<>();
        Map<String, Object> report = new LinkedHashMap<>();
        for (Map.Entry<String, Supplier<ConditionItem>> entry : cases.entrySet()) {
            String name = entry.getKey();
            Condition caseCondition = entry.getValue().get().build();
            Condition scoped = new Condition(definitionsService.getConditionType("booleanCondition"));
            scoped.setParameter("operator", "and");
            scoped.setParameter("subConditions", Arrays.asList(
                    builder.profileProperty("properties." + TAG_PROPERTY).equalTo(TAG_VALUE).build(), caseCondition));

            Set<String> queried = new TreeSet<>();
            for (Profile profile : persistenceService.query(scoped, null, Profile.class)) {
                queried.add(shortId(profile.getItemId()));
            }
            long count = persistenceService.queryCount(scoped, Profile.ITEM_TYPE);
            Set<String> evaluated = new TreeSet<>();
            for (Map.Entry<String, Profile> stored : storedById.entrySet()) {
                if (persistenceService.testMatch(caseCondition, stored.getValue())) {
                    evaluated.add(shortId(stored.getKey()));
                }
            }

            Set<String> exp = expected.get(name);
            Map<String, Object> caseReport = new LinkedHashMap<>();
            caseReport.put("expected", exp);
            caseReport.put("query", queried);
            caseReport.put("count", count);
            caseReport.put("evaluator", evaluated);
            report.put(name, caseReport);

            if (!exp.equals(queried)) {
                failures.add(name + ": query expected " + exp + " but was " + queried);
            }
            if (count != exp.size()) {
                failures.add(name + ": queryCount expected " + exp.size() + " but was " + count);
            }
            if (!exp.equals(evaluated)) {
                failures.add(name + ": evaluator expected " + exp + " but was " + evaluated);
            }
        }

        writeReport(report);
        if (!failures.isEmpty()) {
            fail("nestedCondition parity failures on " + searchEngine + ":\n" + String.join("\n", failures));
        }
    }

    private void addCase(Map<String, Supplier<ConditionItem>> cases, Map<String, Set<String>> expected, String name,
                         Supplier<ConditionItem> condition, String... expectedIds) {
        cases.put(name, condition);
        expected.put(name, new TreeSet<>(Arrays.asList(expectedIds)));
    }

    private ConditionItem key(String value) {
        return builder.profileProperty("properties.interests.key").equalTo(value);
    }

    private ConditionItem nestedInterests(ConditionItem subCondition) {
        return builder.nested(subCondition, "properties.interests");
    }

    private void seed(String id, List<Map<String, Object>> interests, List<Map<String, Object>> pastEvents) {
        Profile profile = new Profile(PREFIX + id);
        profile.setProperty(TAG_PROPERTY, TAG_VALUE);
        if (interests != null) {
            profile.setProperty("interests", interests);
        }
        if (pastEvents != null) {
            profile.setSystemProperty("pastEvents", pastEvents);
        }
        seeded.add(profile);
    }

    @SafeVarargs
    private static List<Map<String, Object>> interests(Map<String, Object>... entries) {
        return new ArrayList<>(Arrays.asList(entries));
    }

    private static Map<String, Object> interest(String key, int value) {
        Map<String, Object> interest = new HashMap<>();
        interest.put("key", key);
        interest.put("value", value);
        return interest;
    }

    private static Map<String, Object> pastEvent(String key, int count) {
        Map<String, Object> pastEvent = new HashMap<>();
        pastEvent.put("key", key);
        pastEvent.put("count", count);
        return pastEvent;
    }

    private static String shortId(String itemId) {
        return itemId.startsWith(PREFIX) ? itemId.substring(PREFIX.length()) : itemId;
    }

    private void writeReport(Map<String, Object> report) throws Exception {
        File dir = parityOutputDir();
        assertTrue("Cannot create " + dir, dir.isDirectory() || dir.mkdirs());
        File out = new File(dir, "nested-" + searchEngine + ".json");
        getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(out, report);
        System.out.println("nestedCondition parity report written to " + out.getAbsolutePath());
    }

    /**
     * Finds {@code itests/target} from the container working directory (which may be nested under
     * {@code itests/target/exam/...}), falling back to {@code ./target}.
     */
    private static File parityOutputDir() {
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        for (File current = dir; current != null; current = current.getParentFile()) {
            if ("target".equals(current.getName()) && current.getParentFile() != null
                    && new File(current.getParentFile(), "pom.xml").isFile()) {
                return new File(current, "parity");
            }
        }
        return new File(new File(dir, "target"), "parity");
    }
}
