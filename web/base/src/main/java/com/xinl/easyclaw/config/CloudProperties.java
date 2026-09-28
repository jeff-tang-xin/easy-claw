package com.xinl.easyclaw.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 云端（hub）接入配置——顶层 {@code cloud} 段（cloud profile 的特例输入变量）。
 * <p>
 * 与 {@link AgentScopeProperties}（{@code agentscope} 前缀，公共配置骨架）平级：
 * 对 AgentScope 而言 cloud 只是模型配置值（base-url/api-key/model-name 三要素）的
 * 另一个供给方，公共结构（providers 表 / model 段）只有一份；本类承载 cloud 模式
 * 仅有的两个输入变量，由 application-cloud.yml 的 providers 条目以
 * {@code ${cloud.hub-url}} / {@code ${cloud.app-key}} 占位引用派生公共字段。
 * <p>
 * 除派生公共模型字段外，本段还驱动 cloud 特例机制：启动时 spoke 持 app-key 调
 * {@code GET /api/spoke/bootstrap} 拉配置快照（组织、按 appkey 绑定展开的模型面、
 * 服务权限），供 cloud-status 观测与后续 B2/B3（远程知识库/黑板）权限裁决。
 * app-key 为空即本地模式：快照不拉取，完全不触网。
 * <p>
 * <b>app-key 是密钥</b>：只写 {@code ~/.easyClaw/application.yml} 运行层顶层
 * {@code cloud} 段或环境变量，绝不写进随源码入库的 yml 模板。
 */
@ConfigurationProperties(prefix = "cloud")
public class CloudProperties {

    /** hub 服务基址，如 {@code http://localhost:18081}（尾部不带斜杠） */
    private String hubUrl = "http://localhost:18081";

    /** spoke 身份凭证（hub 控制台按组织颁发，形如 {@code eck-...}），仅创建时可见明文 */
    private String appKey;

    /**
     * cloud-config 读取触发的按需刷新节流（秒）：距上次尝试不足该值则跳过刷新，默认 5。
     * 前端轮询（5s）驱动 spoke 自动跟上 hub 配置（含运维服务器授权撤销的快速生效），
     * 节流保证不会高频打 hub；0 = 每次读取都刷新。
     */
    private long refreshIntervalSeconds = 5;

    public String getHubUrl() {
        return hubUrl;
    }

    public void setHubUrl(String hubUrl) {
        this.hubUrl = hubUrl;
    }

    public String getAppKey() {
        return appKey;
    }

    public void setAppKey(String appKey) {
        this.appKey = appKey;
    }

    public long getRefreshIntervalSeconds() {
        return refreshIntervalSeconds;
    }

    public void setRefreshIntervalSeconds(long refreshIntervalSeconds) {
        this.refreshIntervalSeconds = refreshIntervalSeconds;
    }
}
