#!/usr/bin/env python3
"""扫描代码文件中的坏味道，输出可定位的问题清单（行号 + 说明）。

用法: run_skill_script(skill="clean-code", script="smell_scan.py", args=["<文件路径>", ...])

只做确定性的机械检查——这类判断靠人肉/模型逐行看容易漏，交给脚本更可靠。
不做主观判断（命名好不好、抽象是否合理），那部分仍由评审者结合上下文决定。
"""
import sys
import os

# 每项: (阈值说明, 判定函数)
MAX_LINE_LEN = 120
MAX_FUNC_LINES = 60
MAX_PARAMS = 5
MAX_NESTING = 4

TODO_MARKERS = ("TODO", "FIXME", "XXX", "HACK")


def scan_text(path, lines):
    """返回 [(行号, 级别, 说明)]。"""
    issues = []

    for i, raw in enumerate(lines, start=1):
        line = raw.rstrip("\n")
        stripped = line.strip()

        if len(line) > MAX_LINE_LEN:
            issues.append((i, "WARN", f"行过长（{len(line)} > {MAX_LINE_LEN}）"))

        for marker in TODO_MARKERS:
            if marker in line:
                issues.append((i, "INFO", f"遗留标记 {marker}：{stripped[:60]}"))
                break

        # 制表符与空格混用会让缩进在不同编辑器下错位
        if "\t" in line and line.lstrip("\t").startswith(" "):
            issues.append((i, "WARN", "制表符与空格混用缩进"))

        if stripped.endswith(" ") or line != line.rstrip():
            issues.append((i, "INFO", "行尾多余空白"))

        # 空的异常吞噬：except 后面直接 pass
        if stripped in ("except:", "except Exception:") :
            issues.append((i, "ERROR", "裸 except 会连 KeyboardInterrupt 一起吞掉"))

    return issues


def scan_python_structure(path, source):
    """用 AST 检查函数长度/参数个数/嵌套深度，非 Python 文件跳过。"""
    import ast
    issues = []
    try:
        tree = ast.parse(source)
    except SyntaxError as e:
        return [(e.lineno or 0, "ERROR", f"语法错误: {e.msg}")]

    def depth(node, current=0):
        deepest = current
        for child in ast.iter_child_nodes(node):
            if isinstance(child, (ast.If, ast.For, ast.While, ast.Try, ast.With)):
                deepest = max(deepest, depth(child, current + 1))
            else:
                deepest = max(deepest, depth(child, current))
        return deepest

    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            length = (getattr(node, "end_lineno", node.lineno) or node.lineno) - node.lineno + 1
            if length > MAX_FUNC_LINES:
                issues.append((node.lineno, "WARN",
                               f"函数 {node.name} 过长（{length} 行 > {MAX_FUNC_LINES}）"))
            nargs = len(node.args.args) + len(node.args.kwonlyargs)
            if nargs > MAX_PARAMS:
                issues.append((node.lineno, "WARN",
                               f"函数 {node.name} 参数过多（{nargs} > {MAX_PARAMS}）"))
            d = depth(node)
            if d > MAX_NESTING:
                issues.append((node.lineno, "WARN",
                               f"函数 {node.name} 嵌套过深（{d} > {MAX_NESTING}）"))
    return issues


def main(argv):
    # Windows 控制台默认 GBK，脚本输出含 emoji/中文会直接 UnicodeEncodeError 崩掉。
    # 独立运行（非 GraalPy 内嵌）时必须自己兜底，否则这个工具在 cmd 里根本不可用。
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except (AttributeError, ValueError):
        pass

    targets = argv[1:]
    if not targets:
        print("用法: smell_scan.py <文件路径> [更多文件...]")
        print("提示: 路径需在允许访问的目录内。")
        return 1

    total = 0
    unreadable = []
    for path in targets:
        if not os.path.isfile(path):
            # 不能静默跳过：沙箱拦截、路径拼错、文件被删都会走到这里，
            # 若照样 return 0，调用方会把"一个文件都没扫到"误读成"代码很干净"。
            unreadable.append(path)
            print(f"❌ 无法读取（不存在或不在允许访问的目录内）: {path}")
            continue
        with open(path, "r", encoding="utf-8", errors="replace") as f:
            source = f.read()
        lines = source.splitlines(keepends=True)

        issues = scan_text(path, lines)
        if path.endswith(".py"):
            issues += scan_python_structure(path, source)

        issues.sort(key=lambda x: (x[0], x[1]))
        print(f"\n=== {path} （{len(lines)} 行，{len(issues)} 处问题）===")
        if not issues:
            print("  ✅ 未发现机械性坏味道")
        for lineno, level, msg in issues:
            print(f"  {level:5s} L{lineno}: {msg}")
        total += len(issues)

    scanned = len(targets) - len(unreadable)
    print(f"\n已扫描 {scanned}/{len(targets)} 个文件，合计 {total} 处问题。"
          f"ERROR 必须修，WARN 需说明理由，INFO 可批量清理。")
    if unreadable:
        print(f"⚠️  有 {len(unreadable)} 个文件未能扫描，结果不完整。")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
