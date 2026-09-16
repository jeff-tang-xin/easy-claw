package com.xinl.easyclaw.hub.service;

import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.common.ApiException;
import com.xinl.easyclaw.hub.contract.auth.ChangePasswordRequest;
import com.xinl.easyclaw.hub.contract.auth.LoginRequest;
import com.xinl.easyclaw.hub.contract.auth.RefreshRequest;
import com.xinl.easyclaw.hub.contract.auth.TokenResponse;
import com.xinl.easyclaw.hub.contract.org.OrgDto;
import com.xinl.easyclaw.hub.contract.org.OrgOptionDto;
import com.xinl.easyclaw.hub.contract.user.AdminCreateUserRequest;
import com.xinl.easyclaw.hub.contract.user.UserDto;
import com.xinl.easyclaw.hub.security.JwtProperties;
import com.xinl.easyclaw.hub.security.JwtService;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.OrganizationRepository;
import com.xinl.easyclaw.hub.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.xinl.easyclaw.hub.config.AdminBootstrap;
import com.xinl.easyclaw.hub.entity.MembershipEntity;
import com.xinl.easyclaw.hub.entity.RefreshTokenEntity;
import com.xinl.easyclaw.hub.repository.MembershipRepository;
import com.xinl.easyclaw.hub.repository.RefreshTokenRepository;

/**
 * 认证：登录/刷新/登出/改密 + 平台管理员用户管理。access=JWT 无状态；refresh=随机串只存 SHA-256 hash，旋转 + 可吊销。
 * 公开注册已取消：初始 admin 由 {@link AdminBootstrap} 引导创建，后续用户由平台管理员添加（临时密码投递邮箱）。
 * 认证链路是成功与失败都记审计的重点模块（登录失败尤其关键）。
 */
@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final MembershipRepository memberships;
    private final OrganizationRepository orgs;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProps;
    private final OrgService orgService;
    private final AuditService auditService;
    private final PasswordMailer passwordMailer;

    public AuthService(UserRepository users, RefreshTokenRepository refreshTokens, MembershipRepository memberships,
                       OrganizationRepository orgs, PasswordEncoder passwordEncoder, JwtService jwtService,
                       JwtProperties jwtProps, OrgService orgService, AuditService auditService,
                       PasswordMailer passwordMailer) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.memberships = memberships;
        this.orgs = orgs;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProps = jwtProps;
        this.orgService = orgService;
        this.auditService = auditService;
        this.passwordMailer = passwordMailer;
    }

    @Transactional
    public TokenResponse login(LoginRequest req) {
        UserEntity u = users.findByUsernameOrEmail(req.usernameOrEmail(), req.usernameOrEmail()).orElse(null);
        // 用户不存在与密码错误走同一分支、同一报错，避免用户枚举；u==null 时短路不再取 hash。
        if (u == null || !passwordEncoder.matches(req.password(), u.getPasswordHash())) {
            auditService.record(AuditModule.AUTH, "login", null, null, "user", null,
                    "登录失败（用户名或密码错误）：" + req.usernameOrEmail(), AuditModule.FAILURE);
            throw ApiException.authInvalid("用户名或密码错误");
        }
        if (!"active".equals(u.getStatus())) {
            auditService.record(AuditModule.AUTH, "login", u.getId(), null, "user", String.valueOf(u.getId()),
                    "账号已被禁用", AuditModule.FAILURE);
            throw ApiException.authInvalid("账号已被禁用");
        }
        auditService.record(AuditModule.AUTH, "login", u.getId(), null, "user", String.valueOf(u.getId()),
                null, AuditModule.SUCCESS);
        return issueTokens(u);
    }

    /** 刷新：校验旧 refresh（hash 匹配 + 未吊销 + 未过期），旋转签发新对。 */
    @Transactional
    public TokenResponse refresh(RefreshRequest req) {
        RefreshTokenEntity rt = refreshTokens.findByTokenHash(sha256(req.refreshToken())).orElse(null);
        if (rt == null) {
            auditService.record(AuditModule.AUTH, "refresh", null, null, "user", null,
                    "refresh token 无效", AuditModule.FAILURE);
            throw ApiException.authInvalid("refresh token 无效");
        }
        if (rt.isRevoked()) {
            auditService.record(AuditModule.AUTH, "refresh", rt.getUserId(), null, "user", String.valueOf(rt.getUserId()),
                    "refresh token 已吊销", AuditModule.FAILURE);
            throw ApiException.authInvalid("refresh token 已吊销");
        }
        if (rt.getExpiresAt().isBefore(Instant.now())) {
            auditService.record(AuditModule.AUTH, "refresh", rt.getUserId(), null, "user", String.valueOf(rt.getUserId()),
                    "refresh token 已过期", AuditModule.FAILURE);
            throw ApiException.authInvalid("refresh token 已过期");
        }
        UserEntity u = users.findById(rt.getUserId())
                .orElseThrow(() -> ApiException.authInvalid("用户不存在"));
        rt.setRevoked(true);
        refreshTokens.save(rt);
        auditService.record(AuditModule.AUTH, "refresh", u.getId(), null, "user", String.valueOf(u.getId()),
                null, AuditModule.SUCCESS);
        return issueTokens(u);
    }

    /** 登出：吊销传入的 refresh token（幂等，不存在也成功）。操作者从 token 反查，不依赖请求上下文。 */
    @Transactional
    public void logout(RefreshRequest req) {
        refreshTokens.findByTokenHash(sha256(req.refreshToken())).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokens.save(rt);
            auditService.record(AuditModule.AUTH, "logout", rt.getUserId(), null, "user", String.valueOf(rt.getUserId()),
                    null, AuditModule.SUCCESS);
        });
    }

    /** 改密（含首登强制改密）：校验旧密码 → 更新 hash → 清除强制标记 → 吊销全部 refresh token（各端重新登录）。 */
    @Transactional
    public void changePassword(Long userId, ChangePasswordRequest req) {
        UserEntity u = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (!passwordEncoder.matches(req.oldPassword(), u.getPasswordHash())) {
            auditService.record(AuditModule.AUTH, "change_password", userId, null, "user", String.valueOf(userId),
                    "旧密码错误", AuditModule.FAILURE);
            throw ApiException.validation("旧密码错误");
        }
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        u.setMustChangePassword(false);
        users.save(u);
        List<RefreshTokenEntity> tokens = refreshTokens.findByUserId(userId);
        for (RefreshTokenEntity rt : tokens) {
            rt.setRevoked(true);
        }
        refreshTokens.saveAll(tokens);
        auditService.record(AuditModule.AUTH, "change_password", userId, null, "user", String.valueOf(userId),
                null, AuditModule.SUCCESS);
    }

    /** 平台管理员创建用户：临时密码服务端生成并投递邮箱，首登强制改密；orgId 给了则同时加入组织（默认 member）。 */
    @Transactional
    public UserDto adminCreateUser(Long actorId, AdminCreateUserRequest req) {
        requirePlatformAdmin(actorId);
        if (users.existsByUsername(req.username())) {
            throw ApiException.conflict("用户名已被占用");
        }
        if (users.existsByEmail(req.email())) {
            throw ApiException.conflict("邮箱已被占用");
        }
        Long orgId = req.orgId();
        String role = "member";
        if (orgId != null) {
            if (!orgs.existsById(orgId)) {
                throw ApiException.validation("组织不存在：" + orgId);
            }
            if (req.role() != null && !req.role().isBlank()) {
                role = req.role().trim();
            }
            // 与 updateMemberRole 同规则：建用户不能直接任命 owner（owner 由建组织/转让产生）
            if (!"member".equals(role) && !"admin".equals(role) && !"guest".equals(role)) {
                throw ApiException.validation("非法角色：" + role + "（可取 member|admin|guest）");
            }
        }
        String tempPassword = TempPasswords.generate();
        UserEntity u = new UserEntity();
        u.setUsername(req.username());
        u.setEmail(req.email());
        u.setDisplayName(req.displayName());
        u.setPasswordHash(passwordEncoder.encode(tempPassword));
        u.setMustChangePassword(true);
        users.save(u);
        if (orgId != null) {
            MembershipEntity m = new MembershipEntity();
            m.setOrgId(orgId);
            m.setUserId(u.getId());
            m.setRole(role);
            memberships.save(m);
        }
        passwordMailer.sendInitialPassword(u.getEmail(), u.getUsername(), tempPassword);
        auditService.record(AuditModule.AUTH, "admin_create_user", actorId, orgId, "user", String.valueOf(u.getId()),
                "username=" + u.getUsername() + (orgId != null ? ",orgRole=" + role : ""), AuditModule.SUCCESS);
        return toUserDto(u);
    }

    /** 全量组织选项（仅平台管理员）：添加用户/创建 provider 表单的组织下拉数据源。 */
    @Transactional(readOnly = true)
    public List<OrgOptionDto> adminOrgOptions(Long actorId) {
        requirePlatformAdmin(actorId);
        return orgs.findAll(Sort.by("id")).stream()
                .map(o -> new OrgOptionDto(o.getId(), o.getName(), o.getSlug()))
                .toList();
    }

    /** 用户列表（仅平台管理员）。 */
    @Transactional(readOnly = true)
    public List<UserDto> adminListUsers(Long actorId) {
        requirePlatformAdmin(actorId);
        return users.findAll(Sort.by("id")).stream().map(AuthService::toUserDto).toList();
    }

    /**
     * 平台管理员删除用户：物理删除并级联清理成员关系与 refresh token（库层无外键，应用层保证一致性）。
     * 保护：不能删除当前登录用户，也不能删除平台管理员。审计日志保留（actor 仅冗余 username，不受删除影响）。
     * 遗留：被删用户已签发的 access token 在 TTL（30 分钟）内仍有效，refresh 已全清无法续期。
     */
    @Transactional
    public void adminDeleteUser(Long actorId, Long targetUserId) {
        requirePlatformAdmin(actorId);
        if (actorId.equals(targetUserId)) {
            throw ApiException.forbidden("不能删除当前登录用户");
        }
        UserEntity target = users.findById(targetUserId)
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (target.isPlatformAdmin()) {
            throw ApiException.forbidden("不能删除平台管理员");
        }
        memberships.deleteByUserId(targetUserId);
        refreshTokens.deleteByUserId(targetUserId);
        users.delete(target);
        auditService.record(AuditModule.USER, "admin_delete_user", actorId, null, "user", String.valueOf(targetUserId),
                "username=" + target.getUsername(), AuditModule.SUCCESS);
    }

    private void requirePlatformAdmin(Long userId) {
        UserEntity u = users.findById(userId)
                .orElseThrow(() -> ApiException.notFound("用户不存在"));
        if (!u.isPlatformAdmin()) {
            throw ApiException.forbidden("仅平台管理员可管理用户");
        }
    }

    private TokenResponse issueTokens(UserEntity u) {
        List<OrgDto> orgs = orgService.listMyOrgs(u.getId());
        Long actOrg = orgs.isEmpty() ? null : orgs.get(0).id();
        String access = jwtService.createAccessToken(u.getId(), u.getUsername(), actOrg, u.isMustChangePassword());

        String refreshPlain = newRefreshTokenString();
        RefreshTokenEntity rt = new RefreshTokenEntity();
        rt.setUserId(u.getId());
        rt.setTokenHash(sha256(refreshPlain));
        rt.setExpiresAt(Instant.now().plus(jwtProps.getRefreshTtlDays(), ChronoUnit.DAYS));
        refreshTokens.save(rt);

        return TokenResponse.of(access, refreshPlain, jwtService.getAccessTtlSeconds(), toUserDto(u), orgs);
    }

    private static String newRefreshTokenString() {
        byte[] buf = new byte[48];
        RANDOM.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static UserDto toUserDto(UserEntity u) {
        return new UserDto(u.getId(), u.getUsername(), u.getEmail(), u.getDisplayName(), u.getStatus(),
                u.isPlatformAdmin(), u.isMustChangePassword());
    }
}
