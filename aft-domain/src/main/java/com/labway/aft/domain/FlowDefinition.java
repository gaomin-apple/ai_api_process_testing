package com.labway.aft.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record FlowDefinition(
        String id,
        String projectId,
        String name,
        String description,
        List<FlowNode> nodes,
        List<FlowEdge> edges,
        FlowConfig config,
        Instant createdAt,
        Instant updatedAt
) {
    public FlowDefinition {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        edges = edges == null ? List.of() : List.copyOf(edges);
        config = config == null ? FlowConfig.defaults() : config;
    }

    public record FlowNode(
            String id,
            String endpointId,
            String name,
            double x,
            double y,
            RequestConfig request,
            List<Extractor> extractors,
            List<Assertion> assertions,
            String nodeType,
            GatewayConfig gateway
    ) {
        public FlowNode {
            nodeType = nodeType == null || nodeType.isBlank() ? "API" : nodeType.toUpperCase();
            request = request == null ? RequestConfig.empty() : request;
            extractors = extractors == null ? List.of() : List.copyOf(extractors);
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
        }

        public boolean gatewayNode() {
            return "GATEWAY".equals(nodeType);
        }

        public boolean parallelNode() {
            return "PARALLEL".equals(nodeType);
        }
    }

    public record FlowConfig(String onFailure) {
        public FlowConfig {
            onFailure = onFailure == null || onFailure.isBlank() ? "FAIL_ALL" : onFailure.toUpperCase();
        }

        public static FlowConfig defaults() {
            return new FlowConfig("FAIL_ALL");
        }

        public boolean continueOnFailure() {
            return "CONTINUE".equals(onFailure);
        }
    }

    public record FlowEdge(String id, String source, String target, Condition condition) {
        public FlowEdge {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("Edge id is required");
        }
    }

    public record Condition(String source, String operator, String expected) {
    }

    public record GatewayConfig(String sourceType, String source, String fixedValue) {
        public GatewayConfig {
            sourceType = sourceType == null || sourceType.isBlank()
                    ? "VARIABLE"
                    : sourceType.toUpperCase();
        }
    }

    public record RequestConfig(
            Map<String, String> path,
            Map<String, String> query,
            Map<String, String> headers,
            Map<String, String> form,
            String body,
            String bodyType,
            String authenticationType,
            String authenticationValue,
            long timeoutMs
    ) {
        public RequestConfig {
            path = path == null ? Map.of() : Map.copyOf(path);
            query = query == null ? Map.of() : Map.copyOf(query);
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            form = form == null ? Map.of() : Map.copyOf(form);
            if (bodyType == null || bodyType.isBlank()) {
                bodyType = !form.isEmpty()
                        ? "FORM_URLENCODED"
                        : body != null && !body.isBlank() ? "JSON" : "NONE";
            }
            timeoutMs = timeoutMs <= 0 ? 10_000 : timeoutMs;
        }

        public static RequestConfig empty() {
            return new RequestConfig(
                    Map.of(), Map.of(), Map.of(), Map.of(),
                    null, "NONE", "NONE", null, 10_000
            );
        }
    }

    public record Extractor(String variable, String source, String expression) {
    }

    public record Assertion(String type, String expression, String operator, String expected) {
    }
}
