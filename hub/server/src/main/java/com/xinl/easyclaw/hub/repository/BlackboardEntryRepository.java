package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.BlackboardEntryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlackboardEntryRepository extends JpaRepository<BlackboardEntryEntity, Long> {

    /** 项目下指定状态的条目，按 id 倒序（追加型，id 即时间序）。 */
    List<BlackboardEntryEntity> findByProjectIdAndStatusOrderByIdDesc(Long projectId, String status);

    /** 项目下指定本+状态的条目，按 id 升序（spoke 读端点：id 即时间序，seq 动态编号）。 */
    List<BlackboardEntryEntity> findByProjectIdAndBookKeyAndStatusOrderByIdAsc(
            Long projectId, String bookKey, String status);

    /** 项目下指定状态的全部条目，按 id 升序（spoke books 端点：内存按 bookKey 分组）。 */
    List<BlackboardEntryEntity> findByProjectIdAndStatusOrderByIdAsc(Long projectId, String status);

    /** 项目内指定本（source=workspace）的活跃条目（spoke 归档整本用）。 */
    List<BlackboardEntryEntity> findByProjectIdAndSourceAndBookKeyAndStatus(
            Long projectId, String source, String bookKey, String status);
}