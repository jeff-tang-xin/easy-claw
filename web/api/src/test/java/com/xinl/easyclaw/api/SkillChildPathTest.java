package com.xinl.easyclaw.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * skill 子文件落点规则。
 *
 * <p>回归背景：旧实现对所有子文件无条件补 {@code .md} 后缀，导致页面无法创建
 * {@code .py} 脚本（{@code check.py} 会变成 {@code check.py.md}），
 * 而 {@code run_skill_script} 只在 {@code scripts/} 下按 {@code .py} 查找。
 */
class SkillChildPathTest {

    @TempDir
    Path tmp;

    @Test
    void pythonChildGoesIntoScriptsDir() {
        Path p = ManageController.resolveChildPath(tmp, "check.py");
        assertEquals(tmp.resolve("scripts").resolve("check.py").normalize(), p,
                ".py 子文件必须落在 scripts/ 下，否则 run_skill_script 找不到");
    }

    @Test
    void pythonChildKeepsExtensionNotSuffixedWithMd() {
        Path p = ManageController.resolveChildPath(tmp, "check.py");
        assertTrue(p.getFileName().toString().endsWith(".py"));
        assertEquals("check.py", p.getFileName().toString(),
                "旧缺陷：被补成 check.py.md");
    }

    @Test
    void explicitScriptsPrefixIsNotDuplicated() {
        Path p = ManageController.resolveChildPath(tmp, "scripts/smell_scan.py");
        assertEquals(tmp.resolve("scripts").resolve("smell_scan.py").normalize(), p,
                "用户已写 scripts/ 前缀时不应变成 scripts/scripts/");
    }

    @Test
    void markdownChildStaysAtSkillRoot() {
        Path p = ManageController.resolveChildPath(tmp, "components.md");
        assertEquals(tmp.resolve("components.md").normalize(), p);
    }

    @Test
    void markdownSuffixStillAppendedWhenMissing() {
        Path p = ManageController.resolveChildPath(tmp, "components");
        assertEquals(tmp.resolve("components.md").normalize(), p,
                "md 子规则的补后缀行为必须保留");
    }

    @Test
    void traversalInChildNameIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> ManageController.resolveChildPath(tmp, "../../evil.py"),
                "子文件名不得逃出 skill 目录");
    }

    @Test
    void traversalViaScriptsPrefixIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> ManageController.resolveChildPath(tmp, "scripts/../../evil.py"));
    }

    @Test
    void blankChildNameIsRejected() {
        assertThrows(ResponseStatusException.class,
                () -> ManageController.resolveChildPath(tmp, "   "));
    }

    @Test
    void displayNameMatchesListingConvention() {
        // 列表接口 collectScripts 用 "scripts/xxx.py"，创建返回值必须一致，
        // 否则前端刚创建时的展示名与刷新后不同。
        assertEquals("scripts/check.py", ManageController.displayChildName("check.py"));
        assertEquals("scripts/check.py", ManageController.displayChildName("scripts/check.py"));
        assertEquals("components", ManageController.displayChildName("components.md"));
    }
}
