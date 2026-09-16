package com.xinl.easyclaw.hub.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import com.xinl.easyclaw.hub.entity.DocEventEntity;

public interface DocEventRepository extends JpaRepository<DocEventEntity, Long> {

    List<DocEventEntity> findByDocIdOrderByVersionDesc(Long docId);
}
