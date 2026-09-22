package com.xinl.easyclaw.hub.security;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 角色 → 权限清单（§4.3，服务端唯一权威；控制台菜单显隐仅为体验）。
 * 组织角色权限按 memberships.role 判定；平台管理员在此基础上叠加 {@link #PLATFORM_ADMIN_PERMS}。
 */
public final class Permissions {

    private Permissions() {
    }

    /** 平台管理员叠加权限：平台级资源（用户 / provider 池 / 平台目录）管理。 */
    public static final Set<String> PLATFORM_ADMIN_PERMS =
            Set.of("user.manage", "provider.manage", "platform.catalog.manage");

    /** 组织角色固定顺序（权限从高到低），供角色-权限矩阵等只读展示复用。 */
    public static final List<String> ORG_ROLES = List.of("owner", "admin", "member", "guest");

    public static Set<String> forRole(String role) {
        if (role == null) {
            return Set.of();
        }
        return switch (role) {
            case "owner" -> Set.of("org.read", "org.manage", "org.delete", "member.manage",
                    "project.read", "project.write", "asset.write", "appkey.self", "appkey.manage",
                    "audit.read", "provider.manage");
            case "admin" -> Set.of("org.read", "org.manage", "member.manage",
                    "project.read", "project.write", "asset.write", "appkey.self", "appkey.manage",
                    "audit.read", "provider.manage");
            // appkey.self：成员可颁发/管理自己的接入密钥；owner/admin 另持 appkey.manage 可治理组织内全部
            case "member" -> Set.of("org.read", "project.read", "project.write", "appkey.self");
            case "guest" -> Set.of("org.read", "project.read");
            default -> Set.of();
        };
    }

    /** 组织角色权限 ∪ 平台管理员权限（platformAdmin=false 时等价 forRole）。返回不可变集合。 */
    public static Set<String> forUser(String role, boolean platformAdmin) {
        Set<String> rolePerms = forRole(role);
        if (!platformAdmin) {
            return rolePerms;
        }
        Set<String> union = new HashSet<>(rolePerms);
        union.addAll(PLATFORM_ADMIN_PERMS);
        return Set.copyOf(union);
    }
}
