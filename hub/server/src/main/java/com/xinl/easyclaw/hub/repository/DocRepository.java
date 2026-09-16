package com.xinl.easyclaw.hub.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import com.xinl.easyclaw.hub.entity.DocEntity;

public interface DocRepository extends JpaRepository<DocEntity, Long> {

    List<DocEntity> findByProjectIdOrderByIdDesc(Long projectId);

    List<DocEntity> findByProjectIdAndDocTypeOrderByIdDesc(Long projectId, String docType);

    /**
     * 乐观锁条件更新：仅当库内版本仍等于 expected 时推进内容并把 version+1。
     * 返回受影响行数（0 即版本冲突）。updated_at 同步推进。
     */
    @Modifying
    @Query("UPDATE DocEntity d SET d.title = :title, d.content = :content, d.version = :expected + 1, "
            + "d.updatedAt = CURRENT_TIMESTAMP WHERE d.id = :id AND d.version = :expected")
    int updateContentIfVersion(Long id, String title, String content, Long expected);
}
