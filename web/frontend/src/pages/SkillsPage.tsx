import {useEffect, useState} from 'react';
import {del, getJson, postJson, putJson} from '../api';
import Modal from '../components/Modal';

interface SkillChild { name: string; description: string; path: string; content: string; }
interface SkillFile { scope: string; name: string; description: string; path: string; content: string; type: string; children?: SkillChild[]; }
interface WorkspaceRef { workspaceId: string; name: string; description?: string; }

const scopeLabel: Record<string, string> = {
  system: '内置',
  global: '全局',
  workspace: '工作区',
  'global-subagent': '全局子Agent',
  'workspace-subagent': '工作区子Agent',
};

export default function SkillsPage() {
  const [tab, setTab] = useState<'skills' | 'subagents'>('skills');
  const [items, setItems] = useState<SkillFile[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceRef[]>([]);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [scope, setScope] = useState('global');
  const [workspaceId, setWorkspaceId] = useState('');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [content, setContent] = useState('');
  const [skillType, setSkillType] = useState<'file' | 'dir'>('file');
  const [children, setChildren] = useState<{name: string; content: string}[]>([]);
  const [viewing, setViewing] = useState<SkillFile | null>(null);
  // 编辑态与 viewing 分离：草稿改坏了可以「取消」回到 viewing.content，不必重新拉列表
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState('');
  const [saving, setSaving] = useState(false);
  const [running, setRunning] = useState('');
  const [runOut, setRunOut] = useState<Record<string, string>>({});

  const load = async () => {
    try {
      setItems(await getJson<SkillFile[]>('/api/skills'));
    } catch (e) {
      setError(String(e));
    }
  };
  const loadWorkspaces = async () => {
    try {
      setWorkspaces(await getJson<WorkspaceRef[]>('/api/workspaces'));
    } catch { /* ignore */ }
  };
  useEffect(() => { load(); loadWorkspaces(); }, []);

  const skills = items.filter((s) => s.scope === 'global' || s.scope === 'workspace' || s.scope === 'system');
  const subagents = items.filter((s) => s.scope === 'global-subagent' || s.scope === 'workspace-subagent');

  const openCreate = (t: 'skills' | 'subagents') => {
    setTab(t);
    setScope(t === 'skills' ? 'global' : 'global-subagent');
    setWorkspaceId('');
    setName('');
    setDescription('');
    setContent('');
    setSkillType('file');
    setChildren([]);
    setCreating(true);
  };

  const create = async () => {
    if (!name.trim()) { alert('请输入名称'); return; }
    const isWs = scope === 'workspace' || scope === 'workspace-subagent';
    if (isWs && !workspaceId) { alert('请选择目标工作区'); return; }
    try {
      const body: any = { scope, name: name.trim(), description, content, type: skillType };
      if (skillType === 'dir') body.children = children;
      if (isWs) body.workspaceId = workspaceId;
      await postJson('/api/skills', body);
      setCreating(false);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const addChild = () => setChildren([...children, { name: '', content: '' }]);
  const addScript = () => setChildren([...children, {
    name: 'check.py',
    content: '"""脚本用途说明（首行会作为描述显示）。"""\nimport sys\n\n\ndef main(argv):\n    print("hello from skill script", argv[1:])\n    return 0\n\n\nsys.exit(main(sys.argv))\n',
  }]);
  const updateChild = (idx: number, field: 'name' | 'content', val: string) => {
    setChildren(children.map((c, i) => i === idx ? { ...c, [field]: val } : c));
  };
  const removeChild = (idx: number) => setChildren(children.filter((_, i) => i !== idx));

  const runScript = async (skill: SkillFile, childName: string) => {
    const script = childName.replace(/^scripts\//, '');
    const key = `${skill.name}/${script}`;
    setRunning(key);
    try {
      const r = await postJson<{ output: string }>('/api/skills/run-script', {
        skill: skill.name,
        script,
        args: [],
        workspaceId: skill.scope === 'workspace' ? workspaceId : undefined,
      });
      setRunOut({ ...runOut, [key]: r.output });
    } catch (e) {
      setRunOut({ ...runOut, [key]: `请求失败: ${String(e)}` });
    } finally {
      setRunning('');
    }
  };

  /** 打开编辑：用当前内容做草稿起点。 */
  const startEdit = () => {
    if (!viewing) return;
    setDraft(viewing.content || '');
    setEditing(true);
  };

  /**
   * 保存声明文件。后端写盘后会 rebuildAgent，故保存即生效，无需重启。
   * 保存成功后同步更新 viewing，避免关掉再打开才看到新内容。
   */
  const saveEdit = async () => {
    if (!viewing) return;
    setSaving(true);
    try {
      await putJson('/api/skills', {
        path: viewing.path,
        content: draft,
        workspaceId: viewing.scope.startsWith('workspace') ? workspaceId : undefined,
      });
      setViewing({ ...viewing, content: draft });
      setEditing(false);
      await load();
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  };

  /** 恢复内置默认：后端用 JAR 模板覆盖磁盘文件，会丢弃本地修改，故需二次确认。 */
  const resetToDefault = async () => {
    if (!viewing) return;
    if (!confirm(`确定把「${viewing.name}」恢复为内置默认版本？当前修改将被覆盖且无法撤销。`)) return;
    setSaving(true);
    try {
      const fresh = await postJson<SkillFile>('/api/skills/reset', {
        name: viewing.name,
        workspaceId: viewing.scope.startsWith('workspace') ? workspaceId : undefined,
      });
      setViewing(fresh);
      setDraft(fresh.content || '');
      setEditing(false);
      await load();
    } catch (e) {
      setError(String(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (path: string) => {    if (!confirm('删除该项？')) return;
    try {
      await del(`/api/skills?path=${encodeURIComponent(path)}`);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const list = tab === 'skills' ? skills : subagents;
  const isAgent = tab === 'subagents';

  return (
    <div className="page">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1 className="page-title">📚 Skills 与子 Agent</h1>
        <button className="btn primary" onClick={() => openCreate(tab)}>＋ 新建{isAgent ? '子 Agent' : 'Skill'}</button>
      </div>
      {error && <div className="error-box">{error}</div>}

      <div style={{ display: 'flex', gap: 8, marginBottom: 16 }}>
        <button className={`btn ${tab === 'skills' ? 'primary' : ''}`} onClick={() => setTab('skills')}>
          📚 Skills（{skills.length}）
        </button>
        <button className={`btn ${tab === 'subagents' ? 'primary' : ''}`} onClick={() => setTab('subagents')}>
          🤖 子 Agent（{subagents.length}）
        </button>
      </div>

      <div className="card">
        <table>
          <thead>
            <tr><th>名称</th><th>类型</th><th>描述</th><th>路径</th><th>操作</th></tr>
          </thead>
          <tbody>
            {list.length === 0 && (
              <tr><td colSpan={5} className="empty">暂无{isAgent ? '子 Agent' : 'Skills'}</td></tr>
            )}
            {list.map((s, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600 }}>
                  {s.type === 'dir' ? '📁 ' : s.type === 'system' ? '⚙️ ' : '📄 '}{s.name}
                </td>
                <td><span className={`badge ${s.scope === 'system' ? '' : 'blue'}`} style={s.scope === 'system' ? {background: '#e3f2fd', color: '#1565c0'} : {}}>{scopeLabel[s.scope] || s.scope}</span></td>
                <td className="hint">{s.description}{s.type === 'dir' && s.children && s.children.length > 0 && ` · ${s.children.length} 条子规则`}</td>
                <td className="hint" style={{ fontSize: 11 }}>{s.scope === 'system' ? '（数据库内置）' : s.path}</td>
                <td>
                  <button className="btn small"
                    onClick={() => { setViewing(s); setEditing(false); setDraft(''); }}>查看</button>
                  {s.scope !== 'system' && <button className="btn danger small" onClick={() => remove(s.path)}>删除</button>}
                  {s.scope === 'system' && <span className="hint" style={{fontSize: 11}}>只读</span>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {creating && (
        <Modal title={isAgent ? '🤖 新建子 Agent' : '📚 新建 Skill'} onClose={() => setCreating(false)} width={680}>
          <div style={{ display: 'flex', gap: 12, flexWrap: 'wrap' }}>
            <div className="field" style={{ flex: 1, minWidth: 180 }}>
              <label>作用域</label>
              <select value={scope} onChange={(e) => { setScope(e.target.value); setWorkspaceId(''); }}>
                {isAgent ? (
                  <>
                    <option value="global-subagent">全局（~/.easyClaw/subagents）</option>
                    <option value="workspace-subagent">工作区（.easyClaw/agent/subagents）</option>
                  </>
                ) : (
                  <>
                    <option value="global">全局（~/.easyClaw/skills）</option>
                    <option value="workspace">工作区（.easyClaw/agent/skills）</option>
                  </>
                )}
              </select>
            </div>
            {(scope === 'workspace' || scope === 'workspace-subagent') && (
              <div className="field" style={{ flex: 1, minWidth: 180 }}>
                <label>目标工作区 *</label>
                <select value={workspaceId} onChange={(e) => setWorkspaceId(e.target.value)}>
                  <option value="">— 请选择 —</option>
                  {workspaces.map(w => (
                    <option key={w.workspaceId} value={w.workspaceId}>
                      {w.name}{w.description ? ` — ${w.description}` : ''}
                    </option>
                  ))}
                </select>
              </div>
            )}
            {!isAgent && (
              <div className="field" style={{ flex: 1, minWidth: 150 }}>
                <label>结构</label>
                <select value={skillType} onChange={(e) => setSkillType(e.target.value as 'file' | 'dir')}>
                  <option value="file">📄 单文件（skill.md）</option>
                  <option value="dir">📁 目录（SKILL.md + 子规则）</option>
                </select>
              </div>
            )}
          </div>

          <div style={{ display: 'flex', gap: 12 }}>
            <div className="field" style={{ flex: 1 }}>
              <label>名称（英文标识）{skillType === 'dir' && '，即目录名'}</label>
              <input value={name} onChange={(e) => setName(e.target.value)}
                placeholder={isAgent ? '如：code-expert' : skillType === 'dir' ? '如：frontend-quality' : '如：code-review'} />
            </div>
          </div>
          <div className="field">
            <label>描述</label>
            <input value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <div className="field">
            <label>{isAgent ? '子 Agent 内容（Markdown，frontmatter 支持 role/model/steps）' : (skillType === 'dir' ? '📌 主入口内容 (SKILL.md)' : '内容（Markdown）')}</label>
            <textarea value={content} onChange={(e) => setContent(e.target.value)} rows={6}
              placeholder={isAgent
                ? '---\ndescription: 资深软件架构师\nrole: code-expert\nsteps: 12\n---\n\n你是名为 code-expert 的子智能体...'
                : skillType === 'dir'
                  ? '---\ndescription: 前端质量标准集\n---\n\n你是前端专家，遵循以下最佳实践...'
                  : '---\ndescription: ...\n---\n\n你的提示词...'} />
          </div>

          {skillType === 'dir' && (
            <div style={{ background: '#fafafa', border: '1px solid #e8e8e8', borderRadius: 8, padding: 12, marginTop: 8 }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <span style={{ fontWeight: 600, fontSize: 13 }}>📎 子文件（{children.length}）</span>
                <div style={{ display: 'flex', gap: 6 }}>
                  <button className="btn small" onClick={addChild}>＋ 子规则(.md)</button>
                  <button className="btn small" onClick={addScript}>＋ 脚本(.py)</button>
                </div>
              </div>
              {children.length === 0 && (
                <div className="hint" style={{ fontSize: 12, textAlign: 'center', padding: '12px 0' }}>
                  暂无子文件。.md 子规则按字母序追加到 SKILL.md 后注入；.py 脚本落在 scripts/ 下，供 run_skill_script 调用
                </div>
              )}
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {children.map((child, idx) => (
                  <div key={idx} style={{ background: '#fff', border: '1px solid #e0e0e0', borderRadius: 6, padding: 10 }}>
                    <div style={{ display: 'flex', gap: 8, marginBottom: 6 }}>
                      <span style={{ fontSize: 12, alignSelf: 'center' }}>
                        {child.name.endsWith('.py') ? '🐍' : '📄'}
                      </span>
                      <input
                        value={child.name}
                        onChange={(e) => updateChild(idx, 'name', e.target.value)}
                        placeholder="文件名（如 components.md 或 check.py）"
                        style={{ flex: 1 }}
                      />
                      <button className="btn danger small" onClick={() => removeChild(idx)}>删除</button>
                    </div>
                    <textarea
                      value={child.content}
                      onChange={(e) => updateChild(idx, 'content', e.target.value)}
                      rows={4}
                      placeholder={child.name.endsWith('.py')
                        ? "Python 脚本内容；沙箱内运行，可读 skill 目录与工作区源码，不可写"
                        : "子规则内容（Markdown）..."}
                      style={{ width: '100%', fontSize: 12, fontFamily: child.name.endsWith('.py') ? 'monospace' : undefined }}
                    />
                  </div>
                ))}
              </div>
            </div>
          )}

          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
            <button className="btn" onClick={() => setCreating(false)}>取消</button>
            <button className="btn primary" onClick={create}>创建</button>
          </div>
        </Modal>
      )}

      {viewing && (
        <Modal
          title={`${viewing.scope.includes('subagent') ? '🤖' : viewing.type === 'dir' ? '📁' : '📚'} ${viewing.name}`}
          onClose={() => { setViewing(null); setEditing(false); setDraft(''); }}
          width={760}
        >
          <div style={{ marginBottom: 12, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
            <span className={`badge ${viewing.scope === 'system' ? '' : 'blue'}`}
              style={viewing.scope === 'system' ? {background: '#e3f2fd', color: '#1565c0'} : {}}>
              {scopeLabel[viewing.scope] || viewing.scope}
            </span>
            <span className="badge" style={{background: '#fff3e0', color: '#e65100'}}>
              {viewing.type === 'dir' ? '目录 Skill' : viewing.type === 'system' ? '内置 Skill' : '单文件 Skill'}
            </span>
            {viewing.description && <span className="hint">{viewing.description}</span>}
          </div>
          {viewing.scope !== 'system' && (
            <div className="hint" style={{ fontSize: 11, marginBottom: 8 }}>
              路径: {viewing.path}
            </div>
          )}

          {/* 主内容：system scope 无磁盘文件（打包在 JAR 内），只能看不能改 */}
          <div style={{ marginBottom: viewing.type === 'dir' && viewing.children?.length ? 16 : 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <div className="hint" style={{ fontSize: 11 }}>
                {viewing.type === 'dir' ? '📌 主入口 (SKILL.md)' : '📄 内容'}
              </div>
              <div style={{ marginLeft: 'auto', display: 'flex', gap: 6 }}>
                {viewing.scope !== 'system' && !editing && (
                  <button className="btn small" onClick={startEdit}>✏️ 编辑</button>
                )}
                {viewing.scope === 'global-subagent' && !editing && (
                  <button className="btn small" disabled={saving} onClick={resetToDefault}>
                    ↩️ 恢复默认
                  </button>
                )}
                {editing && (
                  <>
                    <button className="btn small primary" disabled={saving} onClick={saveEdit}>
                      {saving ? '保存中…' : '💾 保存'}
                    </button>
                    <button className="btn small" disabled={saving}
                      onClick={() => { setEditing(false); setDraft(''); }}>
                      取消
                    </button>
                  </>
                )}
              </div>
            </div>
            {editing && (
              <div className="hint" style={{ fontSize: 11, marginBottom: 6, color: '#e65100' }}>
                提示：子 Agent 的迭代步数由 frontmatter 的 <code>steps:</code> 决定；
                低于全局下限（30）会被自动抬升到 30，高于则按你写的值生效。保存后立即生效。
              </div>
            )}
            {editing ? (
              <textarea
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
                spellCheck={false}
                style={{
                  width: '100%',
                  minHeight: 320,
                  boxSizing: 'border-box',
                  border: '1px solid #1565c0',
                  borderRadius: 6,
                  padding: 14,
                  fontSize: 13,
                  lineHeight: 1.6,
                  fontFamily: 'Consolas, Monaco, monospace',
                  resize: 'vertical',
                }}
              />
            ) : (
              <pre style={{
                background: '#f8f9fa',
                border: '1px solid #e0e0e0',
                borderRadius: 6,
                padding: 14,
                maxHeight: 320,
                overflow: 'auto',
                fontSize: 13,
                lineHeight: 1.6,
                whiteSpace: 'pre-wrap',
                wordBreak: 'break-word',
                margin: 0,
              }}>{viewing.content || '（空）'}</pre>
            )}
          </div>

          {/* 目录 skill 子规则列表 */}
          {viewing.type === 'dir' && viewing.children && viewing.children.length > 0 && (
            <div>
              <div className="hint" style={{ fontSize: 11, marginBottom: 6 }}>
                📎 子文件（{viewing.children.length}）——.md 按字母序注入；🐍 .py 位于 scripts/，可直接试跑
              </div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {viewing.children.map((child, idx) => {
                  const isPy = child.name.endsWith('.py');
                  const key = `${viewing.name}/${child.name.replace(/^scripts\//, '')}`;
                  return (
                  <details key={idx} style={{
                    background: '#fafafa',
                    border: '1px solid #e8e8e8',
                    borderRadius: 6,
                    padding: '4px 12px',
                  }}>
                    <summary style={{
                      cursor: 'pointer',
                      fontWeight: 600,
                      padding: '6px 0',
                      fontSize: 13,
                    }}>
                      {isPy ? '🐍' : '📄'} {child.name}
                      {child.description && <span className="hint" style={{ fontWeight: 400, marginLeft: 8 }}>— {child.description}</span>}
                    </summary>
                    {isPy && (
                      <div style={{ display: 'flex', gap: 8, alignItems: 'center', margin: '4px 0' }}>
                        <button className="btn small primary"
                          disabled={running === key}
                          onClick={() => runScript(viewing, child.name)}>
                          {running === key ? '运行中…' : '▶ 试跑'}
                        </button>
                        <span className="hint" style={{ fontSize: 11 }}>
                          无参运行，权限与 Agent 调用完全一致
                        </span>
                      </div>
                    )}
                    <pre style={{
                      background: '#fff',
                      border: '1px solid #eee',
                      borderRadius: 4,
                      padding: 10,
                      maxHeight: 200,
                      overflow: 'auto',
                      fontSize: 12,
                      lineHeight: 1.55,
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                      margin: '4px 0 8px 0',
                    }}>{child.content || '（空）'}</pre>
                    {isPy && runOut[key] !== undefined && (
                      <pre style={{
                        background: '#1e1e1e',
                        color: '#d4d4d4',
                        borderRadius: 4,
                        padding: 10,
                        maxHeight: 240,
                        overflow: 'auto',
                        fontSize: 12,
                        whiteSpace: 'pre-wrap',
                        wordBreak: 'break-word',
                        margin: '0 0 8px 0',
                      }}>{runOut[key]}</pre>
                    )}
                  </details>
                  );
                })}
              </div>
            </div>
          )}

          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
            <button className="btn" onClick={() => { setViewing(null); setEditing(false); setDraft(''); }}>关闭</button>
          </div>
        </Modal>
      )}

      <p className="hint" style={{ marginTop: 12 }}>
        {isAgent
          ? '子 Agent 的 frontmatter 支持 <code>role: 角色名</code>（按角色模型运行）、<code>model: provider:model</code>（显式指定）、<code>steps</code>；全局与工作区同名时工作区覆盖。'
          : 'Skill 是给 Agent 的操作指南（Markdown）；全局与工作区同名时工作区覆盖。'}
      </p>
    </div>
  );
}
