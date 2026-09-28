package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * 平台管理员守卫：平台目录（菜单/功能开关/工具）端点的统一鉴权入口。
 * 目录为平台级资源，读写均仅 platformAdmin 可用（组织侧开关走各自组织端点，与此无关）。
 */
@Component
public class PlatformAdminGuard {

    private final UserRepository users;

    public PlatformAdminGuard(UserRepository users) {
        this.users = users;
    }

    /** 校验请求用户为平台管理员，否则 403。 */
    public void require(Long userId) {
        boolean admin = users.findById(userId).map(u -> u.isPlatformAdmin()).orElse(false);
        if (!admin) {
            throw ApiException.forbidden("仅平台管理员可操作");
        }
    }
}
