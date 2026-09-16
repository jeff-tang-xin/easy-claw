package com.xinl.easyclaw.hub.config;

import com.xinl.easyclaw.hub.common.AuditModule;
import com.xinl.easyclaw.hub.service.AuditService;
import com.xinl.easyclaw.hub.entity.UserEntity;
import com.xinl.easyclaw.hub.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import com.xinl.easyclaw.hub.service.TempPasswords;

/**
 * 启动引导：系统没有任何平台管理员时保证存在一个（用户名唯一 admin，不派生 admin-2/-3）：
 * 已有名为 admin 的用户则直接提升为平台管理员（不重置其密码），否则创建 admin（随机初始密码只打印一次
 * 到控制台，首登强制改密）。判定条件是「无平台管理员」而非「无用户」：覆盖老库升级场景（已有用户但无
 * 平台管理员，否则注册端点取消后将无人能添加用户）。
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminBootstrap(UserRepository users, PasswordEncoder passwordEncoder, AuditService auditService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.existsByPlatformAdminTrue()) {
            return;
        }
        UserEntity existing = users.findByUsername("admin").orElse(null);
        if (existing != null) {
            existing.setPlatformAdmin(true);
            users.save(existing);
            log.warn("系统无平台管理员：已将现有用户 admin（id={}）提升为平台管理员（密码不变）", existing.getId());
            auditService.record(AuditModule.AUTH, "bootstrap_admin", existing.getId(), null, "user",
                    String.valueOf(existing.getId()), "promote existing username=admin", AuditModule.SUCCESS);
            return;
        }
        String tempPassword = TempPasswords.generate();
        UserEntity u = new UserEntity();
        u.setUsername("admin");
        u.setDisplayName("平台管理员");
        u.setPasswordHash(passwordEncoder.encode(tempPassword));
        u.setPlatformAdmin(true);
        u.setMustChangePassword(true);
        users.save(u);

        String banner = "\n==============================================================\n"
                + "  Easy-Claw Hub 初始平台管理员已创建\n"
                + "  用户名: admin\n"
                + "  初始密码: " + tempPassword + "\n"
                + "  首次登录后必须修改密码；本提示只出现一次，请妥善保存。\n"
                + "==============================================================";
        System.out.println(banner);
        log.warn(banner);
        auditService.record(AuditModule.AUTH, "bootstrap_admin", u.getId(), null, "user", String.valueOf(u.getId()),
                "username=admin", AuditModule.SUCCESS);
    }
}
