package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.OpsCommandLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 运维命令记录仓库（ops_command_logs，V25）：追加型日志，只写与按组织+服务器分页读。
 */
public interface OpsCommandLogRepository extends JpaRepository<OpsCommandLogEntity, Long> {

    /** 组织+服务器维度分页（执行时间倒序，同秒内按 id 倒序保证稳定）。 */
    Page<OpsCommandLogEntity> findByOrgIdAndServerKeyOrderByExecutedAtDescIdDesc(
            Long orgId, String serverKey, Pageable pageable);
}
