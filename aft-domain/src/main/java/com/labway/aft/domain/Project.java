package com.labway.aft.domain;

import java.time.Instant;

public record Project(
        String id,
        String name,
        String openApiSource,
        Instant createdAt,
        Instant updatedAt
) {
}
