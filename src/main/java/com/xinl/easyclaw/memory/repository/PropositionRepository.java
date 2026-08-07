package com.xinl.easyclaw.memory.repository;

import com.xinl.easyclaw.memory.entity.PropositionEntity;
import com.xinl.easyclaw.memory.entity.PropositionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface PropositionRepository extends JpaRepository<PropositionEntity, Long> {

    // 按用户查询
    List<PropositionEntity> findByUserId(String userId);

    Page<PropositionEntity> findByUserId(String userId, Pageable pageable);

    // 按用户和类型查询
    List<PropositionEntity> findByUserIdAndType(String userId, PropositionType type);

    // 按置信度筛选
    List<PropositionEntity> findByUserIdAndConfidenceGreaterThan(String userId, Double threshold);

    // 查询需要遗忘的记忆 (时间 + 引用次数)
    List<PropositionEntity> findByUserIdAndLastAccessedBeforeAndReferenceCountLessThan(
        String userId,
        Instant threshold,
        int minReferenceCount
    );

    // 统计用户的记忆数量
    @Query("SELECT COUNT(p) FROM PropositionEntity p WHERE p.userId = :userId")
    long countByUserId(@Param("userId") String userId);

    // 按类型统计
    @Query("SELECT p.type, COUNT(p) FROM PropositionEntity p WHERE p.userId = :userId GROUP BY p.type")
    List<Object[]> countByType(@Param("userId") String userId);

    // 最近 N 天新增的记忆
    @Query("SELECT p FROM PropositionEntity p WHERE p.userId = :userId AND p.createdAt > :since")
    List<PropositionEntity> findRecentByUserId(@Param("userId") String userId, @Param("since") Instant since);
}
