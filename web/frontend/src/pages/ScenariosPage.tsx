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
  /** 绑定的 skill 名（JSON 数组或逗号分隔）；"" = 清空绑定，缺省/null = 不修改 */
  skills?: string;
  /** 绑定的子 Agent 名 */
  subagents?: string;
  /** 绑定的 MCP 服务名（按服务 name，非 id） */
  mcpServices?: string;
  /** 基础能力档位：none / readonly / standard / full；"" = 未配置（继承默认） */
  capabilityTier?: string;
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

/** /api/skills 返回项（仅取绑定所需字段） */
interface SkillOption {
  scope: string;
  name: string;
  description?: string;
}

/** /api/scenarios/subagents 返回项 */
interface SubagentOption {
  name: string;
  scope: string;
}

/** /api/mcp 返回项（仅取绑定所需字段） */
interface McpOption {
  id: number;
  name: string;
  description?: string;
  isTemplate?: boolean;
}

const TIER_OPTIONS: { value: string; label: string }[] = [
  {value: '', label: '未配置（继承默认）'},
  {value: 'readonly', label: '只读 readonly'},
  {value: 'standard', label: '标准 standard'},
  {value: 'full', label: '完整 full'},
  {value: 'none', label: '禁用全部 none'},
];

const tierLabel = (raw: string | undefined): string => {
  if (!raw) return '';
  const hit = TIER_OPTIONS.find((o) => o.value === raw.trim().toLowerCase());
  return hit ? hit.label.split(' ')[0] : raw;
};

/**
 * 反解绑定字段：后端宽松存储（JSON 数组优先，逗号分隔兜底），此处对齐同一口径。
 * 与 ScenarioBinding.parseNameArray 保持一致，避免界面显示与运行时生效不一致。
 */
const parseNames = (raw: string | undefined | null): string[] => {
  if (!raw || !raw.trim()) return [];
  const text = raw.trim();
  try {
    const parsed = JSON.parse(text);
    if (Array.isArray(parsed)) {
      return parsed.filter((v): v is string => typeof v === 'string' && !!v.trim()).map((v) => v.trim());
    }
    if (typeof parsed === 'string') return parsed.trim() ? [parsed.trim()] : [];
    return [];
  } catch {
    // 非合法 JSON → 逗号分隔兜底
  }
  return text.split(',').map((s) => s.trim()).filter(Boolean);
};

/**
 * 绑定项多选列表。
 * <p>空候选集时给出明确原因提示，而不是渲染一个空白框让用户以为界面坏了。
 */
function BindingPicker({label, hint, options, selected, onToggle, empty}: {
  label: string;
  hint: string;
  options: { name: string; description?: string }[];
  selected: string[];
  onToggle: (name: string) => void;
  empty: string;
}) {
  const stale = selected.filter((n) => !options.some((o) => o.name === n));
  return (
    <div className="field">
      <label>{label} <span className="hint" style={{fontWeight: 400}}>（{selected.length ? `已选 ${selected.length} 项` : '未选 = 不限制'}）</span></label>
      <div className="hint" style={{marginBottom: 6}}>{hint}</div>
      {options.length === 0 ? (
        <div className="hint" style={{padding: '8px 10px', border: '1px solid var(--border)', borderRadius: 6}}>{empty}</div>
      ) : (
        <div style={{maxHeight: 148, overflowY: 'auto', border: '1px solid var(--border)', borderRadius: 6, padding: '4px 0'}}>
          {options.map((o) => (
            <label
              key={o.name}
              style={{display: 'flex', alignItems: 'center', gap: 8, padding: '5px 10px', fontSize: 13, cursor: 'pointer'}}
              title={o.description || o.name}
            >
              <input type="checkbox" checked={selected.includes(o.name)} onChange={() => onToggle(o.name)} />
              <span style={{flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap'}}>
                <code style={{fontSize: 12}}>{o.name}</code>
                {o.description ? <span className="hint" style={{marginLeft: 6}}>{o.description}</span> : null}
              </span>
            </label>
          ))}
        </div>
      )}
      {/* 已绑定但候选集里已不存在的名字：后端保存时会校验失败，必须让用户看见 */}
      {stale.map((n) => (
        <div key={n} className="hint" style={{color: 'var(--danger, #d33)', marginTop: 4}}>
          ⚠️ 已绑定「{n}」但当前不可用（已删除/未连接），保存会失败
          <button className="btn small" style={{marginLeft: 8}} onClick={() => onToggle(n)}>移除</button>
        </div>
      ))}
    </div>
  );
}

/** 序列化绑定字段：空选择序列化为 ""（后端语义：""=清空绑定，null=不修改） */
const serializeNames = (names: string[]): string => (names.length ? JSON.stringify(names) : '');

const emptyScenario = (): Scenario => ({
  id: 0, name: '', displayName: '', icon: '🎬', description: '',
  mode: 'single', systemPrompt: '', workflow: null, active: true, builtin: false,
  skills: '', subagents: '', mcpServices: '', capabilityTier: '',
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
  const [deleting, setDeleting] = useState<Scenario | null>(null);
  const [removing, setRemoving] = useState(false);
  // 能力绑定候选项：仅在打开 Modal 时按需加载，避免列表页无谓请求
  const [skillOptions, setSkillOptions] = useState<SkillOption[]>([]);
  const [subagentOptions, setSubagentOptions] = useState<SubagentOption[]>([]);
  const [mcpOptions, setMcpOptions] = useState<McpOption[]>([]);
  const [bindSkills, setBindSkills] = useState<string[]>([]);
  const [bindSubagents, setBindSubagents] = useState<string[]>([]);
  const [bindMcp, setBindMcp] = useState<string[]>([]);
  const [bindTier, setBindTier] = useState('');
  const [bindLoading, setBindLoading] = useState(false);

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

  /**
   * 加载能力绑定候选项。
   * <p>skills / subagents 都需要 workspaceId 才能拿到「全局 + 工作区级」的完整名单，
   * 这里直接复用页面顶部已有的 selectedWs（工作区下拉），与运行时加载口径一致。
   * <p>任一数据源失败只降级为空列表并提示，不阻塞场景本身的编辑。
   */
  const loadBindingOptions = async () => {
    setBindLoading(true);
    const wsQuery = selectedWs ? `?workspaceId=${encodeURIComponent(selectedWs)}` : '';
    try {
      const [sk, subs, mcp] = await Promise.all([
        getJson<SkillOption[]>(`/api/skills${wsQuery}`).catch(() => [] as SkillOption[]),
        getJson<SubagentOption[]>(`/api/scenarios/subagents${wsQuery}`).catch(() => [] as SubagentOption[]),
        getJson<McpOption[]>('/api/mcp').catch(() => [] as McpOption[]),
      ]);
      // 只保留 skill 作用域：scope 为 global / workspace。
      // 排除 *-subagent（global-subagent / workspace-subagent）—— 那是子 Agent 声明文件，
      // 与 skill 同接口返回、靠 scope 区分，混进来会让用户把子 Agent 当 skill 绑定。
      setSkillOptions(sk.filter((s) => !String(s.scope || '').endsWith('-subagent')));
      setSubagentOptions(subs);
      setMcpOptions(mcp.filter((m) => !m.isTemplate));
    } finally {
      setBindLoading(false);
    }
  };

  /** 从场景实体反解已选绑定项，写入编辑态 */
  const resetBinding = (s: Scenario) => {
    setBindSkills(parseNames(s.skills));
    setBindSubagents(parseNames(s.subagents));
    setBindMcp(parseNames(s.mcpServices));
    setBindTier((s.capabilityTier || '').trim().toLowerCase());
  };

  /** 勾选/取消勾选：不可变更新，顺序稳定（避免每次保存都产生无意义的字段 diff） */
  const toggleIn = (list: string[], set: (v: string[]) => void, name: string) => {
    set(list.includes(name) ? list.filter((n) => n !== name) : [...list, name]);
  };

  const openCreate = () => {
    const blank = emptyScenario();
    setEditing(blank);
    setSteps([]);
    resetBinding(blank);
    loadBindingOptions();
  };

  const openEdit = (s: Scenario) => {
    setEditing({...s});
    setSteps(parseSteps(s.workflow));
    resetBinding(s);
    loadBindingOptions();
  };

  const save = async () => {
    if (!editing) return;
    const body = {
      ...editing,
      workflow: editing.mode === 'team' && steps.length
        ? JSON.stringify({steps})
        : null,
      // 全部显式提交：未勾选任何项时传 ""（后端 blankToNull 会归一为 null=清空绑定），
      // 传 null 会被 update 当成「不修改」，用户就永远无法解绑
      skills: serializeNames(bindSkills),
      subagents: serializeNames(bindSubagents),
      mcpServices: serializeNames(bindMcp),
      capabilityTier: bindTier,
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

  const confirmRemove = async () => {
    if (!deleting) return;
    setRemoving(true);
    try {
      await del(`/api/scenarios/${deleting.id}`);
      setDeleting(null);
      await load();
    } catch (e) {
      setError(String(e));
    } finally {
      setRemoving(false);
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
              {(() => {
                // 绑定摘要：无任何绑定时整行不渲染，避免给未使用该功能的场景增加视觉噪声
                const parts: string[] = [];
                const nSkill = parseNames(s.skills).length;
                const nSub = parseNames(s.subagents).length;
                const nMcp = parseNames(s.mcpServices).length;
                if (nSkill) parts.push(`${nSkill} skill`);
                if (nSub) parts.push(`${nSub} 子Agent`);
                if (nMcp) parts.push(`${nMcp} MCP`);
                const tier = tierLabel(s.capabilityTier);
                if (tier) parts.push(tier);
                if (!parts.length) return null;
                return (
                  <div style={{fontSize: 12, color: 'var(--text-dim, #888)'}} title="场景能力绑定">
                    🧩 {parts.join(' · ')}
                  </div>
                );
              })()}
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
                <button className="btn danger small" onClick={() => setDeleting(s)}>删除</button>
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

          <div style={{borderTop: '1px solid var(--border)', margin: '14px 0 10px', paddingTop: 12}}>
            <div style={{fontWeight: 600, fontSize: 14, marginBottom: 4}}>⚙️ 能力绑定</div>
            <div className="hint" style={{marginBottom: 10}}>
              全部留空 = 不限制（与旧行为一致）。MCP 与能力档位是<strong>硬隔离</strong>（限制实际可调用的工具）；
              skill 与子 Agent 是<strong>软提示</strong>（收窄注入给模型的清单）。
              {bindLoading && <span style={{marginLeft: 6}}>候选项加载中…</span>}
            </div>

            <div className="field">
              <label>基础能力档位</label>
              <div className="hint" style={{marginBottom: 6}}>
                只有显式选择才生效；保持「未配置」时沿用各子 Agent 原有工具集，不会被静默收紧。
                最终可用工具 = 档位工具 ∪ 场景绑定的 MCP 工具。
              </div>
              <select value={bindTier} onChange={(e) => setBindTier(e.target.value)} style={{maxWidth: 260}}>
                {TIER_OPTIONS.map((o) => <option key={o.value} value={o.value}>{o.label}</option>)}
              </select>
            </div>

            <BindingPicker
              label="🧩 Skill"
              hint="勾选后本场景只注入这些 skill；子 Agent 自身声明的 skill 会与此取交集，交集为空时回退为本清单。"
              options={skillOptions}
              selected={bindSkills}
              onToggle={(n) => toggleIn(bindSkills, setBindSkills, n)}
              empty="当前工作区没有可用 skill（在 Skills 页添加）"
            />

            <BindingPicker
              label="🤖 子 Agent"
              hint="勾选后 system prompt 只列出这些成员；未勾选表示全部可用。"
              options={subagentOptions}
              selected={bindSubagents}
              onToggle={(n) => toggleIn(bindSubagents, setBindSubagents, n)}
              empty="没有可用子 Agent（在 Skills 页「子Agent」中添加）"
            />

            <BindingPicker
              label="🔌 MCP 服务"
              hint="按服务绑定，运行时展开为该服务的工具名喂入白名单。未连接的服务会在启动时告警并跳过，不阻断对话。"
              options={mcpOptions}
              selected={bindMcp}
              onToggle={(n) => toggleIn(bindMcp, setBindMcp, n)}
              empty="没有已配置的 MCP 服务（在 MCP 页添加）"
            />
          </div>

          <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end'}}>
            <button className="btn" onClick={() => setEditing(null)}>取消</button>
            <button className="btn primary" onClick={save}>保存</button>
          </div>
        </Modal>
      )}
      {deleting && (
        <Modal
          title="🗑 删除场景"
          subtitle={deleting.displayName || deleting.name}
          onClose={() => setDeleting(null)}
          width={520}
        >
          {(() => {
            const affected = workspaces.filter((w) => activeMap[w.workspaceId] === deleting.id);
            return (
              <div className="hint" style={{lineHeight: 1.8, marginBottom: 12}}>
                确定删除场景 <strong>{deleting.displayName || deleting.name}</strong>？
                {affected.length > 0 ? (
                  <>
                    <div style={{marginTop: 8}}>
                      以下 <strong>{affected.length}</strong> 个工作区正在使用该场景，将自动回到默认主智能体模式
                      （Agent 会立即重建）：
                    </div>
                    <ul style={{margin: '8px 0 0', paddingLeft: 20}}>
                      {affected.map((w) => <li key={w.workspaceId}>{w.name}</li>)}
                    </ul>
                  </>
                ) : (
                  <div style={{marginTop: 8}}>当前没有工作区激活该场景，删除不会影响任何对话。</div>
                )}
              </div>
            );
          })()}
          <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end'}}>
            <button className="btn" onClick={() => setDeleting(null)}>取消</button>
            <button className="btn danger" disabled={removing} onClick={confirmRemove}>
              {removing ? '删除中…' : '确认删除'}
            </button>
          </div>
        </Modal>
      )}
    </div>
  );
}
