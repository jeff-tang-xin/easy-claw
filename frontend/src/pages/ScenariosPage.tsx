import {useEffect, useState} from 'react';
import {del, getJson, postJson, putJson} from '../api';
import Modal from '../components/Modal';

interface Scenario {
  id: number;
  name: string;
  displayName: string;
  icon: string;
  description: string;
  mode: 'single' | 'team';
  systemPrompt: string;
  workflow: string | null;
  active: boolean;
  builtin: boolean;
}

interface Step {
  subagent: string;
  instruction: string;
  parallel: boolean;
}

interface WorkspaceSummary {
  workspaceId: string;
  name: string;
}

const emptyScenario = (): Scenario => ({
  id: 0, name: '', displayName: '', icon: '🎬', description: '',
  mode: 'single', systemPrompt: '', workflow: null, active: true, builtin: false,
});

const parseSteps = (workflow: string | null): Step[] => {
  if (!workflow) return [];
  try {
    const parsed = JSON.parse(workflow);
    if (Array.isArray(parsed.steps)) {
      return parsed.steps.map((s: Partial<Step>) => ({
        subagent: s.subagent || '',
        instruction: s.instruction || '',
        parallel: !!s.parallel,
      }));
    }
  } catch {
    // ignore
  }
  return [];
};

export default function ScenariosPage() {
  const [items, setItems] = useState<Scenario[]>([]);
  const [workspaces, setWorkspaces] = useState<WorkspaceSummary[]>([]);
  const [subagentNames, setSubagentNames] = useState<string[]>([]);
  const [activeMap, setActiveMap] = useState<Record<string, number>>({});
  const [selectedWs, setSelectedWs] = useState('');
  const [editing, setEditing] = useState<Scenario | null>(null);
  const [steps, setSteps] = useState<Step[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = async () => {
    try {
      const [sc, ws, subs] = await Promise.all([
        getJson<Scenario[]>('/api/scenarios'),
        getJson<WorkspaceSummary[]>('/api/workspaces'),
        getJson<{ name: string }[]>('/api/scenarios/subagents'),
      ]);
      setItems(sc);
      setWorkspaces(ws);
      setSubagentNames(subs.map((s) => s.name));
      const act: Record<string, number> = {};
      await Promise.all(ws.map(async (w) => {
        const s = await getJson<Scenario | null>(`/api/scenarios/active/${w.workspaceId}`);
        if (s) act[w.workspaceId] = s.id;
      }));
      setActiveMap(act);
      setSelectedWs((cur) => cur || ws[0]?.workspaceId || '');
    } catch (e) {
      setError(String(e));
    }
  };
  useEffect(() => { load(); }, []);

  const flash = (msg: string) => {
    setNotice(msg);
    setTimeout(() => setNotice(''), 3000);
  };

  const openCreate = () => {
    setEditing(emptyScenario());
    setSteps([]);
  };

  const openEdit = (s: Scenario) => {
    setEditing({...s});
    setSteps(parseSteps(s.workflow));
  };

  const save = async () => {
    if (!editing) return;
    const body = {
      ...editing,
      workflow: editing.mode === 'team' && steps.length
        ? JSON.stringify({steps})
        : null,
    };
    try {
      if (editing.id) await putJson(`/api/scenarios/${editing.id}`, body);
      else await postJson('/api/scenarios', body);
      setEditing(null);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const remove = async (id: number) => {
    if (!confirm('删除该场景？已激活该场景的工作区将自动回到默认模式。')) return;
    try {
      await del(`/api/scenarios/${id}`);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const activate = async (s: Scenario) => {
    if (!selectedWs) return;
    try {
      await postJson(`/api/scenarios/${s.id}/activate/${selectedWs}`, {});
      flash(`已在「${workspaces.find((w) => w.workspaceId === selectedWs)?.name}」激活场景：${s.displayName || s.name}`);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const deactivate = async () => {
    if (!selectedWs) return;
    try {
      await postJson(`/api/scenarios/deactivate/${selectedWs}`, {});
      flash('已停用场景，回到默认主智能体');
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const activeScenarioId = selectedWs ? activeMap[selectedWs] : undefined;
  const activeScenario = items.find((s) => s.id === activeScenarioId);

  return (
    <div className="page">
      <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 12, flexWrap: 'wrap'}}>
        <h1 className="page-title">🎬 场景编排</h1>
        <div style={{display: 'flex', gap: 8, alignItems: 'center'}}>
          <select value={selectedWs} onChange={(e) => setSelectedWs(e.target.value)} style={{minWidth: 180}}>
            {workspaces.map((w) => (
              <option key={w.workspaceId} value={w.workspaceId}>{w.name}</option>
            ))}
          </select>
          <button className="btn primary" onClick={openCreate}>＋ 新建场景</button>
        </div>
      </div>

      {error && <div className="error-box">{error}</div>}
      {notice && <div className="hint" style={{color: 'var(--accent, #2f6fed)'}}>{notice}</div>}

      {selectedWs && (
        <div className="card" style={{padding: '10px 14px', marginBottom: 14, display: 'flex', alignItems: 'center', gap: 10}}>
          <span className="hint">当前工作区场景：</span>
          {activeScenario ? (
            <>
              <span className="badge green">{activeScenario.icon} {activeScenario.displayName || activeScenario.name}</span>
              <span className="badge gray">{activeScenario.mode === 'team' ? '多智能体编排' : '单智能体'}</span>
              <button className="btn small" onClick={deactivate}>停用</button>
            </>
          ) : (
            <>
              <span className="badge gray">默认（未激活场景）</span>
              <span className="hint">在下方选择一个场景激活</span>
            </>
          )}
        </div>
      )}

      <div style={{display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(320px, 1fr))', gap: 14}}>
        {items.map((s) => {
          const isActive = s.id === activeScenarioId;
          return (
            <div key={s.id} className="card" style={{
              padding: 16, display: 'flex', flexDirection: 'column', gap: 8,
              borderColor: isActive ? 'var(--accent, #2f6fed)' : undefined,
            }}>
              <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start'}}>
                <div style={{fontWeight: 600, fontSize: 15}}>
                  <span style={{marginRight: 6}}>{s.icon || '🎬'}</span>{s.displayName || s.name}
                </div>
                <span className={`badge ${s.mode === 'team' ? 'blue' : 'gray'}`}>
                  {s.mode === 'team' ? '🤝 编排' : '👤 单体'}
                </span>
              </div>
              <div className="hint" style={{minHeight: 32}}>{s.description || '（无描述）'}</div>
              {s.mode === 'team' && parseSteps(s.workflow).length > 0 && (
                <div style={{fontSize: 12, color: 'var(--text-dim, #888)', lineHeight: 1.7}}>
                  {parseSteps(s.workflow).map((st, i) => (
                    <div key={i}>
                      {i + 1}. {st.parallel ? '∥ ' : '→ '}
                      <code style={{fontSize: 11}}>{st.subagent}</code>
                      {st.instruction ? `：${st.instruction.length > 26 ? st.instruction.slice(0, 26) + '…' : st.instruction}` : ''}
                    </div>
                  ))}
                </div>
              )}
              <div style={{display: 'flex', gap: 8, marginTop: 'auto', paddingTop: 6}}>
                <button
                  className={`btn small ${isActive ? '' : 'primary'}`}
                  disabled={isActive || !selectedWs}
                  onClick={() => activate(s)}
                >
                  {isActive ? '✓ 已激活' : '激活到当前工作区'}
                </button>
                <button className="btn small" onClick={() => openEdit(s)}>编辑</button>
                <button className="btn danger small" onClick={() => remove(s.id)}>删除</button>
              </div>
            </div>
          );
        })}
      </div>
      <p className="hint" style={{marginTop: 12}}>
        场景激活后立即重建该工作区的 Agent 并注入 system prompt；team 模式会按工作流阶段调度子 Agent
        （在 Skills 页「子Agent」中管理成员）。成员名单：{subagentNames.length ? subagentNames.join('、') : '（暂无全局子 Agent）'}
      </p>

      {editing && (
        <Modal
          title={editing.id ? '🎬 编辑场景' : '🎬 新建场景'}
          subtitle="single = 单智能体场景提示词；team = 多智能体编排工作流"
          onClose={() => setEditing(null)}
          width={720}
        >
          <div style={{display: 'flex', gap: 12}}>
            <div className="field" style={{width: 80}}>
              <label>图标</label>
              <input value={editing.icon} onChange={(e) => setEditing({...editing, icon: e.target.value})} />
            </div>
            <div className="field" style={{flex: 1}}>
              <label>名称（英文标识）</label>
              <input
                value={editing.name}
                disabled={!!editing.builtin && !!editing.id}
                onChange={(e) => setEditing({...editing, name: e.target.value})}
                placeholder="如：team-dev"
              />
            </div>
            <div className="field" style={{flex: 1}}>
              <label>显示名</label>
              <input value={editing.displayName} onChange={(e) => setEditing({...editing, displayName: e.target.value})} />
            </div>
            <div className="field" style={{width: 130}}>
              <label>模式</label>
              <select
                value={editing.mode}
                onChange={(e) => setEditing({...editing, mode: e.target.value as 'single' | 'team'})}
              >
                <option value="single">single 单智能体</option>
                <option value="team">team 多智能体</option>
              </select>
            </div>
          </div>
          <div className="field">
            <label>描述</label>
            <input value={editing.description} onChange={(e) => setEditing({...editing, description: e.target.value})} />
          </div>
          <div className="field">
            <label>场景系统提示词（注入主智能体 system prompt）</label>
            <textarea
              value={editing.systemPrompt || ''}
              onChange={(e) => setEditing({...editing, systemPrompt: e.target.value})}
              rows={5}
              placeholder="本场景下智能体的行为规范、人格、工作方式…"
            />
          </div>

          {editing.mode === 'team' && (
            <div className="field">
              <label>
                编排工作流步骤（按阶段顺序执行；勾选「并行」表示与上一步同时执行）
              </label>
              {steps.map((st, i) => (
                <div key={i} style={{display: 'flex', gap: 8, marginBottom: 8, alignItems: 'center'}}>
                  <span className="hint" style={{width: 22}}>{i + 1}.</span>
                  <select
                    value={st.subagent}
                    style={{width: 160}}
                    onChange={(e) => setSteps(steps.map((s, j) => j === i ? {...s, subagent: e.target.value} : s))}
                  >
                    <option value="">选择子 Agent…</option>
                    {subagentNames.map((n) => <option key={n} value={n}>{n}</option>)}
                    {st.subagent && !subagentNames.includes(st.subagent) && (
                      <option value={st.subagent}>{st.subagent}（未注册）</option>
                    )}
                  </select>
                  <input
                    style={{flex: 1}}
                    value={st.instruction}
                    placeholder="任务指令（派发给该子 Agent 的具体任务）"
                    onChange={(e) => setSteps(steps.map((s, j) => j === i ? {...s, instruction: e.target.value} : s))}
                  />
                  <label style={{display: 'flex', gap: 4, alignItems: 'center', fontSize: 12, whiteSpace: 'nowrap'}}>
                    <input
                      type="checkbox"
                      checked={st.parallel}
                      onChange={(e) => setSteps(steps.map((s, j) => j === i ? {...s, parallel: e.target.checked} : s))}
                    />
                    并行
                  </label>
                  <button className="btn small danger" onClick={() => setSteps(steps.filter((_, j) => j !== i))}>✕</button>
                </div>
              ))}
              <button className="btn small" onClick={() => setSteps([...steps, {subagent: '', instruction: '', parallel: false}])}>
                ＋ 添加步骤
              </button>
            </div>
          )}

          <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end'}}>
            <button className="btn" onClick={() => setEditing(null)}>取消</button>
            <button className="btn primary" onClick={save}>保存</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
