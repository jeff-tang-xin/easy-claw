package com.xinl.easyclaw.workspace;

import com.xinl.easyclaw.config.CloudBootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 工作区可绑定的平台资源（数据库连接 / 运维服务器）——候选下发与归属校验。
 * <p>
 * 存在的理由是把「这个工作区能碰哪台机器、哪个库」收在一处判定。两处都必须用它：
 * 前端候选要按项目过滤（否则人在工作区表单里看到别的项目的机器），写入更要校验
 * （只过滤不校验，等于信前端提交的 key——跨项目的 key 一旦落库，AI 就能在别项目的
 * 生产机上执行命令）。<b>过滤是体验，校验才是边界。</b>
 * <p>
 * 候选只给业务名：主机、端口、账号一律不出现在这里，与内置能力实例的
 * 「只暴露能力，不暴露地址」标准一致。
 */
@Service
@RequiredArgsConstructor
public class WorkspaceResourceService {

    /** 资源类型：数据库连接。 */
    public static final String KIND_DB = "db";
    /** 资源类型：运维服务器。 */
    public static final String KIND_OPS = "ops";

    private final CloudBootstrapService cloudBootstrapService;

    /**
     * 一条可绑定资源。
     *
     * @param key             hub 侧稳定标识（serverKey），落库存的就是它
     * @param label           给人看的业务名（不含主机端口）
     * @param autoConnectable 平台是否下发了凭证，可自动建连；false 时前端应提示「需人工先连或补密码」
     */
    public record ResourceOption(String key, String label, boolean autoConnectable) {
    }

    /** 工作区表单的两组候选。 */
    public record ResourceOptions(List<ResourceOption> databases, List<ResourceOption> servers) {
    }

    /**
     * 按工作区归属项目过滤候选。
     * <p>
     * {@code projectId} 为空（本地模式或未绑项目的工作区）时仍返回「不限定项目」的资源：
     * hub 侧 {@code projectId<=0} 的语义就是对所有项目可见（V18 起沿用），这类资源本就
     * 与项目无关，不该因为工作区没绑项目而被藏起来。
     */
    public ResourceOptions options(Long projectId) {
        List<WorkspaceResourceService.ResourceOption> databases =
                cloudBootstrapService.cloudConfig().dbConnections().stream()
                        .filter(c -> visibleFor(c.projectId(), projectId))
                        .map(c -> new ResourceOption(c.serverKey(),
                                c.name() + " · " + c.dbType() + " · " + c.databaseName(),
                                c.hasPassword()))
                        .toList();
        List<WorkspaceResourceService.ResourceOption> servers =
                cloudBootstrapService.cloudConfig().opsServers().stream()
                        .filter(s -> visibleFor(s.projectId(), projectId))
                        .map(s -> new ResourceOption(s.serverKey(),
                                s.name() + (s.osType() == null || s.osType().isBlank()
                                        ? "" : " · " + s.osType()),
                                s.hasPassword()))
                        .toList();
        return new ResourceOptions(databases, servers);
    }

    /**
     * 校验绑定的资源确实对本工作区可见；不可见直接拒绝写入。
     * <p>
     * 拒绝而不是静默忽略：静默丢弃会让用户以为绑定成功，直到 AI 报「找不到服务器」才发现，
     * 故障极隐蔽；而报错能立刻区分是「项目选错了」还是「授权已撤销」。
     *
     * @param kind                {@link #KIND_DB} / {@link #KIND_OPS}
     * @param key                 待绑定的 serverKey；{@code null}/空白视为「清空绑定」，直接放行
     * @param workspaceProjectId  工作区归属项目
     */
    public void assertBindable(String kind, String key, Long workspaceProjectId) {
        if (key == null || key.isBlank()) {
            return;
        }
        String trimmed = key.trim();
        ResourceOptions pool = options(workspaceProjectId);
        List<ResourceOption> candidates = KIND_DB.equals(kind) ? pool.databases() : pool.servers();
        if (candidates.stream().anyMatch(o -> o.key().equals(trimmed))) {
            return;
        }
        throw new IllegalArgumentException(
                (KIND_DB.equals(kind) ? "数据库" : "服务器") + "「" + trimmed + "」不能绑定到本工作区："
                        + "它不属于本工作区绑定的项目，或平台授权已撤销/资源已下线。"
                        + "请在下拉里重新选择，或先在平台完成授权。");
    }

    /** hub 侧 projectId<=0/null = 不限定项目，对所有工作区可见。 */
    private static boolean visibleFor(Long resourceProjectId, Long workspaceProjectId) {
        if (resourceProjectId == null || resourceProjectId <= 0) {
            return true;
        }
        return resourceProjectId.equals(workspaceProjectId);
    }
}
