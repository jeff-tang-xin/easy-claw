package com.xinl.easyclaw.hub.crypto;

import com.xinl.easyclaw.hub.service.CryptoService;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CryptoService 纯单元测试（不起 Spring 上下文）：AES-256-GCM 加解密往返 / 随机 IV /
 * 异主密钥与损坏密文一律拒绝（IllegalStateException）。
 */
class CryptoServiceTest {

    private static final String KEY1 = "unit-test-master-key-1";
    private static final String KEY2 = "unit-test-master-key-2";

    @Test
    void encryptDecrypt_roundTrip() {
        CryptoService svc = new CryptoService(KEY1);
        String[] samples = {
                "sk-1234567890abcdef",
                "中文密钥-€-mixed",
                "",
                "a",
                "x".repeat(2000),
        };
        for (String plain : samples) {
            assertEquals(plain, svc.decrypt(svc.encrypt(plain)), "加解密往返不一致: " + plain);
        }
    }

    @Test
    void encrypt_samePlaintext_differentCiphertext_randomIv() {
        CryptoService svc = new CryptoService(KEY1);
        String c1 = svc.encrypt("same-plaintext");
        String c2 = svc.encrypt("same-plaintext");
        assertNotEquals(c1, c2, "IV 随机：同明文两次加密密文必须不同");
        // 两份密文各自携带 IV，都能解回原文。
        assertEquals("same-plaintext", svc.decrypt(c1));
        assertEquals("same-plaintext", svc.decrypt(c2));
    }

    @Test
    void decrypt_withDifferentMasterKey_throwsIllegalState() {
        CryptoService svc1 = new CryptoService(KEY1);
        CryptoService svc2 = new CryptoService(KEY2);
        String cipher = svc1.encrypt("secret");
        assertThrows(IllegalStateException.class, () -> svc2.decrypt(cipher));
    }

    @Test
    void decrypt_garbageTruncatedOrTampered_throwsIllegalState() {
        CryptoService svc = new CryptoService(KEY1);
        // 非 Base64。
        assertThrows(IllegalStateException.class, () -> svc.decrypt("!!!not-base64!!!"));
        // 合法 Base64 但不足一个 IV 长度。
        String shortBlob = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
        assertThrows(IllegalStateException.class, () -> svc.decrypt(shortBlob));
        // 篡改密文尾部（GCM 认证失败）。
        byte[] data = Base64.getDecoder().decode(svc.encrypt("tamper-me"));
        data[data.length - 1] ^= 0x01;
        String tampered = Base64.getEncoder().encodeToString(data);
        assertThrows(IllegalStateException.class, () -> svc.decrypt(tampered));
    }
}
