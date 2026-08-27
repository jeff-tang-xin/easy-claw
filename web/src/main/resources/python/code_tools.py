"""代码分析工具的 Python 实现。

由 Java 侧 GraalPyEngine 加载并调用，通过标准库 ast / difflib 提供
语法感知的精确分析，替代原先基于正则与逐行下标对齐的近似实现。

约定：
- 所有入口函数返回 JSON 字符串，避免跨语言传递复杂对象带来的类型映射问题；
- 任何异常都在函数内转成 {"ok": false, "error": "..."}，不向 Java 抛异常；
- 本模块不做任何 IO / 网络 / 进程操作，纯计算，便于收紧 Context 权限。
"""

import ast
import difflib
import json


def analyze_python(code):
    """用 ast 精确统计 Python 代码结构。

    相比正则实现，这里不会把 if/for/while/函数调用的括号误计为函数定义。
    返回的 functions 同时涵盖 async def（AsyncFunctionDef）。
    """
    try:
        tree = ast.parse(code)
    except SyntaxError as e:
        return json.dumps(
            {
                "ok": False,
                "error": "SyntaxError: %s (line %s)" % (e.msg, e.lineno),
            }
        )

    funcs = []
    classes = []
    imports = set()
    max_depth = 0

    def walk(node, depth):
        nonlocal max_depth
        max_depth = max(max_depth, depth)
        for child in ast.iter_child_nodes(node):
            if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                funcs.append(
                    {
                        "name": child.name,
                        "line": child.lineno,
                        "args": len(child.args.args),
                        "isAsync": isinstance(child, ast.AsyncFunctionDef),
                    }
                )
            elif isinstance(child, ast.ClassDef):
                classes.append({"name": child.name, "line": child.lineno})
            elif isinstance(child, ast.Import):
                for a in child.names:
                    imports.add(a.name)
            elif isinstance(child, ast.ImportFrom):
                if child.module:
                    imports.add(child.module)
            # 只有控制流结构才增加嵌套深度，避免把普通语句算进去
            if isinstance(
                child, (ast.If, ast.For, ast.While, ast.Try, ast.With, ast.AsyncFor, ast.AsyncWith)
            ):
                walk(child, depth + 1)
            else:
                walk(child, depth)

    walk(tree, 0)

    lines = code.splitlines()
    blank = sum(1 for line in lines if not line.strip())
    comment = sum(1 for line in lines if line.strip().startswith("#"))

    return json.dumps(
        {
            "ok": True,
            "language": "Python",
            "totalLines": len(lines),
            "blankLines": blank,
            "commentLines": comment,
            "codeLines": len(lines) - blank - comment,
            "chars": len(code),
            "functionCount": len(funcs),
            "classCount": len(classes),
            "maxNestingDepth": max_depth,
            "functions": funcs[:50],
            "classes": classes[:50],
            "imports": sorted(imports)[:50],
        }
    )


def unified_diff(code1, code2, label1="before", label2="after", context=3):
    """基于 difflib（Ratcliff/Obershelp 匹配）生成真实差异。

    与逐行下标对齐的实现相比，在开头插入/删除行时不会造成后续全文错位误报。
    同时返回统计信息与相似度，便于调用方快速判断改动规模。
    """
    a = code1.splitlines()
    b = code2.splitlines()

    if a == b:
        return json.dumps(
            {"ok": True, "identical": True, "added": 0, "removed": 0, "similarity": 1.0, "diff": ""}
        )

    diff_lines = list(
        difflib.unified_diff(a, b, fromfile=label1, tofile=label2, lineterm="", n=context)
    )

    added = 0
    removed = 0
    for line in diff_lines:
        if line.startswith("+") and not line.startswith("+++"):
            added += 1
        elif line.startswith("-") and not line.startswith("---"):
            removed += 1

    ratio = difflib.SequenceMatcher(None, a, b).ratio()

    # 输出体积设上限，避免超长 diff 冲爆模型上下文
    max_lines = 400
    truncated = len(diff_lines) > max_lines
    shown = diff_lines[:max_lines]

    return json.dumps(
        {
            "ok": True,
            "identical": False,
            "added": added,
            "removed": removed,
            "similarity": round(ratio, 4),
            "truncated": truncated,
            "totalDiffLines": len(diff_lines),
            "diff": "\n".join(shown),
        }
    )


def similarity(code1, code2):
    """只算相似度，不产出 diff 文本，用于批量比对场景。"""
    ratio = difflib.SequenceMatcher(None, code1.splitlines(), code2.splitlines()).ratio()
    return json.dumps({"ok": True, "similarity": round(ratio, 4)})


# --------------------------------------------------------------------------
# 结构化数据体检
#
# 只做「模型自己看不出来」的检查，不做单纯的格式转换或复述：
#   - JSON 重复键会被静默覆盖，肉眼几乎不可能发现；
#   - CSV 引号内的逗号/换行会让按逗号切分的直觉判断错列；
#   - 字段数不一致的行需要逐行比对才能定位。
# --------------------------------------------------------------------------


def _duplicate_json_keys(text):
    """找出 JSON 对象中的重复键。

    标准 json.loads 遇到重复键时后者静默覆盖前者，不报错也不告警，
    是配置文件里极隐蔽的一类缺陷。这里用 object_pairs_hook 拦下原始键序列。
    """
    dups = []

    def hook(pairs):
        seen = set()
        for k, _ in pairs:
            if k in seen:
                dups.append(k)
            else:
                seen.add(k)
        return dict(pairs)

    try:
        json.loads(text, object_pairs_hook=hook)
    except ValueError:
        # 语法错误由主流程统一报告，这里不重复处理
        return []
    # 去重但保持出现顺序
    out = []
    for k in dups:
        if k not in out:
            out.append(k)
    return out


def inspect_json(text):
    """校验 JSON 并给出精确的错误位置与结构摘要。"""
    try:
        data = json.loads(text)
    except ValueError as e:
        # json 的 JSONDecodeError 带 lineno/colno，比"解析失败"有用得多
        line = getattr(e, "lineno", None)
        col = getattr(e, "colno", None)
        detail = getattr(e, "msg", str(e))
        snippet = ""
        if line:
            lines = text.splitlines()
            if 0 < line <= len(lines):
                snippet = lines[line - 1].strip()[:200]
        return json.dumps(
            {
                "ok": True,
                "format": "JSON",
                "valid": False,
                "error": detail,
                "errorLine": line,
                "errorColumn": col,
                "errorSnippet": snippet,
            }
        )

    def describe(node, depth=0):
        """递归统计结构，深度设上限避免深层嵌套导致栈溢出。"""
        if depth > 20:
            return {"type": "...", "note": "深度超过 20 层，已停止展开"}
        if isinstance(node, dict):
            return {
                "type": "object",
                "keys": len(node),
                "keyNames": list(node.keys())[:30],
            }
        if isinstance(node, list):
            info = {"type": "array", "length": len(node)}
            if node:
                kinds = sorted({type(x).__name__ for x in node})
                info["elementTypes"] = kinds
                # 数组元素类型不一致往往是数据质量问题
                info["homogeneous"] = len(kinds) == 1
                if isinstance(node[0], dict):
                    # 记录各行字段是否一致，这是表格型 JSON 最常见的坑
                    key_sets = [frozenset(x.keys()) for x in node if isinstance(x, dict)]
                    if key_sets:
                        base = key_sets[0]
                        odd = [i for i, ks in enumerate(key_sets) if ks != base]
                        info["inconsistentRows"] = odd[:20]
                        info["inconsistentRowCount"] = len(odd)
            return info
        return {"type": type(node).__name__}

    dups = _duplicate_json_keys(text)
    return json.dumps(
        {
            "ok": True,
            "format": "JSON",
            "valid": True,
            "root": describe(data),
            "duplicateKeys": dups,
            "chars": len(text),
            "lines": len(text.splitlines()),
        }
    )


def inspect_csv(text, delimiter=","):
    """校验 CSV：字段数一致性、引号处理、空值分布。

    使用 csv 模块而非按分隔符切分，因此能正确处理引号内的分隔符与换行。
    """
    import csv
    import io as _io

    try:
        reader = csv.reader(_io.StringIO(text), delimiter=delimiter)
        rows = list(reader)
    except csv.Error as e:
        return json.dumps({"ok": True, "format": "CSV", "valid": False, "error": str(e)})

    if not rows:
        return json.dumps(
            {"ok": True, "format": "CSV", "valid": False, "error": "内容为空或无有效行"}
        )

    header = rows[0]
    expected = len(header)
    bad_rows = []
    for idx, row in enumerate(rows[1:], start=2):
        if len(row) != expected:
            bad_rows.append({"line": idx, "fields": len(row)})

    # 逐列统计空值，定位数据缺失集中在哪一列
    empty_by_col = {}
    for row in rows[1:]:
        for i, cell in enumerate(row):
            if i < expected and not cell.strip():
                name = header[i] if header[i] else "col%d" % (i + 1)
                empty_by_col[name] = empty_by_col.get(name, 0) + 1

    # 表头重复会让按名取列产生歧义
    dup_headers = []
    seen = set()
    for h in header:
        if h in seen and h not in dup_headers:
            dup_headers.append(h)
        seen.add(h)

    return json.dumps(
        {
            "ok": True,
            "format": "CSV",
            "valid": len(bad_rows) == 0,
            "columns": expected,
            "headers": header[:50],
            "duplicateHeaders": dup_headers,
            "dataRows": len(rows) - 1,
            "malformedRows": bad_rows[:20],
            "malformedRowCount": len(bad_rows),
            "emptyCellsByColumn": empty_by_col,
        }
    )
