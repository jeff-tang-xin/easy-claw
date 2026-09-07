// 场景绑定字段（skills / subagents / mcpServices）的解析口径。
//
// 与后端 ScenarioBinding.parseNameArray 保持一致，改动需同步：
// web/src/main/java/com/xinl/easyclaw/scenario/ScenarioBinding.java
//
// 注意：ScenariosPage.tsx 内另有一份同名局部实现（逗号兜底更宽松），
// 本次未做去重以避免扩大影响面；后续统一时两处都要改。

/**
 * 宽松解析名字数组：优先按 JSON 数组解析，失败则退回分隔符切分。
 *
 * <p>兜底是必要的 —— 这几列可能由人工直接写入数据库或早期 API 传入裸字符串。
 * 合法 JSON 但既非数组也非字符串（数字/对象/布尔）时直接返回空数组：
 * 若继续走兜底，`{"k":"v"}` 会被切成 k、v 这类垃圾条目，比空列表更糟。
 *
 * @param raw 原始字段值
 * @returns 去空、trim、去重后的名字列表（保持原始顺序）
 */
export function parseNames(raw?: string | null): string[] {
  const names: string[] = [];
  if (!raw || !raw.trim()) return names;
  const text = raw.trim();
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    // 非合法 JSON → 按分隔符兜底（与后端 split("[,\\[\\]\"']") 对齐）
    for (const part of text.split(/[,[\]"']/)) addIfPresent(names, part);
    return names;
  }
  if (Array.isArray(parsed)) {
    for (const node of parsed) {
      if (typeof node === 'string') addIfPresent(names, node);
    }
    return names;
  }
  if (typeof parsed === 'string') {
    addIfPresent(names, parsed);
    return names;
  }
  // 合法 JSON 但不是名字数组 → 明确忽略，不走兜底
  return names;
}

function addIfPresent(target: string[], value: string | null | undefined): void {
  if (value == null) return;
  const trimmed = value.trim();
  if (trimmed && !target.includes(trimmed)) target.push(trimmed);
}
