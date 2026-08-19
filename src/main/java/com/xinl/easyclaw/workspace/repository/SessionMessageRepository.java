package com.xinl.easyclaw.workspace.repository;

import com.xinl.easyclaw.workspace.entity.SessionMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface SessionMessageRepository extends JpaRepository<SessionMessageEntity, Long> {

    List<SessionMessageEntity> findBySessionIdOrderBySeqAsc(String sessionId);

    List<SessionMessageEntity> findTop30BySessionIdOrderBySeqDesc(String sessionId);

    @Query("SELECT COALESCE(MAX(s.seq), 0) FROM SessionMessageEntity s WHERE s.sessionId = ?1")
    long maxSeqForSession(String sessionId);

    @Modifying
    @Transactional
    @Query("DELETE FROM SessionMessageEntity s WHERE s.sessionId = ?1")
    void deleteBySessionId(String sessionId);

    @Modifying
    @Transactional
    @Query("DELETE FROM SessionMessageEntity s WHERE s.workspaceId = ?1")
    void deleteByWorkspaceId(String workspaceId);
}
