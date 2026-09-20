package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.org.MemberDto;
import com.xinl.easyclaw.hub.contract.org.OrgDto;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.xinl.easyclaw.hub.common.Slugger;
import com.xinl.easyclaw.hub.entity.MembershipEntity;
import com.xinl.easyclaw.hub.entity.OrganizationEntity;
import com.xinl.easyclaw.hub.repository.MembershipRepository;
import com.xinl.easyclaw.hub.repository.OrganizationRepository;

/**
 * 组织与成员管理。角色判定唯一权威在服务端（查 memberships），不依赖客户端。
 * 组织/成员的变更（建组织、加成员、改角色、移除）记审计。
 */
@Service
public class OrgService {

    private static final Set<String> VALID_ROLES = Set.of("owner", "admin", "member", "guest");

    private final OrganizationRepository org;
    private final MembershipRepository memberships;
    private final UserRepository users;
    private final AuditService auditService;

    public OrgService(OrganizationRepository orgs, MembershipRepository memberships, UserRepository users,
                      AuditService auditService) {
        this.org = orgs;
        this.memberships = memberships;
        this.users = users;
        this.auditService = auditService;
    }

    /** 我所在的组织列表（含我在各组织的角色）。 */
    public List<OrgDto> listMyOrgs(Long userId) {
        List<OrgDto> out = new ArrayList<>();
        for (MembershipEntity m : memberships.findByUserId(userId)) {
            org.findById(m.getOrgId()).ifPresent(o ->
                    out.add(new OrgDto(o.getId(), o.getName(), o.getSlug(), m.getRole(), o.getPlan())));
        }
        return out;
    }

    /** 建组织：创建者即 owner，并写入 owner 成员关系。slug 留空时从名称派生并保证全局唯一。 */
    @Transactional
    public OrgDto createOrg(Long ownerId, CreateOrgRequest req) {
        String slug = resolveOrgSlug(req.name(), req.slug());
        OrganizationEntity o = new OrganizationEntity();
        o.setName(req.name());
        o.setSlug(slug);
        o.setOwnerUserId(ownerId);
        org.save(o);

        MembershipEntity m = new MembershipEntity();
        m.setOrgId(o.getId());
        m.setUserId(ownerId);
        m.setRole("owner");
        memberships.save(m);
        auditService.record(AuditModule.ORG, "create_org", ownerId, o.getId(), "organization", String.valueOf(o.getId()),
                "name=" + o.getName() + ",slug=" + o.getSlug(), AuditModule.SUCCESS);
        return new OrgDto(o.getId(), o.getName(), o.getSlug(), "owner", o.getPlan());
    }

    /** 显式 slug 校验全局唯一；留空则从名称派生（纯中文名兜底 "org"），重名自动追加 -2/-3… 保证唯一。 */
    private String resolveOrgSlug(String name, String requested) {
        if (requested != null && !requested.isBlank()) {
            String slug = requested.trim();
            if (Slugger.isValid(slug)) {
                throw ApiException.validation("slug 只能包含小写字母/数字/连字符");
            }
            if (org.existsBySlug(slug)) {
                throw ApiException.conflict("组织 slug 已存在");
            }
            return slug;
        }
        String base = Slugger.derive(name, "org");
        String candidate = base;
        for (int i = 2; org.existsBySlug(candidate); i++) {
            candidate = base + "-" + i;
        }
        return candidate;
    }

    /** 成员列表：仅组织成员可看。 */
    public List<MemberDto> listMembers(Long requesterId, Long orgId) {
        requireMembership(orgId, requesterId);
        List<MemberDto> out = new ArrayList<>();
        for (MembershipEntity m : memberships.findByOrgId(orgId)) {
            users.findById(m.getUserId()).ifPresent(u ->
                    out.add(new MemberDto(u.getId(), u.getUsername(), u.getDisplayName(), m.getRole(), ldt(m.getCreatedAt()))));
        }
        return out;
    }

    /** 加成员：owner/admin。 */
    @Transactional
    public void addMember(Long requesterId, Long orgId, AddMemberRequest req) {
        requireRole(orgId, requesterId, "owner", "admin");
        String role = req.role() == null || req.role().isBlank() ? "member" : req.role();
        validateRole(role);
        UserEntity target = users.findByUsernameOrEmail(req.usernameOrEmail(), req.usernameOrEmail())
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (memberships.existsByOrgIdAndUserId(orgId, target.getId())) {
            throw ApiException.conflict("用户已是组织成员");
        }
        MembershipEntity m = new MembershipEntity();
        m.setOrgId(orgId);
        m.setUserId(target.getId());
        m.setRole(role);
        memberships.save(m);
        auditService.record(AuditModule.ORG, "add_member", requesterId, orgId, "user", String.valueOf(target.getId()),
                "role=" + role, AuditModule.SUCCESS);
    }

    /** 改角色：owner/admin；owner 不可被降权，A0 不支持直接任命 owner（需转移所有权）。 */
    @Transactional
    public void updateMemberRole(Long requesterId, Long orgId, Long targetUserId, String role) {
        requireRole(orgId, requesterId, "owner", "admin");
        validateRole(role);
        MembershipEntity m = memberships.findByOrgIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("成员不存在"));
        if ("owner".equals(m.getRole())) {
            throw ApiException.forbidden("owner 不可被降权");
        }
        if ("owner".equals(role)) {
            throw ApiException.forbidden("不能直接任命 owner（需转移所有权）");
        }
        String oldRole = m.getRole();
        m.setRole(role);
        memberships.save(m);
        auditService.record(AuditModule.ORG, "update_member_role", requesterId, orgId, "user", String.valueOf(targetUserId),
                "role:" + oldRole + "->" + role, AuditModule.SUCCESS);
    }

    /** 移除成员：owner/admin；不能移除 owner。 */
    @Transactional
    public void removeMember(Long requesterId, Long orgId, Long targetUserId) {
        requireRole(orgId, requesterId, "owner", "admin");
        MembershipEntity m = memberships.findByOrgIdAndUserId(orgId, targetUserId)
                .orElseThrow(() -> ApiException.notFound("成员不存在"));
        if ("owner".equals(m.getRole())) {
            throw ApiException.forbidden("不能移除 owner");
        }
        memberships.delete(m);
        auditService.record(AuditModule.ORG, "remove_member", requesterId, orgId, "user", String.valueOf(targetUserId),
                "role=" + m.getRole(), AuditModule.SUCCESS);
    }

    /** 当前用户在该组织的角色；非成员返回 null。供 project/me 等做角色判定。 */
    public String roleOf(Long orgId, Long userId) {
        return memberships.findByOrgIdAndUserId(orgId, userId)
                .map(MembershipEntity::getRole)
                .orElse(null);
    }

    /** 角色门槛校验的公开入口（供 appkey 等其它服务复用）：内部转调私有实现，不新增判定逻辑。 */
    public void requireOrgRole(Long orgId, Long userId, String... allowed) {
        requireRole(orgId, userId, allowed);
    }

    private MembershipEntity requireMembership(Long orgId, Long userId) {
        return memberships.findByOrgIdAndUserId(orgId, userId)
                .orElseThrow(() -> ApiException.forbidden("非组织成员"));
    }

    private void requireRole(Long orgId, Long userId, String... allowed) {
        MembershipEntity m = requireMembership(orgId, userId);
        for (String a : allowed) {
            if (a.equals(m.getRole())) {
                return;
            }
        }
        throw ApiException.forbidden("权限不足（需 owner/admin）");
    }

    private static void validateRole(String role) {
        if (!VALID_ROLES.contains(role)) {
            throw ApiException.validation("非法角色：" + role);
        }
    }

    private static LocalDateTime ldt(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
