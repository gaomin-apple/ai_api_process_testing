import {
  addEdge,
  applyEdgeChanges,
  applyNodeChanges,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
} from '@xyflow/react'
import { BrainCircuit, ChevronDown, Database, Download, Play, Plus, Save, Settings2, Upload, UploadCloud } from 'lucide-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api } from './api'
import { EnvironmentDialog, ImportDialog, JavaAnalyzeDialog, ProjectDialog } from './components/Dialogs'
import { EndpointCatalog } from './components/EndpointCatalog'
import { FlowCanvas } from './components/FlowCanvas'
import { NodeInspector } from './components/NodeInspector'
import type { GatewayBranch } from './components/NodeInspector'
import { RunReport } from './components/RunReport'
import type {
  CanvasNodeData,
  Condition,
  EndpointDefinition,
  EnvironmentDefinition,
  FlowConfig,
  FlowDefinition,
  FlowNodeDefinition,
  Folder,
  JavaFlowAnalyzeRequest,
  LlmDefaults,
  Project,
  RunResult,
} from './types'

type FlowNode = Node<CanvasNodeData, 'apiNode' | 'gatewayNode' | 'parallelNode'>

const AUTO_CONNECT_DISTANCE = 320

const nodeCenter = (node: FlowNode) => ({
  x: node.position.x + (node.data.definition.nodeType === 'GATEWAY' || node.data.definition.nodeType === 'PARALLEL' ? 110 : 115),
  y: node.position.y + (node.data.definition.nodeType === 'GATEWAY' || node.data.definition.nodeType === 'PARALLEL' ? 42 : 45),
})

const nearestAutoConnectNode = (
  nodes: FlowNode[],
  newNodePosition: { x: number; y: number },
) => {
  const newNodeCenter = { x: newNodePosition.x + 115, y: newNodePosition.y + 45 }
  return nodes
    .map((node) => {
      const center = nodeCenter(node)
      return {
        node,
        distance: Math.hypot(center.x - newNodeCenter.x, center.y - newNodeCenter.y),
      }
    })
    .filter(({ distance }) => distance <= AUTO_CONNECT_DISTANCE)
    .sort((left, right) => {
      const distanceDifference = left.distance - right.distance
      if (Math.abs(distanceDifference) > 0.001) return distanceDifference
      const leftIsFork = left.node.data.definition.nodeType === 'GATEWAY' || left.node.data.definition.nodeType === 'PARALLEL'
      const rightIsFork = right.node.data.definition.nodeType === 'GATEWAY' || right.node.data.definition.nodeType === 'PARALLEL'
      return Number(rightIsFork) - Number(leftIsFork)
    })[0]?.node
}

const blankRequest = (): FlowNodeDefinition['request'] => ({
  path: {},
  query: {},
  headers: {},
  form: {},
  body: '',
  bodyType: 'NONE',
  authenticationType: 'NONE',
  authenticationValue: '',
  timeoutMs: 10000,
})

const flowFromCanvas = (
  projectId: string,
  flowName: string,
  flowId: string | undefined,
  nodes: FlowNode[],
  edges: Edge[],
  config: FlowConfig,
): FlowDefinition => ({
  id: flowId,
  projectId,
  name: flowName,
  description: '',
  nodes: nodes.map((node) => {
    const definition = node.data.definition
    const gateway = definition.gateway && typeof definition.gateway === 'object'
      ? definition.gateway
      : null
    return {
      ...definition,
      gateway: definition.nodeType === 'GATEWAY' ? gateway : null,
      x: node.position.x,
      y: node.position.y,
    }
  }),
  edges: edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    condition: (edge as Edge & { condition?: Condition | null }).condition ?? null,
  })),
  config,
})

const schemaExample = (schemaJson?: string | null): string => {
  if (!schemaJson) return ''
  try {
    const schema = JSON.parse(schemaJson) as {
      type?: string
      example?: unknown
      default?: unknown
      enum?: unknown[]
      properties?: Record<string, unknown>
      items?: unknown
    }
    const sample = (value: unknown, depth = 0): unknown => {
      if (!value || typeof value !== 'object' || depth > 5) return null
      const node = value as {
        type?: string
        example?: unknown
        default?: unknown
        enum?: unknown[]
        properties?: Record<string, unknown>
        items?: unknown
        format?: string
      }
      if (node.example !== undefined) return node.example
      if (node.default !== undefined) return node.default
      if (node.enum?.length) return node.enum[0]
      if (node.type === 'object' || node.properties) {
        return Object.fromEntries(
          Object.entries(node.properties ?? {}).map(([key, child]) => [key, sample(child, depth + 1)]),
        )
      }
      if (node.type === 'array') return [sample(node.items, depth + 1)]
      if (node.type === 'integer' || node.type === 'number') return 0
      if (node.type === 'boolean') return false
      return node.format === 'password' ? '' : ''
    }
    return JSON.stringify(sample(schema), null, 2)
  } catch {
    return ''
  }
}

function App() {
  const [projects, setProjects] = useState<Project[]>([])
  const [projectId, setProjectId] = useState('')
  const [endpoints, setEndpoints] = useState<EndpointDefinition[]>([])
  const [environments, setEnvironments] = useState<EnvironmentDefinition[]>([])
  const [folders, setFolders] = useState<Folder[]>([])
  const [environmentId, setEnvironmentId] = useState('')
  const [flowId, setFlowId] = useState<string>()
  const [flowName, setFlowName] = useState('新建业务流程')
  const [flowConfig, setFlowConfig] = useState<FlowConfig>({ onFailure: 'FAIL_ALL' })
  const [nodes, setNodes] = useState<FlowNode[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  const [selectedNodeId, setSelectedNodeId] = useState<string>()
  const [runResult, setRunResult] = useState<RunResult>()
  const [running, setRunning] = useState(false)
  const [importOpen, setImportOpen] = useState(false)
  const [javaAnalyzeOpen, setJavaAnalyzeOpen] = useState(false)
  const [environmentOpen, setEnvironmentOpen] = useState(false)
  const [projectDialogOpen, setProjectDialogOpen] = useState(false)
  const [llmDefaults, setLlmDefaults] = useState<LlmDefaults>()
  const [notice, setNotice] = useState('')
  const [initializing, setInitializing] = useState(true)

  const currentProject = projects.find((project) => project.id === projectId)
  const currentEnvironment = environments.find((environment) => environment.id === environmentId)
  const selectedNode = nodes.find((node) => node.id === selectedNodeId)
  const gatewayBranches = useMemo<GatewayBranch[]>(() => {
    if (!selectedNodeId) return []
    const nodeNames = new Map(nodes.map((node) => [node.id, node.data.definition.name]))
    return edges
      .filter((edge) => edge.source === selectedNodeId)
      .map((edge) => ({
        edgeId: edge.id,
        targetName: nodeNames.get(edge.target) ?? edge.target,
        condition: (edge as Edge & { condition?: Condition | null }).condition ?? null,
      }))
  }, [edges, nodes, selectedNodeId])
  const endpointMap = useMemo(() => new Map(endpoints.map((endpoint) => [endpoint.id, endpoint])), [endpoints])
  const availableRunVariables = useMemo(() => {
    if (!selectedNodeId) return []
    const byId = new Map(nodes.map((node) => [node.id, node]))
    const previousByTarget = new Map(edges.map((edge) => [edge.target, edge.source]))
    const previousNodes: FlowNode[] = []
    const visited = new Set<string>()
    let currentId = previousByTarget.get(selectedNodeId)
    while (currentId && !visited.has(currentId)) {
      visited.add(currentId)
      const node = byId.get(currentId)
      if (!node) break
      previousNodes.unshift(node)
      currentId = previousByTarget.get(currentId)
    }
    return [...new Set(previousNodes.flatMap((node) =>
      node.data.definition.extractors
        .map((extractor) => extractor.variable.trim())
        .filter(Boolean),
    ))]
  }, [edges, nodes, selectedNodeId])

  const loadProject = useCallback(async (id: string) => {
    const [loadedEndpoints, loadedEnvironments, loadedFlows, loadedFolders] = await Promise.all([
      api.endpoints(id),
      api.environments(id),
      api.flows(id),
      api.folders(id),
    ])
    setEndpoints(loadedEndpoints)
    setEnvironments(loadedEnvironments)
    setFolders(loadedFolders)
    setEnvironmentId((current) =>
      loadedEnvironments.some((environment) => environment.id === current)
        ? current
        : loadedEnvironments[0]?.id ?? '',
    )
    const latest = loadedFlows[0]
    if (latest) {
      setFlowId(latest.id)
      setFlowName(latest.name)
      setFlowConfig(latest.config ?? { onFailure: 'FAIL_ALL' })
      const loadedNodes = latest.nodes.flatMap<FlowNode>((storedDefinition) => {
        if (storedDefinition.nodeType === 'GATEWAY') {
          const storedGateway = storedDefinition.gateway && typeof storedDefinition.gateway === 'object'
            ? storedDefinition.gateway
            : null
          return [{
            id: storedDefinition.id,
            type: 'gatewayNode',
            position: { x: storedDefinition.x, y: storedDefinition.y },
            data: {
              definition: {
                ...storedDefinition,
                request: { ...blankRequest(), ...storedDefinition.request },
                nodeType: 'GATEWAY',
                gateway: storedGateway ?? {
                  sourceType: 'VARIABLE',
                  source: '',
                  fixedValue: '',
                },
              },
            },
          }]
        }
        if (storedDefinition.nodeType === 'PARALLEL') {
          return [{
            id: storedDefinition.id,
            type: 'parallelNode',
            position: { x: storedDefinition.x, y: storedDefinition.y },
            data: {
              definition: {
                ...storedDefinition,
                request: { ...blankRequest(), ...storedDefinition.request },
                nodeType: 'PARALLEL',
                gateway: null,
              },
            },
          }]
        }
        const endpoint = loadedEndpoints.find((item) => item.id === storedDefinition.endpointId)
        if (!endpoint) return []
        const bodyType = storedDefinition.request.bodyType
          ?? (Object.keys(storedDefinition.request.form ?? {}).length
            ? 'FORM_URLENCODED'
            : storedDefinition.request.body || endpoint.requestBodySchema ? 'JSON' : 'NONE')
        const definition = {
          ...storedDefinition,
          request: {
            ...blankRequest(),
            ...storedDefinition.request,
            bodyType,
            body: storedDefinition.request.body || schemaExample(endpoint.requestBodySchema),
          },
          nodeType: 'API' as const,
          gateway: null,
        }
        return [{
          id: definition.id,
          type: 'apiNode',
          position: { x: definition.x, y: definition.y },
          data: { definition, endpoint },
        }]
      })
      setNodes(loadedNodes)
      setEdges(latest.edges.map((e) => ({ ...e, condition: e.condition ?? null })))
    } else {
      setFlowId(undefined)
      setFlowName('新建业务流程')
      setFlowConfig({ onFailure: 'FAIL_ALL' })
      setNodes([])
      setEdges([])
    }
    setRunResult(undefined)
    setSelectedNodeId(undefined)
  }, [])

  useEffect(() => {
    void api.projects()
      .then(async (items) => {
        setProjects(items)
        if (items[0]) {
          setProjectId(items[0].id)
          await loadProject(items[0].id)
        }
      })
      .catch((error: Error) => setNotice(error.message))
      .finally(() => setInitializing(false))
  }, [loadProject])

  useEffect(() => {
    void api.llmConfig()
      .then(setLlmDefaults)
      .catch(() => undefined)
  }, [])

  useEffect(() => {
    if (!runResult) return
    const stepMap = new Map(runResult.steps.map((step) => [step.nodeId, step]))
    setNodes((current) => current.map((node) => ({
      ...node,
      data: { ...node.data, runStep: stepMap.get(node.id) },
    })))
  }, [runResult])

  const createFirstProject = async () => {
    try {
      const project = await api.createProject('Order Service', 'http://localhost:8080')
      setProjects([project])
      setProjectId(project.id)
      await loadProject(project.id)
      setImportOpen(true)
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '创建项目失败')
    }
  }

  const createProject = async (name: string, baseUrl: string) => {
    const project = await api.createProject(name, baseUrl)
    setProjects((prev) => [project, ...prev])
    setProjectId(project.id)
    await loadProject(project.id)
    setNotice('项目已创建')
  }

  const refreshEndpoints = async () => {
    if (!projectId) return
    setEndpoints(await api.endpoints(projectId))
  }

  const onDropEndpoint = useCallback((endpoint: EndpointDefinition, position: { x: number; y: number }) => {
    const nodeId = crypto.randomUUID()
    const pathParameters = Object.fromEntries(
      endpoint.parameters.filter((parameter) => parameter.location === 'path')
        .map((parameter) => [parameter.name, '']),
    )
    const definition: FlowNodeDefinition = {
      id: nodeId,
      endpointId: endpoint.id,
      name: endpoint.summary || endpoint.operationId || endpoint.path,
      x: position.x,
      y: position.y,
      request: {
        ...blankRequest(),
        path: pathParameters,
        bodyType: endpoint.requestBodySchema ? 'JSON' : 'NONE',
        body: schemaExample(endpoint.requestBodySchema),
      },
      extractors: [],
      assertions: [],
      nodeType: 'API',
      gateway: null,
    }
    const nearest = nearestAutoConnectNode(nodes, position)
    setNodes((current) => [
      ...current,
      { id: nodeId, type: 'apiNode', position, data: { endpoint, definition } } satisfies FlowNode,
    ])
    if (nearest) {
      setEdges((currentEdges) => addEdge({
        id: `edge-${nearest.id}-${nodeId}-${crypto.randomUUID()}`,
        source: nearest.id,
        target: nodeId,
      }, currentEdges))
    }
    setSelectedNodeId(nodeId)
  }, [nodes])

  const onAddGateway = useCallback(() => {
    const nodeId = crypto.randomUUID()
    const last = nodes[nodes.length - 1]
    const position = last
      ? { x: last.position.x + 300, y: last.position.y }
      : { x: 120, y: 120 }
    const definition: FlowNodeDefinition = {
      id: nodeId,
      endpointId: null,
      name: '条件网关',
      x: position.x,
      y: position.y,
      request: blankRequest(),
      extractors: [],
      assertions: [],
      nodeType: 'GATEWAY',
      gateway: { sourceType: 'VARIABLE', source: '', fixedValue: '' },
    }
    setNodes((current) => [
      ...current,
      { id: nodeId, type: 'gatewayNode', position, data: { definition } },
    ])
    setSelectedNodeId(nodeId)
  }, [nodes])

  const onAddParallel = useCallback(() => {
    const nodeId = crypto.randomUUID()
    const last = nodes[nodes.length - 1]
    const position = last
      ? { x: last.position.x + 300, y: last.position.y }
      : { x: 120, y: 120 }
    const definition: FlowNodeDefinition = {
      id: nodeId,
      endpointId: null,
      name: '并行分支',
      x: position.x,
      y: position.y,
      request: blankRequest(),
      extractors: [],
      assertions: [],
      nodeType: 'PARALLEL',
      gateway: null,
    }
    setNodes((current) => [
      ...current,
      { id: nodeId, type: 'parallelNode', position, data: { definition } },
    ])
    setSelectedNodeId(nodeId)
  }, [nodes])

  const onNodesChange = useCallback((changes: NodeChange<FlowNode>[]) => {
    setNodes((current) => applyNodeChanges(changes, current))
  }, [])
  const onEdgesChange = useCallback((changes: EdgeChange<Edge>[]) => {
    setEdges((current) => applyEdgeChanges(changes, current))
  }, [])
  const onConnect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target || connection.source === connection.target) return
    setEdges((current) => {
      const duplicate = current.some((edge) =>
        edge.source === connection.source && edge.target === connection.target,
      )
      if (duplicate) return current
      return addEdge({
        ...connection,
        id: `edge-${connection.source}-${connection.target}-${crypto.randomUUID()}`,
      }, current)
    })
  }, [])

  const deleteEdge = useCallback((edgeId: string) => {
    setEdges((current) => current.filter((edge) => edge.id !== edgeId))
  }, [])

  const onEdgeConditionChange = useCallback((edgeId: string, condition: Condition | null) => {
    setEdges((current) => current.map((edge) =>
      edge.id === edgeId ? { ...edge, condition } : edge,
    ))
  }, [])

  const updateSelectedNode = (definition: FlowNodeDefinition) => {
    setNodes((current) => current.map((node) =>
      node.id === definition.id ? { ...node, data: { ...node.data, definition } } : node,
    ))
  }
  const deleteSelectedNode = () => {
    if (!selectedNodeId) return
    setNodes((current) => current.filter((node) => node.id !== selectedNodeId))
    setEdges((current) => current.filter((edge) => edge.source !== selectedNodeId && edge.target !== selectedNodeId))
    setSelectedNodeId(undefined)
  }

  const saveFlow = async () => {
    if (!projectId) return undefined
    const saved = await api.saveFlow(flowFromCanvas(projectId, flowName, flowId, nodes, edges, flowConfig))
    setFlowId(saved.id)
    setNotice('流程已保存')
    return saved
  }

  const runFlow = async () => {
    if (!environmentId || nodes.length === 0) return
    setRunning(true)
    setRunResult(undefined)
    setNodes((current) => current.map((node) => ({ ...node, data: { ...node.data, runStep: undefined } })))
    try {
      const flow = await saveFlow()
      if (flow?.id) setRunResult(await api.runFlow(flow.id, environmentId))
    } catch (error) {
      setNotice(error instanceof Error ? error.message : '流程执行失败')
    } finally {
      setRunning(false)
    }
  }

  const exportProject = async () => {
    if (!projectId) return
    const data = await api.exportProject(projectId)
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' })
    const link = document.createElement('a')
    link.href = URL.createObjectURL(blob)
    link.download = `aft-project-${currentProject?.name ?? projectId}.json`
    link.click()
    URL.revokeObjectURL(link.href)
    setNotice('项目已导出')
  }

  const importProjectData = async () => {
    const input = document.createElement('input')
    input.type = 'file'
    input.accept = '.json'
    input.onchange = async () => {
      const file = input.files?.[0]
      if (!file) return
      try {
        const text = await file.text()
        const data = JSON.parse(text) as Record<string, unknown>
        const project = await api.importProject(data)
        setProjects((prev) => [...prev, project])
        setProjectId(project.id)
        await loadProject(project.id)
        setNotice('项目已导入')
      } catch (error) {
        setNotice(error instanceof Error ? error.message : '导入失败')
      }
    }
    input.click()
  }

  const analyzeJavaFlow = async (request: JavaFlowAnalyzeRequest) => {
    if (!projectId) return
    const result = await api.analyzeJavaFlow(projectId, request)
    await loadProject(projectId)
    setFlowId(result.flow.id)
    setNotice(`AI 流程已生成：扫描 ${result.scan.includedFiles} 个 Java 文件`)
  }

  if (initializing) {
    return <div className="boot-screen"><div className="brand-mark">A</div><strong>正在启动 AFT Studio…</strong></div>
  }

  if (projects.length === 0) {
    return (
      <div className="welcome-screen">
        <div className="welcome-card">
          <div className="brand-mark large">A</div>
          <h1>AFT Studio</h1>
          <p>导入 Spring Boot OpenAPI，拖拽编排并执行真实业务接口流程。</p>
          <button className="primary-button large" onClick={() => void createFirstProject()}>
            <Database size={18} /> 创建本地项目
          </button>
          {notice ? <div className="form-error">{notice}</div> : null}
        </div>
      </div>
    )
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand"><div className="brand-mark">A</div><strong>AFT Studio</strong></div>
        <label className="top-select">
          <span>项目</span>
          <select
            value={projectId}
            onChange={(event) => {
              setProjectId(event.target.value)
              void loadProject(event.target.value)
            }}
          >
            {projects.map((project) => <option value={project.id} key={project.id}>{project.name}</option>)}
          </select>
          <ChevronDown size={15} />
        </label>
        <button
          className="secondary-button compact"
          onClick={() => setProjectDialogOpen(true)}
          title="新建项目"
        >
          <Plus size={14} />
        </button>
        <button className="top-environment" onClick={() => setEnvironmentOpen(true)}>
          <span className="environment-dot" />
          <span>{currentEnvironment?.name ?? '环境'}</span>
          <code>{currentEnvironment?.baseUrl}</code>
          <Settings2 size={15} />
        </button>
        <input className="flow-name" value={flowName} onChange={(event) => setFlowName(event.target.value)} />
        <label className="top-select">
          <span>失败策略</span>
          <select
            value={flowConfig.onFailure}
            onChange={(event) => setFlowConfig({ onFailure: event.target.value as FlowConfig['onFailure'] })}
          >
            <option value="FAIL_ALL">全部终止</option>
            <option value="CONTINUE">继续执行</option>
          </select>
        </label>
        <div className="top-actions">
          <button className="secondary-button" onClick={() => void exportProject()} title="导出项目">
            <Download size={16} />
          </button>
          <button className="secondary-button" onClick={() => void importProjectData()} title="导入项目">
            <Upload size={16} />
          </button>
          <button className="secondary-button" onClick={() => setImportOpen(true)}>
            <UploadCloud size={16} /> 导入 OpenAPI
          </button>
          <button className="secondary-button" onClick={() => setJavaAnalyzeOpen(true)}>
            <BrainCircuit size={16} /> AI 生成流程
          </button>
          <button className="secondary-button" onClick={() => void saveFlow()}>
            <Save size={16} /> 保存
          </button>
          <button className="primary-button" disabled={running || nodes.length === 0} onClick={() => void runFlow()}>
            <Play size={16} fill="currentColor" /> {running ? '运行中…' : '运行流程'}
          </button>
        </div>
      </header>

      <div className="workspace">
        <EndpointCatalog
          endpoints={endpoints}
          folders={folders}
          onCreateFolder={async (name, parentId) => {
            const folder = await api.saveFolder({ id: '', projectId, parentId: parentId ?? null, name, sortOrder: folders.length })
            setFolders((prev) => [...prev, folder])
          }}
          onDeleteFolder={async (id) => {
            await api.deleteFolder(id)
            if (projectId) {
              const [newEndpoints, newFolders] = await Promise.all([
                api.endpoints(projectId),
                api.folders(projectId),
              ])
              setEndpoints(newEndpoints)
              setFolders(newFolders)
            }
          }}
          onMoveFolder={async (folderId, newParentId, sortOrder) => {
            await api.moveFolder(folderId, newParentId, sortOrder)
            if (projectId) {
              setFolders(await api.folders(projectId))
            }
          }}
          onMoveEndpoints={async (folderId, endpointIds) => {
            const id = folderId || null
            await api.moveEndpoints(id ?? '', endpointIds)
            if (projectId) {
              setEndpoints(await api.endpoints(projectId))
            }
          }}
          onRefresh={async () => {
            if (projectId) {
              setEndpoints(await api.endpoints(projectId))
              setFolders(await api.folders(projectId))
            }
          }}
        />
        <FlowCanvas
          nodes={nodes}
          edges={edges}
          running={running}
          runResult={runResult}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={setSelectedNodeId}
          onDropEndpoint={onDropEndpoint}
          onAddGateway={onAddGateway}
          onAddParallel={onAddParallel}
          onEdgeConditionChange={onEdgeConditionChange}
          onDeleteEdge={deleteEdge}
        />
        <NodeInspector
          data={selectedNode?.data}
          availableRunVariables={availableRunVariables}
          gatewayBranches={gatewayBranches}
          onGatewayBranchChange={onEdgeConditionChange}
          onChange={updateSelectedNode}
          onDelete={deleteSelectedNode}
        />
      </div>

      <RunReport result={runResult} running={running} />
      {notice ? <button className="toast" onClick={() => setNotice('')}>{notice}</button> : null}

      <ImportDialog
        open={importOpen}
        onClose={() => setImportOpen(false)}
        onUrl={async (url) => { await api.importUrl(projectId, url); await refreshEndpoints() }}
        onFile={async (file) => { await api.importFile(projectId, file); await refreshEndpoints() }}
      />
      <JavaAnalyzeDialog
        open={javaAnalyzeOpen}
        defaults={llmDefaults}
        onClose={() => setJavaAnalyzeOpen(false)}
        onAnalyze={analyzeJavaFlow}
      />
      <EnvironmentDialog
        key={currentEnvironment?.id + String(environmentOpen)}
        open={environmentOpen}
        environment={currentEnvironment}
        onClose={() => setEnvironmentOpen(false)}
        onSave={async (environment) => {
          const saved = await api.saveEnvironment(environment)
          setEnvironments((current) => current.map((item) => item.id === saved.id ? saved : item))
        }}
      />
      <ProjectDialog
        open={projectDialogOpen}
        onClose={() => setProjectDialogOpen(false)}
        onCreate={createProject}
      />
    </div>
  )
}

export default App
