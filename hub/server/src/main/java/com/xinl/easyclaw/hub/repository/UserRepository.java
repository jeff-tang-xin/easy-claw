package com.xinl.easyclaw.hub.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    /** 登录按用户名或邮箱定位（service 层把同一值传两遍）。 */
    Optional<UserEntity> findByUsernameOrEmail(String username, String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    /** 启动引导判定：是否已存在平台管理员。 */
    boolean existsByPlatformAdminTrue();
}
