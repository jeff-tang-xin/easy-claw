package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.ProjectResourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 项目-资源绑定（V31 N:N）：按项目查绑定、按资源查归属、整组覆盖。
 * 绑定操作只经 {@code ProjectService.bindResources}（整组覆盖 + 审计），不散落各资源服务。
 */
public interface ProjectResourceRepository extends JpaRepository<ProjectResourceEntity, Long> {

    List<ProjectResourceEntity> findByProjectId(Long projectId);

    List<ProjectResourceEntity> findByResourceTypeAndResourceIdIn(String resourceType, List<Long> resourceIds);

    List<ProjectResourceEntity> findByProjectIdAndResourceType(Long projectId, String resourceType);

    void deleteByProjectIdAndResourceType(Long projectId, String resourceType);

    /** 指定项目在指定类型下的绑定 key 集合（整组覆盖前先清后写）。 */
    @Modifying
    @Query("delete from ProjectResourceEntity e where e.projectId = :projectId and e.resourceType = :resourceType")
    void deleteByProjectAndType(@Param("projectId") Long projectId, @Param("resourceType") String resourceType);
}
