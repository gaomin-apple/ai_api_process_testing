import { FileSearch, FileUp, Link2, Plus, X } from 'lucide-react'
import { useState } from 'react'
import type { EnvironmentDefinition, JavaFlowAnalyzeRequest, LlmDefaults } from '../types'

type ImportProps = {
  open: boolean
  onClose: () => void
  onUrl: (url: string) => Promise<void>
  onFile: (file: File) => Promise<void>
}

export function ImportDialog({ open, onClose, onUrl, onFile }: ImportProps) {
  const [url, setUrl] = useState('http://localhost:8080/v3/api-docs')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  if (!open) return null

  const submit = async (action: () => Promise<void>) => {
    setLoading(true)
    setError('')
    try {
      await action()
      onClose()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '导入失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="dialog-backdrop">
      <div className="dialog">
        <div className="dialog-title">
          <div><strong>导入 OpenAPI</strong><span>支持 OpenAPI 3 JSON/YAML</span></div>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>
        <label className="field">
          <span>OpenAPI URL</span>
          <div className="input-action">
            <Link2 size={16} />
            <input value={url} onChange={(event) => setUrl(event.target.value)} />
            <button disabled={loading || !url} onClick={() => submit(() => onUrl(url))}>导入</button>
          </div>
        </label>
        <div className="dialog-divider">或</div>
        <label className="file-drop">
          <FileUp size={24} />
          <strong>选择 JSON 或 YAML 文件</strong>
          <span>最大 10 MB</span>
          <input
            type="file"
            accept=".json,.yaml,.yml,application/json,application/yaml"
            onChange={(event) => {
              const file = event.target.files?.[0]
              if (file) void submit(() => onFile(file))
            }}
          />
        </label>
        {error ? <div className="form-error">{error}</div> : null}
      </div>
    </div>
  )
}

type ProjectProps = {
  open: boolean
  onClose: () => void
  onCreate: (name: string, baseUrl: string) => Promise<void>
}

export function ProjectDialog({ open, onClose, onCreate }: ProjectProps) {
  const [name, setName] = useState('')
  const [baseUrl, setBaseUrl] = useState('http://localhost:8080')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  if (!open) return null

  const submit = async () => {
    if (!name.trim()) {
      setError('请输入项目名称')
      return
    }
    setLoading(true)
    setError('')
    try {
      await onCreate(name.trim(), baseUrl || 'http://localhost:8080')
      setName('')
      setBaseUrl('http://localhost:8080')
      onClose()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '创建失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="dialog-backdrop">
      <div className="dialog compact">
        <div className="dialog-title">
          <div><strong>新建项目</strong><span>创建一个新的 API 测试项目</span></div>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>
        <label className="field">
          <span>项目名称</span>
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="例如：Order Service"
            autoFocus
          />
        </label>
        <label className="field">
          <span>基础地址</span>
          <input
            value={baseUrl}
            onChange={(event) => setBaseUrl(event.target.value)}
            placeholder="http://localhost:8080"
          />
          <small className="field-help">API 服务的基础 URL，用于导入 OpenAPI 和执行测试</small>
        </label>
        {error ? <div className="form-error">{error}</div> : null}
        <div className="dialog-actions">
          <button className="secondary-button" onClick={onClose}>取消</button>
          <button className="primary-button" disabled={loading} onClick={() => void submit()}>
            {loading ? '创建中…' : '创建项目'}
          </button>
        </div>
      </div>
    </div>
  )
}

type EnvironmentProps = {
  open: boolean
  environment?: EnvironmentDefinition
  onClose: () => void
  onSave: (environment: EnvironmentDefinition) => Promise<void>
}

export function EnvironmentDialog({ open, environment, onClose, onSave }: EnvironmentProps) {
  const [baseUrl, setBaseUrl] = useState(environment?.baseUrl ?? '')
  const [variables, setVariables] = useState(JSON.stringify(environment?.variables ?? {}, null, 2))
  const [error, setError] = useState('')
  if (!open || !environment) return null

  const save = async () => {
    try {
      await onSave({ ...environment, baseUrl, variables: JSON.parse(variables || '{}') })
      onClose()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '保存失败')
    }
  }

  return (
    <div className="dialog-backdrop">
      <div className="dialog compact">
        <div className="dialog-title">
          <div><strong>环境配置</strong><span>{environment.name}</span></div>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>
        <label className="field">
          <span>基础地址</span>
          <input value={baseUrl} onChange={(event) => setBaseUrl(event.target.value)} />
        </label>
        <label className="field">
          <span>环境变量 JSON</span>
          <textarea className="code-input" rows={8} value={variables} onChange={(event) => setVariables(event.target.value)} />
        </label>
        {error ? <div className="form-error">{error}</div> : null}
        <div className="dialog-actions">
          <button className="secondary-button" onClick={onClose}>取消</button>
          <button className="primary-button" onClick={() => void save()}>保存环境</button>
        </div>
      </div>
    </div>
  )
}

type JavaAnalyzeProps = {
  open: boolean
  defaults?: LlmDefaults
  onClose: () => void
  onAnalyze: (request: JavaFlowAnalyzeRequest) => Promise<void>
}

export function JavaAnalyzeDialog({ open, defaults, onClose, onAnalyze }: JavaAnalyzeProps) {
  const [sourcePath, setSourcePath] = useState('')
  const [flowName, setFlowName] = useState('AI Generated Flow')
  const [apiBaseUrl, setApiBaseUrl] = useState(defaults?.apiBaseUrl ?? 'https://api.deepseek.com/v1')
  const [model, setModel] = useState(defaults?.model ?? 'deepseek-chat')
  const [apiKey, setApiKey] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  if (!open) return null

  const submit = async () => {
    if (!sourcePath.trim()) {
      setError('请输入 Java 项目目录')
      return
    }
    setLoading(true)
    setError('')
    try {
      await onAnalyze({
        sourcePath: sourcePath.trim(),
        flowName: flowName.trim() || 'AI Generated Flow',
        apiBaseUrl: apiBaseUrl.trim() || undefined,
        model: model.trim() || undefined,
        apiKey: apiKey.trim() || undefined,
      })
      onClose()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'AI 分析失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="dialog-backdrop">
      <div className="dialog wide">
        <div className="dialog-title">
          <div>
            <strong>扫描 Java 项目生成流程</strong>
            <span>扫描本地 Java 源码，通过 OpenAI 兼容接口分析调用关系，默认 DeepSeek</span>
          </div>
          <button className="icon-button" onClick={onClose}><X size={18} /></button>
        </div>
        <label className="field">
          <span>Java 项目目录</span>
          <div className="input-action">
            <FileSearch size={16} />
            <input
              value={sourcePath}
              onChange={(event) => setSourcePath(event.target.value)}
              placeholder="D:\\workspace\\order-service"
              autoFocus
            />
          </div>
          <small className="field-help">填写后端可访问的本地目录；会扫描 .java 文件并限制发送给模型的上下文大小。</small>
        </label>
        <label className="field">
          <span>生成流程名称</span>
          <input value={flowName} onChange={(event) => setFlowName(event.target.value)} />
        </label>
        <div className="field-grid">
          <label className="field">
            <span>OpenAI 兼容 Base URL</span>
            <input value={apiBaseUrl} onChange={(event) => setApiBaseUrl(event.target.value)} />
          </label>
          <label className="field">
            <span>模型</span>
            <input value={model} onChange={(event) => setModel(event.target.value)} />
          </label>
        </div>
        <label className="field">
          <span>API Key</span>
          <input
            type="password"
            value={apiKey}
            onChange={(event) => setApiKey(event.target.value)}
            placeholder={defaults?.apiKeyConfigured ? '已配置环境变量 AFT_LLM_API_KEY，可留空' : 'sk-...'}
          />
          <small className="field-help">
            可留空使用服务端环境变量 AFT_LLM_API_KEY；Base URL 和模型也可用 AFT_LLM_BASE_URL、AFT_LLM_MODEL 配置。
          </small>
        </label>
        {error ? <div className="form-error">{error}</div> : null}
        <div className="dialog-actions">
          <button className="secondary-button" onClick={onClose}>取消</button>
          <button className="primary-button" disabled={loading} onClick={() => void submit()}>
            {loading ? '分析中...' : '生成流程'}
          </button>
        </div>
      </div>
    </div>
  )
}
