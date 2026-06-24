export type Project = {
  id: string
  name: string
  openApiSource?: string | null
  createdAt: string
  updatedAt: string
}

export type EnvironmentDefinition = {
  id: string
  projectId: string
  name: string
  baseUrl: string
  variables: Record<string, string>
}

export type EndpointParameter = {
  name: string
  location: 'path' | 'query' | 'header' | 'cookie' | string
  required: boolean
  schemaJson?: string | null
}

export type EndpointDefinition = {
  id: string
  projectId: string
  operationId?: string | null
  method: string
  path: string
  summary?: string | null
  tags: string[]
  parameters: EndpointParameter[]
  requestBodySchema?: string | null
  responseSchema?: string | null
  active: boolean
  folderId?: string | null
}

export type Folder = {
  id: string
  projectId: string
  parentId?: string | null
  name: string
  sortOrder: number
}

export type RequestConfig = {
  path: Record<string, string>
  query: Record<string, string>
  headers: Record<string, string>
  form: Record<string, string>
  body: string
  bodyType: 'NONE' | 'JSON' | 'FORM_URLENCODED' | 'TEXT'
  authenticationType: 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY' | 'COOKIE'
  authenticationValue: string
  timeoutMs: number
}

export type Extractor = {
  variable: string
  source: 'JSON_PATH' | 'HEADER' | 'COOKIE' | 'STATUS'
  expression: string
}

export type Assertion = {
  type: 'STATUS' | 'JSON_PATH' | 'HEADER' | 'RESPONSE_TIME'
  expression: string
  operator: 'EQUALS' | 'NOT_EQUALS' | 'CONTAINS' | 'EXISTS' | 'LESS_THAN'
  expected: string
}

export type FlowNodeDefinition = {
  id: string
  endpointId?: string | null
  name: string
  x: number
  y: number
  request: RequestConfig
  extractors: Extractor[]
  assertions: Assertion[]
  nodeType?: 'API' | 'GATEWAY' | 'PARALLEL'
  gateway?: GatewayConfig | null
}

export type GatewayConfig = {
  sourceType: 'VARIABLE' | 'FIXED'
  source: string
  fixedValue: string
}

export type Condition = {
  source: string
  operator: 'EQUALS' | 'NOT_EQUALS' | 'CONTAINS' | 'EXISTS' | 'LESS_THAN'
  expected: string
}

export type FlowConfig = {
  onFailure: 'FAIL_ALL' | 'CONTINUE'
}

export type FlowDefinition = {
  id?: string
  projectId: string
  name: string
  description: string
  nodes: FlowNodeDefinition[]
  edges: Array<{ id: string; source: string; target: string; condition?: Condition | null }>
  config?: FlowConfig
  createdAt?: string
  updatedAt?: string
}

export type AssertionResult = {
  type: string
  expression?: string | null
  expected: string
  actual?: string | null
  passed: boolean
  message: string
}

export type StepResult = {
  nodeId: string
  nodeName: string
  endpointId: string
  method: string
  path: string
  status: 'RUNNING' | 'PASSED' | 'FAILED'
  statusCode: number
  durationMs: number
  requestSummary?: string | null
  responseSummary?: string | null
  extractedVariables: Record<string, string>
  assertions: AssertionResult[]
  error?: string | null
}

export type RunResult = {
  id: string
  flowId: string
  flowName: string
  environmentId: string
  status: 'RUNNING' | 'PASSED' | 'FAILED'
  startedAt: string
  finishedAt: string
  durationMs: number
  steps: StepResult[]
  variables: Record<string, string>
  error?: string | null
}

export type LlmDefaults = {
  apiBaseUrl: string
  model: string
  apiKeyConfigured: boolean
}

export type JavaFlowAnalyzeRequest = {
  sourcePath: string
  flowName: string
  apiBaseUrl?: string
  apiKey?: string
  model?: string
}

export type JavaFlowAnalyzeResponse = {
  flow: FlowDefinition
  scan: {
    root: string
    javaFileCandidates: number
    includedFiles: number
    includedCharacters: number
  }
  defaults: LlmDefaults
}

export type CanvasNodeData = {
  endpoint?: EndpointDefinition
  definition: FlowNodeDefinition
  runStep?: StepResult
  selected?: boolean
}
