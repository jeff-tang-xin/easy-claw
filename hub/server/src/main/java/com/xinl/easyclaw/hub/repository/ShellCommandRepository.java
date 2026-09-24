package com.xinl.easyclaw.hub.repository;

import com.xinl.easyclaw.hub.entity.ShellCommandEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShellCommandRepository extends JpaRepository<ShellCommandEntity, Long> {

    List<ShellCommandEntity> findAllByOrderBySortOrderAscIdAsc();

    /** spoke 下发用：仅启用项，按 sort_order,id 保序。 */
    List<ShellCommandEntity> findAllByEnabledTrueOrderBySortOrderAscIdAsc();

    boolean existsByCmd(String cmd);
}
