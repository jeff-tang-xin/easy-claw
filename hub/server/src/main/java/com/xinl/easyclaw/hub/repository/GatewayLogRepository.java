package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.GatewayLogEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GatewayLogRepository extends JpaRepository<GatewayLogEntity, Long>,
        JpaSpecificationExecutor<GatewayLogEntity> {

    /** usage 汇总行：[请求数, 成功数, prompt_tokens 合计, completion_tokens 合计]。 */
    @Query("select count(g), coalesce(sum(case when g.status = 'success' then 1 else 0 end), 0), "
            + "coalesce(sum(g.promptTokens), 0), coalesce(sum(g.completionTokens), 0) "
            + "from GatewayLogEntity g where g.orgId = :orgId and g.createdAt >= :since")
    List<Object[]> summarizeUsage(@Param("orgId") Long orgId, @Param("since") Instant since);

    /** usage 按 model 分组行：[model, 请求数, prompt 合计, completion 合计]。 */
    @Query("select g.model, count(g), coalesce(sum(g.promptTokens), 0), coalesce(sum(g.completionTokens), 0) "
            + "from GatewayLogEntity g where g.orgId = :orgId and g.createdAt >= :since "
            + "group by g.model order by count(g) desc")
    List<Object[]> summarizeUsageByModel(@Param("orgId") Long orgId, @Param("since") Instant since);
}
