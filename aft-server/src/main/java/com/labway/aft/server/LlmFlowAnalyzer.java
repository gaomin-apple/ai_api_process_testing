package com.labway.aft.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labway.aft.domain.EndpointDefinition;
import com.labway.aft.domain.FlowDefinition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LlmFlowAnalyzer {
    private static final String DEFAULT_BASE_URL = "https://api.deepseek.com/v1";
    private static final String DEFAULT_MODEL = "deepseek-chat";
    private static final Pattern PATH_PARAMETER_PATTERN = Pattern.compile("\\{([^}/]+)}");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public LlmFlowAnalyzer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public LlmDefaults defaults() {
        String apiKey = configured("AFT_LLM_API_KEY", null);
        return new LlmDefaults(
                configured("AFT_LLM_BASE_URL", DEFAULT_BASE_URL),
                configured("AFT_LLM_MODEL", DEFAULT_MODEL),
                apiKey != null && !apiKey.isBlank()
        );
    }

    public FlowDefinition analyze(
            String projectId,
            JavaProjectScanner.ScanResult scan,
            List<EndpointDefinition> endpoints,
            JavaFlowAnalyzeRequest request
    ) {
        if (endpoints == null || endpoints.isEmpty()) {
            throw new IllegalArgumentException("No endpoints are available. Import OpenAPI first or scan a Java project with Spring MVC mappings.");
        }
        LlmConfig config = resolveConfig(request);
        String llmJson = callModel(config, buildPrompt(scan, endpoints));
        GeneratedFlow generated = parseGeneratedFlow(llmJson);
        return toFlow(projectId, request.flowName(), generated, endpoints);
    }

    private LlmConfig resolveConfig(JavaFlowAnalyzeRequest request) {
        String baseUrl = firstNonBlank(request.apiBaseUrl(), configured("AFT_LLM_BASE_URL", DEFAULT_BASE_URL));
        String model = firstNonBlank(request.model(), configured("AFT_LLM_MODEL", DEFAULT_MODEL));
        String apiKey = firstNonBlank(request.apiKey(), configured("AFT_LLM_API_KEY", null));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("LLM API key is required. Set AFT_LLM_API_KEY or provide apiKey in the request.");
        }
        return new LlmConfig(baseUrl, model, apiKey);
    }

    private String buildPrompt(JavaProjectScanner.ScanResult scan, List<EndpointDefinition> endpoints) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task", """
                Analyze the Java project source and infer API business call relationships.
                Return one likely executable API relationship graph using only endpointId values from the provided endpoint list.
                Prefer controller/service call order, method names, path semantics, and request/response DTO names.
                Output strict JSON only. Do not include markdown.
                JSON schema:
                {"name":"short flow name","nodes":[{"id":"n1","endpointId":"id","label":"short label","reason":"why this endpoint is included"}],"edges":[{"source":"n1","target":"n2","reason":"why source precedes target"}]}
                Use edges to represent inferred API call relationships. If the relationship is purely linear, still return explicit edges.
                """);
        payload.put("endpoints", endpoints.stream().map(this::endpointPayload).toList());
        payload.put("scan", scan);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to build LLM prompt", exception);
        }
    }

    private Map<String, Object> endpointPayload(EndpointDefinition endpoint) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("endpointId", endpoint.id());
        value.put("method", endpoint.method());
        value.put("path", endpoint.path());
        value.put("operationId", endpoint.operationId());
        value.put("summary", endpoint.summary());
        value.put("tags", endpoint.tags());
        value.put("parameters", endpoint.parameters().stream().map(parameter -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", parameter.name());
            item.put("location", parameter.location());
            item.put("required", parameter.required());
            return item;
        }).toList());
        return value;
    }

    protected String callModel(LlmConfig config, String prompt) {
        Map<String, Object> body = Map.of(
                "model", config.model(),
                "temperature", 0.1,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", "You are a senior backend architect. Return valid JSON only."),
                        Map.of("role", "user", "content", prompt)
                )
        );
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize LLM request", exception);
        }

        HttpRequest request = HttpRequest.newBuilder(chatCompletionsUri(config.baseUrl()))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalArgumentException("LLM request failed with HTTP " + response.statusCode() + ": "
                        + truncate(response.body(), 800));
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("LLM response did not contain choices[0].message.content");
            }
            return content;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Unable to call LLM API: " + exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalArgumentException("LLM request was interrupted", exception);
        }
    }

    private URI chatCompletionsUri(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        if (trimmed.endsWith("/chat/completions")) {
            return URI.create(trimmed);
        }
        if (trimmed.endsWith("/v1")) {
            return URI.create(trimmed + "/chat/completions");
        }
        return URI.create(trimmed + "/v1/chat/completions");
    }

    private GeneratedFlow parseGeneratedFlow(String raw) {
        String json = extractJson(raw);
        try {
            JsonNode root = objectMapper.readTree(json);
            String name = root.path("name").asText("AI Generated Flow");
            List<GeneratedNode> nodes = generatedNodes(root);
            List<GeneratedEdge> edges = generatedEdges(root, nodes);
            return new GeneratedFlow(name, nodes, edges);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Unable to parse LLM JSON: " + exception.getOriginalMessage(), exception);
        }
    }

    private List<GeneratedNode> generatedNodes(JsonNode root) {
        List<GeneratedNode> nodes = new ArrayList<>();
        JsonNode nodesNode = root.path("nodes");
        if (nodesNode.isArray()) {
            for (JsonNode node : nodesNode) {
                String id = node.path("id").asText(null);
                String endpointId = node.path("endpointId").asText(null);
                if (endpointId != null && !endpointId.isBlank()) {
                    nodes.add(new GeneratedNode(
                            firstNonBlank(id, "node-" + (nodes.size() + 1)),
                            endpointId,
                            node.path("label").asText(null),
                            node.path("reason").asText(null)
                    ));
                }
            }
        }
        if (nodes.isEmpty()) {
            JsonNode stepsNode = root.path("steps");
            if (!stepsNode.isArray()) {
                throw new IllegalArgumentException("LLM JSON must include a nodes array or a steps array");
            }
            for (JsonNode step : stepsNode) {
                String endpointId = step.path("endpointId").asText(null);
                if (endpointId != null && !endpointId.isBlank()) {
                    nodes.add(new GeneratedNode(
                            "node-" + (nodes.size() + 1),
                            endpointId,
                            step.path("label").asText(null),
                            step.path("reason").asText(null)
                    ));
                }
            }
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("LLM JSON did not select any endpointId values");
        }
        return nodes;
    }

    private List<GeneratedEdge> generatedEdges(JsonNode root, List<GeneratedNode> nodes) {
        List<GeneratedEdge> edges = new ArrayList<>();
        JsonNode edgesNode = root.path("edges");
        if (edgesNode.isArray()) {
            for (JsonNode edge : edgesNode) {
                String source = edge.path("source").asText(null);
                String target = edge.path("target").asText(null);
                if (source != null && !source.isBlank() && target != null && !target.isBlank()) {
                    edges.add(new GeneratedEdge(source, target, edge.path("reason").asText(null)));
                }
            }
        }
        if (edges.isEmpty() && nodes.size() > 1) {
            for (int index = 0; index < nodes.size() - 1; index++) {
                edges.add(new GeneratedEdge(nodes.get(index).id(), nodes.get(index + 1).id(), "linear fallback"));
            }
        }
        return edges;
    }

    private FlowDefinition toFlow(
            String projectId,
            String requestedName,
            GeneratedFlow generated,
            List<EndpointDefinition> endpoints
    ) {
        Map<String, EndpointDefinition> byId = new LinkedHashMap<>();
        for (EndpointDefinition endpoint : endpoints) {
            byId.put(endpoint.id(), endpoint);
        }

        List<FlowDefinition.FlowNode> nodes = new ArrayList<>();
        Map<String, String> generatedIdToNodeId = new LinkedHashMap<>();
        int index = 0;
        for (GeneratedNode generatedNode : generated.nodes()) {
            EndpointDefinition endpoint = byId.get(generatedNode.endpointId());
            if (endpoint == null || !endpoint.active()) {
                continue;
            }
            String nodeId = "ai-node-" + UUID.randomUUID();
            generatedIdToNodeId.put(generatedNode.id(), nodeId);
            nodes.add(new FlowDefinition.FlowNode(
                    nodeId,
                    endpoint.id(),
                    firstNonBlank(generatedNode.label(), endpoint.summary(), endpoint.operationId(), endpoint.method() + " " + endpoint.path()),
                    120 + index * 300.0,
                    160 + (index % 2) * 130.0,
                    requestFor(endpoint),
                    List.of(),
                    List.of(),
                    "API",
                    null
            ));
            index++;
        }
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("LLM selected endpoints that are not available in this project");
        }

        List<FlowDefinition.FlowEdge> edges = new ArrayList<>();
        for (GeneratedEdge generatedEdge : generated.edges()) {
            String source = generatedIdToNodeId.get(generatedEdge.source());
            String target = generatedIdToNodeId.get(generatedEdge.target());
            if (source != null && target != null && !source.equals(target)) {
                edges.add(new FlowDefinition.FlowEdge(
                        "ai-edge-" + UUID.randomUUID(),
                        source,
                        target,
                        null
                ));
            }
        }
        if (edges.isEmpty() && nodes.size() > 1) {
            for (int edgeIndex = 0; edgeIndex < nodes.size() - 1; edgeIndex++) {
                edges.add(new FlowDefinition.FlowEdge(
                        "ai-edge-" + UUID.randomUUID(),
                        nodes.get(edgeIndex).id(),
                        nodes.get(edgeIndex + 1).id(),
                        null
                ));
            }
        }
        Instant now = Instant.now();
        return new FlowDefinition(
                UUID.randomUUID().toString(),
                projectId,
                firstNonBlank(requestedName, generated.name(), "AI Generated Flow"),
                "Generated from Java project scan and LLM endpoint relationship analysis.",
                nodes,
                edges,
                FlowDefinition.FlowConfig.defaults(),
                now,
                now
        );
    }

    private FlowDefinition.RequestConfig requestFor(EndpointDefinition endpoint) {
        Map<String, String> path = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Matcher matcher = PATH_PARAMETER_PATTERN.matcher(endpoint.path());
        while (matcher.find()) {
            path.put(matcher.group(1), "${run." + matcher.group(1) + "}");
        }
        for (EndpointDefinition.ParameterDefinition parameter : endpoint.parameters()) {
            if ("path".equals(parameter.location())) {
                path.putIfAbsent(parameter.name(), "${run." + parameter.name() + "}");
            } else if (parameter.required() && "query".equals(parameter.location())) {
                query.putIfAbsent(parameter.name(), "${run." + parameter.name() + "}");
            } else if (parameter.required() && "header".equals(parameter.location())) {
                headers.putIfAbsent(parameter.name(), "${run." + parameter.name() + "}");
            }
        }
        return new FlowDefinition.RequestConfig(
                path,
                query,
                headers,
                Map.of(),
                null,
                endpoint.requestBodySchema() == null ? "NONE" : "JSON",
                "NONE",
                null,
                10_000
        );
    }

    private static String extractJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return trimmed;
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        throw new IllegalArgumentException("LLM response was not JSON");
    }

    private static String configured(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "...";
    }

    public record JavaFlowAnalyzeRequest(
            String sourcePath,
            String flowName,
            String apiBaseUrl,
            String apiKey,
            String model
    ) {
    }

    public record JavaFlowAnalyzeResponse(
            FlowDefinition flow,
            JavaProjectScanner.ScanResult scan,
            LlmDefaults defaults
    ) {
    }

    public record LlmDefaults(String apiBaseUrl, String model, boolean apiKeyConfigured) {
    }

    protected record LlmConfig(String baseUrl, String model, String apiKey) {
    }

    private record GeneratedFlow(String name, List<GeneratedNode> nodes, List<GeneratedEdge> edges) {
    }

    private record GeneratedNode(String id, String endpointId, String label, String reason) {
    }

    private record GeneratedEdge(String source, String target, String reason) {
    }
}
