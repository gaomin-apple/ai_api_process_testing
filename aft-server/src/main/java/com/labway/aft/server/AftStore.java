package com.labway.aft.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.labway.aft.domain.EndpointDefinition;
import com.labway.aft.domain.EnvironmentDefinition;
import com.labway.aft.domain.FlowDefinition;
import com.labway.aft.domain.Folder;
import com.labway.aft.domain.Project;
import com.labway.aft.domain.RunResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class AftStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AftStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public List<Project> projects() {
        return jdbc.query("select * from projects order by updated_at desc", this::mapProject);
    }

    public Optional<Project> project(String id) {
        return jdbc.query("select * from projects where id = ?", this::mapProject, id).stream().findFirst();
    }

    public Project saveProject(Project project) {
        jdbc.update("""
                insert into projects(id, name, openapi_source, created_at, updated_at)
                values (?, ?, ?, ?, ?)
                on conflict(id) do update set
                  name = excluded.name,
                  openapi_source = excluded.openapi_source,
                  updated_at = excluded.updated_at
                """, project.id(), project.name(), project.openApiSource(),
                project.createdAt().toString(), project.updatedAt().toString());
        return project;
    }

    public void updateProjectSource(String id, String source) {
        jdbc.update("update projects set openapi_source = ?, updated_at = ? where id = ?",
                source, Instant.now().toString(), id);
    }

    public List<EnvironmentDefinition> environments(String projectId) {
        return jdbc.query("select payload from environments where project_id = ? order by name",
                (rs, row) -> read(rs.getString("payload"), EnvironmentDefinition.class), projectId);
    }

    public Optional<EnvironmentDefinition> environment(String id) {
        return jsonEntity("select payload from environments where id = ?", EnvironmentDefinition.class, id);
    }

    public EnvironmentDefinition saveEnvironment(EnvironmentDefinition environment) {
        jdbc.update("""
                insert into environments(id, project_id, name, payload)
                values (?, ?, ?, ?)
                on conflict(id) do update set
                  project_id = excluded.project_id,
                  name = excluded.name,
                  payload = excluded.payload
                """, environment.id(), environment.projectId(), environment.name(), write(environment));
        return environment;
    }

    public List<EndpointDefinition> endpoints(String projectId) {
        return jdbc.query("""
                        select payload, folder_id from endpoints
                        where project_id = ?
                        order by active desc, path, method
                        """,
                (rs, row) -> withFolderId(
                        read(rs.getString("payload"), EndpointDefinition.class),
                        rs.getString("folder_id")
                ), projectId);
    }

    public List<EndpointDefinition> activeEndpoints(String projectId) {
        return jdbc.query("""
                        select payload, folder_id from endpoints
                        where project_id = ? and active = 1
                        order by path, method
                        """,
                (rs, row) -> withFolderId(
                        read(rs.getString("payload"), EndpointDefinition.class),
                        rs.getString("folder_id")
                ), projectId);
    }

    @Transactional
    public void replaceEndpoints(String projectId, List<EndpointDefinition> endpoints) {
        List<EndpointDefinition> existing = endpoints(projectId);
        Map<String, String> folderMap = new java.util.HashMap<>();
        for (EndpointDefinition ep : existing) {
            if (ep.folderId() != null && !ep.folderId().isBlank()) {
                folderMap.put(ep.id(), ep.folderId());
            }
        }
        jdbc.update("update endpoints set active = 0 where project_id = ?", projectId);
        for (EndpointDefinition endpoint : existing) {
            EndpointDefinition inactive = new EndpointDefinition(
                    endpoint.id(), endpoint.projectId(), endpoint.operationId(), endpoint.method(),
                    endpoint.path(), endpoint.summary(), endpoint.tags(), endpoint.parameters(),
                    endpoint.requestBodySchema(), endpoint.responseSchema(), false
            );
            jdbc.update("update endpoints set payload = ? where id = ?", write(inactive), endpoint.id());
        }
        for (EndpointDefinition endpoint : endpoints) {
            String folderId = endpoint.folderId() != null ? endpoint.folderId() : folderMap.get(endpoint.id());
            jdbc.update("""
                    insert into endpoints(id, project_id, method, path, active, payload, folder_id)
                    values (?, ?, ?, ?, 1, ?, ?)
                    on conflict(id) do update set
                      method = excluded.method,
                      path = excluded.path,
                      active = 1,
                      payload = excluded.payload,
                      folder_id = excluded.folder_id
                    """, endpoint.id(), projectId, endpoint.method(), endpoint.path(),
                    write(endpoint), folderId);
        }
    }

    public Optional<FlowDefinition> flow(String id) {
        return jsonEntity("select payload from flows where id = ?", FlowDefinition.class, id);
    }

    public List<FlowDefinition> flows(String projectId) {
        return jdbc.query("select payload from flows where project_id = ? order by updated_at desc",
                (rs, row) -> read(rs.getString("payload"), FlowDefinition.class), projectId);
    }

    public FlowDefinition saveFlow(FlowDefinition flow) {
        jdbc.update("""
                insert into flows(id, project_id, name, updated_at, payload)
                values (?, ?, ?, ?, ?)
                on conflict(id) do update set
                  project_id = excluded.project_id,
                  name = excluded.name,
                  updated_at = excluded.updated_at,
                  payload = excluded.payload
                """, flow.id(), flow.projectId(), flow.name(), flow.updatedAt().toString(), write(flow));
        return flow;
    }

    public RunResult saveRun(RunResult run) {
        jdbc.update("""
                insert into runs(id, flow_id, environment_id, status, started_at, payload)
                values (?, ?, ?, ?, ?, ?)
                """, run.id(), run.flowId(), run.environmentId(), run.status().name(),
                run.startedAt().toString(), write(run));
        return run;
    }

    public Optional<RunResult> run(String id) {
        return jsonEntity("select payload from runs where id = ?", RunResult.class, id);
    }

    public List<RunResult> runs(String flowId) {
        return jdbc.query("""
                        select payload from runs
                        where flow_id = ?
                        order by started_at desc
                        limit 50
                        """,
                (rs, row) -> read(rs.getString("payload"), RunResult.class), flowId);
    }

    public Map<String, EndpointDefinition> endpointMap(String projectId) {
        return activeEndpoints(projectId).stream().collect(java.util.stream.Collectors.toMap(
                EndpointDefinition::id,
                endpoint -> endpoint
        ));
    }

    public List<Folder> folders(String projectId) {
        return jdbc.query("select * from folders where project_id = ? order by sort_order, name",
                (rs, row) -> new Folder(
                        rs.getString("id"),
                        rs.getString("project_id"),
                        rs.getString("parent_id"),
                        rs.getString("name"),
                        rs.getInt("sort_order")
                ), projectId);
    }

    public Folder saveFolder(Folder folder) {
        jdbc.update("""
                insert into folders(id, project_id, parent_id, name, sort_order)
                values (?, ?, ?, ?, ?)
                on conflict(id) do update set
                  parent_id = excluded.parent_id,
                  name = excluded.name,
                  sort_order = excluded.sort_order
                """, folder.id(), folder.projectId(), folder.parentId(), folder.name(), folder.sortOrder());
        return folder;
    }

    public void deleteFolder(String id) {
        jdbc.update("update endpoints set folder_id = null where folder_id = ?", id);
        jdbc.update("update folders set parent_id = null where parent_id = ?", id);
        jdbc.update("delete from folders where id = ?", id);
    }

    public void moveEndpoints(String folderId, List<String> endpointIds) {
        for (String endpointId : endpointIds) {
            jdbc.update("update endpoints set folder_id = ? where id = ?", folderId, endpointId);
        }
    }

    public void moveFolder(String folderId, String newParentId, int sortOrder) {
        jdbc.update("update folders set parent_id = ?, sort_order = ? where id = ?",
                newParentId != null && newParentId.isBlank() ? null : newParentId, sortOrder, folderId);
    }

    private Project mapProject(ResultSet rs, int row) throws SQLException {
        return new Project(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("openapi_source"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at"))
        );
    }

    private <T> Optional<T> jsonEntity(String sql, Class<T> type, Object... args) {
        return jdbc.query(sql, (rs, row) -> read(rs.getString("payload"), type), args).stream().findFirst();
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize stored value", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize stored value", exception);
        }
    }

    private EndpointDefinition withFolderId(EndpointDefinition endpoint, String folderId) {
        if (folderId == null || folderId.isBlank()) return endpoint;
        return new EndpointDefinition(
                endpoint.id(), endpoint.projectId(), endpoint.operationId(), endpoint.method(),
                endpoint.path(), endpoint.summary(), endpoint.tags(), endpoint.parameters(),
                endpoint.requestBodySchema(), endpoint.responseSchema(), endpoint.active(), folderId
        );
    }
}
