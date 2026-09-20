import {useState} from 'react';

/**
 * AppKey 接入指引：把「拿到 key 之后怎么配」讲清楚，避免用户去翻文档。
 * 内容全部对齐 hub 网关真实契约（GatewayController / AppKeyAuthFilter / application-cloud.yml）：
 *   - 端点   POST {origin}/api/gateway/v1/chat/completions（OpenAI 兼容）
 *   - 鉴权   Authorization: Bearer <appkey>
 *   - 模型   绑定面内真实模型名，或逻辑别名 hub_cloud（需先在该 key 配置云端路由）
 * base-url 用 window.location.origin 动态生成：控制台与网关同源单端口部署（见 vite.config.ts 注释）。
 * plainKey 仅在创建成功时可得；为 null 时以占位符 <你的 AppKey> 展示，绝不臆造密钥。
 */

const KEY_PLACEHOLDER = '<你的 AppKey>';

type TabKey = 'curl' | 'python' | 'node' | 'easyclaw';

interface Tab {
  key: TabKey;
  label: string;
  hint: string;
  build: (baseUrl: string, keyExpr: string, model: string) => string;
}

const TABS: Tab[] = [
  {
    key: 'curl',
    label: 'curl',
    hint: '直接验证连通性',
    build: (baseUrl, keyExpr, model) =>
      `curl ${baseUrl}/chat/completions \\\n` +
      `  -H "Authorization: Bearer ${keyExpr}" \\\n` +
      `  -H "Content-Type: application/json" \\\n` +
      `  -d '{"model":"${model}","messages":[{"role":"user","content":"你好"}]}'`,
  },
  {
    key: 'python',
    label: 'OpenAI SDK (Python)',
    hint: 'base_url 指向 hub 网关即可',
    build: (baseUrl, keyExpr, model) =>
      `from openai import OpenAI\n\n` +
      `client = OpenAI(base_url="${baseUrl}", api_key="${keyExpr}")\n\n` +
      `resp = client.chat.completions.create(\n` +
      `    model="${model}",\n` +
      `    messages=[{"role": "user", "content": "你好"}],\n` +
      `)\n` +
      `print(resp.choices[0].message.content)`,
  },
  {
    key: 'node',
    label: 'OpenAI SDK (Node)',
    hint: '任何 OpenAI 兼容客户端同理',
    build: (baseUrl, keyExpr, model) =>
      `import OpenAI from "openai";\n\n` +
      `const client = new OpenAI({ baseURL: "${baseUrl}", apiKey: "${keyExpr}" });\n\n` +
      `const resp = await client.chat.completions.create({\n` +
      `  model: "${model}",\n` +
      `  messages: [{ role: "user", content: "你好" }],\n` +
      `});\n` +
      `console.log(resp.choices[0].message.content);`,
  },
  {
    key: 'easyclaw',
    label: 'EasyClaw 云端模式',
    hint: 'web/api 的 cloud profile：只需 hub-url + app-key 两个输入',
    build: (baseUrl, keyExpr) =>
      `# ~/.easyClaw/application.yml —— cloud profile 只需这两个输入，\n` +
      `# 网关 base-url 与模型面由 profile 自动派生（providers.openai.base-url = <hub-url>/api/gateway/v1）。\n` +
      `# 模型位（agentscope.model.default-model 或 agents.<id>.model-name）填 openai:hub_cloud\n` +
      `spring:\n` +
      `  profiles:\n` +
      `    active: dev-sqlite,cloud\n` +
      `cloud:\n` +
      `  hub-url: ${baseUrl.replace(/\/api\/gateway\/v1$/, '')}\n` +
      `  app-key: ${keyExpr.startsWith('eck-') ? keyExpr : '${HUB_APPKEY}'}  # 密钥走环境变量，勿入库`,
  },
];

function SnippetBlock({code, onCopy, copied}: {code: string; onCopy: () => void; copied: boolean}) {
  return (
    <div className="guide-snippet">
      <pre className="log-body guide-code">{code}</pre>
      <button type="button" className="btn btn-ghost btn-sm guide-copy" onClick={onCopy}>
        {copied ? '已复制 ✓' : '复制'}
      </button>
    </div>
  );
}

export default function AccessGuide({
  plainKey,
  model,
  modelNote,
  collapsible = false,
}: {
  /** 明文密钥；仅创建成功时非空，其余场景传 null 用占位符 */
  plainKey: string | null;
  /** 示例里用的模型名：真实模型名，或逻辑别名 hub_cloud */
  model: string;
  /** 模型位下方的一句话说明 */
  modelNote: string;
  /** 折叠模式：默认收起，点标题展开（用于路由弹窗，避免撑高） */
  collapsible?: boolean;
}) {
  const [tab, setTab] = useState<TabKey>('curl');
  const [copied, setCopied] = useState(false);
  const baseUrl = `${window.location.origin}/api/gateway/v1`;
  const keyExpr = plainKey ?? KEY_PLACEHOLDER;
  const active = TABS.find((t) => t.key === tab) ?? TABS[0];
  const code = active.build(baseUrl, keyExpr, model);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(code);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1800);
    } catch {
      // 复制失败静默：用户可手动选中
    }
  };

  const body = (
    <div className="guide-body">
      <div className="guide-facts">
        <div className="guide-fact">
          <span className="guide-fact-label">Base URL</span>
          <code className="guide-fact-val">{baseUrl}</code>
        </div>
        <div className="guide-fact">
          <span className="guide-fact-label">鉴权</span>
          <code className="guide-fact-val">Authorization: Bearer {plainKey ? keyExpr : '‹appkey›'}</code>
        </div>
        <div className="guide-fact">
          <span className="guide-fact-label">model</span>
          <code className="guide-fact-val">{model}</code>
        </div>
      </div>
      <p className="guide-model-note">{modelNote}</p>
      {!plainKey && (
        <p className="guide-warn">
          明文密钥仅在创建时显示一次，此处以占位符演示；请把 <code>{KEY_PLACEHOLDER}</code> 换成你自己的密钥。
        </p>
      )}
      <div className="guide-tabs">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            className={t.key === tab ? 'tab-btn active' : 'tab-btn'}
            onClick={() => setTab(t.key)}
            title={t.hint}
          >
            {t.label}
          </button>
        ))}
      </div>
      <SnippetBlock code={code} onCopy={copy} copied={copied} />
    </div>
  );

  if (!collapsible) {
    return (
      <div className="access-guide">
        <h4 className="access-guide-title">如何接入（用这个 AppKey 配置你的客户端）</h4>
        {body}
      </div>
    );
  }
  return (
    <details className="access-guide access-guide-collapsible">
      <summary className="access-guide-title">查看接入方式（curl / OpenAI SDK / EasyClaw 云端模式）</summary>
      {body}
    </details>
  );
}
