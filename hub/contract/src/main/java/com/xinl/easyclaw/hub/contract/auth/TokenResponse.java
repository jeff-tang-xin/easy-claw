package com.xinl.easyclaw.hub.contract.auth;

import com.xinl.easyclaw.hub.contract.org.OrgDto;
import com.xinl.easyclaw.hub.contract.user.UserDto;
import java.util.List;

/**
 * 登录/刷新成功的令牌响应。
 */
public record TokenResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn,
        UserDto user,
        List<OrgDto> orgs) {

    public static TokenResponse of(String accessToken, String refreshToken, long expiresIn, UserDto user, List<OrgDto> orgs) {
        return new TokenResponse(accessToken, refreshToken, "Bearer", expiresIn, user, orgs);
    }
}
