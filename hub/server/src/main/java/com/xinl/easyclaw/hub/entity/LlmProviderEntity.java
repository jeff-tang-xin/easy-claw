package com.xinl.easyclaw.hub.entity;

import com.xinl.easyclaw.hub.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * LLM provider 实体（llm_providers 表）：真实 api key 只存 AES-GCM 密文。
 * orgId 为 null = 平台共享池（platformAdmin 维护），否则归属组织（owner/admin 维护）；
 * apiType = openai（OpenAI 兼容协议）| anthropic（Anthropic 原生协议），A1 网关转发按此分流。
 * slug 唯一性为「作用域内唯一」（平台池内唯一 + 每组织内唯一），由 V5 复合唯一索引保证，实体不再标 unique。
 * 公共字段 id/created_at/updated_at 见 {@link BaseEntity}。
 */
@Getter
@Setter
@Entity
@Table(name = "llm_providers")
public class LlmProviderEntity extends BaseEntity {

    /** 归属组织；null = 平台共享池。创建后不可改。 */
    @Column(name = "org_id")
    private Long orgId;

    @Column(nullable = false, length = 64)
    private String slug;

    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** 真实 api key 的 AES-GCM 密文（Base64(iv+密文)），绝不落明文。 */
    @Column(name = "api_key_ciphertext", nullable = false, columnDefinition = "TEXT")
    private String apiKeyCiphertext;

    /** 逗号分隔可用模型清单（空串 = 未配置）。 */
    @Column(nullable = false, length = 500)
    private String models = "";

    /** active|disabled */
    @Column(nullable = false, length = 20)
    private String status = "active";

    /** 协议类型：openai（OpenAI 兼容）| anthropic（Anthropic 原生 /v1/messages）。 */
    @Column(name = "api_type", nullable = false, length = 20)
    private String apiType = "openai";

    @Column(length = 255)
    private String remark;
}
