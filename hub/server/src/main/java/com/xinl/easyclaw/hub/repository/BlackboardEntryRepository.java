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

    /** 项目内指定登记人+本+状态的 workspace 条目，按 id 升序（V29 spoke 读端点：个人本）。 */
    List<BlackboardEntryEntity> findByProjectIdAndSourceAndAuthorUserIdAndBookKeyAndStatusOrderByIdAsc(
            Long projectId, String source, Long authorUserId, String bookKey, String status);

    /** 项目内指定登记人的 workspace 条目，按 id 升序（V29 spoke books 端点：个人本清单分组）。 */
    List<BlackboardEntryEntity> findByProjectIdAndSourceAndAuthorUserIdAndStatusOrderByIdAsc(
            Long projectId, String source, Long authorUserId, String status);

    /** 项目内指定登记人+本（source=workspace）的活跃条目（V29 spoke 归档整本用）。 */
    List<BlackboardEntryEntity> findByProjectIdAndSourceAndAuthorUserIdAndBookKeyAndStatus(
            Long projectId, String source, Long authorUserId, String bookKey, String status);
}