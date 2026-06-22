import { useState } from 'react'
import { CheckCircle2, ChevronDown, Copy, Download, Eye, ShieldCheck, XCircle } from 'lucide-react'
import type { RunResult, StepResult } from '../types'

type Props = {
  result?: RunResult
  running: boolean
}

const download = (result: RunResult) => {
  const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = `aft-run-${result.id}.json`
  link.click()
  URL.revokeObjectURL(link.href)
}

const parseRequestSummary = (summary: string | null | undefined) => {
  if (!summary) return null
  try {
    return JSON.parse(summary) as { url: string; headers: Record<string, string>; body?: string }
  } catch {
    return null
  }
}

const generateCurl = (step: StepResult): string => {
  const parsed = parseRequestSummary(step.requestSummary)
  if (!parsed) return ''

  const parts = [`curl -X ${step.method}`]

  if (parsed.headers) {
    Object.entries(parsed.headers).forEach(([key, value]) => {
      parts.push(`  -H '${key}: ${value}'`)
    })
  }

  if (parsed.body) {
    parts.push(`  -d '${parsed.body}'`)
  }

  parts.push(`  '${parsed.url}'`)
  return parts.join(' \\\n')
}

const copyToClipboard = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text)
    return true
  } catch {
    return false
  }
}

const tryFormatJson = (text: string): string => {
  try {
    return JSON.stringify(JSON.parse(text), null, 2)
  } catch {
    return text
  }
}

const RequestDetailModal = ({ step, onClose }: { step: StepResult; onClose: () => void }) => {
  const parsed = parseRequestSummary(step.requestSummary)
  const curl = generateCurl(step)
  const [copied, setCopied] = useState<'curl' | 'headers' | 'body' | 'response' | null>(null)

  const handleCopy = async (type: 'curl' | 'headers' | 'body' | 'response', text: string) => {
    const success = await copyToClipboard(text)
    if (success) {
      setCopied(type)
      setTimeout(() => setCopied(null), 2000)
    }
  }

  const headersText = parsed?.headers
    ? Object.entries(parsed.headers).map(([k, v]) => `${k}: ${v}`).join('\n')
    : ''

  return (
    <div className="dialog-backdrop" onClick={onClose}>
      <div className="dialog wide" onClick={(e) => e.stopPropagation()}>
        <div className="dialog-title">
          <div>
            <strong>
              <span className={`method method-${step.method.toLowerCase()}`}>{step.method}</span>
              {' '}{step.path}
            </strong>
            <span>实际请求详情</span>
          </div>
          <button className="icon-button" onClick={onClose}>
            <XCircle size={18} />
          </button>
        </div>

        {parsed?.url && (
          <div className="request-detail-section">
            <div className="request-detail-header">
              <span>请求 URL</span>
            </div>
            <code className="curl-block">{parsed.url}</code>
          </div>
        )}

        {parsed?.headers && Object.keys(parsed.headers).length > 0 && (
          <div className="request-detail-section">
            <div className="request-detail-header">
              <span>请求头 Headers</span>
              <button className="text-button" onClick={() => handleCopy('headers', headersText)}>
                <Copy size={14} />
                {copied === 'headers' ? '已复制' : '复制'}
              </button>
            </div>
            <div className="params-block">
              {Object.entries(parsed.headers).map(([key, value]) => (
                <div className="param-row" key={key}>
                  <span className="param-label">{key}:</span>
                  <code className="param-value">{value}</code>
                </div>
              ))}
            </div>
          </div>
        )}

        {parsed?.body && (
          <div className="request-detail-section">
            <div className="request-detail-header">
              <span>请求体 Body</span>
              <button className="text-button" onClick={() => handleCopy('body', tryFormatJson(parsed.body || ''))}>
                <Copy size={14} />
                {copied === 'body' ? '已复制' : '复制'}
              </button>
            </div>
            <pre className="curl-block">{tryFormatJson(parsed.body)}</pre>
          </div>
        )}

        {step.responseSummary && (
          <div className="request-detail-section">
            <div className="request-detail-header">
              <span>响应体 Response · {step.statusCode}</span>
              <button className="text-button" onClick={() => handleCopy('response', tryFormatJson(step.responseSummary || ''))}>
                <Copy size={14} />
                {copied === 'response' ? '已复制' : '复制'}
              </button>
            </div>
            <pre className="curl-block">{tryFormatJson(step.responseSummary)}</pre>
          </div>
        )}

        <div className="request-detail-section">
          <div className="request-detail-header">
            <span>cURL 命令</span>
            <button className="text-button" onClick={() => handleCopy('curl', curl)}>
              <Copy size={14} />
              {copied === 'curl' ? '已复制' : '复制'}
            </button>
          </div>
          <pre className="curl-block">{curl || '无请求数据'}</pre>
        </div>

        <div className="dialog-actions">
          <button className="secondary-button" onClick={onClose}>关闭</button>
        </div>
      </div>
    </div>
  )
}

export function RunReport({ result, running }: Props) {
  const [detailStep, setDetailStep] = useState<StepResult | null>(null)

  const handleCopyCurl = async (step: StepResult) => {
    const curl = generateCurl(step)
    if (curl) {
      await copyToClipboard(curl)
    }
  }

  return (
    <section className="report-panel">
      <div className="report-heading">
        <div>
          <ChevronDown size={16} />
          <strong>执行报告</strong>
          {result ? <span>{result.steps.length} 个步骤 · {result.durationMs} ms</span> : null}
        </div>
        {result ? (
          <button className="text-button" onClick={() => download(result)}>
            <Download size={15} /> 导出 JSON
          </button>
        ) : null}
      </div>
      <div className="report-table-wrap">
        <table className="report-table">
          <thead>
            <tr>
              <th>步骤</th>
              <th>接口</th>
              <th>状态</th>
              <th>耗时</th>
              <th>响应状态</th>
              <th>响应摘要</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {result?.steps.map((step, index) => (
              <tr key={step.nodeId} className={step.status === 'FAILED' ? 'failed-row' : ''}>
                <td>{index + 1}</td>
                <td>
                  <span className={`method method-${step.method.toLowerCase()}`}>{step.method}</span>
                  <code>{step.path}</code>
                </td>
                <td>
                  <span className={`run-status ${step.status.toLowerCase()}`}>
                    {step.status === 'PASSED' ? <CheckCircle2 size={15} /> : <XCircle size={15} />}
                    {step.status === 'PASSED' ? '通过' : '失败'}
                  </span>
                </td>
                <td>{step.durationMs} ms</td>
                <td>{step.statusCode || '-'}</td>
                <td className="response-cell" title={step.error ?? step.responseSummary ?? ''}>
                  {step.error ?? step.responseSummary ?? '-'}
                </td>
                <td className="action-cell">
                  <button
                    className="icon-button"
                    title="查看请求详情"
                    onClick={() => setDetailStep(step)}
                  >
                    <Eye size={15} />
                  </button>
                  <button
                    className="icon-button"
                    title="复制 cURL"
                    onClick={() => handleCopyCurl(step)}
                  >
                    <Copy size={15} />
                  </button>
                </td>
              </tr>
            ))}
            {!result ? (
              <tr>
                <td colSpan={7} className="report-empty">
                  {running ? '正在执行流程…' : '运行流程后，这里会显示每个接口的请求、断言和响应摘要。'}
                </td>
              </tr>
            ) : null}
          </tbody>
        </table>
      </div>
      <div className="report-footer">
        <span className={`footer-dot ${result?.status.toLowerCase() ?? ''}`} />
        {running ? '流程执行中' : result ? `流程${result.status === 'PASSED' ? '通过' : '失败'}` : '等待运行'}
        {result ? (
          <span className="redaction-hint" title="Token、Authorization、Cookie 和密码仅在报告中脱敏">
            <ShieldCheck size={13} />
            *** 仅为报告脱敏，节点传递和实际请求使用原始值
          </span>
        ) : null}
        {result?.error ? <span className="run-error">{result.error}</span> : null}
      </div>

      {detailStep && (
        <RequestDetailModal step={detailStep} onClose={() => setDetailStep(null)} />
      )}
    </section>
  )
}
