package com.xinl.easyclaw.base.orchestration;

import java.util.List;

/**
 * 编排行为规范渲染结果。
 *
 * @param prompt   待注入主控 system prompt 的行为规范文本；{@code null} 表示无内容可注入
 *                 （工作流非法或无步骤），原因见 {@code warnings}
 * @param warnings 渲染过程产生的告警（工作流非法、成员缺失等）；base 契约层保持零日志依赖，
 *                 由调用方负责落日志
 */
public record BehaviorSpec(String prompt, List<String> warnings) {

    public BehaviorSpec {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
