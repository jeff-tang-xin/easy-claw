import {useEffect, useState} from 'react';
import {del, getJson, postJson, putJson} from '../api';
import Modal from '../components/Modal';

interface McpService {
  id: number;
  name: string;
  description?: string;
  transport?: string;
  url?: string;
  command?: string;
  args?: string;
  env?: string;
  cwd?: string;
  headers?: string;
  timeoutSeconds?: number;
  initTimeoutSeconds?: number;
  implementationConfig?: string;
  isConnected?: boolean;
  serverName?: string;
  serverTitle?: string;
  serverVersion?: string;
  serverInstructions?: string;
  capabilities?: string;
  availableTools?: string;
}

interface KVRow { key: string; value: string; }

interface ParamRow { name: string; in: 'path' | 'query' | 'body'; type: string; required: boolean; description: string; }

function parseJsonObj(text: string | undefined): KVRow[] {
  if (!text) return [];
  try {
    const obj = JSON.parse(text);
    return Object.entries(obj).map(([k, v]) => ({key: k, value: String(v)}));
  } catch {
    return [];
  }
}

function rowsToJson(rows: KVRow[]): string {
  const obj: Record<string, string> = {};
  rows.forEach(r => { if (r.key.trim()) obj[r.key.trim()] = r.value; });
  return Object.keys(obj).length ? JSON.stringify(obj) : '';
}

function parseJsonArr(text: string | undefined): string[] {
  if (!text) return [];
  try {
    return JSON.parse(text);
  } catch {
    return [];
  }
}

function arrToJson(arr: string[]): string {
  const filtered = arr.filter(s => s.trim());
  return filtered.length ? JSON.stringify(filtered) : '';
}

function KVEditor({rows, onChange, placeholderKey, placeholderValue}: {
  rows: KVRow[];
  onChange: (rows: KVRow[]) => void;
  placeholderKey?: string;
  placeholderValue?: string;
}) {
  const addRow = () => onChange([...rows, {key: '', value: ''}]);
  const updateRow = (i: number, k: string, v: string) => {
    const copy = [...rows]; copy[i] = {key: k, value: v}; onChange(copy);
  };
  const removeRow = (i: number) => onChange(rows.filter((_, idx) => idx !== i));

  return (
    <div>
      <div style={{display: 'flex', flexDirection: 'column', gap: 4}}>
        {rows.map((r, i) => (
          <div key={i} style={{display: 'flex', gap: 4, alignItems: 'center'}}>
            <input
              value={r.key}
              onChange={e => updateRow(i, e.target.value, r.value)}
              placeholder={placeholderKey || 'Key'}
              style={{flex: 1, padding: '4px 6px', fontSize: 12}}
            />
            <span style={{color: '#888'}}>=</span>
            <input
              value={r.value}
              onChange={e => updateRow(i, r.key, e.target.value)}
              placeholder={placeholderValue || 'Value'}
              style={{flex: 2, padding: '4px 6px', fontSize: 12}}
            />
            <button className="btn small danger" style={{padding: '2px 8px'}} onClick={() => removeRow(i)}>×</button>
          </div>
        ))}
      </div>
      <button className="btn small" style={{marginTop: 4}} onClick={addRow}>＋ 添加</button>
    </div>
  );
}

// ==================== HTTP_TOOL 配置辅助 ====================

interface HttpToolForm {
  method: string;
  url: string;
  bodyMode: string;
  params: ParamRow[];
}

function parseHttpToolConfig(json: string | undefined, fallbackUrl?: string): HttpToolForm {
  const form: HttpToolForm = {method: 'GET', url: fallbackUrl || '', bodyMode: 'json', params: []};
  if (!json) return form;
  try {
    const cfg = JSON.parse(json);
    if (cfg.method) form.method = cfg.method.toUpperCase();
    if (cfg.url) form.url = cfg.url;
    else if (cfg.urlTemplate) form.url = cfg.urlTemplate;
    if (cfg.bodyMode) form.bodyMode = cfg.bodyMode;
    if (cfg.params && typeof cfg.params === 'object') {
      form.params = Object.entries(cfg.params).map(([name, def]: [string, any]) => ({
        name,
        in: def.in || 'query',
        type: def.type || 'string',
        required: !!def.required,
        description: def.description || '',
      }));
    }
  } catch {}
  return form;
}

function buildHttpToolConfig(form: HttpToolForm): string {
  const params: Record<string, any> = {};
  form.params.forEach(p => {
    if (!p.name.trim()) return;
    const def: any = {in: p.in, type: p.type || 'string'};
    if (p.required) def.required = true;
    if (p.description) def.description = p.description;
    params[p.name.trim()] = def;
  });
  const obj: any = {method: form.method, url: form.url, bodyMode: form.bodyMode, params};
  return JSON.stringify(obj);
}

const PARAM_TYPES = ['string', 'integer', 'number', 'boolean', 'array', 'object'];
const PARAM_IN = ['query', 'path', 'body'] as const;

export default function McpPage() {
  const [items, setItems] = useState<McpService[]>([]);
  const [error, setError] = useState('');
  const [editing, setEditing] = useState<McpService | null>(null);
  const [editMode, setEditMode] = useState<'form' | 'json'>('form');
  const [jsonText, setJsonText] = useState('');
  const [httpForm, setHttpForm] = useState<HttpToolForm>({method: 'GET', url: '', bodyMode: 'json', params: []});

  const load = async () => {
    try {
      setItems(await getJson<McpService[]>('/api/mcp'));
    } catch (e) {
      setError(String(e));
    }
  };
  useEffect(() => { load(); }, []);

  const save = async () => {
    if (!editing) return;
    const toSave: McpService = {...editing};
    // HTTP_TOOL：把表单数据序列化到 implementationConfig，同时 url 字段同步
    if (transport === 'HTTP_TOOL') {
      toSave.implementationConfig = buildHttpToolConfig(httpForm);
      toSave.url = httpForm.url;
      toSave.command = ''; toSave.args = ''; toSave.env = ''; toSave.cwd = '';
    }
    try {
      if (toSave.id) await putJson(`/api/mcp/${toSave.id}`, toSave);
      else await postJson('/api/mcp', toSave);
      setEditing(null);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const remove = async (id: number) => {
    if (!confirm('删除该 MCP 服务？')) return;
    try { await del(`/api/mcp/${id}`); await load(); }
    catch (e) { setError(String(e)); }
  };

  const connect = async (id: number) => {
    try { await postJson(`/api/mcp/${id}/connect`, {}); await load(); }
    catch (e) { setError(String(e)); }
  };

  const disconnect = async (id: number) => {
    try { await postJson(`/api/mcp/${id}/disconnect`, {}); await load(); }
    catch (e) { setError(String(e)); }
  };

  // ==================== JSON 导入 ====================

  const importFromJson = async () => {
    try {
      const parsed = JSON.parse(jsonText);
      const servers: Record<string, any> = parsed.mcpServers || (typeof parsed === 'object' ? parsed : {});
      let count = 0;
      for (const [name, cfg] of Object.entries(servers)) {
        if (!cfg || typeof cfg !== 'object') continue;
        const svc: McpService = {
          id: 0,
          name,
          transport: cfg.command ? 'STDIO' : (cfg.url?.endsWith('/sse') ? 'SSE' : 'STREAMABLE_HTTP'),
          url: cfg.url || '',
          command: cfg.command || '',
          args: cfg.args ? JSON.stringify(cfg.args) : '',
          env: cfg.env ? JSON.stringify(cfg.env) : '',
          cwd: cfg.cwd || '',
          headers: cfg.headers ? JSON.stringify(cfg.headers) : '',
        };
        await postJson('/api/mcp', svc);
        count++;
      }
      setJsonText('');
      alert(`成功导入 ${count} 个 MCP 服务`);
      await load();
    } catch (e: any) {
      setError('JSON 解析失败: ' + e.message);
    }
  };

  // ==================== 编辑表单辅助 ====================

  const inferTransport = (s: McpService): string => {
    if (s.transport) return s.transport;
    if (s.implementationConfig) return 'HTTP_TOOL';
    if (s.command) return 'STDIO';
    if (s.url) return s.url.toLowerCase().endsWith('/sse') ? 'SSE' : 'STREAMABLE_HTTP';
    return 'STREAMABLE_HTTP';
  };

  const openNewForm = () => {
    setEditing({
      id: 0, name: '', description: '', transport: 'STDIO',
      command: '', args: '[]', env: '{}', cwd: '',
      url: '', headers: '{}',
    });
    setHttpForm({method: 'GET', url: '', bodyMode: 'json', params: []});
    setEditMode('form');
  };

  const openEditForm = (m: McpService) => {
    setEditing({...m});
    if (inferTransport(m) === 'HTTP_TOOL') {
      setHttpForm(parseHttpToolConfig(m.implementationConfig, m.url));
    }
    setEditMode('form');
  };

  const openImportJson = () => {
    setEditing({id: 0, name: ''});
    setEditMode('json');
    setJsonText('{\n  "mcpServers": {\n    "example": {\n      "command": "npx",\n      "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path"],\n      "env": {},\n      "cwd": null\n    }\n  }\n}');
  };

  // ==================== 渲染 ====================

  const transport = editing ? inferTransport(editing) : '';

  return (
    <div className="page">
      <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
        <h1 className="page-title">🔌 MCP 服务管理</h1>
        <div style={{display: 'flex', gap: 8}}>
          <button className="btn" onClick={openImportJson}>📦 批量导入 JSON</button>
          <button className="btn primary" onClick={openNewForm}>＋ 添加服务</button>
        </div>
      </div>
      {error && <div className="error-box">{error}</div>}

      {/* 列表 */}
      <div className="card">
        <table>
          <thead>
            <tr><th>名称</th><th>传输</th><th>服务端描述</th><th>状态</th><th></th></tr>
          </thead>
          <tbody>
            {items.map((m) => {
              const transportLabel = m.transport?.toLowerCase()
                || (m.implementationConfig ? 'http_tool' : m.command ? 'stdio' : 'http');
              const transportBadgeClass = transportLabel === 'stdio' ? 'blue'
                : transportLabel === 'http_tool' ? 'orange' : '';
              const displayName = m.serverName && m.serverName !== m.name ? `${m.name} (${m.serverName})` : m.name;
              const versionTag = m.serverVersion ? ` v${m.serverVersion}` : '';
              return (
                <tr key={m.id}>
                  <td style={{fontWeight: 600}}>
                    {displayName}
                    {versionTag && <span className="hint" style={{marginLeft: 4}}>{versionTag}</span>}
                  </td>
                  <td><span className={`badge ${transportBadgeClass}`}>{transportLabel}</span></td>
                  <td style={{maxWidth: 360}}>
                    <div
                      className="hint"
                      title={m.serverInstructions || m.description || ''}
                      style={{
                        fontSize: 12,
                        overflow: 'hidden',
                        textOverflow: 'ellipsis',
                        whiteSpace: 'nowrap',
                        maxWidth: 360,
                      }}
                    >
                      {m.serverInstructions || m.description || '—'}
                    </div>
                  </td>
                  <td>
                    {m.isConnected
                      ? <span className="badge green">已连接</span>
                      : <span className="badge gray">未连接</span>}
                  </td>
                  <td>
                    {m.isConnected
                      ? <button className="btn small" onClick={() => disconnect(m.id)}>断开</button>
                      : <button className="btn small primary" onClick={() => connect(m.id)}>连接</button>}
                    {' '}
                    <button className="btn small" onClick={() => openEditForm(m)}>编辑</button>
                    {' '}
                    <button className="btn danger small" onClick={() => remove(m.id)}>删除</button>
                  </td>
                </tr>
              );
            })}
            {items.length === 0 && (
              <tr><td colSpan={5} className="hint" style={{textAlign: 'center', padding: 24}}>
                暂无 MCP 服务。点击右上角添加，或从 JSON 批量导入。
              </td></tr>
            )}
          </tbody>
        </table>
      </div>

      {/* 编辑弹窗 */}
      {editing && (
        <Modal
          title={
            editMode === 'json' ? '📦 从 JSON 批量导入' :
              editing.id ? '🔌 编辑 MCP 服务' : '🔌 添加 MCP 服务'
          }
          onClose={() => setEditing(null)}
          width={640}
        >
          {editMode === 'json' ? (
            <div>
              <p className="hint" style={{marginTop: 0}}>
                粘贴标准 mcpServers JSON（兼容 Claude Desktop 格式），将批量创建服务。
              </p>
              <textarea
                value={jsonText}
                onChange={e => setJsonText(e.target.value)}
                style={{
                  width: '100%', minHeight: 260, fontFamily: 'monospace',
                  fontSize: 13, padding: 8, borderRadius: 6, border: '1px solid #ddd',
                  boxSizing: 'border-box',
                }}
              />
              <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 8}}>
                <button className="btn" onClick={() => setEditing(null)}>取消</button>
                <button className="btn primary" onClick={importFromJson}>导入</button>
              </div>
            </div>
          ) : (
            <div style={{display: 'flex', flexDirection: 'column', gap: 10}}>
              {/* 基础信息 */}
              <div style={{display: 'flex', gap: 10}}>
                <div className="field" style={{flex: 1}}>
                  <label>服务名 *</label>
                  <input value={editing.name} onChange={e => setEditing({...editing, name: e.target.value})}
                         placeholder="唯一标识，如 filesystem" />
                </div>
                <div className="field" style={{flex: 1}}>
                  <label>传输类型</label>
                  <select value={transport}
                          onChange={e => setEditing({...editing, transport: e.target.value})}>
                    <option value="STDIO">STDIO（本地命令）</option>
                    <option value="STREAMABLE_HTTP">Streamable HTTP（远程 MCP）</option>
                    <option value="SSE">SSE（旧版兼容）</option>
                    <option value="HTTP_TOOL">REST API 桥接（内置）</option>
                  </select>
                </div>
              </div>
              <div className="field">
                <label>备注</label>
                <input value={editing.description || ''} onChange={e => setEditing({...editing, description: e.target.value})}
                       placeholder="可选，仅用于自己识别" />
              </div>

              {/* STDIO 配置 */}
              {transport === 'STDIO' && (
                <>
                  <div className="field">
                    <label>启动命令 *</label>
                    <input value={editing.command || ''}
                           onChange={e => setEditing({...editing, command: e.target.value})}
                           placeholder="npx / uvx / python" />
                  </div>
                  <div className="field">
                    <label>命令参数 (JSON 数组)</label>
                    <textarea
                      value={editing.args || '[]'}
                      onChange={e => setEditing({...editing, args: e.target.value})}
                      style={{fontFamily: 'monospace', fontSize: 12, minHeight: 60}}
                      placeholder='["-y", "@modelcontextprotocol/server-filesystem", "/path"]'
                    />
                  </div>
                  <div className="field">
                    <label>环境变量 (JSON 对象)</label>
                    <textarea
                      value={editing.env || '{}'}
                      onChange={e => setEditing({...editing, env: e.target.value})}
                      style={{fontFamily: 'monospace', fontSize: 12, minHeight: 60}}
                      placeholder='{"NODE_ENV": "production"}'
                    />
                  </div>
                  <div className="field">
                    <label>工作目录 cwd</label>
                    <input value={editing.cwd || ''}
                           onChange={e => setEditing({...editing, cwd: e.target.value})}
                           placeholder="可选，如 D:/workspace" />
                  </div>
                </>
              )}

              {/* HTTP_TOOL 桥接配置 */}
              {transport === 'HTTP_TOOL' && (
                <>
                  <div style={{background: '#fff8e1', padding: '8px 12px', borderRadius: 6, fontSize: 12, color: '#8a6d00'}}>
                    💡 直接把 REST API 包装成 MCP 工具，Agent 可像调用 MCP tool 一样调用此接口
                  </div>
                  <div style={{display: 'flex', gap: 10}}>
                    <div className="field" style={{flex: 0, minWidth: 110}}>
                      <label>HTTP Method *</label>
                      <select value={httpForm.method}
                              onChange={e => setHttpForm({...httpForm, method: e.target.value})}>
                        {['GET','POST','PUT','PATCH','DELETE','HEAD'].map(m => <option key={m}>{m}</option>)}
                      </select>
                    </div>
                    <div className="field" style={{flex: 1}}>
                      <label>URL 模板 *</label>
                      <input value={httpForm.url}
                             onChange={e => setHttpForm({...httpForm, url: e.target.value})}
                             placeholder="https://api.example.com/weather/{city}?lang={lang}" />
                    </div>
                  </div>
                  <div className="field">
                    <label>Body 模式</label>
                    <select value={httpForm.bodyMode}
                            onChange={e => setHttpForm({...httpForm, bodyMode: e.target.value})}>
                      <option value="json">JSON</option>
                      <option value="form">form-data</option>
                      <option value="none">无 Body</option>
                    </select>
                  </div>
                  <div className="field">
                    <label>参数定义</label>
                    <div style={{fontSize: 11, color: '#888', marginBottom: 4}}>
                      <code>in</code>：path（URL 模板变量 {'{xxx}'}）/ query（?key=val）/ body（JSON body 字段）
                    </div>
                    <div style={{display: 'flex', flexDirection: 'column', gap: 4}}>
                      {httpForm.params.map((p, i) => (
                        <div key={i} style={{display: 'flex', gap: 4, alignItems: 'center', fontSize: 12}}>
                          <input value={p.name} onChange={e => {
                            const copy = [...httpForm.params]; copy[i] = {...p, name: e.target.value}; setHttpForm({...httpForm, params: copy});
                          }} placeholder="参数名" style={{flex: 1, padding: '4px 6px'}} />
                          <select value={p.in} onChange={e => {
                            const copy = [...httpForm.params]; copy[i] = {...p, in: e.target.value as any}; setHttpForm({...httpForm, params: copy});
                          }} style={{padding: '4px 2px'}}>
                            {PARAM_IN.map(v => <option key={v} value={v}>{v}</option>)}
                          </select>
                          <select value={p.type} onChange={e => {
                            const copy = [...httpForm.params]; copy[i] = {...p, type: e.target.value}; setHttpForm({...httpForm, params: copy});
                          }} style={{padding: '4px 2px'}}>
                            {PARAM_TYPES.map(t => <option key={t}>{t}</option>)}
                          </select>
                          <label style={{display: 'flex', alignItems: 'center', gap: 2, whiteSpace: 'nowrap'}}>
                            <input type="checkbox" checked={p.required} onChange={e => {
                              const copy = [...httpForm.params]; copy[i] = {...p, required: e.target.checked}; setHttpForm({...httpForm, params: copy});
                            }} /> 必填
                          </label>
                          <input value={p.description} onChange={e => {
                            const copy = [...httpForm.params]; copy[i] = {...p, description: e.target.value}; setHttpForm({...httpForm, params: copy});
                          }} placeholder="描述（给 Agent 看的）" style={{flex: 2, padding: '4px 6px'}} />
                          <button className="btn small danger" style={{padding: '2px 8px'}} onClick={() => setHttpForm({...httpForm, params: httpForm.params.filter((_, idx) => idx !== i)})}>×</button>
                        </div>
                      ))}
                    </div>
                    <button className="btn small" style={{marginTop: 4}} onClick={() => setHttpForm({...httpForm, params: [...httpForm.params, {name: '', in: 'query', type: 'string', required: false, description: ''}]})}>＋ 添加参数</button>
                  </div>
                </>
              )}

              {/* HTTP 配置（外部 MCP） */}
              {(transport === 'STREAMABLE_HTTP' || transport === 'SSE') && (
                <div className="field">
                  <label>MCP 端点 URL *</label>
                  <input value={editing.url || ''}
                         onChange={e => setEditing({...editing, url: e.target.value})}
                         placeholder={transport === 'SSE'
                           ? 'https://example.com/sse'
                           : 'https://example.com/mcp'} />
                </div>
              )}

              {/* Headers（HTTP 传输和 HTTP_TOOL 都可以有） */}
              {(transport === 'STREAMABLE_HTTP' || transport === 'SSE' || transport === 'HTTP_TOOL') && (
                <div className="field">
                  <label>请求 Headers</label>
                  <KVEditor
                    rows={parseJsonObj(editing.headers)}
                    onChange={rows => setEditing({...editing, headers: rowsToJson(rows)})}
                    placeholderKey="Authorization"
                    placeholderValue="Bearer sk-xxx"
                  />
                </div>
              )}

              {/* 超时配置 */}
              <div style={{display: 'flex', gap: 10}}>
                <div className="field" style={{flex: 1}}>
                  <label>请求超时 (秒)</label>
                  <input type="number" min={1} value={editing.timeoutSeconds ?? 30}
                         onChange={e => setEditing({...editing, timeoutSeconds: parseInt(e.target.value) || undefined})} />
                </div>
                <div className="field" style={{flex: 1}}>
                  <label>初始化超时 (秒)</label>
                  <input type="number" min={1} value={editing.initTimeoutSeconds ?? 60}
                         onChange={e => setEditing({...editing, initTimeoutSeconds: parseInt(e.target.value) || undefined})} />
                </div>
              </div>

              {/* 保存按钮 */}
              <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 4}}>
                <button className="btn" onClick={() => setEditing(null)}>取消</button>
                <button className="btn primary" onClick={save}>保存</button>
              </div>
            </div>
          )}
        </Modal>
      )}
    </div>
  );
}
