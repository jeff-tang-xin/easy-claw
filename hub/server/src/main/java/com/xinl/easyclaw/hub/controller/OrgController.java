package com.xinl.easyclaw.hub.controller;

import com.xinl.easyclaw.hub.security.CurrentUserHolder;
import com.xinl.easyclaw.hub.contract.org.AddMemberRequest;
import com.xinl.easyclaw.hub.contract.org.CreateOrgRequest;
import com.xinl.easyclaw.hub.contract.org.MemberDto;
import com.xinl.easyclaw.hub.contract.org.OrgDto;
import com.xinl.easyclaw.hub.contract.org.UpdateMemberRoleRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.xinl.easyclaw.hub.service.OrgService;

/**
 * 组织与成员端点（§4.2）。
 */
@RestController
@RequestMapping("/api/orgs")
public class OrgController {

    private final OrgService orgService;

    public OrgController(OrgService orgService) {
        this.orgService = orgService;
    }

    @GetMapping
    public List<OrgDto> myOrgs() {
        return orgService.listMyOrgs(CurrentUserHolder.requireUserId());
    }

    @PostMapping
    public OrgDto create(@Valid @RequestBody CreateOrgRequest req) {
        return orgService.createOrg(CurrentUserHolder.requireUserId(), req);
    }

    @GetMapping("/{orgId}/members")
    public List<MemberDto> members(@PathVariable Long orgId) {
        return orgService.listMembers(CurrentUserHolder.requireUserId(), orgId);
    }

    @PostMapping("/{orgId}/members")
    public ResponseEntity<Void> addMember(@PathVariable Long orgId, @Valid @RequestBody AddMemberRequest req) {
        orgService.addMember(CurrentUserHolder.requireUserId(), orgId, req);
        return ResponseEntity.status(201).build();
    }

    @PatchMapping("/{orgId}/members/{userId}")
    public ResponseEntity<Void> updateRole(@PathVariable Long orgId, @PathVariable Long userId,
                                           @Valid @RequestBody UpdateMemberRoleRequest req) {
        orgService.updateMemberRole(CurrentUserHolder.requireUserId(), orgId, userId, req.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{orgId}/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long orgId, @PathVariable Long userId) {
        orgService.removeMember(CurrentUserHolder.requireUserId(), orgId, userId);
        return ResponseEntity.noContent().build();
    }
}
