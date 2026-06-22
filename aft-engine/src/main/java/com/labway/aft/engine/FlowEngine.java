package com.labway.aft.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.labway.aft.domain.EndpointDefinition;
import com.labway.aft.domain.EnvironmentDefinition;
import com.labway.aft.domain.FlowDefinition;
import com.labway.aft.domain.RunResult;
import okhttp3.FormBody;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.Buffer;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FlowEngine {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType TEXT = MediaType.get("text/plain; charset=utf-8");
    private static final long MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final OkHttpClient baseClient;
    private final ObjectMapper objectMapper;
    private final TemplateResolver templates = new TemplateResolver();
    private final FlowValidator validator = new FlowValidator();
    private final SecretRedactor redactor = new SecretRedactor();

    public FlowEngine(OkHttpClient baseClient, ObjectMapper objectMapper) {
        this.baseClient = baseClient;
        this.objectMapper = objectMapper;
    }

    public RunResult execute(
            FlowDefinition flow,
            EnvironmentDefinition environment,
            Map<String, EndpointDefinition> endpoints
    ) {
        List<String> validationErrors = validator.validate(flow, endpoints);
        if (!validationErrors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", validationErrors));
        }

        Instant startedAt = Instant.now();
        boolean continueOnFailure = flow.config().continueOnFailure();

        Map<String, FlowDefinition.FlowNode> nodeMap = new HashMap<>();
        flow.nodes().forEach(node -> nodeMap.put(node.id(), node));

        Map<String, List<String>> adjacency = new HashMap<>();
        Map<String, List<String>> reverseAdjacency = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();
        flow.nodes().forEach(node -> {
            adjacency.put(node.id(), new ArrayList<>());
            reverseAdjacency.put(node.id(), new ArrayList<>());
            inDegree.put(node.id(), 0);
        });
        for (FlowDefinition.FlowEdge edge : flow.edges()) {
            adjacency.get(edge.source()).add(edge.target());
            reverseAdjacency.get(edge.target()).add(edge.source());
            inDegree.merge(edge.target(), 1, Integer::sum);
        }

        Map<String, List<FlowDefinition.FlowEdge>> outEdges = new HashMap<>();
        flow.edges().forEach(edge ->
                outEdges.computeIfAbsent(edge.source(), k -> new ArrayList<>()).add(edge));

        ConcurrentHashMap<String, String> variables = new ConcurrentHashMap<>();
        variables.put("env.baseUrl", trimTrailingSlash(environment.baseUrl()));
        environment.variables().forEach((key, value) -> variables.put("env." + key, value));

        List<RunResult.StepResult> steps = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean failed = new AtomicBoolean(false);
        Set<String> completed = Collections.synchronizedSet(new HashSet<>());
        Set<String> ready = new HashSet<>();

        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }

        ExecutorService executor = Executors.newFixedThreadPool(
                Math.min(Runtime.getRuntime().availableProcessors(), 8));

        try {
            while (!ready.isEmpty()) {
                if (failed.get() && !continueOnFailure) {
                    break;
                }

                List<String> currentBatch = new ArrayList<>(ready);
                ready.clear();

                List<String> gatewayNodes = new ArrayList<>();
                List<String> parallelNodes = new ArrayList<>();

                for (String nodeId : currentBatch) {
                    FlowDefinition.FlowNode node = nodeMap.get(nodeId);
                    if (node.gatewayNode()) {
                        gatewayNodes.add(nodeId);
                    } else if (node.parallelNode()) {
                        parallelNodes.add(nodeId);
                    }
                }

                for (String nodeId : gatewayNodes) {
                    FlowDefinition.FlowNode node = nodeMap.get(nodeId);
                    List<FlowDefinition.FlowNode> next = validator.nextNodes(node, flow, variables);
                    completed.add(nodeId);
                    for (FlowDefinition.FlowNode nextNode : next) {
                        if (isNodeReady(nextNode.id(), reverseAdjacency, completed)) {
                            ready.add(nextNode.id());
                        }
                    }
                }

                for (String nodeId : parallelNodes) {
                    completed.add(nodeId);
                    List<FlowDefinition.FlowEdge> nodeEdges = outEdges.getOrDefault(nodeId, List.of());
                    for (FlowDefinition.FlowEdge edge : nodeEdges) {
                        if (edge.condition() != null && !evaluateCondition(edge.condition(), variables)) {
                            continue;
                        }
                        if (isNodeReady(edge.target(), reverseAdjacency, completed)) {
                            ready.add(edge.target());
                        }
                    }
                }

                List<String> apiNodes = new ArrayList<>();
                for (String nodeId : currentBatch) {
                    if (!gatewayNodes.contains(nodeId) && !parallelNodes.contains(nodeId)) {
                        apiNodes.add(nodeId);
                    }
                }

                if (!apiNodes.isEmpty()) {
                    List<Future<StepExecution>> futures = new ArrayList<>();
                    for (String nodeId : apiNodes) {
                        FlowDefinition.FlowNode node = nodeMap.get(nodeId);
                        EndpointDefinition endpoint = endpoints.get(node.endpointId());
                        Map<String, String> snapshot = new HashMap<>(variables);
                        futures.add(executor.submit(() -> executeStep(node, endpoint, snapshot)));
                    }

                    for (int i = 0; i < apiNodes.size(); i++) {
                        String nodeId = apiNodes.get(i);
                        try {
                            StepExecution execution = futures.get(i).get();
                            RunResult.StepResult step = execution.result();
                            steps.add(step);
                            completed.add(nodeId);

                            if (step.status() == RunResult.Status.FAILED) {
                                failed.set(true);
                                if (!continueOnFailure) {
                                    break;
                                }
                            }

                            execution.rawExtractedVariables().forEach(
                                    (key, value) -> variables.put("run." + key, value));

                            List<String> successors = adjacency.get(nodeId);
                            for (String successor : successors) {
                                if (isNodeReady(successor, reverseAdjacency, completed)) {
                                    ready.add(successor);
                                }
                            }
                        } catch (Exception e) {
                            failed.set(true);
                            if (!continueOnFailure) {
                                break;
                            }
                        }
                    }
                }
            }
        } finally {
            executor.shutdown();
        }

        Instant finishedAt = Instant.now();
        RunResult.Status status = failed.get() ? RunResult.Status.FAILED : RunResult.Status.PASSED;
        String runError = status == RunResult.Status.FAILED
                ? "Flow execution failed. Check step details for errors."
                : null;
        return new RunResult(
                UUID.randomUUID().toString(),
                flow.id(),
                flow.name(),
                environment.id(),
                status,
                startedAt,
                finishedAt,
                Duration.between(startedAt, finishedAt).toMillis(),
                steps,
                redactor.redactHeaders(variables),
                runError
        );
    }

    private boolean isNodeReady(
            String nodeId,
            Map<String, List<String>> reverseAdjacency,
            Set<String> completed
    ) {
        List<String> predecessors = reverseAdjacency.get(nodeId);
        return predecessors != null && completed.containsAll(predecessors);
    }

    private StepExecution executeStep(
            FlowDefinition.FlowNode node,
            EndpointDefinition endpoint,
            Map<String, String> variables
    ) {
        long started = System.nanoTime();
        FlowDefinition.RequestConfig config = node.request();
        try {
            Request request = buildRequest(endpoint, config, variables);
            OkHttpClient client = baseClient.newBuilder()
                    .callTimeout(Duration.ofMillis(config.timeoutMs()))
                    .build();
            try (Response response = client.newCall(request).execute()) {
                String responseBody = response.body() == null ? "" : response.peekBody(MAX_RESPONSE_BYTES).string();
                Map<String, String> extracted = extract(config, node.extractors(), response, responseBody);
                List<RunResult.AssertionResult> assertions =
                        evaluateAssertions(node.assertions(), response, responseBody);
                boolean passed = assertions.stream().allMatch(RunResult.AssertionResult::passed);
                String error = passed ? null : assertions.stream()
                        .filter(assertion -> !assertion.passed())
                        .map(RunResult.AssertionResult::message)
                        .findFirst().orElse("Assertion failed");
                RunResult.StepResult result = stepResult(
                        node, endpoint, passed, response.code(), started, request, responseBody,
                        redactor.redactHeaders(extracted), assertions, error
                );
                return new StepExecution(result, extracted);
            }
        } catch (Exception exception) {
            RunResult.StepResult result = new RunResult.StepResult(
                    node.id(), node.name(), endpoint.id(), endpoint.method(), endpoint.path(),
                    RunResult.Status.FAILED, 0, elapsedMs(started), null, null, Map.of(), List.of(),
                    exception.getMessage()
            );
            return new StepExecution(result, Map.of());
        }
    }

    private Request buildRequest(
            EndpointDefinition endpoint,
            FlowDefinition.RequestConfig config,
            Map<String, String> variables
    ) {
        String path = endpoint.path();
        for (Map.Entry<String, String> entry : config.path().entrySet()) {
            path = path.replace("{" + entry.getKey() + "}", templates.resolve(entry.getValue(), variables));
        }
        if (path.contains("{")) {
            throw new IllegalArgumentException("Unresolved path parameter in " + path);
        }

        HttpUrl base = HttpUrl.get(templates.resolve("${env.baseUrl}", variables) + path);
        HttpUrl.Builder url = base.newBuilder();
        config.query().forEach((key, value) ->
                url.addQueryParameter(key, templates.resolve(value, variables)));

        Request.Builder request = new Request.Builder().url(url.build());
        config.headers().forEach((key, value) -> request.header(key, templates.resolve(value, variables)));
        applyAuthentication(request, config, variables);
        RequestBody body = requestBody(config, variables);
        request.method(endpoint.method(), permitsBody(endpoint.method()) ? body : null);
        return request.build();
    }

    private RequestBody requestBody(FlowDefinition.RequestConfig config, Map<String, String> variables) {
        String bodyType = config.bodyType().toUpperCase(Locale.ROOT);
        return switch (bodyType) {
            case "FORM_URLENCODED" -> {
                FormBody.Builder form = new FormBody.Builder();
                config.form().forEach((key, value) -> form.add(key, templates.resolve(value, variables)));
                yield form.build();
            }
            case "JSON" -> {
                String body = templates.resolve(config.body(), variables);
                yield RequestBody.create(body == null || body.isBlank() ? "{}" : body, JSON);
            }
            case "TEXT" -> {
                String body = templates.resolve(config.body(), variables);
                yield RequestBody.create(body == null ? "" : body, TEXT);
            }
            case "NONE" -> RequestBody.create(new byte[0], null);
            default -> throw new IllegalArgumentException("Unsupported request body type: " + config.bodyType());
        };
    }

    private void applyAuthentication(
            Request.Builder request,
            FlowDefinition.RequestConfig config,
            Map<String, String> variables
    ) {
        String type = config.authenticationType() == null
                ? "NONE"
                : config.authenticationType().toUpperCase(Locale.ROOT);
        String value = templates.resolve(config.authenticationValue(), variables);
        switch (type) {
            case "BEARER" -> request.header("Authorization", "Bearer " + value);
            case "BASIC" -> request.header("Authorization", "Basic " + value);
            case "COOKIE" -> request.header("Cookie", value);
            case "API_KEY" -> request.header("X-API-Key", value);
            case "NONE" -> {
            }
            default -> throw new IllegalArgumentException("Unsupported authentication type: " + type);
        }
    }

    private Map<String, String> extract(
            FlowDefinition.RequestConfig config,
            List<FlowDefinition.Extractor> extractors,
            Response response,
            String body
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        for (FlowDefinition.Extractor extractor : extractors) {
            String value = switch (extractor.source().toUpperCase(Locale.ROOT)) {
                case "JSON_PATH" -> stringify(JsonPath.read(body, extractor.expression()));
                case "HEADER" -> response.header(extractor.expression());
                case "COOKIE" -> cookie(response.headers(), extractor.expression());
                case "STATUS" -> Integer.toString(response.code());
                default -> throw new IllegalArgumentException("Unsupported extractor source: " + extractor.source());
            };
            if (value == null) {
                throw new IllegalArgumentException("Extractor returned no value: " + extractor.variable());
            }
            values.put(extractor.variable(), value);
        }
        return values;
    }

    private List<RunResult.AssertionResult> evaluateAssertions(
            List<FlowDefinition.Assertion> assertions,
            Response response,
            String body
    ) {
        List<RunResult.AssertionResult> results = new ArrayList<>();
        for (FlowDefinition.Assertion assertion : assertions) {
            String actual = switch (assertion.type().toUpperCase(Locale.ROOT)) {
                case "STATUS" -> Integer.toString(response.code());
                case "JSON_PATH" -> stringify(JsonPath.read(body, assertion.expression()));
                case "HEADER" -> response.header(assertion.expression());
                case "RESPONSE_TIME" -> Long.toString(response.receivedResponseAtMillis() - response.sentRequestAtMillis());
                default -> throw new IllegalArgumentException("Unsupported assertion type: " + assertion.type());
            };
            boolean passed = compare(actual, assertion.expected(), assertion.operator());
            results.add(new RunResult.AssertionResult(
                    assertion.type(), assertion.expression(), assertion.expected(), actual, passed,
                    passed ? "Passed" : "Expected " + assertion.expected() + " but was " + actual
            ));
        }
        if (assertions.isEmpty()) {
            boolean passed = response.isSuccessful();
            results.add(new RunResult.AssertionResult(
                    "STATUS", null, "2xx", Integer.toString(response.code()), passed,
                    passed ? "Passed" : "Expected a successful HTTP status"
            ));
        }
        return results;
    }

    private RunResult.StepResult stepResult(
            FlowDefinition.FlowNode node,
            EndpointDefinition endpoint,
            boolean passed,
            int statusCode,
            long started,
            Request request,
            String responseBody,
            Map<String, String> extracted,
            List<RunResult.AssertionResult> assertions,
            String error
    ) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        request.headers().forEach(pair -> headers.put(pair.getFirst(), pair.getSecond()));
        Map<String, Object> requestDetails = new LinkedHashMap<>();
        requestDetails.put("url", request.url().toString());
        requestDetails.put("headers", headers);
        if (request.body() != null) {
            Buffer buffer = new Buffer();
            request.body().writeTo(buffer);
            requestDetails.put("body", truncate(buffer.readUtf8(), 50_000));
        }
        String requestSummary = objectMapper.writeValueAsString(requestDetails);
        return new RunResult.StepResult(
                node.id(), node.name(), endpoint.id(), endpoint.method(), endpoint.path(),
                passed ? RunResult.Status.PASSED : RunResult.Status.FAILED,
                statusCode, elapsedMs(started), requestSummary,
                redactor.redactText(truncate(responseBody, 100_000)),
                extracted, assertions, error
        );
    }

    private static boolean compare(String actual, String expected, String operator) {
        String normalized = operator == null ? "EQUALS" : operator.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "EQUALS" -> String.valueOf(actual).equals(expected);
            case "NOT_EQUALS" -> !String.valueOf(actual).equals(expected);
            case "CONTAINS" -> actual != null && actual.contains(expected);
            case "EXISTS" -> actual != null;
            case "LESS_THAN" -> Double.parseDouble(actual) < Double.parseDouble(expected);
            default -> throw new IllegalArgumentException("Unsupported assertion operator: " + operator);
        };
    }

    private boolean evaluateCondition(FlowDefinition.Condition condition, Map<String, String> variables) {
        String actual = variables.get(condition.source());
        if (actual == null) {
            actual = variables.get("run." + condition.source());
        }
        if (actual == null) {
            return false;
        }
        String op = condition.operator() == null ? "EQUALS" : condition.operator().toUpperCase(Locale.ROOT);
        String expected = condition.expected() == null ? "" : condition.expected();
        return switch (op) {
            case "EQUALS" -> actual.equals(expected);
            case "NOT_EQUALS" -> !actual.equals(expected);
            case "CONTAINS" -> actual.contains(expected);
            case "EXISTS" -> true;
            case "LESS_THAN" -> {
                try {
                    yield Double.parseDouble(actual) < Double.parseDouble(expected);
                } catch (NumberFormatException e) {
                    yield false;
                }
            }
            default -> false;
        };
    }

    private static String cookie(Headers headers, String name) {
        return headers.values("Set-Cookie").stream()
                .filter(value -> value.startsWith(name + "="))
                .map(value -> value.substring(name.length() + 1).split(";", 2)[0])
                .findFirst().orElse(null);
    }

    private static boolean permitsBody(String method) {
        return !("GET".equals(method) || "HEAD".equals(method));
    }

    private static long elapsedMs(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private static String stringify(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "...";
    }

    private static String trimTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private record StepExecution(
            RunResult.StepResult result,
            Map<String, String> rawExtractedVariables
    ) {
    }
}
