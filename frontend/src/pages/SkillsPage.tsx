import {useEffect, useState} from 'react';
import {del, getJson, postJson} from '../api';
import Modal from '../components/Modal';

interface SkillFile { scope: string; name: string; description: string; path: string; content: string; }

const scopeLabel: Record<string, string> = {
  global: '全局',
  workspace: '工作区',
  'global-subagent': '全局子Agent',
  'workspace-subagent': '工作区子Agent',
};

export default function SkillsPage() {
  const [tab, setTab] = useState<'skills' | 'subagents'>('skills');
  const [items, setItems] = useState<SkillFile[]>([]);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [scope, setScope] = useState('global');
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [content, setContent] = useState('');

  const load = async () => {
    try {
      setItems(await getJson<SkillFile[]>('/api/skills'));
    } catch (e) {
      setError(String(e));
    }
  };
  useEffect(() => { load(); }, []);

  const skills = items.filter((s) => s.scope === 'global' || s.scope === 'workspace');
  const subagents = items.filter((s) => s.scope === 'global-subagent' || s.scope === 'workspace-subagent');

  const openCreate = (t: 'skills' | 'subagents') => {
    setTab(t);
    setScope(t === 'skills' ? 'global' : 'global-subagent');
    setName('');
    setDescription('');
    setContent('');
    setCreating(true);
  };

  const create = async () => {
    try {
      await postJson('/api/skills', { scope, name, description, content });
      setCreating(false);
      await load();
    } catch (e) {
      setError(String(e));
    }
  };

  const remove = async (path: string) => {
    if (!confirm('删除该项？')) return;
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
            <tr><th>名称</th><th>类型</th><th>描述</th><th>路径</th><th></th></tr>
          </thead>
          <tbody>
            {list.length === 0 && (
              <tr><td colSpan={5} className="empty">暂无{isAgent ? '子 Agent' : 'Skills'}</td></tr>
            )}
            {list.map((s, i) => (
              <tr key={i}>
                <td style={{ fontWeight: 600 }}>{s.name}</td>
                <td><span className="badge blue">{scopeLabel[s.scope] || s.scope}</span></td>
                <td className="hint">{s.description}</td>
                <td className="hint" style={{ fontSize: 11 }}>{s.path}</td>
                <td><button className="btn danger small" onClick={() => remove(s.path)}>删除</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {creating && (
        <Modal title={isAgent ? '🤖 新建子 Agent' : '📚 新建 Skill'} onClose={() => setCreating(false)} width={620}>
          <div style={{ display: 'flex', gap: 12 }}>
            <div className="field" style={{ flex: 1 }}>
              <label>作用域</label>
              <select value={scope} onChange={(e) => setScope(e.target.value)}>
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
            <div className="field" style={{ flex: 1 }}>
              <label>名称（英文标识）</label>
              <input value={name} onChange={(e) => setName(e.target.value)} placeholder={isAgent ? '如：code-reviewer' : '如：code-review'} />
            </div>
          </div>
          <div className="field">
            <label>描述</label>
            <input value={description} onChange={(e) => setDescription(e.target.value)} />
          </div>
          <div className="field">
            <label>内容（Markdown，frontmatter 支持 description/model/role/steps）</label>
            <textarea value={content} onChange={(e) => setContent(e.target.value)} rows={7}
              placeholder={'---\ndescription: ...\nmodel: deepseek:deepseek-chat\nrole: main\n---\n\n你的提示词...'} />
          </div>
          <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
            <button className="btn" onClick={() => setCreating(false)}>取消</button>
            <button className="btn primary" onClick={create}>创建</button>
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
