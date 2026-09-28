package com.xinl.easyclaw.hub.repository;

import java.util.List;
import java.util.Optional;
import com.xinl.easyclaw.hub.entity.MenuItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuItemRepository extends JpaRepository<MenuItemEntity, Long> {

    /** 按 sort_order、id 稳定排序，供平台目录列表与树组装。 */
    List<MenuItemEntity> findAllByOrderBySortOrderAscIdAsc();

    Optional<MenuItemEntity> findByMenuKey(String menuKey);

    boolean existsByMenuKey(String menuKey);
}
