package com.xinl.easyclaw.hub.security;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import org.springframework.stereotype.Service;
import com.xinl.easyclaw.hub.service.AuthService;

/**
 * access token（JWT）签发与校验，无状态（不查库）。refresh token 的签发/旋转在 AuthService。
 */
@Service
public class JwtService {

    private final Algorithm algorithm;
    private final JWTVerifier verifier;
    private final long accessTtlMinutes;

    public JwtService(JwtProperties props) {
        this.algorithm = Algorithm.HMAC256(props.getSecret());
        this.verifier = JWT.require(algorithm).build();
        this.accessTtlMinutes = props.getAccessTtlMinutes();
    }

    public String createAccessToken(Long userId, String username, Long currentOrgId, boolean mustChangePassword) {
        Instant now = Instant.now();
        com.auth0.jwt.JWTCreator.Builder builder = JWT.create()
                .withSubject(String.valueOf(userId))
                .withClaim("username", username)
                .withClaim("mcp", mustChangePassword)
                .withIssuedAt(Date.from(now))
                .withExpiresAt(Date.from(now.plus(accessTtlMinutes, ChronoUnit.MINUTES)));
        if (currentOrgId != null) {
            builder.withClaim("act_org", currentOrgId);
        }
        return builder.sign(algorithm);
    }

    /** 校验并解析；失败抛 {@link com.auth0.jwt.exceptions.JWTVerificationException}。 */
    public DecodedJWT verify(String token) {
        return verifier.verify(token);
    }

    public long getAccessTtlSeconds() {
        return accessTtlMinutes * 60;
    }
}
