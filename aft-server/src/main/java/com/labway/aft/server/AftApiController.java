package com.labway.aft.server;

import com.labway.aft.domain.EndpointDefinition;
import com.labway.aft.domain.EnvironmentDefinition;
import com.labway.aft.domain.FlowDefinition;
import com.labway.aft.domain.Folder;
import com.labway.aft.domain.Project;
import com.labway.aft.domain.RunResult;
import com.labway.aft.engine.FlowEngine;
import com.labway.aft.engine.FlowValidator;
import com.labway.aft.openapi.OpenApiImporter;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class AftApiController {
    private final AftStore store;
    private final OpenApiImporter importer;
    private final FlowEngine engine;
    private final FlowValidator validator;

    public AftApiController(AftStore store, OpenApiImporter importer, FlowEngine engine, FlowValidator validator) {
        this.store = store;
        this.importer = importer;
        this.engine = engine;
        this.validator = validator;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "UP", "time", Instant.now());
    }

    @GetMapping("/projects")
    public List<Project> projects() {
        return store.projects();
    }

    @PostMapping("/projects")
    public Project createProject(@RequestBody ProjectRequest request) {
        Instant now = Instant.now();
        Project project = new Project(UUID.randomUUID().toString(), request.name(), null, now, now);
        store.saveProject(project);
        EnvironmentDefinition environment = new EnvironmentDefinition(
                UUID.randomUUID().toString(), project.id(), "Local",
                request.baseUrl() == null ? "http://localhost:8080" : request.baseUrl(), Map.of()
        );
        store.saveEnvironment(environment);
        return project;
    }

    @PostMapping("/projects/{projectId}/openapi/url")
    public ImportResponse importUrl(@PathVariable String projectId, @RequestBody ImportUrlRequest request) {
        requireProject(projectId);
        Map<String, String> beforeSnapshot = endpointContentSnapshot(projectId);
        OpenApiImporter.ImportResult result = importer.importLocation(projectId, request.url());
        store.replaceEndpoints(projectId, result.endpoints());
        store.updateProjectSource(projectId, request.url());
        assignChangedEndpointsToVersionFolder(projectId, beforeSnapshot);
        return new ImportResponse(result.endpoints().size(), result.warnings());
    }

    @PostMapping(value = "/projects/{projectId}/openapi/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResponse importFile(
            @PathVariable String projectId,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        requireProject(projectId);
        Map<String, String> beforeSnapshot = endpointContentSnapshot(projectId);
        String contents = new String(file.getBytes(), StandardCharsets.UTF_8);
        OpenApiImporter.ImportResult result = importer.importContents(projectId, contents);
        store.replaceEndpoints(projectId, result.endpoints());
        store.updateProjectSource(projectId, "file:" + file.getOriginalFilename());
        assignChangedEndpointsToVersionFolder(projectId, beforeSnapshot);
        return new ImportResponse(result.endpoints().size(), result.warnings());
    }

    private Map<String, String> endpointContentSnapshot(String projectId) {
        return store.activeEndpoints(projectId).stream()
                .collect(java.util.stream.Collectors.toMap(
                        EndpointDefinition::id,
                        ep -> ep.method() + "|" + ep.path() + "|" + ep.parameters().size()
                ));
    }

    private void assignChangedEndpointsToVersionFolder(String projectId, Map<String, String> beforeSnapshot) {
        List<EndpointDefinition> afterEndpoints = store.activeEndpoints(projectId);
        List<String> changedIds = new java.util.ArrayList<>();
        for (EndpointDefinition ep : afterEndpoints) {
            String beforeContent = beforeSnapshot.get(ep.id());
            String afterContent = ep.method() + "|" + ep.path() + "|" + ep.parameters().size();
            if (beforeContent == null || !beforeContent.equals(afterContent)) {
                changedIds.add(ep.id());
            }
        }
        if (changedIds.isEmpty()) {
            return;
        }
        List<Folder> existingFolders = store.folders(projectId);
        int maxVersion = existingFolders.stream()
                .filter(f -> f.name() != null && f.name().matches("V\\d+"))
                .mapToInt(f -> Integer.parseInt(f.name().substring(1)))
                .max()
                .orElse(0);
        String folderName = "V" + (maxVersion + 1);
        Folder folder = new Folder(UUID.randomUUID().toString(), projectId, null, folderName, maxVersion + 1);
        store.saveFolder(folder);
        store.moveEndpoints(folder.id(), changedIds);
    }

    @GetMapping("/projects/{projectId}/endpoints")
    public List<EndpointDefinition> endpoints(@PathVariable String projectId) {
        return store.endpoints(projectId);
    }

    @GetMapping("/projects/{projectId}/folders")
    public List<Folder> folders(@PathVariable String projectId) {
        return store.folders(projectId);
    }

    @PostMapping("/folders")
    public Folder saveFolder(@RequestBody Folder folder) {
        Folder normalized = new Folder(
                folder.id() == null || folder.id().isBlank() ? UUID.randomUUID().toString() : folder.id(),
                folder.projectId(),
                folder.parentId() != null && folder.parentId().isBlank() ? null : folder.parentId(),
                folder.name(), folder.sortOrder()
        );
        return store.saveFolder(normalized);
    }

    @DeleteMapping("/folders/{id}")
    public void deleteFolder(@PathVariable String id) {
        store.deleteFolder(id);
    }

    @PostMapping("/folders/move")
    public void moveEndpoints(@RequestBody MoveRequest request) {
        String folderId = (request.folderId() == null || request.folderId().isBlank()) ? null : request.folderId();
        store.moveEndpoints(folderId, request.endpointIds());
    }

    @PostMapping("/folders/move-folder")
    public void moveFolder(@RequestBody MoveFolderRequest request) {
        if (request.folderId().equals(request.newParentId())) {
            throw new IllegalArgumentException("Cannot move a folder under itself");
        }
        store.moveFolder(request.folderId(), request.newParentId(), request.sortOrder());
    }

    public record MoveRequest(String folderId, List<String> endpointIds) {
    }

    public record MoveFolderRequest(String folderId, String newParentId, int sortOrder) {
    }

    @GetMapping("/projects/{projectId}/environments")
    public List<EnvironmentDefinition> environments(@PathVariable String projectId) {
        return store.environments(projectId);
    }

    @PutMapping("/environments/{id}")
    public EnvironmentDefinition saveEnvironment(
            @PathVariable String id,
            @RequestBody EnvironmentDefinition environment
    ) {
        if (!id.equals(environment.id())) {
            throw new IllegalArgumentException("Environment id does not match request path");
        }
        return store.saveEnvironment(environment);
    }

    @GetMapping("/projects/{projectId}/flows")
    public List<FlowDefinition> flows(@PathVariable String projectId) {
        return store.flows(projectId);
    }

    @PostMapping("/flows")
    public FlowDefinition saveFlow(@RequestBody FlowDefinition flow) {
        List<String> errors = validator.validateForSave(flow, store.endpointMap(flow.projectId()));
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", errors));
        }
        Instant now = Instant.now();
        FlowDefinition normalized = new FlowDefinition(
                flow.id() == null || flow.id().isBlank() ? UUID.randomUUID().toString() : flow.id(),
                flow.projectId(),
                flow.name(),
                flow.description(),
                flow.nodes(),
                flow.edges(),
                flow.config(),
                flow.createdAt() == null ? now : flow.createdAt(),
                now
        );
        return store.saveFlow(normalized);
    }

    @PostMapping("/flows/{flowId}/run")
    public RunResult run(@PathVariable String flowId, @RequestParam String environmentId) {
        FlowDefinition flow = store.flow(flowId)
                .orElseThrow(() -> new ResourceNotFoundException("Flow not found: " + flowId));
        EnvironmentDefinition environment = store.environment(environmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Environment not found: " + environmentId));
        return store.saveRun(engine.execute(flow, environment, store.endpointMap(flow.projectId())));
    }

    @GetMapping("/flows/{flowId}/runs")
    public List<RunResult> runs(@PathVariable String flowId) {
        return store.runs(flowId);
    }

    @GetMapping("/runs/{runId}")
    public RunResult run(@PathVariable String runId) {
        return store.run(runId)
                .orElseThrow(() -> new ResourceNotFoundException("Run not found: " + runId));
    }

    private void requireProject(String projectId) {
        store.project(projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }

    @GetMapping("/projects/{projectId}/export")
    public Map<String, Object> exportProject(@PathVariable String projectId) {
        requireProject(projectId);
        Project project = store.project(projectId).orElseThrow();
        return Map.of(
                "project", project,
                "endpoints", store.endpoints(projectId),
                "folders", store.folders(projectId),
                "environments", store.environments(projectId),
                "flows", store.flows(projectId)
        );
    }

    @PostMapping("/projects/import")
    public Project importProject(@RequestBody Map<String, Object> data) {
        Instant now = Instant.now();
        @SuppressWarnings("unchecked")
        Map<String, Object> projectData = (Map<String, Object>) data.get("project");
        String name = (String) projectData.getOrDefault("name", "Imported Project");
        String newProjectId = UUID.randomUUID().toString();
        Project project = new Project(newProjectId, name, null, now, now);
        store.saveProject(project);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> foldersData = (List<Map<String, Object>>) data.getOrDefault("folders", List.of());
        Map<String, String> folderIdMap = new java.util.HashMap<>();
        for (Map<String, Object> fd : foldersData) {
            String oldId = (String) fd.get("id");
            String newId = UUID.randomUUID().toString();
            folderIdMap.put(oldId, newId);
        }
        for (Map<String, Object> fd : foldersData) {
            String oldId = (String) fd.get("id");
            String newId = folderIdMap.get(oldId);
            String oldParentId = fd.get("parentId") != null ? (String) fd.get("parentId") : null;
            String newParentId = oldParentId != null ? folderIdMap.getOrDefault(oldParentId, null) : null;
            Folder folder = new Folder(newId, newProjectId, newParentId, (String) fd.get("name"),
                    fd.get("sortOrder") != null ? ((Number) fd.get("sortOrder")).intValue() : 0);
            store.saveFolder(folder);
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpointsData = (List<Map<String, Object>>) data.getOrDefault("endpoints", List.of());
        List<EndpointDefinition> endpoints = new java.util.ArrayList<>();
        for (Map<String, Object> ed : endpointsData) {
            String epId = UUID.randomUUID().toString();
            String folderId = ed.get("folderId") != null ? folderIdMap.getOrDefault((String) ed.get("folderId"), null) : null;
            @SuppressWarnings("unchecked")
            List<String> tags = ed.get("tags") != null ? (List<String>) ed.get("tags") : List.of();
            EndpointDefinition ep = new EndpointDefinition(
                    epId, newProjectId, (String) ed.get("operationId"),
                    (String) ed.get("method"), (String) ed.get("path"),
                    (String) ed.get("summary"), tags, List.of(),
                    (String) ed.get("requestBodySchema"), (String) ed.get("responseSchema"),
                    true, folderId
            );
            endpoints.add(ep);
        }
        store.replaceEndpoints(newProjectId, endpoints);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> envsData = (List<Map<String, Object>>) data.getOrDefault("environments", List.of());
        for (Map<String, Object> envd : envsData) {
            EnvironmentDefinition env = new EnvironmentDefinition(
                    UUID.randomUUID().toString(), newProjectId,
                    (String) envd.get("name"),
                    (String) envd.get("baseUrl"),
                    envd.get("variables") != null ? (Map<String, String>) envd.get("variables") : Map.of()
            );
            store.saveEnvironment(env);
        }

        return project;
    }

    public record ProjectRequest(String name, String baseUrl) {
    }

    public record ImportUrlRequest(String url) {
    }

    public record ImportResponse(int imported, List<String> warnings) {
    }
}
