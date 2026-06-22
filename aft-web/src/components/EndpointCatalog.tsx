import { ChevronDown, ChevronRight, FileJson, Folder as FolderIcon, FolderPlus, Search, Trash2, X } from 'lucide-react'
import { useDeferredValue, useMemo, useRef, useState } from 'react'
import type { DragEvent } from 'react'
import type { EndpointDefinition, Folder } from '../types'

type Props = {
  endpoints: EndpointDefinition[]
  folders: Folder[]
  onCreateFolder: (name: string, parentId?: string) => void
  onDeleteFolder: (id: string) => void
  onMoveEndpoints: (folderId: string, endpointIds: string[]) => void
  onMoveFolder: (folderId: string, newParentId: string | null, sortOrder: number) => void
  onRefresh: () => void
}

const methodClass = (method: string) => `method method-${method.toLowerCase()}`

const searchableText = (endpoint: EndpointDefinition) => {
  const pathWithoutLeadingSlash = endpoint.path.replace(/^\/+/, '')
  return [
    endpoint.method,
    endpoint.path,
    pathWithoutLeadingSlash,
    endpoint.summary ?? '',
    endpoint.operationId ?? '',
    ...endpoint.tags,
  ].join(' ').toLowerCase()
}

type FolderNode = {
  folder: Folder
  children: FolderNode[]
  endpoints: EndpointDefinition[]
}

function buildTree(folders: Folder[], endpoints: EndpointDefinition[]): { roots: FolderNode[]; ungrouped: EndpointDefinition[] } {
  const nodeMap = new Map<string, FolderNode>()
  for (const folder of folders) {
    nodeMap.set(folder.id, { folder, children: [], endpoints: [] })
  }
  const roots: FolderNode[] = []
  for (const folder of folders) {
    const node = nodeMap.get(folder.id)!
    if (folder.parentId && nodeMap.has(folder.parentId)) {
      nodeMap.get(folder.parentId)!.children.push(node)
    } else {
      roots.push(node)
    }
  }
  const ungrouped: EndpointDefinition[] = []
  for (const endpoint of endpoints) {
    if (!endpoint.active) continue
    if (endpoint.folderId && nodeMap.has(endpoint.folderId)) {
      nodeMap.get(endpoint.folderId)!.endpoints.push(endpoint)
    } else {
      ungrouped.push(endpoint)
    }
  }
  return { roots, ungrouped }
}

function countEndpoints(node: FolderNode): number {
  let count = node.endpoints.length
  for (const child of node.children) {
    count += countEndpoints(child)
  }
  return count
}

export function EndpointCatalog({
  endpoints,
  folders,
  onCreateFolder,
  onDeleteFolder,
  onMoveEndpoints,
  onMoveFolder,
  onRefresh,
}: Props) {
  const [query, setQuery] = useState('')
  const deferredQuery = useDeferredValue(query.trim().toLowerCase())
  const [collapsedFolders, setCollapsedFolders] = useState<Set<string>>(new Set())
  const [selectedEndpoints, setSelectedEndpoints] = useState<Set<string>>(new Set())
  const [newFolderParentId, setNewFolderParentId] = useState<string | undefined>(undefined)
  const [newFolderName, setNewFolderName] = useState('')
  const [dragOverFolderId, setDragOverFolderId] = useState<string | null>(null)
  const [dragOverFolderIsTarget, setDragOverFolderIsTarget] = useState(false)
  const dragOverRef = useRef<{ folderId: string | null; isTarget: boolean }>({ folderId: null, isTarget: false })
  const [draggingFolderId, setDraggingFolderId] = useState<string | null>(null)

  const activeCount = useMemo(
    () => endpoints.reduce((count, endpoint) => count + Number(endpoint.active), 0),
    [endpoints],
  )

  const toggleFolder = (folderId: string) => {
    setCollapsedFolders((prev) => {
      const next = new Set(prev)
      if (next.has(folderId)) next.delete(folderId)
      else next.add(folderId)
      return next
    })
  }

  const toggleSelect = (endpointId: string) => {
    setSelectedEndpoints((prev) => {
      const next = new Set(prev)
      if (next.has(endpointId)) next.delete(endpointId)
      else next.add(endpointId)
      return next
    })
  }

  const selectAll = (endpointIds: string[]) => {
    setSelectedEndpoints((prev) => {
      const next = new Set(prev)
      const allSelected = endpointIds.every((id) => next.has(id))
      if (allSelected) {
        endpointIds.forEach((id) => next.delete(id))
      } else {
        endpointIds.forEach((id) => next.add(id))
      }
      return next
    })
  }

  const handleCreateFolder = () => {
    const name = newFolderName.trim()
    if (!name) return
    onCreateFolder(name, newFolderParentId || undefined)
    setNewFolderName('')
    setNewFolderParentId(undefined)
  }

  const onDragStart = (event: DragEvent, endpoint: EndpointDefinition) => {
    const ids = selectedEndpoints.has(endpoint.id)
      ? [...selectedEndpoints]
      : [endpoint.id]
    event.dataTransfer.setData('application/aft-endpoint', JSON.stringify(endpoint))
    event.dataTransfer.setData('application/aft-endpoint-move', JSON.stringify(ids))
    event.dataTransfer.effectAllowed = 'copy'
  }

  const updateDragState = (folderId: string | null, isTarget: boolean) => {
    if (dragOverRef.current.folderId !== folderId || dragOverRef.current.isTarget !== isTarget) {
      dragOverRef.current = { folderId, isTarget }
      setDragOverFolderId(folderId)
      setDragOverFolderIsTarget(isTarget)
    }
  }

  const { roots, ungrouped: _ungrouped } = useMemo(() => {
    const terms = deferredQuery.split(/\s+/).filter(Boolean)
    const filteredEndpoints = endpoints.filter((endpoint) => {
      if (!endpoint.active) return false
      if (terms.length === 0) return true
      const haystack = searchableText(endpoint)
      return terms.every((term) => haystack.includes(term))
    })
    return buildTree(folders, filteredEndpoints)
  }, [deferredQuery, endpoints, folders])

  const ungroupedFiltered = useMemo(() => {
    const terms = deferredQuery.split(/\s+/).filter(Boolean)
    return endpoints.filter((endpoint) => {
      if (!endpoint.active) return false
      if (terms.length > 0) {
        const haystack = searchableText(endpoint)
        if (terms.some((term) => !haystack.includes(term))) return false
      }
      if (endpoint.folderId && folders.some((f) => f.id === endpoint.folderId)) return false
      return true
    })
  }, [deferredQuery, endpoints, folders])

  const visibleCount = useMemo(() => {
    let count = ungroupedFiltered.length
    const countTree = (nodes: FolderNode[]) => {
      for (const node of nodes) {
        count += node.endpoints.length
        countTree(node.children)
      }
    }
    countTree(roots)
    return count
  }, [roots, ungroupedFiltered])

  const allFolderIds = useMemo(() => {
    const ids: string[] = []
    const collect = (nodes: FolderNode[]) => {
      for (const node of nodes) {
        ids.push(node.folder.id)
        collect(node.children)
      }
    }
    collect(roots)
    return ids
  }, [roots])

    const renderFolderNode = (node: FolderNode, depth: number) => {
    const isCollapsed = collapsedFolders.has(node.folder.id)
    const epCount = countEndpoints(node)
    const isFolderDropTarget = dragOverFolderId === node.folder.id && dragOverFolderIsTarget
    const isEndpointDropTarget = dragOverFolderId === node.folder.id && !dragOverFolderIsTarget
    return (
      <section
        className={`endpoint-group ${isEndpointDropTarget ? 'drag-over' : ''} ${isFolderDropTarget ? 'folder-drop-target' : ''}`}
        key={node.folder.id}
        onDragOver={(e) => {
          if (!draggingFolderId && e.dataTransfer.types.includes('application/aft-endpoint-move')) {
            e.preventDefault()
            e.dataTransfer.dropEffect = 'move'
            updateDragState(node.folder.id, false)
          }
        }}
        onDragLeave={(e) => {
          if (!draggingFolderId && dragOverRef.current.folderId === node.folder.id && !dragOverRef.current.isTarget) {
            const relatedTarget = e.relatedTarget as HTMLElement | null
            if (relatedTarget && e.currentTarget.contains(relatedTarget)) return
            updateDragState(null, false)
          }
        }}
        onDrop={(e) => {
          if (!draggingFolderId) {
            const raw = e.dataTransfer.getData('application/aft-endpoint-move')
            if (raw) {
              e.preventDefault()
              updateDragState(null, false)
              onMoveEndpoints(node.folder.id, JSON.parse(raw))
              setSelectedEndpoints(new Set())
            }
          }
        }}
      >
        <div
          className="group-title"
          style={{ paddingLeft: 8 + depth * 16 }}
          draggable
          onDragStart={(e) => {
            e.dataTransfer.setData('application/aft-folder', node.folder.id)
            e.dataTransfer.effectAllowed = 'move'
            setDraggingFolderId(node.folder.id)
          }}
          onDragEnd={() => setDraggingFolderId(null)}
          onDragOver={(e) => {
            e.preventDefault()
            e.stopPropagation()
            e.dataTransfer.dropEffect = 'move'
            const isFolder = e.dataTransfer.types.includes('application/aft-folder')
            updateDragState(node.folder.id, isFolder)
          }}
          onDragLeave={(e) => {
            const relatedTarget = e.relatedTarget as HTMLElement | null
            if (relatedTarget && e.currentTarget.contains(relatedTarget)) return
            if (dragOverRef.current.folderId === node.folder.id) {
              updateDragState(null, false)
            }
          }}
          onDrop={(e) => {
            e.preventDefault()
            e.stopPropagation()
            updateDragState(null, false)
            const folderRaw = e.dataTransfer.getData('application/aft-folder')
            if (folderRaw && folderRaw !== node.folder.id) {
              onMoveFolder(folderRaw, node.folder.id, node.folder.sortOrder)
              return
            }
            const raw = e.dataTransfer.getData('application/aft-endpoint-move')
            if (raw) {
              onMoveEndpoints(node.folder.id, JSON.parse(raw))
              setSelectedEndpoints(new Set())
            }
          }}
        >
          <button className="group-toggle" onClick={() => toggleFolder(node.folder.id)}>
            {isCollapsed ? <ChevronRight size={15} /> : <ChevronDown size={15} />}
          </button>
          <FolderIcon size={15} />
          <strong>{node.folder.name}</strong>
          <span>{epCount}</span>
          <button
            className="icon-button"
            style={{ marginLeft: 'auto' }}
            title="在此文件夹下创建子文件夹"
            onClick={() => setNewFolderParentId(node.folder.id)}
          >
            <FolderPlus size={13} />
          </button>
          <button
            className="icon-button"
            title="选择全部"
            onClick={() => {
              const ids: string[] = []
              const collect = (n: FolderNode) => {
                ids.push(...n.endpoints.map((e) => e.id))
                n.children.forEach(collect)
              }
              collect(node)
              selectAll(ids)
            }}
          >
            <span style={{ fontSize: 10 }}>全选</span>
          </button>
          <button
            className="icon-button danger"
            title="删除文件夹"
            onClick={() => onDeleteFolder(node.folder.id)}
          >
            <Trash2 size={14} />
          </button>
        </div>
        {!isCollapsed && (
          <>
            {node.children.map((child) => renderFolderNode(child, depth + 1))}
            {node.endpoints.map((endpoint) => (
              <button
                draggable={!draggingFolderId}
                className={`endpoint-row ${selectedEndpoints.has(endpoint.id) ? 'selected' : ''}`}
                key={endpoint.id}
                onClick={() => toggleSelect(endpoint.id)}
                onDragStart={draggingFolderId ? undefined : (event) => onDragStart(event, endpoint)}
                title={endpoint.summary ?? endpoint.path}
                style={{ paddingLeft: 24 + depth * 16 }}
              >
                <input
                  type="checkbox"
                  className="endpoint-checkbox"
                  checked={selectedEndpoints.has(endpoint.id)}
                  onChange={() => toggleSelect(endpoint.id)}
                  onClick={(e) => e.stopPropagation()}
                />
                <span className={methodClass(endpoint.method)}>{endpoint.method}</span>
                <span className="endpoint-path">{endpoint.path}</span>
              </button>
            ))}
          </>
        )}
      </section>
    )
  }

  return (
    <aside className="catalog panel">
      <div className="panel-title">
        <div>
          <strong>接口目录</strong>
          <span>{activeCount} 个接口</span>
        </div>
        <div style={{ display: 'flex', gap: 4 }}>
          <button className="icon-button" onClick={() => setNewFolderParentId('')} title="新建文件夹">
            <FolderPlus size={17} />
          </button>
        </div>
      </div>

      {newFolderParentId !== undefined && (
        <div className="new-folder-row">
          <input
            value={newFolderName}
            onChange={(e) => setNewFolderName(e.target.value)}
            placeholder={newFolderParentId ? "子文件夹名称" : "文件夹名称"}
            autoFocus
            onKeyDown={(e) => {
              if (e.key === 'Enter') handleCreateFolder()
              if (e.key === 'Escape') { setNewFolderParentId(undefined); setNewFolderName('') }
            }}
          />
          <button className="text-button" onClick={handleCreateFolder}>创建</button>
          <button className="text-button" onClick={() => { setNewFolderParentId(undefined); setNewFolderName('') }}>取消</button>
        </div>
      )}

      {selectedEndpoints.size > 0 && (
        <div className="batch-bar">
          <span>已选 {selectedEndpoints.size} 个接口</span>
          <select
            className="batch-move-select"
            value=""
            onChange={(e) => {
              const val = e.target.value
              onMoveEndpoints(val === '__ungrouped' ? '' : val, [...selectedEndpoints])
              setSelectedEndpoints(new Set())
            }}
          >
            <option value="" disabled>移动到…</option>
            <option value="__ungrouped">未分组</option>
            {folders.map((f) => (
              <option key={f.id} value={f.id}>{f.name}</option>
            ))}
          </select>
          <button className="text-button" onClick={() => setSelectedEndpoints(new Set())}>取消选择</button>
        </div>
      )}

      <label className="search-field">
        <Search size={16} />
        <input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          placeholder="搜索路径、方法或名称"
          aria-label="搜索接口路径、方法或名称"
        />
        {query ? (
          <button
            type="button"
            className="search-clear"
            onClick={() => setQuery('')}
            title="清空搜索"
            aria-label="清空搜索"
          >
            <X size={14} />
          </button>
        ) : null}
      </label>
      <div className="search-meta">
        {deferredQuery ? `找到 ${visibleCount} 个接口` : `按接口路径、方法、名称搜索`}
      </div>
      <div
        className="endpoint-groups"
        onDragOver={(e) => {
          if (e.dataTransfer.types.includes('application/aft-folder')) {
            e.preventDefault()
            e.dataTransfer.dropEffect = 'move'
            updateDragState('__root__', true)
          }
        }}
        onDragLeave={(e) => {
          if (dragOverRef.current.folderId === '__root__' && !e.currentTarget.contains(e.relatedTarget as Node)) {
            updateDragState(null, false)
          }
        }}
        onDrop={(e) => {
          if (dragOverRef.current.folderId === '__root__') {
            e.preventDefault()
            updateDragState(null, false)
            const folderRaw = e.dataTransfer.getData('application/aft-folder')
            if (folderRaw) {
              onMoveFolder(folderRaw, null, 0)
            }
          }
        }}
      >
        {dragOverFolderId === '__root__' && (
          <div className="root-drop-hint">释放以移到顶层</div>
        )}
        {roots.map((node: FolderNode) => renderFolderNode(node, 0))}
        {ungroupedFiltered.length > 0 && (
          <section className="endpoint-group" key="__ungrouped">
            <div className="group-title">
              <button className="group-toggle" onClick={() => toggleFolder('__ungrouped')}>
                {collapsedFolders.has('__ungrouped') ? <ChevronRight size={15} /> : <ChevronDown size={15} />}
              </button>
              <FileJson size={15} />
              <strong>未分组</strong>
              <span>{ungroupedFiltered.length}</span>
              <button
                className="icon-button"
                style={{ marginLeft: 'auto' }}
                title="选择全部"
                onClick={() => selectAll(ungroupedFiltered.map((e) => e.id))}
              >
                <span style={{ fontSize: 10 }}>全选</span>
              </button>
            </div>
            {!collapsedFolders.has('__ungrouped') && ungroupedFiltered.map((endpoint) => (
              <button
                draggable={!draggingFolderId}
                className={`endpoint-row ${selectedEndpoints.has(endpoint.id) ? 'selected' : ''}`}
                key={endpoint.id}
                onClick={() => toggleSelect(endpoint.id)}
                onDragStart={draggingFolderId ? undefined : (event) => onDragStart(event, endpoint)}
                title={endpoint.summary ?? endpoint.path}
              >
                <input
                  type="checkbox"
                  className="endpoint-checkbox"
                  checked={selectedEndpoints.has(endpoint.id)}
                  onChange={() => toggleSelect(endpoint.id)}
                  onClick={(e) => e.stopPropagation()}
                />
                <span className={methodClass(endpoint.method)}>{endpoint.method}</span>
                <span className="endpoint-path">{endpoint.path}</span>
              </button>
            ))}
          </section>
        )}
        {roots.length === 0 && ungroupedFiltered.length === 0 ? (
          <div className="empty-list">
            {deferredQuery ? <Search size={28} /> : <FileJson size={28} />}
            <strong>{deferredQuery ? '没有匹配的接口' : '暂无接口'}</strong>
            <span>
              {deferredQuery ? '尝试输入路径片段，例如 /system/auth/login' : '导入 OpenAPI URL 或文件后开始编排'}
            </span>
          </div>
        ) : null}
      </div>
    </aside>
  )
}
