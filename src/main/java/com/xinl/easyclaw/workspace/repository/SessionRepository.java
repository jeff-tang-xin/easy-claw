package com.xinl.easyclaw.workspace.repository;

import com.xinl.easyclaw.workspace.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {
    List<SessionEntity> findByWorkspaceId(String workspaceId);
    List<SessionEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);
}
