package com.xinl.easyclaw.ops.service;

import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

/**
 * 运维连接密码的应用层加密（RSA-OAEP）。
 * <p>
 * <b>背景</b>：运维连接密码此前经浏览器明文 POST 到 {@code /api/ops/connect}（hub 未下发
 * 密码、用户当次手输的场景）。本服务让前端用 RSA 公钥加密后再传，网络路径上不再出现明文：
 * <ul>
 *   <li>spoke 启动时生成 RSA-2048 密钥对，<b>仅存内存</b>（不落盘、不持久化）——重启即换钥，
 *       前端每次连接前现取公钥，无密钥分发/轮换问题；</li>
 *   <li>{@code GET /api/ops/public-key} 下发公钥（X.509 SPKI，Base64）；</li>
 *   <li>前端 WebCrypto {@code RSA-OAEP} + {@code SHA-256} 加密密码，Base64 后随 connect 请求上送；</li>
 *   <li>{@link #decrypt(String)} 私钥解密，明文仅在 SSH 认证瞬间存在。</li>
 * </ul>
 * <p>
 * <b>OAEP 参数必须显式指定</b>：WebCrypto 的 RSA-OAEP(hash=SHA-256) 对主摘要与 MGF1 都用
 * SHA-256；JCE 的 {@code OAEPWithSHA-256AndMGF1Padding} 在部分 Provider 上 MGF1 仍取 SHA-1，
 * 会导致解密失败——故用 {@link OAEPParameterSpec} 显式锁定 SHA-256/MGF1(SHA-256)。
 * <p>
 * <b>边界说明</b>：应用层加密防的是「链路明文落盘/被日志与代理记录」，不能替代传输层 TLS
 * 防主动中间人（公钥本身也经同一通道下发）。生产部署仍应启用 https/wss。
 */
@Service
public class OpsCryptoService {

    private volatile KeyPair keyPair;

    @PostConstruct
    public void init() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        this.keyPair = generator.generateKeyPair();
    }

    /** 前端加密用公钥（X.509 SubjectPublicKeyInfo，DER，Base64） */
    public String publicKeySpkiBase64() {
        PublicKey publicKey = keyPair.getPublic();
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * 解密前端上送的密码密文（Base64(RSA-OAEP-SHA-256(utf8(密码)))）。
     *
     * @throws Exception 密文格式错误/密钥不匹配（如 spoke 重启后前端持旧公钥加密）——调用方转为 400
     */
    public String decrypt(String base64Ciphertext) throws Exception {
        byte[] ciphertext = Base64.getDecoder().decode(base64Ciphertext);
        Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        // 显式 OAEP 参数：与 WebCrypto RSA-OAEP(SHA-256) 逐位对齐（MGF1 也用 SHA-256）
        cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate(), new OAEPParameterSpec(
                "SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    /** 仅供测试/诊断：私钥不出本类，此方法返回算法标识 */
    public String algorithm() {
        PrivateKey privateKey = keyPair.getPrivate();
        return privateKey.getAlgorithm() + "/" + privateKey.getFormat();
    }
}
