package com.xinl.easyclaw.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 路由回退：非 /api 路径统一转发到 index.html（React BrowserRouter 刷新/直达时生效）
 */
@Controller
public class SpaController {

    @GetMapping(value = {
            "/", "/workspaces", "/workspaces/*", "/skills", "/tools", "/mcp", "/settings",
            "/scenarios", "/blackboard", "/knowledge",
            "/chat", "/chat/", "/chat/**"
    })
    public String spa() {
        return "forward:/index.html";
    }
}
