package com.labway.aft.domain;

public record Folder(
        String id,
        String projectId,
        String parentId,
        String name,
        int sortOrder
) {
}
