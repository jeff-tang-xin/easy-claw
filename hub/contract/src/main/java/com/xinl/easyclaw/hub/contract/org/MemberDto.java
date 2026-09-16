package com.xinl.easyclaw.hub.contract.org;

import java.time.LocalDateTime;

/**
 * 组织成员视图。
 */
public record MemberDto(Long userId, String username, String displayName, String role, LocalDateTime joinedAt) {
}
