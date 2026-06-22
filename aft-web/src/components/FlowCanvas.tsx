import {
  Background,
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  getBezierPath,
  type Connection,
  type Edge,
  type EdgeProps,
  type OnEdgesChange,
  type OnNodesChange,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from '@xyflow/react'
import { CheckCircle2, Clock3, GitBranch, GitFork, Plus, Trash2, XCircle, X } from 'lucide-react'
import { memo, useCallback, useState } from 'react'
import { useMemo } from 'react'
import type { DragEvent } from 'react'
import type { CanvasNodeData, Condition, EndpointDefinition, RunResult } from '../types'

type FlowNode = Node<CanvasNodeData, 'apiNode' | 'gatewayNode' | 'parallelNode'>

type Props = {
  nodes: FlowNode[]
  edges: Edge[]
  onNodesChange: OnNodesChange<FlowNode>
  onEdgesChange: OnEdgesChange<Edge>
  onConnect: (connection: Connection) => void
  onNodeClick: (nodeId: string) => void
  onDropEndpoint: (endpoint: EndpointDefinition, position: { x: number; y: number }) => void
  onAddGateway: () => void
  onAddParallel: () => void
  onEdgeConditionChange: (edgeId: string, condition: Condition | null) => void
  onDeleteEdge: (edgeId: string) => void
  running: boolean
  runResult?: RunResult
}

type RuntimeState = 'idle' | 'running' | 'passed' | 'failed'
type LuminousEdgeData = { runtimeState?: RuntimeState; onDelete?: (id: string) => void }
type LuminousEdgeType = Edge<LuminousEdgeData, 'luminous'>

function LuminousEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  data,
}: EdgeProps<LuminousEdgeType>) {
  const [hovered, setHovered] = useState(false)
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  })
  const state = data?.runtimeState ?? 'idle'
  return (
    <>
      <g onMouseEnter={() => setHovered(true)} onMouseLeave={() => setHovered(false)}>
        <BaseEdge id={`${id}-glow`} path={path} className={`flow-edge-glow ${state}`} />
        <BaseEdge
          id={id}
          path={path}
          markerEnd={markerEnd}
          interactionWidth={18}
          className={`flow-edge-stream ${state}`}
        />
      </g>
      {hovered && data?.onDelete && (
        <EdgeLabelRenderer>
          <div
            className="edge-delete-hover"
            style={{
              position: 'absolute',
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
              pointerEvents: 'all',
            }}
            onMouseEnter={() => setHovered(true)}
            onMouseLeave={() => setHovered(false)}
          >
            <button
              className="edge-delete-hover-btn"
              title="删除连接"
              onClick={(e) => { e.stopPropagation(); data.onDelete!(id) }}
            >
              <X size={11} />
            </button>
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  )
}

const edgeTypes = { luminous: LuminousEdge }

function ApiNode({ data, selected }: NodeProps<FlowNode>) {
  const endpoint = data.endpoint
  if (!endpoint) return null
  const step = data.runStep
  const statusIcon = step?.status === 'FAILED'
    ? <XCircle size={16} />
    : step?.status === 'PASSED'
      ? <CheckCircle2 size={16} />
      : <Clock3 size={16} />
  return (
    <div className={`api-node ${selected ? 'selected' : ''} ${step?.status?.toLowerCase() ?? ''} ${endpoint.active ? '' : 'inactive'}`}>
      <Handle type="target" position={Position.Left} />
      <div className="node-heading">
        <span className="node-status">{statusIcon}</span>
        <strong>{data.definition.name}</strong>
      </div>
      <div className="node-endpoint">
        <span className={`method method-${endpoint.method.toLowerCase()}`}>
          {endpoint.method}
        </span>
        <span>{endpoint.path}</span>
      </div>
      <div className="node-meta">
        <span>{endpoint.active ? (step ? `${step.statusCode || '-'} · ${step.durationMs} ms` : '等待运行') : '接口已失效'}</span>
        <span>{data.definition.extractors.length} 提取</span>
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

function GatewayNode({ data, selected }: NodeProps<FlowNode>) {
  const gateway = data.definition.gateway
  const criterion = gateway?.sourceType === 'FIXED'
    ? `固定值: ${gateway.fixedValue || '未设置'}`
    : `变量: ${gateway?.source || '未选择'}`
  return (
    <div className={`gateway-node ${selected ? 'selected' : ''}`}>
      <Handle type="target" position={Position.Left} />
      <div className="gateway-diamond"><GitBranch size={21} /></div>
      <div className="gateway-content">
        <strong>{data.definition.name}</strong>
        <span>{criterion}</span>
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

function ParallelNode({ data, selected }: NodeProps<FlowNode>) {
  return (
    <div className={`parallel-node ${selected ? 'selected' : ''}`}>
      <Handle type="target" position={Position.Left} />
      <div className="parallel-diamond"><GitFork size={21} /></div>
      <div className="parallel-content">
        <strong>{data.definition.name}</strong>
        <span>并行执行</span>
      </div>
      <Handle type="source" position={Position.Right} />
    </div>
  )
}

const MemoApiNode = memo(ApiNode)
const MemoGatewayNode = memo(GatewayNode)
const MemoParallelNode = memo(ParallelNode)
const nodeTypes = { apiNode: MemoApiNode, gatewayNode: MemoGatewayNode, parallelNode: MemoParallelNode }

function ConditionEditor({
  edge,
  nodes,
  onClose,
  onChange,
}: {
  edge: Edge
  nodes: FlowNode[]
  onClose: () => void
  onChange: (condition: Condition | null) => void
}) {
  const cond = (edge as Edge & { condition?: Condition | null }).condition
  const sourceNode = nodes.find((n) => n.id === edge.source)
  const targetNode = nodes.find((n) => n.id === edge.target)
  const sourceIsGateway = sourceNode?.data.definition.nodeType === 'GATEWAY'
  const gateway = sourceNode?.data.definition.gateway
  const [source, setSource] = useState(cond?.source ?? (sourceIsGateway ? '__gateway__' : ''))
  const opInit: Condition['operator'] = cond?.operator ?? 'EQUALS'
  const [operator, setOperator] = useState(opInit)
  const [expected, setExpected] = useState(cond?.expected ?? '')
  const extractors = sourceNode?.data.definition.extractors.filter((e) => e.variable) ?? []
  const lastValues = sourceNode?.data.runStep?.extractedVariables ?? {}

  const apply = () => {
    if (!source.trim()) {
      onChange(null)
    } else {
      onChange({ source: source.trim(), operator, expected })
    }
    onClose()
  }

  const conditionPreview = source
    ? `${source} ${operator === 'EQUALS' ? '==' : operator === 'NOT_EQUALS' ? '!=' : operator === 'CONTAINS' ? '包含' : operator === 'EXISTS' ? '存在' : '<'} ${expected || '?'}`
    : ''

  return (
    <div className="edge-editor-backdrop" onClick={onClose}>
      <div className="edge-editor wide" onClick={(e) => e.stopPropagation()}>
        <div className="edge-editor-title">
          分支条件配置
          <button className="icon-button" onClick={onClose} style={{ marginLeft: 'auto' }}>
            <X size={16} />
          </button>
        </div>

        <div className="condition-context">
          <span className="condition-node-label">
            {sourceNode?.data.endpoint ? (
              <span className={`method method-${sourceNode.data.endpoint.method.toLowerCase()}`}>
                {sourceNode.data.endpoint.method}
              </span>
            ) : <GitBranch size={14} />}
            {sourceNode?.data.definition.name}
          </span>
          <span className="condition-arrow">→</span>
          <span className="condition-node-label">
            {targetNode?.data.endpoint ? (
              <span className={`method method-${targetNode.data.endpoint.method.toLowerCase()}`}>
                {targetNode.data.endpoint.method}
              </span>
            ) : <GitBranch size={14} />}
            {targetNode?.data.definition.name}
          </span>
        </div>

        {conditionPreview && (
          <div className="condition-preview">
            <span className="condition-preview-label">条件表达式</span>
            <code>{conditionPreview}</code>
          </div>
        )}

        <div className="condition-section">
          <div className="condition-section-title">选择判断变量</div>
          {sourceIsGateway ? (
            <div className="condition-preview">
              <span className="condition-preview-label">
                {gateway?.sourceType === 'FIXED' ? '网关固定值' : '网关变量'}
              </span>
              <code>{gateway?.sourceType === 'FIXED' ? gateway.fixedValue : gateway?.source}</code>
            </div>
          ) : extractors.length > 0 ? (
            <div className="condition-var-list">
              {extractors.map((ext) => {
                const val = lastValues[ext.variable]
                const isSelected = source === ext.variable
                return (
                  <button
                    key={ext.variable}
                    className={`condition-var-chip ${isSelected ? 'selected' : ''}`}
                    onClick={() => setSource(ext.variable)}
                  >
                    <span className="var-name">{ext.variable}</span>
                    <span className="var-source">{ext.source === 'JSON_PATH' ? 'JSON' : ext.source}</span>
                    {val !== undefined && <span className="var-value" title={val}>{val}</span>}
                  </button>
                )
              })}
            </div>
          ) : (
            <div className="condition-empty">该节点未配置提取变量，请先在节点「提取」标签中添加</div>
          )}
        </div>

        <div className="condition-section">
          <div className="condition-section-title">比较方式</div>
          <div className="condition-op-list">
            {([
              ['EQUALS', '等于', '=='],
              ['NOT_EQUALS', '不等于', '!='],
              ['CONTAINS', '包含', 'contains'],
              ['EXISTS', '存在', 'exists'],
              ['LESS_THAN', '小于', '<'],
            ] as const).map(([value, label, symbol]) => (
              <button
                key={value}
                className={`condition-op-btn ${operator === value ? 'selected' : ''}`}
                onClick={() => setOperator(value)}
              >
                <span className="op-symbol">{symbol}</span>
                <span className="op-label">{label}</span>
              </button>
            ))}
          </div>
        </div>

        {operator !== 'EXISTS' && (
          <div className="condition-section">
            <div className="condition-section-title">期望值</div>
            <input
              className="condition-value-input"
              value={expected}
              onChange={(e) => setExpected(e.target.value)}
              placeholder="输入期望值，例如: 0, true, success"
            />
            {source && lastValues[source] !== undefined && (
              <small className="condition-hint">
                上次运行值: <code>{lastValues[source]}</code>
                <button className="text-button" onClick={() => setExpected(lastValues[source])}>使用此值</button>
              </small>
            )}
          </div>
        )}

        <div className="edge-editor-actions">
          <button className="secondary-button" onClick={() => { onChange(null); onClose() }}>清除条件</button>
          <button className="primary-button" onClick={apply}>确定</button>
        </div>
      </div>
    </div>
  )
}

const operatorSymbol = (op: string) => {
  switch (op) {
    case 'EQUALS': return '=='
    case 'NOT_EQUALS': return '!='
    case 'CONTAINS': return 'contains'
    case 'EXISTS': return 'exists'
    case 'LESS_THAN': return '<'
    default: return op
  }
}

export function FlowCanvas({
  nodes,
  edges,
  onNodesChange,
  onEdgesChange,
  onConnect,
  onNodeClick,
  onDropEndpoint,
  onAddGateway,
  onAddParallel,
  onEdgeConditionChange,
  onDeleteEdge,
  running,
  runResult,
}: Props) {
  const [instance, setInstance] = useState<ReactFlowInstance<FlowNode, Edge> | null>(null)
  const [editingEdge, setEditingEdge] = useState<Edge | null>(null)
  const [selectedEdge, setSelectedEdge] = useState<{
    id: string
    position: { x: number; y: number }
  } | null>(null)
  const onDragOver = useCallback((event: DragEvent) => {
    event.preventDefault()
    event.dataTransfer.dropEffect = 'copy'
  }, [])
  const onDrop = useCallback((event: DragEvent) => {
    event.preventDefault()
    const raw = event.dataTransfer.getData('application/aft-endpoint')
    if (!raw || !instance) return
    const endpoint = JSON.parse(raw) as EndpointDefinition
    onDropEndpoint(endpoint, instance.screenToFlowPosition({ x: event.clientX, y: event.clientY }))
  }, [instance, onDropEndpoint])

  const onEdgeDoubleClick = useCallback((_: React.MouseEvent, edge: Edge) => {
    setEditingEdge(edge)
  }, [])
  const runtimeEdges = useMemo<LuminousEdgeType[]>(() => {
    const stateByEdge = new Map<string, RuntimeState>()
    if (running) {
      edges.forEach((edge) => stateByEdge.set(edge.id, 'running'))
    } else if (runResult) {
      const bySource = new Map<string, Edge[]>()
      edges.forEach((edge) => {
        bySource.set(edge.source, [...(bySource.get(edge.source) ?? []), edge])
      })
      const findPath = (source: string, target: string): Edge[] => {
        const queue: Array<{ nodeId: string; path: Edge[] }> = [{ nodeId: source, path: [] }]
        const visited = new Set<string>()
        while (queue.length) {
          const current = queue.shift()
          if (!current || visited.has(current.nodeId)) continue
          visited.add(current.nodeId)
          if (current.nodeId === target) return current.path
          for (const edge of bySource.get(current.nodeId) ?? []) {
            queue.push({ nodeId: edge.target, path: [...current.path, edge] })
          }
        }
        return []
      }
      for (let index = 0; index < runResult.steps.length - 1; index += 1) {
        const current = runResult.steps[index]
        const next = runResult.steps[index + 1]
        const state: RuntimeState = next.status === 'FAILED' ? 'failed' : 'passed'
        findPath(current.nodeId, next.nodeId).forEach((edge) => stateByEdge.set(edge.id, state))
      }
    }
    return edges.map((edge) => ({
      ...edge,
      type: 'luminous',
      data: {
        ...(edge.data ?? {}),
        runtimeState: stateByEdge.get(edge.id) ?? 'idle',
        onDelete: onDeleteEdge,
      },
    }))
  }, [edges, runResult, running, onDeleteEdge])

  return (
    <main className="canvas-panel">
      <div className="canvas-title">
        <div>
          <strong>流程画布</strong>
          <span>拖到已有节点附近会自动连接，也可从右侧连接点手动连线</span>
        </div>
        <div className="canvas-actions">
          <button className="secondary-button compact" onClick={onAddGateway}>
            <Plus size={14} /> 添加网关
          </button>
          <button className="secondary-button compact" onClick={onAddParallel}>
            <Plus size={14} /> 添加并行
          </button>
          <span className="linear-hint">支持条件分支 · 并行执行</span>
        </div>
      </div>
      <div className="flow-area" onDrop={onDrop} onDragOver={onDragOver}>
        <ReactFlow
          nodes={nodes}
          edges={runtimeEdges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          onInit={setInstance}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={(_, node) => onNodeClick(node.id)}
          onEdgeClick={(event, edge) => {
            if (!instance) return
            setSelectedEdge({
              id: edge.id,
              position: instance.screenToFlowPosition({
                x: event.clientX,
                y: event.clientY,
              }),
            })
          }}
          onEdgeDoubleClick={onEdgeDoubleClick}
          onPaneClick={() => setSelectedEdge(null)}
          fitView
          minZoom={0.35}
          maxZoom={1.7}
          defaultEdgeOptions={{ type: 'luminous' }}
          deleteKeyCode={null}
        >
          <Background color="#dbe3ee" gap={22} size={1} />
          <Controls position="top-left" />
          <MiniMap
            position="bottom-left"
            pannable
            zoomable
            nodeColor={(node) =>
              (node as FlowNode).data.runStep?.status === 'FAILED' ? '#ef4444' : '#2563eb'
            }
          />
          <EdgeLabelRenderer>
            {edges.map((edge) => {
              const cond = (edge as Edge & { condition?: Condition | null }).condition
              const sourceNode = nodes.find((n) => n.id === edge.source)
              const targetNode = nodes.find((n) => n.id === edge.target)
              if (!sourceNode || !targetNode) return null
              const sourceIsGateway = sourceNode.data.definition.nodeType === 'GATEWAY'
              if (!cond && !sourceIsGateway) return null
              const sx = sourceNode.position.x + 230
              const sy = sourceNode.position.y + 45
              const tx = targetNode.position.x
              const ty = targetNode.position.y + 45
              const mx = (sx + tx) / 2
              const my = (sy + ty) / 2
              const label = cond
                ? `网关值 ${operatorSymbol(cond.operator)} ${cond.expected || '未设置'}`
                : sourceIsGateway ? '默认分支' : `${sourceNode.data.definition.name} → ${targetNode.data.definition.name}`
              return (
                <div
                  key={edge.id}
                  className={`edge-label ${cond ? 'conditional' : 'fallback'}`}
                  style={{
                    position: 'absolute',
                    transform: `translate(-50%, -50%) translate(${mx}px, ${my}px)`,
                    pointerEvents: 'all',
                  }}
                >
                  <span>{label}</span>
                </div>
              )
            })}
            {selectedEdge ? (
              <div
                className="edge-action-popover"
                style={{
                  position: 'absolute',
                  transform: `translate(-50%, -125%) translate(${selectedEdge.position.x}px, ${selectedEdge.position.y}px)`,
                  pointerEvents: 'all',
                }}
                onClick={(event) => event.stopPropagation()}
              >
                <button
                  className="edge-delete-button"
                  title="删除当前节点连接"
                  onClick={() => {
                    onDeleteEdge(selectedEdge.id)
                    setSelectedEdge(null)
                  }}
                >
                  <Trash2 size={13} />
                  删除当前连接
                </button>
              </div>
            ) : null}
          </EdgeLabelRenderer>
        </ReactFlow>
        {nodes.length === 0 ? (
          <div className="canvas-empty">
            <div className="empty-flow-mark">+</div>
            <strong>从左侧拖入第一个接口</strong>
            <span>节点不会自动连接，请从节点右侧连接点拖向目标节点</span>
          </div>
        ) : null}
      </div>
      {editingEdge && (
        <ConditionEditor
          edge={editingEdge}
          nodes={nodes}
          onClose={() => setEditingEdge(null)}
          onChange={(condition) => onEdgeConditionChange(editingEdge.id, condition)}
        />
      )}
    </main>
  )
}
