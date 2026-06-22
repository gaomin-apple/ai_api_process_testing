package com.labway.aft.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labway.aft.domain.FlowDefinition.FlowNode;
import com.labway.aft.domain.FlowDefinition.GatewayConfig;
import com.labway.aft.domain.FlowDefinition.RequestConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowDefinitionJsonTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void apiNodeSerializesGatewayAsNullInsteadOfBoolean() throws Exception {
        FlowNode node = new FlowNode(
                "api-1", "endpoint-1", "Login", 0, 0,
                RequestConfig.empty(), List.of(), List.of(), "API", null
        );

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(node));

        assertTrue(json.get("gateway").isNull());
        assertEquals("API", json.get("nodeType").asText());
    }

    @Test
    void gatewayConfigRoundTripsAsObject() throws Exception {
        FlowNode node = new FlowNode(
                "gateway-1", null, "Route", 0, 0,
                RequestConfig.empty(), List.of(), List.of(), "GATEWAY",
                new GatewayConfig("VARIABLE", "status", "")
        );

        String json = objectMapper.writeValueAsString(node);
        JsonNode tree = objectMapper.readTree(json);
        FlowNode restored = objectMapper.readValue(json, FlowNode.class);

        assertTrue(tree.get("gateway").isObject());
        assertEquals("status", restored.gateway().source());
        assertTrue(restored.gatewayNode());
    }
}
