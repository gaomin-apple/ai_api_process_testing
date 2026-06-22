package com.labway.aft.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labway.aft.domain.EndpointDefinition;
import com.labway.aft.domain.EnvironmentDefinition;
import com.labway.aft.domain.FlowDefinition;
import com.labway.aft.domain.RunResult;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FlowEngineTest {
    @Test
    void passesExtractedValueToNextRequest() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"token\":\"secret-token\"}}"));
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"ok\":true}"));

            EndpointDefinition login = endpoint("login", "POST", "/login");
            EndpointDefinition query = endpoint("query", "GET", "/orders");
            FlowDefinition.FlowNode first = new FlowDefinition.FlowNode(
                    "n1", login.id(), "Login", 0, 0,
                    new FlowDefinition.RequestConfig(
                            Map.of(), Map.of(), Map.of(), Map.of(),
                            "{\"username\":\"admin\",\"password\":\"secret\"}", "JSON",
                            "NONE", null, 5_000
                    ),
                    List.of(new FlowDefinition.Extractor("token", "JSON_PATH", "$.data.token")),
                    List.of(),
                    "API", null
            );
            FlowDefinition.FlowNode second = new FlowDefinition.FlowNode(
                    "n2", query.id(), "Query", 240, 0,
                    new FlowDefinition.RequestConfig(
                            Map.of(), Map.of(), Map.of("token", "${run.token}"), Map.of(), null, "NONE",
                            "BEARER", "${run.token}", 5_000
                    ),
                    List.of(),
                    List.of(new FlowDefinition.Assertion("STATUS", "", "EQUALS", "200")),
                    "API", null
            );
            FlowDefinition flow = new FlowDefinition(
                    "flow", "project", "Order flow", "",
                    List.of(first, second),
                    List.of(new FlowDefinition.FlowEdge("e1", "n1", "n2", null)),
                    FlowDefinition.FlowConfig.defaults(), null, null
            );
            EnvironmentDefinition environment = new EnvironmentDefinition(
                    "env", "project", "Test", server.url("/").toString(), Map.of()
            );

            RunResult result = new FlowEngine(new OkHttpClient(), new ObjectMapper())
                    .execute(flow, environment, Map.of(login.id(), login, query.id(), query));

            assertEquals(RunResult.Status.PASSED, result.status());
            assertEquals("***", result.steps().get(0).extractedVariables().get("token"));
            var loginRequest = server.takeRequest();
            assertEquals("application/json; charset=utf-8", loginRequest.getHeader("Content-Type"));
            assertEquals(
                    "{\"username\":\"admin\",\"password\":\"secret\"}",
                    loginRequest.getBody().readUtf8()
            );
            var queryRequest = server.takeRequest();
            assertEquals("secret-token", queryRequest.getHeader("token"));
            assertEquals("Bearer secret-token", queryRequest.getHeader("Authorization"));
        }
    }

    @Test
    void gatewaySelectsBranchFromExtractedVariable() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"status\":\"approved\"}}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"branch\":\"approved\"}"));

            EndpointDefinition check = endpoint("check", "GET", "/check");
            EndpointDefinition approved = endpoint("approved", "GET", "/approved");
            EndpointDefinition rejected = endpoint("rejected", "GET", "/rejected");
            FlowDefinition.FlowNode first = apiNode(
                    "n1", check, List.of(new FlowDefinition.Extractor("status", "JSON_PATH", "$.data.status"))
            );
            FlowDefinition.FlowNode gateway = gatewayNode(
                    "g1", new FlowDefinition.GatewayConfig("VARIABLE", "status", "")
            );
            FlowDefinition.FlowNode approvedNode = apiNode("n2", approved, List.of());
            FlowDefinition.FlowNode rejectedNode = apiNode("n3", rejected, List.of());
            FlowDefinition flow = new FlowDefinition(
                    "flow-gateway", "project", "Gateway flow", "",
                    List.of(first, gateway, approvedNode, rejectedNode),
                    List.of(
                            new FlowDefinition.FlowEdge("e1", "n1", "g1", null),
                            new FlowDefinition.FlowEdge(
                                    "e2", "g1", "n2",
                                    new FlowDefinition.Condition("__gateway__", "EQUALS", "approved")
                            ),
                            new FlowDefinition.FlowEdge("e3", "g1", "n3", null)
                    ),
                    FlowDefinition.FlowConfig.defaults(), null, null
            );
            EnvironmentDefinition environment = new EnvironmentDefinition(
                    "env", "project", "Test", server.url("/").toString(), Map.of()
            );

            RunResult result = new FlowEngine(new OkHttpClient(), new ObjectMapper())
                    .execute(flow, environment, Map.of(
                            check.id(), check,
                            approved.id(), approved,
                            rejected.id(), rejected
                    ));

            assertEquals(RunResult.Status.PASSED, result.status());
            assertEquals(2, result.steps().size());
            assertEquals("/check", server.takeRequest().getPath());
            assertEquals("/approved", server.takeRequest().getPath());
            assertEquals(0, server.getRequestCount() - 2);
        }
    }

    @Test
    void gatewaySelectsBranchFromFixedValue() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

            EndpointDefinition enabled = endpoint("enabled", "GET", "/enabled");
            EndpointDefinition disabled = endpoint("disabled", "GET", "/disabled");
            FlowDefinition.FlowNode gateway = gatewayNode(
                    "g1", new FlowDefinition.GatewayConfig("FIXED", "", "enabled")
            );
            FlowDefinition flow = new FlowDefinition(
                    "flow-fixed-gateway", "project", "Fixed gateway flow", "",
                    List.of(gateway, apiNode("n1", enabled, List.of()), apiNode("n2", disabled, List.of())),
                    List.of(
                            new FlowDefinition.FlowEdge(
                                    "e1", "g1", "n1",
                                    new FlowDefinition.Condition("__gateway__", "EQUALS", "enabled")
                            ),
                            new FlowDefinition.FlowEdge("e2", "g1", "n2", null)
                    ),
                    FlowDefinition.FlowConfig.defaults(), null, null
            );
            EnvironmentDefinition environment = new EnvironmentDefinition(
                    "env", "project", "Test", server.url("/").toString(), Map.of()
            );

            RunResult result = new FlowEngine(new OkHttpClient(), new ObjectMapper())
                    .execute(flow, environment, Map.of(enabled.id(), enabled, disabled.id(), disabled));

            assertEquals(RunResult.Status.PASSED, result.status());
            assertEquals(1, result.steps().size());
            assertEquals("/enabled", server.takeRequest().getPath());
        }
    }

    @Test
    void parallelNodeExecutesBranchesConcurrently() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"branch\":\"a\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"branch\":\"b\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"merged\":true}"));

            EndpointDefinition branchA = endpoint("branchA", "GET", "/branch-a");
            EndpointDefinition branchB = endpoint("branchB", "GET", "/branch-b");
            EndpointDefinition merge = endpoint("merge", "GET", "/merge");

            FlowDefinition.FlowNode parallel = new FlowDefinition.FlowNode(
                    "p1", null, "Parallel Fork", 0, 0,
                    FlowDefinition.RequestConfig.empty(),
                    List.of(), List.of(), "PARALLEL", null
            );
            FlowDefinition.FlowNode nodeA = apiNode("a1", branchA, List.of());
            FlowDefinition.FlowNode nodeB = apiNode("b1", branchB, List.of());
            FlowDefinition.FlowNode mergeNode = apiNode("m1", merge, List.of());

            FlowDefinition flow = new FlowDefinition(
                    "flow-parallel", "project", "Parallel flow", "",
                    List.of(parallel, nodeA, nodeB, mergeNode),
                    List.of(
                            new FlowDefinition.FlowEdge("e1", "p1", "a1", null),
                            new FlowDefinition.FlowEdge("e2", "p1", "b1", null),
                            new FlowDefinition.FlowEdge("e3", "a1", "m1", null),
                            new FlowDefinition.FlowEdge("e4", "b1", "m1", null)
                    ),
                    FlowDefinition.FlowConfig.defaults(), null, null
            );
            EnvironmentDefinition environment = new EnvironmentDefinition(
                    "env", "project", "Test", server.url("/").toString(), Map.of()
            );

            RunResult result = new FlowEngine(new OkHttpClient(), new ObjectMapper())
                    .execute(flow, environment, Map.of(
                            branchA.id(), branchA,
                            branchB.id(), branchB,
                            merge.id(), merge
                    ));

            assertEquals(RunResult.Status.PASSED, result.status());
            assertEquals(3, result.steps().size());
        }
    }

    @Test
    void autoDetectParallelFromMultipleEdges() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

            EndpointDefinition start = endpoint("start", "GET", "/start");
            EndpointDefinition taskA = endpoint("taskA", "GET", "/task-a");
            EndpointDefinition taskB = endpoint("taskB", "GET", "/task-b");

            FlowDefinition.FlowNode startNode = apiNode("s1", start, List.of());
            FlowDefinition.FlowNode nodeA = apiNode("a1", taskA, List.of());
            FlowDefinition.FlowNode nodeB = apiNode("b1", taskB, List.of());

            FlowDefinition flow = new FlowDefinition(
                    "flow-auto-parallel", "project", "Auto parallel flow", "",
                    List.of(startNode, nodeA, nodeB),
                    List.of(
                            new FlowDefinition.FlowEdge("e1", "s1", "a1", null),
                            new FlowDefinition.FlowEdge("e2", "s1", "b1", null)
                    ),
                    FlowDefinition.FlowConfig.defaults(), null, null
            );
            EnvironmentDefinition environment = new EnvironmentDefinition(
                    "env", "project", "Test", server.url("/").toString(), Map.of()
            );

            RunResult result = new FlowEngine(new OkHttpClient(), new ObjectMapper())
                    .execute(flow, environment, Map.of(
                            start.id(), start,
                            taskA.id(), taskA,
                            taskB.id(), taskB
                    ));

            assertEquals(RunResult.Status.PASSED, result.status());
            assertEquals(3, result.steps().size());
        }
    }

    @Test
    void continueOnFailureStrategy() throws IOException, InterruptedException {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(500).setBody("{\"error\":\"server error\"}"));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"ok\":true}"));

            EndpointDefinition taskA = endpoint("taskA", "GET", "/task-a");
            EndpointDefinition taskB = endpoint("taskB", "GET", "/task-b");

            FlowDefinition.FlowNode nodeA = apiNode("a1", taskA, List.of());
            FlowDefinition.FlowNode nodeB = apiNode("b1", taskB, List.of());

            FlowDefinition flow = new FlowDefinition(
                    "flow-continue", "project", "Continue on failure flow", "",
                    List.of(nodeA, nodeB),
                    List.of(
                            new FlowDefinition.FlowEdge("e1", "a1", "b1", null)
                    ),
                    new FlowDefinition.FlowConfig("CONTINUE"), null, null
            );
            EnvironmentDefinition environment = new EnvironmentDefinition(
                    "env", "project", "Test", server.url("/").toString(), Map.of()
            );

            RunResult result = new FlowEngine(new OkHttpClient(), new ObjectMapper())
                    .execute(flow, environment, Map.of(
                            taskA.id(), taskA,
                            taskB.id(), taskB
                    ));

            assertEquals(RunResult.Status.FAILED, result.status());
            assertEquals(2, result.steps().size());
            assertEquals(RunResult.Status.FAILED, result.steps().get(0).status());
            assertEquals(RunResult.Status.PASSED, result.steps().get(1).status());
        }
    }

    private FlowDefinition.FlowNode apiNode(
            String id,
            EndpointDefinition endpoint,
            List<FlowDefinition.Extractor> extractors
    ) {
        return new FlowDefinition.FlowNode(
                id, endpoint.id(), endpoint.id(), 0, 0,
                FlowDefinition.RequestConfig.empty(),
                extractors, List.of(), "API", null
        );
    }

    private FlowDefinition.FlowNode gatewayNode(
            String id,
            FlowDefinition.GatewayConfig gateway
    ) {
        return new FlowDefinition.FlowNode(
                id, null, "Gateway", 0, 0,
                FlowDefinition.RequestConfig.empty(),
                List.of(), List.of(), "GATEWAY", gateway
        );
    }

    private FlowDefinition.FlowNode parallelNode(String id) {
        return new FlowDefinition.FlowNode(
                id, null, "Parallel Fork", 0, 0,
                FlowDefinition.RequestConfig.empty(),
                List.of(), List.of(), "PARALLEL", null
        );
    }

    private EndpointDefinition endpoint(String id, String method, String path) {
        return new EndpointDefinition(
                id, "project", id, method, path, id, List.of("Test"),
                List.of(), null, null, true
        );
    }
}
