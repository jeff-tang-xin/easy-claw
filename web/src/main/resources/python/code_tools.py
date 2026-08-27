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
