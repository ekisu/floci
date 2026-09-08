package io.github.hectorvent.floci.services.eventbridge;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class EventBridgeLogsTargetIntegrationTest {
    private static final String ACCOUNT = "000000000001";
    private static final String OTHER_ACCOUNT = "000000000002";
    private static final String REGION = "us-east-1";
    private static final String OTHER_REGION = "eu-west-1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void matchedEventsBecomeReadableLogsWithEnvelopeAndTimestamp() throws Exception {
        String rule = uniqueName();
        String group = "/aws/events/" + rule;
        createGroup(group, ACCOUNT, REGION);
        configureRule(rule, group, ACCOUNT, REGION, Map.of());

        publish(rule, "first");
        publish(rule + ".unmatched", "ignored");
        publish(rule, "second");

        var events = filter(group, ACCOUNT, REGION);
        assertEquals(2, events.size());
        for (var log : events) {
            var envelope = MAPPER.readTree((String) log.get("message"));
            assertEquals(rule, envelope.path("source").asText());
            assertEquals("OrderPlaced", envelope.path("detail-type").asText());
            assertEquals(ACCOUNT, envelope.path("account").asText());
            assertEquals(REGION, envelope.path("region").asText());
            assertFalse(envelope.path("id").asText().isBlank());
            assertEquals(Instant.parse(envelope.path("time").asText()).toEpochMilli(),
                    ((Number) log.get("timestamp")).longValue());
            var read = call("Logs_20140328.GetLogEvents", Map.of("logGroupName", group,
                    "logStreamName", log.get("logStreamName")), ACCOUNT, REGION);
            assertEquals(log.get("message"), read.getString("events[0].message"));
        }
        var filtered = call("Logs_20140328.FilterLogEvents",
                Map.of("logGroupName", group, "filterPattern", "second"), ACCOUNT, REGION);
        assertEquals(1, filtered.getList("events").size());
    }

    @Test
    void deliveryRejectsDirectCrossAccountLogs() throws Exception {
        String rule = uniqueName();
        String group = "/aws/events/" + rule;
        for (String account : List.of(ACCOUNT, OTHER_ACCOUNT)) {
            for (String region : List.of(REGION, OTHER_REGION)) {
                createGroup(group, account, region);
            }
        }
        call("AWSEvents.PutRule", Map.of("Name", rule,
                "EventPattern", MAPPER.writeValueAsString(Map.of("source", List.of(rule)))), ACCOUNT, REGION);
        var target = Map.of("Id", "logs", "Arn", "arn:aws:logs:" + OTHER_REGION + ":"
                + OTHER_ACCOUNT + ":log-group:" + group, "accountId", OTHER_ACCOUNT, "AccountId", OTHER_ACCOUNT);
        assertEquals(0, call("AWSEvents.PutTargets", Map.of("Rule", rule, "Targets", List.of(target)),
                ACCOUNT, REGION).getInt("FailedEntryCount"));
        List<Map<String, Object>> listed = call("AWSEvents.ListTargetsByRule", Map.of("Rule", rule),
                ACCOUNT, REGION).getList("Targets");
        assertEquals(List.of(Map.of("Id", "logs", "Arn", target.get("Arn"))), listed);
        publish(rule, "isolated");
        for (String account : List.of(ACCOUNT, OTHER_ACCOUNT)) {
            for (String region : List.of(REGION, OTHER_REGION)) {
                assertTrue(filter(group, account, region).isEmpty(), account + "/" + region);
                assertTrue(call("Logs_20140328.DescribeLogStreams", Map.of("logGroupName", group),
                        account, region).getList("logStreams").isEmpty(), account + "/" + region);
            }
        }
    }

    @Test
    void deliveryRejectsInvalidLogsConfiguration() throws Exception {
        String rule = uniqueName();
        String group = "/aws/events/" + rule;
        createGroup(group, ACCOUNT, REGION);
        configureRule(rule, group, ACCOUNT, REGION, Map.of());
        String arn = "arn:aws:logs:" + REGION + ":" + ACCOUNT + ":log-group:";
        var invalidTargets = new ArrayList<Map<String, Object>>();
        for (String invalidArn : List.of(arn, arn + group + ":log-stream:foo",
                "arn:aws:logs::" + ACCOUNT + ":log-group:" + group,
                "arn:aws:logs:" + REGION + "::log-group:" + group, "invalid:logs:arn")) {
            invalidTargets.add(Map.of("Id", "logs", "Arn", invalidArn));
        }
        invalidTargets.add(Map.of("Id", "logs", "Arn", arn + group, "Input", "{}"));
        invalidTargets.add(Map.of("Id", "logs", "Arn", arn + group, "InputPath", "$.detail"));
        for (var target : invalidTargets) {
            assertEquals(0, call("AWSEvents.PutTargets", Map.of("Rule", rule, "Targets", List.of(target)),
                    ACCOUNT, REGION).getInt("FailedEntryCount"));
            List<Map<String, Object>> stored = call("AWSEvents.ListTargetsByRule", Map.of("Rule", rule),
                    ACCOUNT, REGION).getList("Targets");
            assertEquals(1, stored.size());
            assertEquals("logs", stored.getFirst().get("Id"));
            assertEquals(target.get("Arn"), stored.getFirst().get("Arn"));
            publish(rule, "invalid");
            assertTrue(filter(group, ACCOUNT, REGION).isEmpty());
            assertTrue(call("Logs_20140328.DescribeLogStreams", Map.of("logGroupName", group),
                    ACCOUNT, REGION).getList("logStreams").isEmpty());
        }
    }

    @Test
    void forwardedEventUsesReceivingRuleAccountForLogsDelivery() throws Exception {
        String rule = uniqueName();
        String bus = rule + "-receiver";
        String group = "/aws/events/" + rule;
        createGroup(group, ACCOUNT, REGION);
        createGroup(group, OTHER_ACCOUNT, REGION);
        String busArn = call("AWSEvents.CreateEventBus", Map.of("Name", bus),
                OTHER_ACCOUNT, REGION).getString("EventBusArn");
        call("AWSEvents.PutRule", Map.of("Name", rule, "EventBusName", bus,
                "EventPattern", MAPPER.writeValueAsString(Map.of("source", List.of(rule)))), OTHER_ACCOUNT, REGION);
        assertEquals(0, call("AWSEvents.PutTargets", Map.of("Rule", rule, "EventBusName", bus,
                "Targets", List.of(Map.of("Id", "logs", "Arn", "arn:aws:logs:" + REGION + ":"
                        + OTHER_ACCOUNT + ":log-group:" + group))), OTHER_ACCOUNT, REGION).getInt("FailedEntryCount"));
        call("AWSEvents.PutRule", Map.of("Name", rule,
                "EventPattern", MAPPER.writeValueAsString(Map.of("source", List.of(rule)))), ACCOUNT, REGION);
        assertEquals(0, call("AWSEvents.PutTargets", Map.of("Rule", rule,
                "Targets", List.of(Map.of("Id", "bus", "Arn", busArn))), ACCOUNT, REGION).getInt("FailedEntryCount"));

        publish(rule, "forwarded");

        var received = filter(group, OTHER_ACCOUNT, REGION);
        assertEquals(1, received.size());
        var envelope = MAPPER.readTree((String) received.getFirst().get("message"));
        assertEquals(ACCOUNT, envelope.path("account").asText());
        assertEquals("forwarded", envelope.path("detail").path("message").asText());
        assertTrue(filter(group, ACCOUNT, REGION).isEmpty());
        assertTrue(call("Logs_20140328.DescribeLogStreams", Map.of("logGroupName", group),
                ACCOUNT, REGION).getList("logStreams").isEmpty());
    }

    @Test
    void scheduledRuleDeliversToItsRegisteredLogsTarget() throws Exception {
        String rule = uniqueName();
        String group = "/aws/events/" + rule;
        createGroup(group, OTHER_ACCOUNT, REGION);
        call("AWSEvents.PutRule", Map.of("Name", rule, "ScheduleExpression", "rate(1 minute)",
                "State", "DISABLED"), OTHER_ACCOUNT, REGION);
        assertEquals(0, call("AWSEvents.PutTargets", Map.of("Rule", rule, "Targets", List.of(
                Map.of("Id", "logs", "Arn", "arn:aws:logs:" + REGION + ":" + OTHER_ACCOUNT
                        + ":log-group:" + group))), OTHER_ACCOUNT, REGION).getInt("FailedEntryCount"));
        try {
            call("AWSEvents.EnableRule", Map.of("Name", rule), OTHER_ACCOUNT, REGION);
            var rows = filter(group, OTHER_ACCOUNT, REGION);
            assertEquals(1, rows.size());
            var envelope = MAPPER.readTree((String) rows.getFirst().get("message"));
            assertEquals("Scheduled Event", envelope.path("detail-type").asText());
            assertEquals(OTHER_ACCOUNT, envelope.path("account").asText());
        } finally {
            call("AWSEvents.DisableRule", Map.of("Name", rule), OTHER_ACCOUNT, REGION);
        }
    }

    @Test
    void missingGroupIsNotCreatedAndLaterProvisioningAllowsDelivery() throws Exception {
        String rule = uniqueName();
        String group = "/aws/events/" + rule;
        configureRule(rule, group, ACCOUNT, REGION, Map.of());
        publish(rule, "missing");
        assertTrue(call("Logs_20140328.DescribeLogGroups", Map.of("logGroupNamePrefix", group),
                ACCOUNT, REGION).getList("logGroups").isEmpty());
        createGroup(group, ACCOUNT, REGION);
        publish(rule, "provisioned");
        assertEquals(1, filter(group, ACCOUNT, REGION).size());
        assertTrue(((String) filter(group, ACCOUNT, REGION).getFirst().get("message")).contains("provisioned"));
    }

    @Test
    void transformerSuppliesLogTimestampAndUnquotedMessageAndPreservesPolicies() throws Exception {
        String rule = uniqueName();
        String group = "/aws/events/" + rule;
        createGroup(group, ACCOUNT, REGION);
        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[]}";
        call("Logs_20140328.PutResourcePolicy", Map.of("policyName", rule, "policyDocument", policy), ACCOUNT, REGION);
        configureRule(rule, group + ":*", ACCOUNT, REGION, Map.of("InputTransformer", Map.of(
                "InputPathsMap", Map.of("when", "$.detail.when", "message", "$.detail.message"),
                "InputTemplate", "{\"timestamp\":<when>,\"message\":<message>}")));
        publish(rule, "quoted \"message\"\nnext line");
        var log = filter(group, ACCOUNT, REGION).getFirst();
        assertEquals("quoted \"message\"\nnext line", log.get("message"));
        assertEquals(Instant.parse("2026-09-08T12:34:56.789Z").toEpochMilli(),
                ((Number) log.get("timestamp")).longValue());
        List<Map<String, Object>> policies = call("Logs_20140328.DescribeResourcePolicies", Map.of(),
                ACCOUNT, REGION).getList("resourcePolicies");
        assertTrue(policies.stream().anyMatch(p -> rule.equals(p.get("policyName"))
                && policy.equals(p.get("policyDocument"))));
    }

    @Test
    void concurrentDeliveriesAreAllRetained() throws Exception {
        String rule = uniqueName();
        String group = "/aws/events/" + rule;
        createGroup(group, ACCOUNT, REGION);
        configureRule(rule, group, ACCOUNT, REGION, Map.of());
        List<Callable<Void>> deliveries = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            String message = "concurrent-" + i;
            deliveries.add(() -> { publish(rule, message); return null; });
        }
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (var result : executor.invokeAll(deliveries)) {
                result.get();
            }
        }
        var events = filter(group, ACCOUNT, REGION);
        assertEquals(12, events.size());
        assertEquals(12, events.stream().map(e -> e.get("message")).distinct().count());
    }

    private static String uniqueName() {
        return "eb-logs-" + UUID.randomUUID();
    }

    private static void createGroup(String group, String account, String region) throws Exception {
        call("Logs_20140328.CreateLogGroup", Map.of("logGroupName", group), account, region);
    }

    private static void configureRule(String rule, String group, String account, String region,
                                      Map<String, Object> options) throws Exception {
        call("AWSEvents.PutRule", Map.of("Name", rule,
                "EventPattern", MAPPER.writeValueAsString(Map.of("source", List.of(rule)))), ACCOUNT, REGION);
        var target = new java.util.HashMap<String, Object>(options);
        target.put("Id", "logs");
        target.put("Arn", "arn:aws:logs:" + region + ":" + account + ":log-group:" + group);
        assertEquals(0, call("AWSEvents.PutTargets", Map.of("Rule", rule, "Targets", List.of(target)),
                ACCOUNT, REGION).getInt("FailedEntryCount"));
    }

    private static void publish(String source, String message) throws Exception {
        String detail = MAPPER.writeValueAsString(Map.of("message", message, "when", "2026-09-08T12:34:56.789Z"));
        assertEquals(0, call("AWSEvents.PutEvents", Map.of("Entries", List.of(Map.of(
                "Source", source, "DetailType", "OrderPlaced", "Detail", detail))),
                ACCOUNT, REGION).getInt("FailedEntryCount"));
    }

    private static List<Map<String, Object>> filter(String group, String account, String region) throws Exception {
        return call("Logs_20140328.FilterLogEvents", Map.of("logGroupName", group), account, region).getList("events");
    }

    private static JsonPath call(String operation, Map<String, Object> body, String account, String region) throws Exception {
        return call(operation, body, account, region, 200);
    }

    private static JsonPath call(String operation, Map<String, Object> body, String account, String region,
                                 int status) throws Exception {
        String service = operation.startsWith("Logs_") ? "logs" : "events";
        return given().contentType("application/x-amz-json-1.1")
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account + "/20260908/"
                        + region + "/" + service + "/aws4_request, SignedHeaders=host, Signature=abc")
                .header("X-Amz-Target", operation).body(MAPPER.writeValueAsString(body))
                .when().post("/").then().statusCode(status).extract().jsonPath();
    }
}
