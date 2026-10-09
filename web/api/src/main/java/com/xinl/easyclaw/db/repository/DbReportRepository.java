package com.xinl.easyclaw.db.repository;

import com.xinl.easyclaw.db.entity.DbReportEntity;
import com.xinl.easyclaw.db.service.DbReportMeta;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DB 报表仓库（V32）。列表查询用构造器投影（{@link DbReportMeta}）避开
 * htmlContent 大字段；详情按 (id, workspaceId) 双条件——跨工作区访问一律查不到。
 */
@Repository
public interface DbReportRepository extends JpaRepository<DbReportEntity, Long> {

    @Query("select new com.xinl.easyclaw.db.service.DbReportMeta("
            + "r.id, r.title, r.serverName, r.dbType, r.databaseName, r.createdAt, length(r.htmlContent)) "
            + "from DbReportEntity r where r.workspaceId = :workspaceId order by r.createdAt desc")
    List<DbReportMeta> listMeta(@Param("workspaceId") String workspaceId);

    Optional<DbReportEntity> findByIdAndWorkspaceId(Long id, String workspaceId);
}
