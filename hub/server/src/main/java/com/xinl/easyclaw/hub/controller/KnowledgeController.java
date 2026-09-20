package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.knowledge.CreateKnowledgeItemRequest;
import com.xinl.easyclaw.hub.contract.knowledge.KnowledgeHistoryItemDto;
import com.xinl.easyclaw.hub.contract.knowledge.KnowledgeHistoryVersionDto;
import com.xinl.easyclaw.hub.contract.knowledge.KnowledgeItemDto;
import com.xinl.easyclaw.hub.contract.knowledge.KnowledgeItemListItemDto;
import com.xinl.easyclaw.hub.contract.knowledge.UpdateKnowledgeItemRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.service.KnowledgeService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目知识库条目端点（A3-S1）。路由风格为平铺形态（{@code /api/knowledge?projectId=}，
 * projectId 在创建请求体内）；更新走 POST + 乐观锁，版本冲突 409 VERSION_CONFLICT 携带最新快照。
 * 检索（keyword/semantic）属 A3-S2/S3，本批不提供。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public List<KnowledgeItemListItemDto> list(@RequestParam Long projectId) {
        return knowledgeService.list(CurrentUserHolder.requireUserId(), projectId);
    }

    @GetMapping("/{id}")
    public KnowledgeItemDto get(@PathVariable Long id) {
        return knowledgeService.get(CurrentUserHolder.requireUserId(), id);
    }

    @GetMapping("/{id}/history")
    public List<KnowledgeHistoryItemDto> history(@PathVariable Long id) {
        return knowledgeService.history(CurrentUserHolder.requireUserId(), id);
    }

    @GetMapping("/{id}/history/{version}")
    public KnowledgeHistoryVersionDto historyVersion(@PathVariable Long id, @PathVariable Long version) {
        return knowledgeService.historyVersion(CurrentUserHolder.requireUserId(), id, version);
    }

    @PostMapping
    public KnowledgeItemDto create(@Valid @RequestBody CreateKnowledgeItemRequest req) {
        return knowledgeService.create(CurrentUserHolder.requireUserId(), req);
    }

    @PostMapping("/{id}")
    public KnowledgeItemDto update(@PathVariable Long id, @Valid @RequestBody UpdateKnowledgeItemRequest req) {
        return knowledgeService.update(CurrentUserHolder.requireUserId(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        knowledgeService.delete(CurrentUserHolder.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
