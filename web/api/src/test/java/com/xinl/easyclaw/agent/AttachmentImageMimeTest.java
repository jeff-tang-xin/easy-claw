package com.xinl.easyclaw.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 覆盖「图片传输损坏」缺陷：附件 mimeType 缺失时必须仍被识别为图片。
 *
 * <p>缺陷成因：{@code buildUserMessage} 原判据为 {@code mimeType.startsWith("image/")}，
 * 浏览器在 Windows 缺注册表项时 {@code File.type} 为空串，图片因此落入文本分支，
 * 被 UTF-8 强解为乱码送给模型。
 */
class AttachmentImageMimeTest {

    /** 构造带指定魔数的伪图片字节（长度补足到 12，满足嗅探最小长度）。 */
    private static byte[] withHeader(int... header) {
        byte[] d = new byte[16];
        for (int i = 0; i < header.length; i++) {
            d[i] = (byte) header[i];
        }
        return d;
    }

    private static byte[] png() {
        return withHeader(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    }

    @Test
    @DisplayName("声明了 image/* 时直接采信声明值")
    void declaredImageMimeWins() {
        assertEquals("image/png", AgentService.resolveImageMime("image/png", "a.png", png()));
        // 声明优先于魔数：声明 jpeg 而内容是 png 时仍返回声明值，避免与前端展示不一致
        assertEquals("image/jpeg", AgentService.resolveImageMime("image/jpeg", "a.png", png()));
    }

    @Test
    @DisplayName("核心回归：mimeType 为 null 或空串时靠魔数识别为图片")
    void missingMimeFallsBackToMagicBytes() {
        assertEquals("image/png", AgentService.resolveImageMime(null, "x", png()));
        assertEquals("image/png", AgentService.resolveImageMime("", "x", png()));
        assertEquals("image/png", AgentService.resolveImageMime("   ", "x", png()));
    }

    @Test
    @DisplayName("application/octet-stream 也需靠魔数纠正")
    void octetStreamFallsBackToMagicBytes() {
        assertEquals("image/png", AgentService.resolveImageMime("application/octet-stream", "x", png()));
    }

    @Test
    @DisplayName("各主流图片格式的魔数均可识别")
    void sniffsCommonFormats() {
        assertEquals("image/jpeg", AgentService.resolveImageMime(null, "x", withHeader(0xFF, 0xD8, 0xFF, 0xE0)));
        assertEquals("image/gif", AgentService.resolveImageMime(null, "x", withHeader('G', 'I', 'F', '8')));
        assertEquals("image/bmp", AgentService.resolveImageMime(null, "x", withHeader('B', 'M')));
        assertEquals("image/webp", AgentService.resolveImageMime(null, "x",
                withHeader('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P')));
    }

    @Test
    @DisplayName("魔数不可用时按扩展名兜底")
    void fallsBackToExtension() {
        byte[] noMagic = new byte[16];
        assertEquals("image/png", AgentService.resolveImageMime(null, "shot.PNG", noMagic));
        assertEquals("image/jpeg", AgentService.resolveImageMime(null, "shot.jpeg", noMagic));
    }

    @Test
    @DisplayName("真正的文本附件不得被误判为图片，否则会当作图片发给模型")
    void textAttachmentStaysText() {
        byte[] text = "package com.foo;\nclass A {}\n".getBytes(StandardCharsets.UTF_8);
        assertNull(AgentService.resolveImageMime(null, "A.java", text));
        assertNull(AgentService.resolveImageMime("text/plain", "a.txt", text));
        assertNull(AgentService.resolveImageMime(null, "notes.md", text));
    }

    @Test
    @DisplayName("空数据与超短数据不触发越界")
    void handlesShortInputSafely() {
        assertNull(AgentService.resolveImageMime(null, "x", new byte[0]));
        assertNull(AgentService.resolveImageMime(null, "x", null));
        assertNull(AgentService.resolveImageMime(null, "x", new byte[]{0x11, 0x22}));
    }
}
