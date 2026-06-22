package com.labway.aft.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record RunResult(
        String id,
        String flowId,
        String flowName,
        String environmentId,
        Status status,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        List<StepResult> steps,
        Map<String, String> variables,
        String error
) {
    public RunResult {
        steps = steps == null ? List.of() : List.copyOf(steps);
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public enum Status {
        RUNNING, PASSED, FAILED
    }

    public record StepResult(
            String nodeId,
            String nodeName,
            String endpointId,
            String method,
            String path,
            Status status,
            int statusCode,
            long durationMs,
            String requestSummary,
            String responseSummary,
            Map<String, String> extractedVariables,
            List<AssertionResult> assertions,
            String error
    ) {
        public StepResult {
            extractedVariables = extractedVariables == null ? Map.of() : Map.copyOf(extractedVariables);
            assertions = assertions == null ? List.of() : List.copyOf(assertions);
        }
    }

    public record AssertionResult(
            String type,
            String expression,
            String expected,
            String actual,
            boolean passed,
            String message
    ) {
    }
}
