package com.xinl.easyclaw.hub.contract.docs;

/**
 * 设置/取消负责人请求。assigneeUserId 为 null 表示取消负责人；非空时必须是文档所属组织的成员。
 */
public record AssignDocRequest(Long assigneeUserId) {
}
