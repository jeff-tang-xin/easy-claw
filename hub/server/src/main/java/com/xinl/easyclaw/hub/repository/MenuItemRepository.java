package com.xinl.easyclaw.hub.repository;

import java.util.List;
import java.util.Optional;
import com.xinl.easyclaw.hub.entity.MenuItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItemEntity, Long> {

    /** 按 sort_order、id 稳定排序，供菜单树组装。 */
    List<MenuItemEntity> findByWorkspaceIdOrderBySortOrderAscIdAsc(Long workspaceId);

    Optional<MenuItemEntity> findByWorkspaceIdAndMenuKey(Long workspaceId, String menuKey);

    boolean existsByWorkspaceIdAndMenuKey(Long workspaceId, String menuKey);

    void deleteByWorkspaceId(Long workspaceId);
}
