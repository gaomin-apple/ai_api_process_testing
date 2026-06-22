package com.labway.aft.domain;

import java.util.List;

public record EndpointDefinition(
        String id,
        String projectId,
        String operationId,
        String method,
        String path,
        String summary,
        List<String> tags,
        List<ParameterDefinition> parameters,
        String requestBodySchema,
        String responseSchema,
        boolean active,
        String folderId
) {
    public EndpointDefinition {
        tags = tags == null ? List.of() : List.copyOf(tags);
        parameters = parameters == null ? List.of() : List.copyOf(parameters);
    }

    public EndpointDefinition(
            String id, String projectId, String operationId, String method,
            String path, String summary, List<String> tags, List<ParameterDefinition> parameters,
            String requestBodySchema, String responseSchema, boolean active
    ) {
        this(id, projectId, operationId, method, path, summary, tags, parameters,
                requestBodySchema, responseSchema, active, null);
    }

    public record ParameterDefinition(
            String name,
            String location,
            boolean required,
            String schemaJson
    ) {
    }
}
