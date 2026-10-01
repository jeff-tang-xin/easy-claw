package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.DbQueryLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DbQueryLogRepository extends JpaRepository<DbQueryLogEntity, Long> {

    /** 按连接分页审计查询：org_id + server_key 过滤，执行时间倒序（管理端主路径）。 */
    Page<DbQueryLogEntity> findByOrgIdAndServerKeyOrderByExecutedAtDescIdDesc(
            Long orgId, String serverKey, Pageable pageable);
}
