package com.xinl.easyclaw.ops.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 运维连接凭证的本地对称加密（AES-256-GCM）。
 * <p>
 * 用户要求连接配置落库且本地加密解密：密钥文件 {@code ~/.easyClaw/ops-crypto.key}
 * 与系统库 {@code ai-assistant.db} 同目录，首次使用时自动生成 32 字节随机密钥（base64 存储）。
 * 密文格式：{@code base64(iv(12B) || AES-256-GCM(plaintext))}，每次加密随机 IV。
 * <p>
 * <b>威胁模型说明</b>：这是「本地静态加密」——拿到密文但拿不到本机密钥文件时无法还原。
 * 密钥与库同目录意味着能读库的进程通常也能读密钥，它防的是「库文件被单独拷走/泄露」，
 * 不防本机 root。备份/迁移时必须把密钥文件和库一起带走。
 */
@Component
public class LocalCryptoService {

    private static final Logger log = LoggerFactory.getLogger(LocalCryptoService.class);
    private static final String ALGO = "AES/GCM/NoPadding";
    private static final String KEY_TYPE = "AES";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;

    public LocalCryptoService() {
        this.key = loadOrCreateKey();
    }

    /** 加密；null/空串原样返回（调用方按「未填写」处理） */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array());
        } catch (Exception e) {
            throw new IllegalStateException("运维凭证加密失败: " + e.getMessage(), e);
        }
    }

    /** 解密；null/空串原样返回。密钥不匹配（换机器没带密钥文件）时抛出明确错误 */
    public String decrypt(String enc) {
        if (enc == null || enc.isEmpty()) {
            return enc;
        }
        try {
            byte[] all = Base64.getDecoder().decode(enc);
            if (all.length <= IV_LEN) {
                throw new IllegalArgumentException("密文长度非法");
            }
            Cipher cipher = Cipher.getInstance(ALGO);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(TAG_BITS, all, 0, IV_LEN));
            byte[] plain = cipher.doFinal(all, IV_LEN, all.length - IV_LEN);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "运维凭证解密失败（密钥文件 ~/.easyClaw/ops-crypto.key 与数据库不匹配？）: " + e.getMessage(), e);
        }
    }

    private static SecretKey loadOrCreateKey() {
        Path path = Paths.get(System.getProperty("user.home"), ".easyClaw", "ops-crypto.key");
        try {
            if (Files.exists(path)) {
                byte[] raw = Base64.getDecoder().decode(Files.readString(path).trim());
                if (raw.length != 32) {
                    throw new IllegalStateException("密钥文件长度非法（应为 32 字节 base64）");
                }
                log.info("已加载运维凭证密钥: {}", path);
                return new SecretKeySpec(raw, KEY_TYPE);
            }
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            Files.createDirectories(path.getParent());
            Files.writeString(path, Base64.getEncoder().encodeToString(raw));
            log.info("首次使用，已生成运维凭证密钥: {}", path);
            return new SecretKeySpec(raw, KEY_TYPE);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("初始化运维凭证密钥失败: " + path + " - " + e.getMessage(), e);
        }
    }
}
