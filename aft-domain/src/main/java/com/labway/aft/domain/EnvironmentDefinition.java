package com.labway.aft.domain;

import java.util.Map;

public record EnvironmentDefinition(
        String id,
        String projectId,
        String name,
        String baseUrl,
        Map<String, String> variables
) {
    public EnvironmentDefinition {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }
}
