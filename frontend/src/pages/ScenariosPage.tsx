import {useEffect, useState} from 'react';
import {del, getJson, postJson, putJson} from '../api';
import Modal from '../components/Modal';

interface ActionMeta {
  actionId: string;
  agentType: string;
  description: string;
  pre: string[];
  post: string[];
  readOnly: boolean;
}

interface AgentMeta {
  agentType: string;
  displayName: string;
  emoji: string;
  description: string;
  className: string;
  actions: ActionMeta[];
}

interface ActionBinding {
  actionId: string;
  agentType: string;
  enabled: boolean;
}

interface Scenario {
  id: string;
  name: string;
  description: string;
  icon: string;
  intent: string;
  isPreset: boolean;
  actionBindings: ActionBinding[];
  mcpBindings: { mcpName: string; enabled: boolean }[];
  skills: string[];
  enabledAgents: string[];
  createdAt: string;
  updatedAt: string;
}

export default function ScenariosPage() {
  const [scenarios, setScenarios] = useState<Scenario[]>([]);
  const [agents, setAgents] = useState<AgentMeta[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [editId, setEditId] = useState<string | null>(null);
  const [editName, setEditName] = useState('');
  const [editDesc, setEditDesc] = useState('');
  const [editIcon, setEditIcon] = useState('🤖');
  const [editEnabledAgents, setEditEnabledAgents] = useState<string[]>([]);
    const [editActionIds, setEditActionIds] = useState<Record<string, boolean>>({});

  const [editSkills, setEditSkills] = useState<string[]>([]);
  const [expandedAgents, setExpandedAgents] = useState<Set<string>>(new Set());
  const [showModal, setShowModal] = useState(false);

  const load = async () => {
    try {
      setError('');
      const [sList, aList] = await Promise.all([
        getJson<Scenario[]>('/api/scenarios'),
        getJson<AgentMeta[]>('/api/scenarios/agents'),
      ]);
      setScenarios(sList);
      setAgents(aList);
    } catch (e) {
      setError(String(e));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); }, []);
  const buildInitialActionMap = (s: Scenario | null, enabledAgents: string[]) => {
    const map: Record<string, boolean> = {};
    const allActions = agents.flatMap((a) => a.actions);

    if (s?.actionBindings?.length) {
      for (const act of allActions) {
        map[act.actionId] = false;
      }
      for (const b of s.actionBindings) {
        map[b.actionId] = b.enabled;
      }
      return map;
    }

    const enabledSet = new Set(enabledAgents);
    for (const act of allActions) {
      map[act.actionId] = enabledSet.has(act.agentType);
    }
    return map;
  };



  const openCreate = () => {
    setEditId(null);
    setEditName('');
    setEditDesc('');
    setEditIcon('🤖');
    const defaultAgents = agents.map((a) => a.agentType).filter((t) => t !== 'mcp' && t !== 'skill');
    setEditEnabledAgents(defaultAgents);
    setEditActionIds(buildInitialActionMap(null, defaultAgents));
      setEditSkills([]);
    setExpandedAgents(new Set());
    setShowModal(true);
  };

  const openEdit = (s: Scenario) => {
    setEditId(s.id);
    setEditName(s.name);
    setEditDesc(s.description || '');
    setEditIcon(s.icon || '🤖');
    const hasBindings = s.actionBindings?.length > 0;
    const initialAgents = hasBindings
      ? Array.from(new Set(s.actionBindings.filter((b) => b.enabled).map((b) => b.agentType)))
      : (s.enabledAgents?.length ? s.enabledAgents : []);
    setEditEnabledAgents(initialAgents);
    setEditActionIds(buildInitialActionMap(hasBindings ? s : null, initialAgents));
      setEditSkills(s.skills || []);
    setExpandedAgents(new Set());
    setShowModal(true);
  };

  const closeEdit = () => {
    setShowModal(false);
    setEditId(null);
  };

  const toggleAgent = (agentType: string) => {
    const wasOn = editEnabledAgents.includes(agentType);
    const nextAgents = wasOn ? editEnabledAgents.filter((a) => a !== agentType) : [...editEnabledAgents, agentType];
    setEditEnabledAgents(nextAgents);
    setEditActionIds((prev) => {
      const next = { ...prev };
        const actions = agents.find((a) => a.agentType === agentType)?.actions || [];
        for (const act of actions) {
          next[act.actionId] = !wasOn;
        }
        return next;
    });
    /*

    );
    */

  };

  const toggleAction = (agentType: string, actionId: string) => {
    const next = { ...editActionIds, [actionId]: !editActionIds[actionId] };
    setEditActionIds(next);

    const groupActions = agents.find((a) => a.agentType === agentType)?.actions || [];
    const anyOn = groupActions.some((a) => next[a.actionId]);
    setEditEnabledAgents((prev) =>
      anyOn
        ? (prev.includes(agentType) ? prev : [...prev, agentType])
        : prev.filter((a) => a !== agentType)
    );
  };


  const toggleExpand = (agentType: string) => {
    setExpandedAgents((prev) => {
      const next = new Set(prev);
      if (next.has(agentType)) next.delete(agentType);
      else next.add(agentType);
      return next;
    });
  };

  const save = async () => {
    if (!editName.trim()) { setError('请填写场景名称'); return; }
    try {
        const actionBindings = agents
          .flatMap((a) => a.actions)
          .filter((a) => a.agentType !== 'skill')
          .map((a) => ({ actionId: a.actionId, agentType: a.agentType, enabled: !!editActionIds[a.actionId] }))
          .filter((b) => b.enabled);

      const payload = {
        name: editName,
        description: editDesc,
        icon: editIcon,
        enabledAgents: editEnabledAgents,
          actionBindings: actionBindings,
            skills: editSkills,
      };
      if (editId) {
        await putJson(`/api/scenarios/${editId}`, payload);
      } else {
        await postJson('/api/scenarios', payload);
      }
      closeEdit();
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const remove = async (id: string) => {
    const s = scenarios.find((x) => x.id === id);
    if (s?.isPreset) { setError('预设场景不可删除'); return; }
    if (!confirm('确定删除该场景？')) return;
    try {
      await del(`/api/scenarios/${id}`);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const AGENT_ORDER = ['coding', 'file', 'research', 'content', 'mail', 'interaction', 'data', 'devops', 'mcp'];

  return (
    <div className="page">
      <div style={{display: 'flex', justifyContent: 'space-between', alignItems: 'center'}}>
        <h1 className="page-title">🎯 场景管理</h1>
        <button className="btn primary" onClick={openCreate}>＋ 新建场景</button>
      </div>

      {error && <div className="error-box">{error}</div>}

      {loading ? (
        <div className="empty">加载中...</div>
      ) : (
        <div style={{display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(380px, 1fr))', gap: 14}}>
          {scenarios.map((s) => {
            const enabled = s.enabledAgents?.length
              ? s.enabledAgents
              : Array.from(new Set(s.actionBindings.filter((b) => b.enabled).map((b) => b.agentType)));
            return (
              <div key={s.id} className="card">
                <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                  <span style={{fontSize: '1.8em'}}>{s.icon || '🤖'}</span>
                  <div style={{flex: 1, minWidth: 0}}>
                    <div style={{fontWeight: 700}}>
                      {s.name}
                      {s.isPreset && <span style={{fontSize: 11, marginLeft: 6, padding: '1px 6px', borderRadius: 8, background: '#e0e7ff', color: '#4338ca'}}>预设</span>}
                    </div>
                    <div className="hint" style={{fontSize: 12}}>{s.description || '—'}</div>
                  </div>
                  {!s.isPreset && (
                    <button className="btn danger small" onClick={() => remove(s.id)} title="删除">🗑</button>
                  )}
                </div>
                <div style={{marginTop: 10, display: 'flex', flexWrap: 'wrap', gap: 4}}>
                  {enabled.map((t) => {
                    const a = agents.find((x) => x.agentType === t);
                    return (
                      <span key={t} style={{fontSize: 11, padding: '2px 8px', borderRadius: 10, background: 'var(--accent-soft)', color: 'var(--accent)'}}>
                        {a?.emoji || ''} {a?.displayName || t}
                      </span>
                    );
                  })}
                  {enabled.length === 0 && <span className="hint" style={{fontSize: 11}}>未启用任何 Action</span>}
                </div>
                  {s.skills && s.skills.length > 0 && (
                    <div style={{marginTop: 8, display: 'flex', flexWrap: 'wrap', gap: 4}}>
                      {s.skills.map((skill) => (
                        <span key={skill} style={{fontSize: 11, padding: '2px 8px', borderRadius: 10, background: 'rgba(16,185,129,0.1)', color: '#0f766e'}}>
                          📚 {skill}
                        </span>
                      ))}
                    </div>
                  )}
                <div style={{marginTop: 10, textAlign: 'right'}}>
                  <button className="btn small" onClick={() => openEdit(s)}>编辑</button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {showModal && (
        <Modal title={editId ? '🎯 编辑场景' : '🎯 新建场景'} onClose={closeEdit} width={960}>
          <div className="field">
            <label>图标</label>
            <input value={editIcon} onChange={(e) => setEditIcon(e.target.value)} style={{width: 80}} placeholder="🤖" />
          </div>
          <div className="field">
            <label>场景名称</label>
            <input value={editName} onChange={(e) => setEditName(e.target.value)} placeholder="如：编码专家" />
          </div>
          <div className="field">
            <label>描述</label>
              <div style={{display: 'flex', gap: 8, marginBottom: 8}}>
                
                <button className="btn small" style={{display:'none'}}></button>
              </div>
            <textarea value={editDesc} onChange={(e) => setEditDesc(e.target.value)} rows={2} placeholder="场景说明（可选）" />
          </div>

          <div className="field">
            <label>可用 Action 分组</label>
            <div className="hint" style={{fontSize: 11, marginTop: -4, marginBottom: 4}}>
              内置 SubAgent + MCP/REST 工具。点击卡片或开关勾选整个分组，点击 ▶ 展开可对单个 Action 做细粒度勾选
            </div>
              <div style={{display: 'flex', gap: 8, marginBottom: 8}}>
                <button className="btn small" onClick={() => { const types = agents.map(a => a.agentType).filter(t => t !== 'mcp' && t !== 'skill'); setEditEnabledAgents(types); setEditActionIds(buildInitialActionMap(null, types)); }}>全选</button>
                <button className="btn small" onClick={() => { setEditEnabledAgents([]); setEditActionIds(buildInitialActionMap(null, [])); }}>清空</button>
              </div>
            <div style={{display: 'flex', flexDirection: 'column', gap: 10, maxHeight: 460, overflowY: 'auto', padding: 8}}>
              {AGENT_ORDER.map((type) => {
                const a = agents.find((x) => x.agentType === type);
                if (!a) return null;
                const on = editEnabledAgents.includes(type);
                const expanded = expandedAgents.has(type);
                return (
                  <div key={type} style={{border: `1px solid ${on ? 'var(--accent)' : 'var(--border)'}`, borderRadius: 8, overflow: 'hidden'}}>
                    <div
                      style={{
                        display: 'flex', alignItems: 'center', gap: 10,
                        padding: '14px 18px', cursor: 'pointer', minHeight: 56,
                        background: on ? 'var(--accent-soft, #eef2ff)' : 'transparent',
                      }}
                      onClick={() => toggleAgent(type)}
                    >
                      <span style={{fontSize: 22, flexShrink: 0}}>{a.emoji}</span>
                      <div style={{flex: 1, minWidth: 0, overflow: 'hidden'}}>
                        <div style={{fontWeight: 600, fontSize: 14, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{a.displayName}</div>
                        <div className="hint" style={{fontSize: 11, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis'}}>{a.description}</div>
                      </div>
                      <label className={`toggle ${on ? 'on' : ''}`} style={{flexShrink: 0}} onClick={(e) => { e.stopPropagation(); toggleAgent(type); }}>
                        <span className="toggle-thumb" />
                      </label>
                      <button
                        className="btn small"
                        style={{padding: '2px 6px', fontSize: 11, flexShrink: 0, whiteSpace: 'nowrap'}}
                        onClick={(e) => { e.stopPropagation(); toggleExpand(type); }}
                      >
                        {expanded ? '▼' : '▶'} {a.actions.length}
                      </button>
                    </div>
                    {expanded && a.actions.length > 0 && (
                      <div style={{padding: '6px 14px 10px 48px', background: 'rgba(0,0,0,0.02)'}}>
                            <>

                        {a.actions.map((act) => (
                            <>

                          <label key={act.actionId} style={{display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, padding: '3px 0', color: 'var(--text-secondary, #64748b)', cursor: 'pointer'}}>
                            <input
                                type="checkbox"
                                checked={!!editActionIds[act.actionId]}
                                onChange={() => toggleAction(type, act.actionId)}
                              />
                              <code style={{background: 'rgba(0,0,0,0.06)', padding: '1px 5px', borderRadius: 4, fontSize: 11}}>{act.actionId}</code>
                            <span style={{marginLeft: 6}}>{act.description}</span>
                            </label>
                            
                          {null}
                            
                            </>

                        ))}
                            </>

                      </div>
                    )}
                  </div>
                );
              })}
            </div>
            <div className="hint" style={{marginTop: 6}}>
              已选 {editEnabledAgents.length} / {agents.length} 个分组
              {editEnabledAgents.includes('mcp') && !agents.find(a => a.agentType === 'mcp')?.actions?.length
                ? <span style={{marginLeft: 8, color: '#f59e0b'}}>⚠ MCP 分组暂无可绑定的 HTTP_TOOL 工具，请先到 MCP 页面创建</span>
                : null}
            </div>
          </div>

          <div className="field">
            <label>绑定 Skills</label>
            <div className="hint" style={{fontSize: 11, marginTop: -4, marginBottom: 4}}>
              推荐 LLM 优先加载的技能规范，可在 Skills 页面维护
            </div>
              <div style={{display: 'flex', gap: 8, marginBottom: 8}}>
                <button
                  className="btn small"
                  onClick={() => setEditSkills((agents.find(a => a.agentType === 'skill')?.actions || []).map(a => a.actionId))}
                >全选</button>
                <button className="btn small" onClick={() => setEditSkills([])}>清空</button>
              </div>
            <div style={{display: 'flex', flexWrap: 'wrap', gap: 6}}>
              {agents.find(a => a.agentType === 'skill')?.actions.map(skill => (
                <label key={skill.actionId} style={{
                  display: 'inline-flex', alignItems: 'center', gap: 4,
                  padding: '4px 10px', borderRadius: 14, border: '1px solid var(--border)',
                  cursor: 'pointer', background: editSkills.includes(skill.actionId) ? 'var(--accent-soft, #eef2ff)' : 'transparent',
                }}>
                  <input
                    type="checkbox"
                    checked={editSkills.includes(skill.actionId)}
                    onChange={(e) => {
                      const id = skill.actionId;
                      setEditSkills(prev => e.target.checked ? [...prev, id] : prev.filter(x => x !== id));
                    }}
                  />
                  <span style={{fontSize: 12}}>{skill.actionId}</span>
                </label>
              ))}
              {(!agents.find(a => a.agentType === 'skill') || agents.find(a => a.agentType === 'skill')?.actions.length === 0) && (
                <span className="hint">暂无可绑定 Skill</span>
              )}
            </div>
          </div>


          <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end'}}>
            <button className="btn" onClick={closeEdit}>取消</button>
            <button className="btn primary" onClick={save}>保存</button>
          </div>
        </Modal>
      )}
    </div>
  );
}
