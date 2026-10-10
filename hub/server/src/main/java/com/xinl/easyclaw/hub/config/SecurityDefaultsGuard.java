package com.xinl.easyclaw.hub.config;

import com.xinl.easyclaw.hub.security.JwtProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 启动安全护栏：JWT 签名密钥与敏感字段加密主密钥若仍是仓库里的默认占位值，直接拒绝启动。
 * 默认值只用于本地开发，一旦带上默认值上线，攻击者可伪造任意 JWT / 解密全部密文，属高危缺陷。
 * 生产必须通过环境变量（HUB_JWT_SECRET / HUB_MASTER_KEY）覆盖，此处校验不通过即抛异常阻断启动。
 */
@Component
public class SecurityDefaultsGuard {

    private static final Logger log = LoggerFactory.getLogger(SecurityDefaultsGuard.class);

    /** application.yml 中 HUB_JWT_SECRET 的默认占位值。 */
    private static final String DEFAULT_JWT_SECRET = "change-me-in-prod";
    /** application.yml 中 HUB_MASTER_KEY 的默认占位值。 */
    private static final String DEFAULT_MASTER_KEY = "dev-only-master-key-change-me";

    private final JwtProperties jwtProps;
    private final String masterKey;

    public SecurityDefaultsGuard(JwtProperties jwtProps,
                                 @Value("${hub.security.master-key}") String masterKey) {
        this.jwtProps = jwtProps;
        this.masterKey = masterKey;
    }

    @PostConstruct
    public void guard() {
        if (DEFAULT_JWT_SECRET.equals(jwtProps.getSecret())) {
            throw new IllegalStateException(
                    "hub.jwt.secret 仍为默认占位值（change-me-in-prod），禁止启动：请通过环境变量 HUB_JWT_SECRET 设置强随机密钥");
        }
        if (DEFAULT_MASTER_KEY.equals(masterKey)) {
            throw new IllegalStateException(
                    "hub.security.master-key 仍为默认占位值（dev-only-master-key-change-me），禁止启动：请通过环境变量 HUB_MASTER_KEY 设置强随机主密钥");
        }
        log.info("安全默认值校验通过：JWT 签名密钥与加密主密钥均已由环境变量覆盖");
    }
}