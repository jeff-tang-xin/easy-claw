package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.BlackboardEntryEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BlackboardEntryRepository extends JpaRepository<BlackboardEntryEntity, Long> {

    /** 项目下指定状态的条目，按 id 倒序（追加型，id 即时间序）。 */
    List<BlackboardEntryEntity> findByProjectIdAndStatusOrderByIdDesc(Long projectId, String status);
}