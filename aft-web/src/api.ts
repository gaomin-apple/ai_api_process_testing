import type {
  EndpointDefinition,
  EnvironmentDefinition,
  FlowDefinition,
  Folder,
  JavaFlowAnalyzeRequest,
  JavaFlowAnalyzeResponse,
  LlmDefaults,
  Project,
  RunResult,
} from './types'

async function request<T>(url: string, init?: RequestInit): Promise<T> {
  const response = await fetch(url, init)
  if (!response.ok) {
    const problem = await response.json().catch(() => null)
    throw new Error(problem?.detail ?? `请求失败：${response.status}`)
  }
  const text = await response.text()
  if (!text) return undefined as T
  return JSON.parse(text) as T
}

const json = (body: unknown): RequestInit => ({
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})

export const api = {
  projects: () => request<Project[]>('/api/projects'),
  createProject: (name: string, baseUrl: string) =>
    request<Project>('/api/projects', json({ name, baseUrl })),
  endpoints: (projectId: string) =>
    request<EndpointDefinition[]>(`/api/projects/${projectId}/endpoints`),
  environments: (projectId: string) =>
    request<EnvironmentDefinition[]>(`/api/projects/${projectId}/environments`),
  saveEnvironment: (environment: EnvironmentDefinition) =>
    request<EnvironmentDefinition>(`/api/environments/${environment.id}`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(environment),
    }),
  folders: (projectId: string) =>
    request<Folder[]>(`/api/projects/${projectId}/folders`),
  saveFolder: (folder: Folder) =>
    request<Folder>('/api/folders', json(folder)),
  deleteFolder: (id: string) =>
    request<void>(`/api/folders/${id}`, { method: 'DELETE' }),
  moveEndpoints: (folderId: string, endpointIds: string[]) =>
    request<void>('/api/folders/move', json({ folderId, endpointIds })),
  moveFolder: (folderId: string, newParentId: string | null, sortOrder: number) =>
    request<void>('/api/folders/move-folder', json({ folderId, newParentId, sortOrder })),
  exportProject: (projectId: string) =>
    request<Record<string, unknown>>(`/api/projects/${projectId}/export`),
  importProject: (data: Record<string, unknown>) =>
    request<Project>('/api/projects/import', json(data)),
  flows: (projectId: string) =>
    request<FlowDefinition[]>(`/api/projects/${projectId}/flows`),
  saveFlow: (flow: FlowDefinition) => request<FlowDefinition>('/api/flows', json(flow)),
  llmConfig: () => request<LlmDefaults>('/api/llm/config'),
  analyzeJavaFlow: (projectId: string, body: JavaFlowAnalyzeRequest) =>
    request<JavaFlowAnalyzeResponse>(`/api/projects/${projectId}/java/analyze-flow`, json(body)),
  importUrl: (projectId: string, url: string) =>
    request<{ imported: number; warnings: string[] }>(
      `/api/projects/${projectId}/openapi/url`,
      json({ url }),
    ),
  importFile: async (projectId: string, file: File) => {
    const data = new FormData()
    data.append('file', file)
    return request<{ imported: number; warnings: string[] }>(
      `/api/projects/${projectId}/openapi/file`,
      { method: 'POST', body: data },
    )
  },
  runFlow: (flowId: string, environmentId: string) =>
    request<RunResult>(
      `/api/flows/${flowId}/run?environmentId=${encodeURIComponent(environmentId)}`,
      { method: 'POST' },
    ),
}
