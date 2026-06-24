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
}
