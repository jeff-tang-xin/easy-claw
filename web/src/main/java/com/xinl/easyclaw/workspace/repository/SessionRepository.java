package com.xinl.easyclaw.workspace.repository;

import com.xinl.easyclaw.workspace.entity.SessionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SessionRepository extends JpaRepository<SessionEntity, String> {
    List<SessionEntity> findByWorkspaceId(String workspaceId);
    List<SessionEntity> findByWorkspaceIdOrderByCreatedAtDesc(String workspaceId);

    /** 归属校验：会话是否确实属于该工作区（防止跨工作区读历史 / 停他人回合） */
    boolean existsByIdAndWorkspaceId(String id, String workspaceId);
}
