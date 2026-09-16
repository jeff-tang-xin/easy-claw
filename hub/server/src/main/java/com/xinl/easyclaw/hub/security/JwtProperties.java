package com.xinl.easyclaw.hub.security;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 配置（application.yml 的 hub.jwt.*）。密钥走环境变量，绝不入库/入仓。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "hub.jwt")
public class JwtProperties {

    /** 签名密钥（HMAC256）。 */
    private String secret;

    /** access token 有效期（分钟）。 */
    private long accessTtlMinutes = 30;

    /** refresh token 有效期（天）。 */
    private long refreshTtlDays = 30;
}
