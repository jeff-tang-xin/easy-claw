package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.CloudBootstrapService;
import com.xinl.easyclaw.config.SpokeDbConnectionView;
import com.xinl.easyclaw.config.SpokeOpsServerView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工作区资源绑定的项目边界。
 * <p>
 * 这条线决定「这个 AI 工作区能碰哪台机器、哪个库」。只过滤不校验等于信前端提交的 key：
 * 跨项目的 key 一旦落库，AI 就能在别的项目的生产机上执行命令。所以候选过滤与写入校验
 * 各测一遍，且拒绝必须<b>报错</b>而不是静默忽略——静默丢弃会让用户以为绑定成功。
 */
@DisplayName("工作区资源绑定：按项目过滤候选 + 跨项目拒绝写入")
class WorkspaceResourceServiceTest {

    private CloudBootstrapService cloud;
    private WorkspaceResourceService svc;

    @BeforeEach
    void setUp() {
        cloud = mock(CloudBootstrapService.class);
        svc = new WorkspaceResourceService(cloud);
    }

    private static SpokeOpsServerView server(String key, Long projectId) {
        return new SpokeOpsServerView(key, "机-" + key, "10.0.0.9", 22, "deploy",
                null, projectId, "s3cret", true, "Linux", null);
    }

    private static SpokeDbConnectionView db(String key, Long projectId) {
        return new SpokeDbConnectionView(key, "库-" + key, "mysql", "10.0.0.1", 3306,
                "orders_db", "reader", null, true, projectId, null, true);
    }

    private void given(List<SpokeOpsServerView> ops, List<SpokeDbConnectionView> dbs) {
        when(cloud.cloudConfig()).thenReturn(new CloudBootstrapService.CloudConfigView(
                true, true, List.of(), ops, List.of(), dbs));
    }

    // ==================== 候选过滤 ====================

    @Test
    @DisplayName("本项目的资源进候选，别项目的被藏起来")
    void onlyCurrentProjectResourcesAreOffered() {
        given(List.of(server("prod-web", 1L), server("other-app", 2L)),
                List.of(db("orders", 1L), db("other-db", 2L)));

        WorkspaceResourceService.ResourceOptions options = svc.options(1L);

        assertThat(options.servers()).extracting(WorkspaceResourceService.ResourceOption::key)
                .containsExactly("prod-web");
        assertThat(options.databases()).extracting(WorkspaceResourceService.ResourceOption::key)
                .containsExactly("orders");
    }

    @Test
    @DisplayName("hub 标为「不限定项目」的资源对所有工作区可见（含未绑项目的）")
    void projectAgnosticResourcesStayVisible() {
        given(List.of(server("shared-bastion", 0L), server("legacy", null)),
                List.of());

        assertThat(svc.options(7L).servers())
                .extracting(WorkspaceResourceService.ResourceOption::key)
                .containsExactly("shared-bastion", "legacy");
        // 未绑项目的工作区同样能看到它们
        assertThat(svc.options(null).servers()).hasSize(2);
    }

    @Test
    @DisplayName("未绑项目的工作区看不到任何限定项目的资源")
    void unboundWorkspaceSeesNoProjectScopedResource() {
        given(List.of(server("prod-web", 1L)), List.of(db("orders", 1L)));

        assertThat(svc.options(null).servers()).isEmpty();
        assertThat(svc.options(null).databases()).isEmpty();
    }

    @Test
    @DisplayName("候选只给业务名：主机、端口、账号不出现在这里")
    void candidateLabelsCarryNoAddress() {
        given(List.of(server("prod-web", 1L)), List.of(db("orders", 1L)));

        String labels = (svc.options(1L).servers() + " " + svc.options(1L).databases())
                .toLowerCase(java.util.Locale.ROOT);
        assertThat(labels)
                .doesNotContain("10.0.0")
                .doesNotContain("3306")
                .doesNotContain("22")
                .doesNotContain("deploy")
                .doesNotContain("s3cret");
    }

    // ==================== 写入校验 ====================

    @Test
    @DisplayName("本项目的 key 放行")
    void currentProjectKeyIsAccepted() {
        given(List.of(server("prod-web", 1L)), List.of(db("orders", 1L)));

        assertThatCode(() -> svc.assertBindable(WorkspaceResourceService.KIND_OPS, "prod-web", 1L))
                .doesNotThrowAnyException();
        assertThatCode(() -> svc.assertBindable(WorkspaceResourceService.KIND_DB, "orders", 1L))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("别项目的 key 直接拒绝，文案说清原因（不是静默忽略）")
    void foreignProjectKeyIsRefused() {
        given(List.of(server("prod-web", 1L), server("other-app", 2L)), List.of());

        assertThatThrownBy(() ->
                svc.assertBindable(WorkspaceResourceService.KIND_OPS, "other-app", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不属于本工作区绑定的项目")
                .hasMessageContaining("other-app");
    }

    @Test
    @DisplayName("平台已撤销/下线的资源拒绝写入")
    void revokedResourceIsRefused() {
        given(List.of(), List.of());

        assertThatThrownBy(() ->
                svc.assertBindable(WorkspaceResourceService.KIND_OPS, "gone-box", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能绑定到本工作区");
    }

    @Test
    @DisplayName("null = 本次不改，空白串 = 显式清空，两者都不做归属校验")
    void nullAndBlankMeanNoBindingChange() {
        // 清单为空也不报错：清空绑定不该要求「该资源仍然可见」
        given(List.of(), List.of());

        assertThatCode(() -> svc.assertBindable(WorkspaceResourceService.KIND_OPS, null, 1L))
                .doesNotThrowAnyException();
        assertThatCode(() -> svc.assertBindable(WorkspaceResourceService.KIND_DB, "  ", 1L))
                .doesNotThrowAnyException();
    }
}
