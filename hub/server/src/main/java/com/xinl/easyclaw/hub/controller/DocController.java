package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.contract.docs.AssignDocRequest;
import com.xinl.easyclaw.hub.contract.docs.CreateDocRequest;
import com.xinl.easyclaw.hub.contract.docs.DocDto;
import com.xinl.easyclaw.hub.contract.docs.DocEventDto;
import com.xinl.easyclaw.hub.contract.docs.DocListItemDto;
import com.xinl.easyclaw.hub.contract.docs.UpdateDocRequest;
import com.xinl.easyclaw.hub.security.CurrentUserHolder;
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
import com.xinl.easyclaw.hub.service.DocService;

/**
 * 项目文档端点（A2）。列表按项目 + 可选 docType 过滤；更新走乐观锁，冲突 409 携带最新快照。
 */
@RestController
@RequestMapping("/api/docs")
public class DocController {

    private final DocService docService;

    public DocController(DocService docService) {
        this.docService = docService;
    }

    @GetMapping
    public List<DocListItemDto> list(@RequestParam Long projectId,
                                     @RequestParam(required = false) String docType) {
        return docService.list(CurrentUserHolder.requireUserId(), projectId, docType);
    }

    @GetMapping("/{id}")
    public DocDto get(@PathVariable Long id) {
        return docService.get(CurrentUserHolder.requireUserId(), id);
    }

    @GetMapping("/{id}/history")
    public List<DocEventDto> history(@PathVariable Long id) {
        return docService.history(CurrentUserHolder.requireUserId(), id);
    }

    @PostMapping
    public DocDto create(@Valid @RequestBody CreateDocRequest req) {
        return docService.create(CurrentUserHolder.requireUserId(), req);
    }

    @PostMapping("/{id}")
    public DocDto update(@PathVariable Long id, @Valid @RequestBody UpdateDocRequest req) {
        return docService.update(CurrentUserHolder.requireUserId(), id, req);
    }

    @PostMapping("/{id}/assign")
    public DocDto assign(@PathVariable Long id, @Valid @RequestBody AssignDocRequest req) {
        return docService.assign(CurrentUserHolder.requireUserId(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        docService.delete(CurrentUserHolder.requireUserId(), id);
        return ResponseEntity.noContent().build();
    }
}
