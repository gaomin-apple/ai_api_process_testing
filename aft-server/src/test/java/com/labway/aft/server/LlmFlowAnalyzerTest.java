package com.labway.aft.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labway.aft.domain.EndpointDefinition;
import com.labway.aft.domain.FlowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmFlowAnalyzerTest {
    @Test
    void generatedFlowFillsRequiredParametersWithRunPlaceholders() {
        EndpointDefinition endpoint = new EndpointDefinition(
                "ep_get_order",
                "project-1",
                "getOrder",
                "GET",
                "/orders/{id}",
                "Get order",
                List.of("Order"),
                List.of(
                        new EndpointDefinition.ParameterDefinition("id", "path", true, null),
                        new EndpointDefinition.ParameterDefinition("tenantId", "query", true, null),
                        new EndpointDefinition.ParameterDefinition("X-Trace-Id", "header", true, null)
                ),
                null,
                null,
                true
        );
        JavaProjectScanner.ScanResult scan = new JavaProjectScanner.ScanResult(
                "D:/service",
                1,
                1,
                120,
                List.of(),
                List.of()
        );
        LlmFlowAnalyzer analyzer = new StubAnalyzer();

        FlowDefinition flow = analyzer.analyze(
                "project-1",
                scan,
                List.of(endpoint),
                new LlmFlowAnalyzer.JavaFlowAnalyzeRequest(
                        "D:/service",
                        "Order lookup",
                        "https://example.test/v1",
                        "test-key",
                        "test-model"
                )
        );

        FlowDefinition.RequestConfig request = flow.nodes().get(0).request();
        assertEquals(Map.of("id", "${run.id}"), request.path());
        assertEquals(Map.of("tenantId", "${run.tenantId}"), request.query());
        assertEquals(Map.of("X-Trace-Id", "${run.X-Trace-Id}"), request.headers());
    }

    @Test
    void generatedFlowUsesExplicitRelationshipEdges() {
        EndpointDefinition login = endpoint("ep_login", "POST", "/login", "Login");
        EndpointDefinition create = endpoint("ep_create_order", "POST", "/orders", "Create order");
        EndpointDefinition query = endpoint("ep_query_order", "GET", "/orders/{id}", "Query order");
        LlmFlowAnalyzer analyzer = new GraphAnalyzer();

        FlowDefinition flow = analyzer.analyze(
                "project-1",
                emptyScan(),
                List.of(login, create, query),
                new LlmFlowAnalyzer.JavaFlowAnalyzeRequest(
                        "D:/service",
                        "Order process",
                        "https://example.test/v1",
                        "test-key",
                        "test-model"
                )
        );

        assertEquals(3, flow.nodes().size());
        assertEquals(2, flow.edges().size());
        String loginNodeId = nodeId(flow, "ep_login");
        String createNodeId = nodeId(flow, "ep_create_order");
        String queryNodeId = nodeId(flow, "ep_query_order");
        assertEquals(loginNodeId, flow.edges().get(0).source());
        assertEquals(createNodeId, flow.edges().get(0).target());
        assertEquals(createNodeId, flow.edges().get(1).source());
        assertEquals(queryNodeId, flow.edges().get(1).target());
    }

    private static class StubAnalyzer extends LlmFlowAnalyzer {
        StubAnalyzer() {
            super(new ObjectMapper());
        }

        @Override
        protected String callModel(LlmFlowAnalyzer.LlmConfig config, String prompt) {
            return """
                    {
                      "name": "Order lookup",
                      "steps": [
                        {"endpointId": "ep_get_order", "label": "Get order", "reason": "lookup"}
                      ]
                    }
                    """;
        }
    }

    private static class GraphAnalyzer extends LlmFlowAnalyzer {
        GraphAnalyzer() {
            super(new ObjectMapper());
        }

        @Override
        protected String callModel(LlmFlowAnalyzer.LlmConfig config, String prompt) {
            return """
                    {
                      "name": "Order process",
                      "nodes": [
                        {"id": "login", "endpointId": "ep_login", "label": "Login"},
                        {"id": "create", "endpointId": "ep_create_order", "label": "Create order"},
                        {"id": "query", "endpointId": "ep_query_order", "label": "Query order"}
                      ],
                      "edges": [
                        {"source": "login", "target": "create", "reason": "token required"},
                        {"source": "create", "target": "query", "reason": "query created order"}
                      ]
                    }
                    """;
        }
    }

    private static EndpointDefinition endpoint(String id, String method, String path, String summary) {
        return new EndpointDefinition(
                id,
                "project-1",
                id,
                method,
                path,
                summary,
                List.of("Test"),
                List.of(),
                null,
                null,
                true
        );
    }

    private static JavaProjectScanner.ScanResult emptyScan() {
        return new JavaProjectScanner.ScanResult(
                "D:/service",
                1,
                1,
                120,
                List.of(),
                List.of()
        );
    }

    private static String nodeId(FlowDefinition flow, String endpointId) {
        return flow.nodes().stream()
                .filter(node -> endpointId.equals(node.endpointId()))
                .findFirst()
                .orElseThrow()
                .id();
    }
}
