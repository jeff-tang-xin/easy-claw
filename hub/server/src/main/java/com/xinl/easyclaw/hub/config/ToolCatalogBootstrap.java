package com.xinl.easyclaw.hub.config;

import com.xinl.easyclaw.hub.repository.PlatformToolRepository;
import com.xinl.easyclaw.hub.service.ToolCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动引导：播种平台内置工具目录（38 项：22 框架 + 16 自定义 @Tool）。
 * 按 toolKey 幂等——缺失则插入，已存在不覆盖，保留平台开关与排序的人工调整；
 * 每次启动执行无害，新增内置工具随版本升级自动补齐。
 */
@Component
public class ToolCatalogBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ToolCatalogBootstrap.class);

    private final ToolCatalogService toolCatalogService;
    private final PlatformToolRepository platformTools;

    public ToolCatalogBootstrap(ToolCatalogService toolCatalogService, PlatformToolRepository platformTools) {
        this.toolCatalogService = toolCatalogService;
        this.platformTools = platformTools;
    }

    @Override
    public void run(ApplicationArguments args) {
        long before = platformTools.count();
        toolCatalogService.seedBuiltinTools();
        long after = platformTools.count();
        if (after > before) {
            log.info("平台工具目录播种完成：新增 {} 项，共 {} 项", after - before, after);
        }
    }
}
