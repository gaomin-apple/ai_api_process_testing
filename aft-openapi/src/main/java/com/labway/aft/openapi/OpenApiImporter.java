package com.labway.aft.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.labway.aft.domain.EndpointDefinition;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class OpenApiImporter {
    private static final Set<String> HTTP_METHODS = Set.of(
            "get", "post", "put", "patch", "delete", "head", "options", "trace"
    );
    private static final int MAX_SPEC_BYTES = 10 * 1024 * 1024;

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final HttpClient httpClient;

    public OpenApiImporter(ObjectMapper objectMapper) {
        this.jsonMapper = objectMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ImportResult importLocation(String projectId, String location) {
        try {
            String contents;
            if (location.startsWith("http://") || location.startsWith("https://")) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(location))
                        .timeout(Duration.ofSeconds(15))
                        .header("Accept", "application/json, application/yaml, text/yaml, */*")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new OpenApiImportException("OpenAPI URL returned HTTP " + response.statusCode());
                }
                try (InputStream input = response.body()) {
                    byte[] bytes = input.readNBytes(MAX_SPEC_BYTES + 1);
                    if (bytes.length > MAX_SPEC_BYTES) {
                        throw new OpenApiImportException("OpenAPI document exceeds 10 MB");
                    }
                    contents = new String(bytes, StandardCharsets.UTF_8);
                }
            } else {
                contents = Files.readString(Path.of(location));
            }
            return importContents(projectId, contents);
        } catch (IOException exception) {
            throw new OpenApiImportException("Unable to read OpenAPI location", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OpenApiImportException("OpenAPI request was interrupted", exception);
        }
    }

    public ImportResult importContents(String projectId, String contents) {
        try {
            JsonNode root = mapperFor(contents).readTree(contents);
            validate(root);
            List<EndpointDefinition> endpoints = toEndpoints(projectId, root);
            List<String> warnings = endpoints.isEmpty()
                    ? List.of("OpenAPI document contains no HTTP operations")
                    : List.of();
            return new ImportResult(endpoints, warnings);
        } catch (JsonProcessingException exception) {
            throw new OpenApiImportException("Unable to parse OpenAPI JSON/YAML: " + exception.getOriginalMessage(), exception);
        }
    }

    private ObjectMapper mapperFor(String contents) {
        String stripped = contents.stripLeading();
        return stripped.startsWith("{") || stripped.startsWith("[") ? jsonMapper : yamlMapper;
    }

    private void validate(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new OpenApiImportException("OpenAPI document must be an object");
        }
        String version = text(root, "openapi");
        if (version == null || !version.startsWith("3.")) {
            throw new OpenApiImportException("Only OpenAPI 3 documents are supported");
        }
        if (!root.path("paths").isObject()) {
            throw new OpenApiImportException("OpenAPI document is missing paths");
        }
    }

    private List<EndpointDefinition> toEndpoints(String projectId, JsonNode root) {
        List<EndpointDefinition> endpoints = new ArrayList<>();
        root.path("paths").fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();
            List<JsonNode> sharedParameters = nodes(pathItem.path("parameters"));
            pathItem.fields().forEachRemaining(operationEntry -> {
                String method = operationEntry.getKey().toLowerCase();
                if (!HTTP_METHODS.contains(method) || !operationEntry.getValue().isObject()) {
                    return;
                }
                endpoints.add(toEndpoint(
                        projectId,
                        method.toUpperCase(),
                        path,
                        operationEntry.getValue(),
                        sharedParameters
                ));
            });
        });
        endpoints.sort(Comparator.comparing(EndpointDefinition::path).thenComparing(EndpointDefinition::method));
        return endpoints;
    }

    private EndpointDefinition toEndpoint(
            String projectId,
            String method,
            String path,
            JsonNode operation,
            List<JsonNode> sharedParameters
    ) {
        Map<String, EndpointDefinition.ParameterDefinition> parameters = new LinkedHashMap<>();
        for (JsonNode parameter : sharedParameters) {
            EndpointDefinition.ParameterDefinition definition = toParameter(parameter);
            parameters.put(definition.location() + ":" + definition.name(), definition);
        }
        for (JsonNode parameter : nodes(operation.path("parameters"))) {
            EndpointDefinition.ParameterDefinition definition = toParameter(parameter);
            parameters.put(definition.location() + ":" + definition.name(), definition);
        }

        String requestSchema = contentSchema(operation.path("requestBody").path("content"));
        String responseSchema = responseSchema(operation.path("responses"));
        String summary = firstNonBlank(text(operation, "summary"), text(operation, "description"));
        return new EndpointDefinition(
                stableId(projectId + " " + method + " " + path),
                projectId,
                text(operation, "operationId"),
                method,
                path,
                summary,
                strings(operation.path("tags")),
                List.copyOf(parameters.values()),
                requestSchema,
                responseSchema,
                true
        );
    }

    private EndpointDefinition.ParameterDefinition toParameter(JsonNode parameter) {
        return new EndpointDefinition.ParameterDefinition(
                text(parameter, "name"),
                text(parameter, "in"),
                parameter.path("required").asBoolean(false),
                json(parameter.path("schema"))
        );
    }

    private String responseSchema(JsonNode responses) {
        if (!responses.isObject()) {
            return null;
        }
        Iterator<Map.Entry<String, JsonNode>> fields = responses.fields();
        JsonNode fallback = null;
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> response = fields.next();
            if ("default".equals(response.getKey())) {
                fallback = response.getValue();
            }
            if (response.getKey().matches("2\\d\\d")) {
                return contentSchema(response.getValue().path("content"));
            }
        }
        return fallback == null ? null : contentSchema(fallback.path("content"));
    }

    private String contentSchema(JsonNode content) {
        if (!content.isObject() || content.isEmpty()) {
            return null;
        }
        JsonNode media = content.path("application/json");
        if (media.isMissingNode()) {
            media = content.elements().next();
        }
        return json(media.path("schema"));
    }

    private String json(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        try {
            return jsonMapper.writeValueAsString(node);
        } catch (JsonProcessingException exception) {
            throw new OpenApiImportException("Unable to serialize OpenAPI schema", exception);
        }
    }

    private static List<JsonNode> nodes(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<JsonNode> values = new ArrayList<>();
        array.forEach(values::add);
        return values;
    }

    private static List<String> strings(JsonNode array) {
        if (!array.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String stableId(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return "ep_" + HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record ImportResult(List<EndpointDefinition> endpoints, List<String> warnings) {
    }

    public static final class OpenApiImportException extends RuntimeException {
        public OpenApiImportException(String message) {
            super(message);
        }

        public OpenApiImportException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
