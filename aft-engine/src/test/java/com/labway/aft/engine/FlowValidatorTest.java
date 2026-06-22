package com.labway.aft.engine;

import com.labway.aft.domain.EndpointDefinition;
import com.labway.aft.domain.FlowDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowValidatorTest {
    private final FlowValidator validator = new FlowValidator();

    @Test
    void draftSaveAllowsGatewayWithoutOutgoingBranches() {
        EndpointDefinition endpoint = endpoint();
        FlowDefinition flow = flowWithIncompleteGateway(endpoint);

        List<String> errors = validator.validateForSave(flow, Map.of(endpoint.id(), endpoint));

        assertTrue(errors.isEmpty());
    }

    @Test
    void executionRequiresGatewayBranches() {
        EndpointDefinition endpoint = endpoint();
        FlowDefinition flow = flowWithIncompleteGateway(endpoint);

        List<String> errors = validator.validate(flow, Map.of(endpoint.id(), endpoint));

        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error ->
                error.equals("Gateway Condition gateway must connect at least two outgoing branches")));
    }

    @Test
    void executionRequiresGatewayBranchExpectedValue() {
        EndpointDefinition endpoint = endpoint();
        FlowDefinition.FlowNode gateway = new FlowDefinition.FlowNode(
                "gateway", null, "Condition gateway", 0, 0,
                FlowDefinition.RequestConfig.empty(), List.of(), List.of(), "GATEWAY",
                new FlowDefinition.GatewayConfig("FIXED", "", "admin")
        );
        FlowDefinition.FlowNode firstTarget = new FlowDefinition.FlowNode(
                "first", endpoint.id(), "First", 300, -100,
                FlowDefinition.RequestConfig.empty(), List.of(), List.of(), "API", null
        );
        FlowDefinition.FlowNode defaultTarget = new FlowDefinition.FlowNode(
                "default", endpoint.id(), "Default", 300, 100,
                FlowDefinition.RequestConfig.empty(), List.of(), List.of(), "API", null
        );
        FlowDefinition flow = new FlowDefinition(
                "flow", "project", "Draft", "",
                List.of(gateway, firstTarget, defaultTarget),
                List.of(
                        new FlowDefinition.FlowEdge(
                                "conditional", "gateway", "first",
                                new FlowDefinition.Condition("__gateway__", "EQUALS", "")
                        ),
                        new FlowDefinition.FlowEdge("fallback", "gateway", "default", null)
                ),
                FlowDefinition.FlowConfig.defaults(), null, null
        );

        List<String> errors = validator.validate(flow, Map.of(endpoint.id(), endpoint));

        assertTrue(errors.stream().anyMatch(error -> error.contains("is missing an expected value")));
    }

    private FlowDefinition flowWithIncompleteGateway(EndpointDefinition endpoint) {
        FlowDefinition.FlowNode apiNode = new FlowDefinition.FlowNode(
                "api", endpoint.id(), "API", 0, 0,
                FlowDefinition.RequestConfig.empty(), List.of(), List.of(), "API", null
        );
        FlowDefinition.FlowNode gatewayNode = new FlowDefinition.FlowNode(
                "gateway", null, "Condition gateway", 300, 0,
                FlowDefinition.RequestConfig.empty(), List.of(), List.of(), "GATEWAY",
                new FlowDefinition.GatewayConfig("VARIABLE", "role", "")
        );
        return new FlowDefinition(
                "flow", "project", "Draft", "",
                List.of(apiNode, gatewayNode),
                List.of(new FlowDefinition.FlowEdge("edge", "api", "gateway", null)),
                FlowDefinition.FlowConfig.defaults(), null, null
        );
    }

    private EndpointDefinition endpoint() {
        return new EndpointDefinition(
                "endpoint", "project", "API", "GET", "/api", "API",
                List.of(), List.of(), null, null, true
        );
    }
}
