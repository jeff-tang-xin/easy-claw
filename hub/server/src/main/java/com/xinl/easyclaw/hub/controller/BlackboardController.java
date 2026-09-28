package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.blackboard.BlackboardEntryDto;
import com.xinl.easyclaw.hub.contract.blackboard.CreateBlackboardEntryRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.BlackboardService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目共享黑板端点（A4）。路由风格对齐知识库的平铺形态（{@code /api/blackboard?projectId=}，
 * projectId 在创建请求体内）；归档为条目的状态标签，走 {@code POST /api/blackboard/{id}/archive}。
 */
@RestController
@RequestMapping("/api/blackboard")
public class BlackboardController {

    private final BlackboardService blackboardService;

    public BlackboardController(BlackboardService blackboardService) {
        this.blackboardService = blackboardService;
    }

    /** 活跃条目（未归档），最新在前。 */
    @GetMapping
    public List<BlackboardEntryDto> listActive(@RequestParam Long projectId) {
        return blackboardService.listActive(CurrentUserHolder.requireUserId(), projectId);
    }

    /** 归档条目，最新在前。 */
    @GetMapping("/archives")
    public List<BlackboardEntryDto> listArchives(@RequestParam Long projectId) {
        return blackboardService.listArchives(CurrentUserHolder.requireUserId(), projectId);
    }

    /** 单条详情（active/archived 均可）。 */
    @GetMapping("/{id}")
    public BlackboardEntryDto get(@PathVariable Long id) {
        return blackboardService.get(CurrentUserHolder.requireUserId(), id);
    }

    @PostMapping
    public BlackboardEntryDto append(@Valid @RequestBody CreateBlackboardEntryRequest req) {
        return blackboardService.append(CurrentUserHolder.requireUserId(), req);
    }

    /** 归档条目（幂等：已归档直接返回当前态）。 */
    @PostMapping("/{id}/archive")
    public BlackboardEntryDto archive(@PathVariable Long id) {
        return blackboardService.archive(CurrentUserHolder.requireUserId(), id);
    }

    /** 取消归档（幂等：本就活跃直接返回当前态）。 */
    @PostMapping("/{id}/unarchive")
    public BlackboardEntryDto unarchive(@PathVariable Long id) {
        return blackboardService.unarchive(CurrentUserHolder.requireUserId(), id);
    }
}