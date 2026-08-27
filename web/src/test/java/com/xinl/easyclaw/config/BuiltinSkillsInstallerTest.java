package com.xinl.easyclaw.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 扩展名白名单行为。
 *
 * <p>这层过滤是执行入口的准入控制：{@code ~/.easyClaw/skills/} 下的 .py 会被
 * run_skill_script 执行，所以"什么能落地"必须是可测试的确定行为，而不是靠 glob 模式碰运气。
 */
class BuiltinSkillsInstallerTest {

    private boolean allowed(String path) throws Exception {
        Method m = BuiltinSkillsInstaller.class.getDeclaredMethod("isAllowed", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, path);
    }

    @Test
    @DisplayName("白名单内的类型放行（含 scripts/ 下的 .py）")
    void allowsWhitelisted() throws Exception {
        assertTrue(allowed("clean-code/SKILL.md"));
        assertTrue(allowed("clean-code/scripts/smell_scan.py"));
        assertTrue(allowed("clean-code/data/rules.json"));
        assertTrue(allowed("x/notes.txt"));
        assertTrue(allowed("x/table.csv"));
    }

    @Test
    @DisplayName("白名单外的类型一律拒绝")
    void rejectsNonWhitelisted() throws Exception {
        assertFalse(allowed("x/evil.exe"), "可执行文件不得落地");
        assertFalse(allowed("x/lib.dll"));
        assertFalse(allowed("x/run.sh"), "shell 脚本不在支持范围内");
        assertFalse(allowed("x/run.bat"));
        assertFalse(allowed("x/id_rsa"), "无扩展名一律拒绝");
        assertFalse(allowed("x/archive.zip"));
    }

    @Test
    @DisplayName("扩展名判定大小写不敏感")
    void caseInsensitive() throws Exception {
        assertTrue(allowed("x/README.MD"));
        assertTrue(allowed("x/Script.PY"));
    }

    @Test
    @DisplayName("只看最后一个点后的扩展名，不被复合名骗过")
    void usesLastExtension() throws Exception {
        // "evil.py.exe" 的真实类型是 exe，必须拒绝
        assertFalse(allowed("x/evil.py.exe"));
        assertTrue(allowed("x/report.v2.md"));
        assertEquals(true, allowed("a.b.c/deep/file.py"));
    }
}
