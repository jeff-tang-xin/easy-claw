package com.xinl.easyclaw.hub.contract.spoke;

/** 下发给 spoke 的组织项目清单项（GET /api/spoke/projects，供 solo 工作区绑定选择）。 */
public record SpokeProjectInfo(Long id, String name, String slug) {
}
