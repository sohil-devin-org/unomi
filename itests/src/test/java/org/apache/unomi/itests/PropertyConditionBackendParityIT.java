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

import org.apache.unomi.api.Event;
import org.apache.unomi.api.GeoPoint;
import org.apache.unomi.api.Item;
import org.apache.unomi.api.Profile;
import org.apache.unomi.api.Session;
import org.apache.unomi.api.conditions.Condition;
import org.joda.time.DateTime;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerSuite;

import java.io.File;
import java.util.*;
import java.util.function.Consumer;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Checks that the Elasticsearch and OpenSearch property condition query builders return the same items.
 * <p>
 * A fixed dataset of profiles, sessions and events is seeded, then a table of property conditions
 * (every comparison operator and value type) is run through {@code persistenceService.query} and
 * {@code persistenceService.queryCount}. Each case asserts one constant set of matched item IDs, so the
 * same test passing on both engines shows they return identical results. The actual ID sets are also
 * written to {@code itests/target/parity/property-&lt;engine&gt;.json} so two runs can be diffed.
 */
@RunWith(PaxExam.class)
@ExamReactorStrategy(PerSuite.class)
public class PropertyConditionBackendParityIT extends BaseIT {

    private static final String P = "pcparity-p";
    private static final String S = "pcparity-s";
    private static final String E = "pcparity-e";
    private static final String ERROR = "ERROR";

    private final List<Item> seeded = new ArrayList<>();
    private final Map<String, Object> report = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();

    // All dates use the JVM default time zone, like the isDay/isNotDay builders do.
    private Date dayNoon;
    private Date dayEvening;
    private Date dayStart;
    private Date nextDayStart;
    private Date previousDayEnd;

    @Before
    public void setUp() throws InterruptedException {
        for (String typeId : Arrays.asList("profilePropertyCondition", "sessionPropertyCondition", "eventPropertyCondition",
                "booleanCondition", "idsCondition")) {
            keepTrying("Condition type " + typeId + " not registered", () -> definitionsService.getConditionType(typeId),
                    Objects::nonNull, DEFAULT_TRYING_TIMEOUT, 60);
        }
        DateTime start = new DateTime(2015, 2, 1, 0, 0, 0, 0);
        dayStart = start.toDate();
        dayNoon = start.plusHours(12).toDate();
        dayEvening = start.plusHours(20).plusMinutes(30).toDate();
        nextDayStart = start.plusDays(1).toDate();
        previousDayEnd = start.minusMillis(1).toDate();

        Profile p1 = profile(1, props -> {
            props.put("firstName", "Alice");
            props.put("city", "Café de Paris");
            props.put("email", "alice@example.com");
            props.put("age", 30);
            props.put("score", 0.15);
            props.put("lastVisit", dayEvening);
            props.put("tags", Arrays.asList("red", "blue"));
            props.put("level", "10");
            props.put("bigCounter", 9007199254740993L);
        });
        Profile p2 = profile(2, props -> {
            props.put("firstName", "bob");
            props.put("city", "London");
            props.put("email", "bob@test.org");
            props.put("age", 31);
            props.put("score", 1.5);
            props.put("lastVisit", nextDayStart);
            props.put("tags", Collections.singletonList("blue"));
            props.put("level", "9");
            props.put("bigCounter", 9007199254740992L);
        });
        Profile p3 = profile(3, props -> {
            props.put("firstName", "Carol");
            props.put("city", "PARIS");
            props.put("email", "carol@example.com");
            props.put("age", 18);
            props.put("score", 0.0);
            props.put("lastVisit", dayStart);
            props.put("tags", Arrays.asList("green", "red", "blue"));
            props.put("level", "25");
            props.put("bigCounter", 5L);
        });
        Profile p4 = profile(4, props -> {
            props.put("firstName", "Élodie");
            props.put("city", "Lyon");
            props.put("email", "elodie@example.fr");
            props.put("age", 45);
            props.put("score", -2.5);
            props.put("lastVisit", previousDayEnd);
            props.put("tags", Collections.singletonList("yellow"));
            props.put("level", "100");
        });
        Profile p5 = profile(5, props -> {
        });
        Profile p6 = profile(6, props -> {
            props.put("firstName", null);
            props.put("tags", Collections.emptyList());
        });

        Session s1 = session(1, p1, new GeoPoint(48.8566, 2.3522), "mobile", 120);
        Session s2 = session(2, p2, new GeoPoint(51.5074, -0.1278), "desktop", 30);
        Session s3 = session(3, p3, null, null, null);

        Event e1 = event(1, s1, p1, "view", "home", 3, "value1");
        Event e2 = event(2, s2, p2, "view", "checkout", 10, "value2");
        Event e3 = event(3, s3, p3, "click", null, null, null);

        seeded.addAll(Arrays.asList(p1, p2, p3, p4, p5, p6, s1, s2, s3, e1, e2, e3));
        for (Item item : seeded) {
            persistenceService.save(item);
        }
        persistenceService.refreshIndex(Profile.class);
        persistenceService.refreshIndex(Session.class);
        persistenceService.refreshIndex(Event.class);
    }

    @After
    public void tearDown() throws InterruptedException {
        for (Item item : seeded) {
            persistenceService.remove(item.getItemId(), item.getClass());
        }
        persistenceService.refreshIndex(Profile.class);
        persistenceService.refreshIndex(Session.class);
        persistenceService.refreshIndex(Event.class);
    }

    @Test
    public void testPropertyConditionParity() throws Exception {
        // equals / notEquals (all value types)
        profileCase("equals-string-folded", "properties.firstName", "equals", "propertyValue", "ALICE", ids(P, 1));
        profileCase("equals-string-accent", "properties.city", "equals", "propertyValue", "cafe de paris", ids(P, 1));
        profileCase("equals-integer", "properties.age", "equals", "propertyValueInteger", 30, ids(P, 1));
        profileCase("equals-double", "properties.score", "equals", "propertyValueDouble", 0.15, ids(P, 1));
        profileCase("equals-date", "properties.lastVisit", "equals", "propertyValueDate", dayEvening, ids(P, 1));
        profileCase("equals-dateExpr", "properties.lastVisit", "equals", "propertyValueDateExpr",
                String.valueOf(dayStart.getTime()), ids(P, 3));
        profileCase("equals-multivalued-field", "properties.tags", "equals", "propertyValue", "green", ids(P, 3));
        profileCase("notEquals-string", "properties.firstName", "notEquals", "propertyValue", "alice", ids(P, 2, 3, 4, 5, 6));
        profileCase("notEquals-integer", "properties.age", "notEquals", "propertyValueInteger", 30, ids(P, 2, 3, 4, 5, 6));

        // range comparisons
        profileCase("greaterThan-integer", "properties.age", "greaterThan", "propertyValueInteger", 30, ids(P, 2, 4));
        profileCase("greaterThanOrEqualTo-integer", "properties.age", "greaterThanOrEqualTo", "propertyValueInteger", 30, ids(P, 1, 2, 4));
        profileCase("lessThan-integer", "properties.age", "lessThan", "propertyValueInteger", 30, ids(P, 3));
        profileCase("lessThanOrEqualTo-integer", "properties.age", "lessThanOrEqualTo", "propertyValueInteger", 30, ids(P, 1, 3));
        profileCase("greaterThan-double", "properties.score", "greaterThan", "propertyValueDouble", 0.15, ids(P, 2));
        profileCase("lessThanOrEqualTo-double", "properties.score", "lessThanOrEqualTo", "propertyValueDouble", 0.0, ids(P, 3, 4));
        profileCase("greaterThan-date", "properties.lastVisit", "greaterThan", "propertyValueDate", dayStart, ids(P, 1, 2));
        profileCase("greaterThanOrEqualTo-date", "properties.lastVisit", "greaterThanOrEqualTo", "propertyValueDate", dayStart, ids(P, 1, 2, 3));
        profileCase("lessThan-date", "properties.lastVisit", "lessThan", "propertyValueDate", dayStart, ids(P, 4));
        profileCase("lessThanOrEqualTo-date", "properties.lastVisit", "lessThanOrEqualTo", "propertyValueDate", nextDayStart, ids(P, 1, 2, 3, 4));
        profileCase("lessThan-dateExpr-now", "properties.lastVisit", "lessThan", "propertyValueDateExpr", "now-1d", ids(P, 1, 2, 3, 4));
        profileCase("greaterThan-dateExpr-now", "properties.lastVisit", "greaterThan", "propertyValueDateExpr", "now-1d", ids(P));
        profileCase("greaterThanOrEqualTo-dateExpr-math", "properties.lastVisit", "greaterThanOrEqualTo", "propertyValueDateExpr",
                dayNoon.getTime() + "||/d", ids(P, 1, 2, 3));
        profileCase("lessThan-dateExpr-iso", "properties.lastVisit", "lessThan", "propertyValueDateExpr",
                new DateTime(nextDayStart).toString(), ids(P, 1, 3, 4));
        profileCase("greaterThanOrEqualTo-string", "properties.firstName", "greaterThanOrEqualTo", "propertyValue", "bob", ids(P, 2, 3, 4));
        // integer value compared to a string (keyword) field: the engines compare text, so both must send "10" (not "10.0")
        profileCase("greaterThanOrEqualTo-integer-on-string-field", "properties.level", "greaterThanOrEqualTo", "propertyValueInteger", 10,
                ids(P, 1, 2, 3, 4));
        profileCase("lessThanOrEqualTo-integer-on-string-field", "properties.level", "lessThanOrEqualTo", "propertyValueInteger", 10,
                ids(P, 1));
        // long above 2^53: must not lose precision
        profileCase("greaterThanOrEqualTo-long-precision", "properties.bigCounter", "greaterThanOrEqualTo", "propertyValueInteger",
                9007199254740993L, ids(P, 1));
        profileCase("lessThan-long-precision", "properties.bigCounter", "lessThan", "propertyValueInteger",
                9007199254740993L, ids(P, 2, 3));

        // between
        profileCase("between-integer", "properties.age", "between", "propertyValuesInteger", Arrays.asList(18, 30), ids(P, 1, 3));
        profileCase("between-double", "properties.score", "between", "propertyValuesDouble", Arrays.asList(0.0, 1.5), ids(P, 1, 2, 3));
        profileCase("between-date", "properties.lastVisit", "between", "propertyValuesDate", Arrays.asList(dayStart, nextDayStart),
                ids(P, 1, 2, 3));
        profileCase("between-dateExpr", "properties.lastVisit", "between", "propertyValuesDateExpr",
                Arrays.asList(String.valueOf(dayStart.getTime()), String.valueOf(dayEvening.getTime())), ids(P, 1, 3));
        profileCase("between-string", "properties.firstName", "between", "propertyValues", Arrays.asList("alice", "bob"), ids(P, 1, 2));
        profileCase("between-long-precision", "properties.bigCounter", "between", "propertyValuesInteger",
                Arrays.asList(9007199254740993L, 9007199254740995L), ids(P, 1));

        // existence
        profileCase("exists", "properties.firstName", "exists", null, null, ids(P, 1, 2, 3, 4));
        profileCase("missing", "properties.firstName", "missing", null, null, ids(P, 5, 6));
        profileCase("exists-empty-list", "properties.tags", "exists", null, null, ids(P, 1, 2, 3, 4));
        profileCase("missing-unknown-field", "properties.doesNotExist", "missing", null, null, ids(P, 1, 2, 3, 4, 5, 6));

        // content
        profileCase("contains", "properties.email", "contains", "propertyValue", "EXAMPLE", ids(P, 1, 3, 4));
        profileCase("notContains", "properties.email", "notContains", "propertyValue", "example", ids(P, 2, 5, 6));
        profileCase("startsWith", "properties.city", "startsWith", "propertyValue", "Caf", ids(P, 1));
        profileCase("endsWith", "properties.city", "endsWith", "propertyValue", "paris", ids(P, 1, 3));
        profileCase("matchesRegex", "properties.firstName", "matchesRegex", "propertyValue", "[a-c].*", ids(P, 1, 2, 3));
        profileCase("contains-integer-value-only", "properties.email", "contains", "propertyValueInteger", 1, ERROR_RESULT);

        // collections
        profileCase("in-string", "properties.tags", "in", "propertyValues", Arrays.asList("green", "yellow"), ids(P, 3, 4));
        profileCase("in-integer", "properties.age", "in", "propertyValuesInteger", Arrays.asList(30, 45), ids(P, 1, 4));
        profileCase("in-double", "properties.score", "in", "propertyValuesDouble", Arrays.asList(0.15, 1.5), ids(P, 1, 2));
        profileCase("in-date", "properties.lastVisit", "in", "propertyValuesDate", Arrays.asList(dayStart, nextDayStart), ids(P, 2, 3));
        profileCase("in-dateExpr", "properties.lastVisit", "in", "propertyValuesDateExpr",
                Collections.singletonList(String.valueOf(dayEvening.getTime())), ids(P, 1));
        profileCase("in-long-precision", "properties.bigCounter", "in", "propertyValuesInteger",
                Collections.singletonList(9007199254740993L), ids(P, 1));
        profileCase("notIn-string", "properties.tags", "notIn", "propertyValues", Arrays.asList("red", "yellow"), ids(P, 2, 5, 6));
        profileCase("notIn-integer", "properties.age", "notIn", "propertyValuesInteger", Arrays.asList(30, 31), ids(P, 3, 4, 5, 6));
        profileCase("all", "properties.tags", "all", "propertyValues", Arrays.asList("red", "blue"), ids(P, 1, 3));
        profileCase("inContains", "properties.email", "inContains", "propertyValues", Arrays.asList("example", "o"), ids(P, 1, 3, 4));
        profileCase("hasSomeOf", "properties.tags", "hasSomeOf", "propertyValues", Arrays.asList("green", "yellow"), ids(P, 3, 4));
        profileCase("hasNoneOf", "properties.tags", "hasNoneOf", "propertyValues", Arrays.asList("red", "yellow"), ids(P, 2, 5, 6));

        // day comparisons
        profileCase("isDay-date", "properties.lastVisit", "isDay", "propertyValueDate", dayNoon, ids(P, 1, 3));
        profileCase("isDay-dateExpr", "properties.lastVisit", "isDay", "propertyValueDateExpr",
                String.valueOf(dayNoon.getTime()), ids(P, 1, 3));
        profileCase("isNotDay-date", "properties.lastVisit", "isNotDay", "propertyValueDate", dayNoon, ids(P, 2, 4, 5, 6));
        profileCase("isNotDay-dateExpr", "properties.lastVisit", "isNotDay", "propertyValueDateExpr",
                String.valueOf(dayNoon.getTime()), ids(P, 2, 4, 5, 6));

        // sessionPropertyCondition (incl. geo distance)
        Condition distance = condition("sessionPropertyCondition", "properties.location", "distance", null, null);
        distance.setParameter("center", "48.85,2.35");
        distance.setParameter("distance", 50000.0);
        runCase("session-distance", Session.class, Session.ITEM_TYPE, distance, ids(S, 1));
        Condition distanceArc = condition("sessionPropertyCondition", "properties.location", "distance", null, null);
        distanceArc.setParameter("center", "48.85,2.35");
        distanceArc.setParameter("distance", 400000.0);
        distanceArc.setParameter("unit", "Arc");
        runCase("session-distance-arc", Session.class, Session.ITEM_TYPE, distanceArc, ids(S, 1, 2));
        Condition distanceNoCenter = condition("sessionPropertyCondition", "properties.location", "distance", null, null);
        distanceNoCenter.setParameter("distance", 50000.0);
        runCase("session-distance-missing-center", Session.class, Session.ITEM_TYPE, distanceNoCenter, ERROR_RESULT);
        runCase("session-equals", Session.class, Session.ITEM_TYPE,
                condition("sessionPropertyCondition", "properties.device", "equals", "propertyValue", "Mobile"), ids(S, 1));
        runCase("session-greaterThan-integer", Session.class, Session.ITEM_TYPE,
                condition("sessionPropertyCondition", "properties.pageViews", "greaterThan", "propertyValueInteger", 30), ids(S, 1));
        runCase("session-missing", Session.class, Session.ITEM_TYPE,
                condition("sessionPropertyCondition", "properties.device", "missing", null, null), ids(S, 3));

        // eventPropertyCondition (incl. flattened / flat_object field)
        runCase("event-equals", Event.class, Event.ITEM_TYPE,
                condition("eventPropertyCondition", "properties.page", "equals", "propertyValue", "home"), ids(E, 1));
        runCase("event-greaterThanOrEqualTo-integer", Event.class, Event.ITEM_TYPE,
                condition("eventPropertyCondition", "properties.count", "greaterThanOrEqualTo", "propertyValueInteger", 5), ids(E, 2));
        runCase("event-in-eventType", Event.class, Event.ITEM_TYPE,
                condition("eventPropertyCondition", "eventType", "in", "propertyValues", Arrays.asList("click", "other")), ids(E, 3));
        runCase("event-flattened-equals", Event.class, Event.ITEM_TYPE,
                condition("eventPropertyCondition", "flattenedProperties.key", "equals", "propertyValue", "value1"), ids(E, 1));
        runCase("event-flattened-exists", Event.class, Event.ITEM_TYPE,
                condition("eventPropertyCondition", "flattenedProperties.key", "exists", null, null), ids(E, 1, 2));

        writeReport();
        if (!failures.isEmpty()) {
            fail("Property condition results differ from the expected (engine-independent) results on "
                    + engine() + ":\n" + String.join("\n", failures));
        }
    }

    private static final Set<String> ERROR_RESULT = Collections.singleton(ERROR);

    private void profileCase(String name, String propertyName, String operator, String valueParam, Object value, Set<String> expected) {
        runCase(name, Profile.class, Profile.ITEM_TYPE,
                condition("profilePropertyCondition", propertyName, operator, valueParam, value), expected);
    }

    private Condition condition(String typeId, String propertyName, String operator, String valueParam, Object value) {
        Condition condition = new Condition(definitionsService.getConditionType(typeId));
        condition.setParameter("propertyName", propertyName);
        condition.setParameter("comparisonOperator", operator);
        if (valueParam != null) {
            condition.setParameter(valueParam, value);
        }
        return condition;
    }

    private <T extends Item> void runCase(String name, Class<T> clazz, String itemType, Condition condition, Set<String> expected) {
        List<String> scopeIds = new ArrayList<>();
        for (Item item : seeded) {
            if (clazz.isInstance(item)) {
                scopeIds.add(item.getItemId());
            }
        }
        Condition ids = new Condition(definitionsService.getConditionType("idsCondition"));
        ids.setParameter("ids", scopeIds);
        ids.setParameter("match", true);
        Condition scoped = new Condition(definitionsService.getConditionType("booleanCondition"));
        scoped.setParameter("operator", "and");
        scoped.setParameter("subConditions", Arrays.asList(ids, condition));

        Set<String> actual = new TreeSet<>();
        long count = -1;
        String error = null;
        try {
            for (T item : persistenceService.query(scoped, "itemId", clazz, 0, 100).getList()) {
                actual.add(item.getItemId());
            }
            count = persistenceService.queryCount(scoped, itemType);
        } catch (Exception e) {
            error = e.getClass().getName() + ": " + e.getMessage();
            actual = new TreeSet<>(ERROR_RESULT);
        }

        Set<String> evaluator = new TreeSet<>();
        for (Item item : seeded) {
            if (clazz.isInstance(item)) {
                try {
                    if (persistenceService.testMatch(condition, item)) {
                        evaluator.add(item.getItemId());
                    }
                } catch (Exception e) {
                    evaluator.add(item.getItemId() + ":" + ERROR);
                }
            }
        }

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("condition", condition.getParameterValues());
        entry.put("actual", actual);
        entry.put("count", count);
        entry.put("expected", new TreeSet<>(expected));
        entry.put("evaluator", evaluator);
        if (error != null) {
            entry.put("error", error);
        }
        report.put(name, entry);

        boolean countMatches = error != null || count == actual.size();
        if (!actual.equals(new TreeSet<>(expected)) || !countMatches) {
            failures.add("  " + name + ": expected " + new TreeSet<>(expected) + " but got " + actual
                    + " (queryCount=" + count + ")" + (error != null ? " error=" + error : ""));
        }
    }

    private void writeReport() throws Exception {
        File dir = new File(findTargetDir(), "parity");
        assertTrue("Cannot create " + dir, dir.isDirectory() || dir.mkdirs());
        File file = new File(dir, "property-" + engine() + ".json");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("engine", engine());
        root.put("timeZone", TimeZone.getDefault().getID());
        root.put("cases", report);
        getObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file, root);
        System.out.println("Property condition parity report written to " + file.getAbsolutePath());
    }

    /** Finds itests/target from inside the Pax Exam Karaf container (itests/target/exam/&lt;id&gt;/...). */
    private static File findTargetDir() {
        for (String start : new String[] {System.getProperty("karaf.home"), System.getProperty("user.dir")}) {
            if (start == null) {
                continue;
            }
            for (File dir = new File(start).getAbsoluteFile(); dir != null; dir = dir.getParentFile()) {
                if ("target".equals(dir.getName()) && new File(dir.getParentFile(), "pom.xml").isFile()) {
                    return dir;
                }
            }
        }
        return new File(System.getProperty("user.dir"), "target");
    }

    private String engine() {
        return getPersistenceBackend().providerId();
    }

    private static Set<String> ids(String prefix, int... numbers) {
        Set<String> result = new TreeSet<>();
        for (int n : numbers) {
            result.add(prefix + n);
        }
        return result;
    }

    private static Profile profile(int n, Consumer<Map<String, Object>> filler) {
        Profile profile = new Profile(P + n);
        Map<String, Object> props = new HashMap<>();
        filler.accept(props);
        profile.setProperties(props);
        return profile;
    }

    private Session session(int n, Profile profile, GeoPoint location, String device, Integer pageViews) {
        Session session = new Session(S + n, profile, dayEvening, "pcparity");
        if (location != null) {
            Map<String, Object> loc = new HashMap<>();
            loc.put("lat", location.getLat());
            loc.put("lon", location.getLon());
            session.setProperty("location", loc);
        }
        if (device != null) {
            session.setProperty("device", device);
        }
        if (pageViews != null) {
            session.setProperty("pageViews", pageViews);
        }
        return session;
    }

    private Event event(int n, Session session, Profile profile, String type, String page, Integer count, String flattened) {
        Map<String, Object> props = new HashMap<>();
        if (page != null) {
            props.put("page", page);
        }
        if (count != null) {
            props.put("count", count);
        }
        Event event = new Event(E + n, type, session, profile, "pcparity", null, null, props, dayEvening, true);
        if (flattened != null) {
            Map<String, Object> flat = new HashMap<>();
            flat.put("key", flattened);
            event.setFlattenedProperties(flat);
        }
        return event;
    }
}
