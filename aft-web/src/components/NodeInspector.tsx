import { Plus, Trash2 } from 'lucide-react'
import { useState } from 'react'
import type { Assertion, CanvasNodeData, Condition, Extractor, FlowNodeDefinition } from '../types'

type Tab = 'request' | 'extract' | 'assert'
type RequestTab = 'params' | 'body' | 'headers' | 'auth'

type Props = {
  data?: CanvasNodeData
  availableRunVariables: string[]
  gatewayBranches: GatewayBranch[]
  onGatewayBranchChange: (edgeId: string, condition: Condition | null) => void
  onChange: (definition: FlowNodeDefinition) => void
  onDelete: () => void
}

export type GatewayBranch = {
  edgeId: string
  targetName: string
  condition: Condition | null
}

const parseMap = (value: string) => {
  if (!value.trim()) return {}
  return JSON.parse(value) as Record<string, string>
}

const mapText = (value: Record<string, string>) =>
  Object.keys(value).length ? JSON.stringify(value, null, 2) : ''

export function NodeInspector({
  data,
  availableRunVariables,
  gatewayBranches,
  onGatewayBranchChange,
  onChange,
  onDelete,
}: Props) {
  const [tab, setTab] = useState<Tab>('request')
  const [requestTab, setRequestTab] = useState<RequestTab>('body')
  if (!data) {
    return (
      <aside className="inspector panel">
        <div className="panel-title">
          <div>
            <strong>节点配置</strong>
            <span>选择一个流程节点</span>
          </div>
        </div>
        <div className="inspector-empty">
          <strong>未选择节点</strong>
          <span>点击画布中的接口节点编辑请求、提取和断言。</span>
        </div>
      </aside>
    )
  }

  const definition = data.definition
  const patch = (next: Partial<FlowNodeDefinition>) => onChange({ ...definition, ...next })
  const patchRequest = (next: Partial<FlowNodeDefinition['request']>) =>
    patch({ request: { ...definition.request, ...next } })

  const updateExtractor = (index: number, next: Partial<Extractor>) => {
    patch({ extractors: definition.extractors.map((item, i) => i === index ? { ...item, ...next } : item) })
  }
  const updateAssertion = (index: number, next: Partial<Assertion>) => {
    patch({ assertions: definition.assertions.map((item, i) => i === index ? { ...item, ...next } : item) })
  }

  if (definition.nodeType === 'GATEWAY') {
    const gateway = definition.gateway ?? { sourceType: 'VARIABLE' as const, source: '', fixedValue: '' }
    const patchGateway = (next: Partial<typeof gateway>) =>
      patch({ gateway: { ...gateway, ...next } })
    return (
      <aside className="inspector panel">
        <div className="panel-title">
          <div>
            <strong>网关配置</strong>
            <span>根据条件选择后续接口</span>
          </div>
          <button className="icon-button danger" onClick={onDelete} title="删除网关">
            <Trash2 size={17} />
          </button>
        </div>
        <div className="inspector-content gateway-inspector">
          <label className="field">
            <span>网关名称</span>
            <input
              key={`${definition.id}-gateway-name`}
              defaultValue={definition.name}
              onBlur={(event) => patch({ name: event.target.value })}
            />
          </label>
          <label className="field">
            <span>判断值来源</span>
            <select
              value={gateway.sourceType}
              onChange={(event) => patchGateway({
                sourceType: event.target.value as 'VARIABLE' | 'FIXED',
              })}
            >
              <option value="VARIABLE">前序接口提取变量</option>
              <option value="FIXED">固定值</option>
            </select>
          </label>
          {gateway.sourceType === 'VARIABLE' ? (
            <label className="field">
              <span>判断变量</span>
              <select
                value={gateway.source}
                onChange={(event) => patchGateway({ source: event.target.value })}
              >
                <option value="">请选择变量</option>
                {availableRunVariables.map((variable) => (
                  <option value={variable} key={variable}>{variable}</option>
                ))}
              </select>
              <small className="field-help">变量来自网关之前接口的"提取"配置。</small>
            </label>
          ) : (
            <label className="field">
              <span>固定判断值</span>
              <input
                key={`${definition.id}-gateway-fixed`}
                defaultValue={gateway.fixedValue}
                onBlur={(event) => patchGateway({ fixedValue: event.target.value })}
              />
            </label>
          )}
          <div className="gateway-branches">
            <div className="gateway-branches-title">
              <strong>后续节点分支</strong>
              <span>{gatewayBranches.length} 条连线</span>
            </div>
            {gatewayBranches.length === 0 ? (
              <div className="gateway-branch-empty">
                请先从网关右侧连接点连向至少两个后续接口节点。
              </div>
            ) : gatewayBranches.map((branch) => {
              const conditional = branch.condition !== null
              return (
                <div className="gateway-branch-card" key={branch.edgeId}>
                  <div className="gateway-branch-target">
                    <span>执行节点</span>
                    <strong>{branch.targetName}</strong>
                  </div>
                  <label className="field compact-field">
                    <span>执行条件</span>
                    <select
                      value={conditional ? 'EQUALS' : 'DEFAULT'}
                      onChange={(event) => {
                        onGatewayBranchChange(
                          branch.edgeId,
                          event.target.value === 'DEFAULT'
                            ? null
                            : {
                                source: '__gateway__',
                                operator: 'EQUALS',
                                expected: branch.condition?.expected ?? '',
                              },
                        )
                      }}
                    >
                      <option value="EQUALS">网关值等于</option>
                      <option value="DEFAULT">默认分支</option>
                    </select>
                  </label>
                  {conditional ? (
                    <label className="field compact-field">
                      <span>等于这个值时执行</span>
                      <input
                        value={branch.condition?.expected ?? ''}
                        onChange={(event) => onGatewayBranchChange(branch.edgeId, {
                          source: '__gateway__',
                          operator: 'EQUALS',
                          expected: event.target.value,
                        })}
                        placeholder="例如：admin"
                      />
                    </label>
                  ) : (
                    <small className="gateway-default-hint">其他条件都不匹配时执行此节点</small>
                  )}
                </div>
              )
            })}
          </div>
          <div className="gateway-help">
            <strong>分支配置方式</strong>
            <span>为每个后续节点填写匹配值，只保留一个默认分支。</span>
            <span>运行时只执行第一条匹配分支；都不匹配则执行默认分支。</span>
          </div>
        </div>
      </aside>
    )
  }

  if (definition.nodeType === 'PARALLEL') {
    return (
      <aside className="inspector panel">
        <div className="panel-title">
          <div>
            <strong>并行分支配置</strong>
            <span>同时执行多个后续接口</span>
          </div>
          <button className="icon-button danger" onClick={onDelete} title="删除并行节点">
            <Trash2 size={17} />
          </button>
        </div>
        <div className="inspector-content parallel-inspector">
          <label className="field">
            <span>节点名称</span>
            <input
              key={`${definition.id}-parallel-name`}
              defaultValue={definition.name}
              onBlur={(event) => patch({ name: event.target.value })}
            />
          </label>
          <div className="parallel-branches">
            <div className="parallel-branches-title">
              <strong>并行执行分支</strong>
              <span>{gatewayBranches.length} 条连线</span>
            </div>
            {gatewayBranches.length === 0 ? (
              <div className="parallel-branch-empty">
                请从并行节点右侧连接点连向多个后续接口节点，运行时将同时执行。
              </div>
            ) : gatewayBranches.map((branch) => {
              const hasCondition = branch.condition !== null
              return (
                <div className="parallel-branch-card" key={branch.edgeId}>
                  <div className="parallel-branch-target">
                    <span>执行节点</span>
                    <strong>{branch.targetName}</strong>
                  </div>
                  <label className="field compact-field">
                    <span>执行条件</span>
                    <select
                      value={hasCondition ? 'CONDITIONAL' : 'ALWAYS'}
                      onChange={(event) => {
                        onGatewayBranchChange(
                          branch.edgeId,
                          event.target.value === 'ALWAYS'
                            ? null
                            : {
                                source: availableRunVariables[0] ?? '',
                                operator: 'EQUALS',
                                expected: '',
                              },
                        )
                      }}
                    >
                      <option value="ALWAYS">无条件执行</option>
                      <option value="CONDITIONAL">满足条件时执行</option>
                    </select>
                  </label>
                  {hasCondition ? (
                    <>
                      <label className="field compact-field">
                        <span>判断变量</span>
                        <select
                          value={branch.condition?.source ?? ''}
                          onChange={(event) => onGatewayBranchChange(branch.edgeId, {
                            source: event.target.value,
                            operator: branch.condition?.operator ?? 'EQUALS',
                            expected: branch.condition?.expected ?? '',
                          })}
                        >
                          <option value="">请选择变量</option>
                          {availableRunVariables.map((variable) => (
                            <option value={variable} key={variable}>{variable}</option>
                          ))}
                        </select>
                      </label>
                      <label className="field compact-field">
                        <span>比较方式</span>
                        <select
                          value={branch.condition?.operator ?? 'EQUALS'}
                          onChange={(event) => onGatewayBranchChange(branch.edgeId, {
                            source: branch.condition?.source ?? '',
                            operator: event.target.value as Condition['operator'],
                            expected: branch.condition?.expected ?? '',
                          })}
                        >
                          <option value="EQUALS">等于</option>
                          <option value="NOT_EQUALS">不等于</option>
                          <option value="CONTAINS">包含</option>
                          <option value="EXISTS">存在</option>
                          <option value="LESS_THAN">小于</option>
                        </select>
                      </label>
                      {branch.condition?.operator !== 'EXISTS' && (
                        <label className="field compact-field">
                          <span>期望值</span>
                          <input
                            value={branch.condition?.expected ?? ''}
                            onChange={(event) => onGatewayBranchChange(branch.edgeId, {
                              source: branch.condition?.source ?? '',
                              operator: branch.condition?.operator ?? 'EQUALS',
                              expected: event.target.value,
                            })}
                            placeholder="例如：admin, true, 200"
                          />
                        </label>
                      )}
                    </>
                  ) : (
                    <small className="parallel-branch-hint">该分支始终执行</small>
                  )}
                </div>
              )
            })}
          </div>
          <div className="parallel-help">
            <strong>并行执行说明</strong>
            <span>满足条件的分支将同时执行，提高流程执行效率。</span>
            <span>无条件的分支始终执行；有条件分支仅在条件满足时执行。</span>
          </div>
        </div>
      </aside>
    )
  }

  return (
    <aside className="inspector panel">
      <div className="panel-title">
        <div>
          <strong>节点配置</strong>
          <span>{data.endpoint?.method} {data.endpoint?.path}</span>
        </div>
        <button className="icon-button danger" onClick={onDelete} title="删除节点">
          <Trash2 size={17} />
        </button>
      </div>
      <div className="inspector-tabs">
        <button className={tab === 'request' ? 'active' : ''} onClick={() => setTab('request')}>请求</button>
        <button className={tab === 'extract' ? 'active' : ''} onClick={() => setTab('extract')}>提取</button>
        <button className={tab === 'assert' ? 'active' : ''} onClick={() => setTab('assert')}>断言</button>
      </div>

      <div className="inspector-content">
        {tab === 'request' ? (
          <>
            <label className="field">
              <span>步骤名称</span>
              <input
                key={`${definition.id}-name`}
                defaultValue={definition.name}
                onBlur={(event) => patch({ name: event.target.value })}
              />
            </label>
            <div className="endpoint-summary">
              <span className={`method method-${data.endpoint?.method.toLowerCase()}`}>{data.endpoint?.method}</span>
              <code>{data.endpoint?.path}</code>
            </div>
            <div className="request-tabs">
              <button className={requestTab === 'params' ? 'active' : ''} onClick={() => setRequestTab('params')}>
                Params
              </button>
              <button className={requestTab === 'body' ? 'active' : ''} onClick={() => setRequestTab('body')}>
                Body{data.endpoint?.requestBodySchema ? <span className="tab-count">1</span> : null}
              </button>
              <button className={requestTab === 'headers' ? 'active' : ''} onClick={() => setRequestTab('headers')}>
                Headers
              </button>
              <button className={requestTab === 'auth' ? 'active' : ''} onClick={() => setRequestTab('auth')}>
                Auth
              </button>
            </div>

            {requestTab === 'params' ? (
              <div className="request-section">
                <label className="field">
                  <span>Path 参数 JSON</span>
                  <textarea
                    key={`${definition.id}-path`}
                    rows={5}
                    defaultValue={mapText(definition.request.path)}
                    placeholder={'{"id":"${run.orderId}"}'}
                    onBlur={(event) => patchRequest({ path: parseMap(event.target.value) })}
                  />
                </label>
                <label className="field">
                  <span>Query 参数 JSON</span>
                  <textarea
                    key={`${definition.id}-query`}
                    rows={5}
                    defaultValue={mapText(definition.request.query)}
                    placeholder={'{"page":"1"}'}
                    onBlur={(event) => patchRequest({ query: parseMap(event.target.value) })}
                  />
                </label>
              </div>
            ) : null}

            {requestTab === 'body' ? (
              <div className="request-section">
                <div className="body-type-tabs">
                  {([
                    ['NONE', 'none'],
                    ['FORM_URLENCODED', 'x-www-form-urlencoded'],
                    ['JSON', 'JSON'],
                    ['TEXT', 'Text'],
                  ] as const).map(([value, label]) => (
                    <button
                      key={value}
                      className={definition.request.bodyType === value ? 'active' : ''}
                      onClick={() => patchRequest({ bodyType: value })}
                    >
                      {label}
                    </button>
                  ))}
                </div>
                {definition.request.bodyType === 'JSON' ? (
                  <label className="field body-editor">
                    <span>JSON 请求体</span>
                    <textarea
                      key={`${definition.id}-json-body`}
                      className="code-input"
                      rows={14}
                      defaultValue={definition.request.body}
                      placeholder={'{\n  "username": "admin",\n  "password": "password"\n}'}
                      onBlur={(event) => patchRequest({ body: event.target.value })}
                    />
                    <small>支持变量，例如 <code>{'${run.token}'}</code>、<code>{'${env.tenantId}'}</code></small>
                  </label>
                ) : null}
                {definition.request.bodyType === 'FORM_URLENCODED' ? (
                  <label className="field body-editor">
                    <span>表单字段 JSON</span>
                    <textarea
                      key={`${definition.id}-form-body`}
                      className="code-input"
                      rows={12}
                      defaultValue={mapText(definition.request.form)}
                      placeholder={'{\n  "username": "admin",\n  "password": "password"\n}'}
                      onBlur={(event) => patchRequest({ form: parseMap(event.target.value) })}
                    />
                  </label>
                ) : null}
                {definition.request.bodyType === 'TEXT' ? (
                  <label className="field body-editor">
                    <span>文本请求体</span>
                    <textarea
                      key={`${definition.id}-text-body`}
                      className="code-input"
                      rows={14}
                      defaultValue={definition.request.body}
                      onBlur={(event) => patchRequest({ body: event.target.value })}
                    />
                  </label>
                ) : null}
                {definition.request.bodyType === 'NONE' ? (
                  <div className="body-empty">该请求不发送 Body</div>
                ) : null}
              </div>
            ) : null}

            {requestTab === 'headers' ? (
              <div className="request-section">
                <label className="field">
                  <span>请求头 JSON</span>
                  <textarea
                    key={`${definition.id}-headers-${JSON.stringify(definition.request.headers)}`}
                    rows={12}
                    defaultValue={mapText(definition.request.headers)}
                    placeholder={'{\n  "token": "${run.token}"\n}'}
                    onBlur={(event) => patchRequest({ headers: parseMap(event.target.value) })}
                  />
                </label>
                <div className="header-presets">
                  <button
                    type="button"
                    className="secondary-button compact"
                    onClick={() => patchRequest({
                      headers: { ...definition.request.headers, token: '${run.token}' },
                    })}
                  >
                    添加 token Header
                  </button>
                  <button
                    type="button"
                    className="secondary-button compact"
                    onClick={() => patchRequest({
                      headers: {
                        ...definition.request.headers,
                        Authorization: 'Bearer ${run.token}',
                      },
                    })}
                  >
                    添加 Bearer Header
                  </button>
                </div>
                <small className="field-help">
                  登录节点提取变量名为 <code>token</code> 后，后续节点使用 <code>{'${run.token}'}</code> 引用。
                </small>
              </div>
            ) : null}

            {requestTab === 'auth' ? (
              <div className="request-section">
                <label className="field">
                  <span>认证方式</span>
                  <select
                    value={definition.request.authenticationType}
                    onChange={(event) => patchRequest({
                      authenticationType: event.target.value as FlowNodeDefinition['request']['authenticationType'],
                    })}
                  >
                    <option value="NONE">无</option>
                    <option value="BEARER">Bearer Token</option>
                    <option value="BASIC">Basic</option>
                    <option value="API_KEY">API Key</option>
                    <option value="COOKIE">Cookie</option>
                  </select>
                </label>
                {definition.request.authenticationType !== 'NONE' ? (
                  <>
                    {availableRunVariables.length ? (
                      <label className="field">
                        <span>引用前置节点变量</span>
                        <select
                          value=""
                          onChange={(event) => {
                            if (event.target.value) {
                              patchRequest({ authenticationValue: `\${run.${event.target.value}}` })
                            }
                          }}
                        >
                          <option value="">请选择变量</option>
                          {availableRunVariables.map((variable) => (
                            <option value={variable} key={variable}>
                              {variable}（{`\${run.${variable}}`}）
                            </option>
                          ))}
                        </select>
                      </label>
                    ) : null}
                    <label className="field">
                      <span>认证值</span>
                      <input
                        key={`${definition.id}-authentication-value-${definition.request.authenticationValue}`}
                        defaultValue={definition.request.authenticationValue}
                        placeholder="${run.token}"
                        onBlur={(event) => patchRequest({ authenticationValue: event.target.value })}
                      />
                      {availableRunVariables.length ? (
                        <small className="field-help">
                          可用变量：{availableRunVariables.map((variable) => `\${run.${variable}}`).join('、')}
                        </small>
                      ) : null}
                    </label>
                  </>
                ) : null}
              </div>
            ) : null}

            <label className="field timeout-field">
              <span>请求超时 ms</span>
              <input
                key={`${definition.id}-timeout`}
                type="number"
                defaultValue={definition.request.timeoutMs}
                onBlur={(event) => patchRequest({ timeoutMs: Number(event.target.value) })}
              />
            </label>
          </>
        ) : null}

        {tab === 'extract' ? (
          <div className="repeat-list">
            {definition.extractors.map((extractor, index) => (
              <div className="repeat-item" key={`${definition.id}-extractor-${index}`}>
                <div className="repeat-heading">
                  <strong>变量 {index + 1}</strong>
                  <button
                    className="icon-button"
                    onClick={() => patch({ extractors: definition.extractors.filter((_, i) => i !== index) })}
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
                <label className="field">
                  <span>变量名</span>
                  <input
                    defaultValue={extractor.variable}
                    onBlur={(event) => updateExtractor(index, { variable: event.target.value.trim() })}
                  />
                  {extractor.variable ? (
                    <small className="field-help">
                      后续节点引用：<code>{`\${run.${extractor.variable}}`}</code>
                    </small>
                  ) : null}
                </label>
                <div className="field-grid">
                  <label className="field">
                    <span>来源</span>
                    <select value={extractor.source} onChange={(event) => updateExtractor(index, { source: event.target.value as Extractor['source'] })}>
                      <option value="JSON_PATH">JSONPath</option>
                      <option value="HEADER">Header</option>
                      <option value="COOKIE">Cookie</option>
                      <option value="STATUS">状态码</option>
                    </select>
                  </label>
                  <label className="field">
                    <span>表达式</span>
                    <input
                      defaultValue={extractor.expression}
                      placeholder="$.data.id"
                      onBlur={(event) => updateExtractor(index, { expression: event.target.value })}
                    />
                  </label>
                </div>
              </div>
            ))}
            <button
              className="add-row"
              onClick={() => patch({ extractors: [...definition.extractors, { variable: '', source: 'JSON_PATH', expression: '$.data.id' }] })}
            >
              <Plus size={15} /> 添加提取变量
            </button>
          </div>
        ) : null}

        {tab === 'assert' ? (
          <div className="repeat-list">
            {definition.assertions.map((assertion, index) => (
              <div className="repeat-item" key={`${definition.id}-assertion-${index}`}>
                <div className="repeat-heading">
                  <strong>断言 {index + 1}</strong>
                  <button
                    className="icon-button"
                    onClick={() => patch({ assertions: definition.assertions.filter((_, i) => i !== index) })}
                  >
                    <Trash2 size={15} />
                  </button>
                </div>
                <div className="field-grid">
                  <label className="field">
                    <span>类型</span>
                    <select value={assertion.type} onChange={(event) => updateAssertion(index, { type: event.target.value as Assertion['type'] })}>
                      <option value="STATUS">状态码</option>
                      <option value="JSON_PATH">JSONPath</option>
                      <option value="HEADER">Header</option>
                      <option value="RESPONSE_TIME">响应时间</option>
                    </select>
                  </label>
                  <label className="field">
                    <span>比较</span>
                    <select value={assertion.operator} onChange={(event) => updateAssertion(index, { operator: event.target.value as Assertion['operator'] })}>
                      <option value="EQUALS">等于</option>
                      <option value="NOT_EQUALS">不等于</option>
                      <option value="CONTAINS">包含</option>
                      <option value="EXISTS">存在</option>
                      <option value="LESS_THAN">小于</option>
                    </select>
                  </label>
                </div>
                {assertion.type !== 'STATUS' && assertion.type !== 'RESPONSE_TIME' ? (
                  <label className="field">
                    <span>表达式</span>
                    <input
                      defaultValue={assertion.expression}
                      placeholder="$.code"
                      onBlur={(event) => updateAssertion(index, { expression: event.target.value })}
                    />
                  </label>
                ) : null}
                <label className="field">
                  <span>期望值</span>
                  <input
                    defaultValue={assertion.expected}
                    onBlur={(event) => updateAssertion(index, { expected: event.target.value })}
                  />
                </label>
              </div>
            ))}
            <button
              className="add-row"
              onClick={() => patch({ assertions: [...definition.assertions, { type: 'STATUS', expression: '', operator: 'EQUALS', expected: '200' }] })}
            >
              <Plus size={15} /> 添加断言
            </button>
          </div>
        ) : null}
      </div>
    </aside>
  )
}
