package com.xinl.easyclaw.agent.orchestrator;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编排审计校验测试（P1）
 */
class OrchestrationAuditVerifierTest {

    private static final String WORKFLOW = """
            {"steps":[
              {"subagent":"planner"},
              {"subagent":"coder"},
              {"subagent":"reviewer","parallel":true}
            ]}""";

    @Test
    @DisplayName("无工作流时无需审计")
    void noWorkflowNeedsNoAudit() {
        assertTrue(OrchestrationAuditVerifier.verify(null, "任何回复").isEmpty());
    }

    @Test
    @DisplayName("缺少审计标记应被标记为不可确认")
    void missingAuditIsDetected() {
        var result = OrchestrationAuditVerifier.verify(WORKFLOW, "我已完成任务。").orElseThrow();
        assertFalse(result.auditPresent());
        assertFalse(result.consistent());
        assertTrue(result.summary().contains("缺少"));
    }

    @Test
    @DisplayName("审计覆盖全部阶段时判定一致")
    void fullAuditIsConsistent() {
        String reply = """
                汇总完成。
                <orchestration-audit stages="2" executed="1:planner,2:coder|reviewer" skipped="" />""";
        var result = OrchestrationAuditVerifier.verify(WORKFLOW, reply).orElseThrow();
        assertTrue(result.auditPresent());
        assertTrue(result.consistent(), result.summary());
    }

    @Test
    @DisplayName("漏掉阶段应被识别为不一致")
    void missingStageIsFlagged() {
        String reply = "<orchestration-audit stages=\"2\" executed=\"1:planner\" skipped=\"\" />";
        var result = OrchestrationAuditVerifier.verify(WORKFLOW, reply).orElseThrow();
        assertFalse(result.consistent());
        assertTrue(result.summary().contains("阶段 2"), result.summary());
    }

    @Test
    @DisplayName("显式声明跳过的阶段会被记录为差异（可追溯裁剪）")
    void skippedStageIsRecorded() {
        String reply = "<orchestration-audit stages=\"2\" executed=\"1:planner\" skipped=\"2\" />";
        var result = OrchestrationAuditVerifier.verify(WORKFLOW, reply).orElseThrow();
        assertTrue(result.auditPresent());
        assertFalse(result.consistent());
        assertTrue(result.skippedStages().contains(2));
    }

    @Test
    @DisplayName("单引号与多余空白同样可解析")
    void tolerantToQuotingAndWhitespace() {
        String reply = "<orchestration-audit   stages='2'  executed='1:planner , 2:coder|reviewer' />";
        Optional<OrchestrationAuditVerifier.AuditResult> result =
                OrchestrationAuditVerifier.verify(WORKFLOW, reply);
        assertTrue(result.orElseThrow().consistent(), result.get().summary());
    }
}
