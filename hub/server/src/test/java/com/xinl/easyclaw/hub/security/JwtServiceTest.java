package com.xinl.easyclaw.hub.security;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JwtService 纯单元测试（不起 Spring 上下文）：签发/校验/声明携带/篡改与过期拒绝。
 */
class JwtServiceTest {

    private static JwtService newService(long accessTtlMinutes) {
        JwtProperties props = new JwtProperties();
        props.setSecret("unit-test-secret");
        props.setAccessTtlMinutes(accessTtlMinutes);
        return new JwtService(props);
    }

    @Test
    void createAccessToken_carriesSubjectUsernameAndOrg() {
        JwtService svc = newService(30);
        String token = svc.createAccessToken(42L, "alice", 7L, false);

        DecodedJWT jwt = svc.verify(token);
        assertEquals("42", jwt.getSubject());
        assertEquals("alice", jwt.getClaim("username").asString());
        assertEquals(7L, jwt.getClaim("act_org").asLong());
        assertFalse(jwt.getClaim("mcp").asBoolean());
        assertEquals(1800L, svc.getAccessTtlSeconds());
    }

    @Test
    void createAccessToken_mustChangePassword_carriesMcpClaim() {
        JwtService svc = newService(30);
        DecodedJWT jwt = svc.verify(svc.createAccessToken(1L, "bob", null, true));
        assertEquals(Boolean.TRUE, jwt.getClaim("mcp").asBoolean());
    }

    @Test
    void createAccessToken_withoutOrg_omitsClaim() {
        JwtService svc = newService(30);
        DecodedJWT jwt = svc.verify(svc.createAccessToken(1L, "bob", null, false));
        assertTrue(jwt.getClaim("act_org").isNull());
        assertNull(jwt.getClaim("act_org").asLong());
    }

    @Test
    void verify_tamperedToken_throws() {
        JwtService svc = newService(30);
        String token = svc.createAccessToken(1L, "bob", null, false);
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertThrows(JWTVerificationException.class, () -> svc.verify(tampered));
    }

    @Test
    void verify_wrongSecret_throws() {
        String token = newService(30).createAccessToken(1L, "bob", null, false);
        JwtProperties other = new JwtProperties();
        other.setSecret("another-secret");
        other.setAccessTtlMinutes(30);
        assertThrows(JWTVerificationException.class, () -> new JwtService(other).verify(token));
    }

    @Test
    void verify_expiredToken_throws() {
        JwtService svc = newService(-1);
        String token = svc.createAccessToken(1L, "bob", null, false);
        assertThrows(JWTVerificationException.class, () -> svc.verify(token));
    }
}
