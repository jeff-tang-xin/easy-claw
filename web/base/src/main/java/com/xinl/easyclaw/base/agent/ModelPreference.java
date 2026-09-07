package com.xinl.easyclaw.base.agent;

/**
 * 模型偏好。
 * <p>
 * 对应原 {@code AgentFactory.resolveRoleModel(modelId, baseUrl, apiKey)} 的三个入参，
 * 收敛为一个值对象，使「哪个智能体用哪个模型」成为智能体自身的声明。
 * <p>
 * <b>安全</b>：{@code apiKey} 仅在内存中传递，不得写入日志或落盘
 * （运行期配置存放于 {@code ~/.easyClaw/application.yml}，绝不进工作区）。
 *
 * @param modelId 模型标识，为 null/空时回退全局默认模型
 * @param baseUrl 自定义接入点，可为 null
 * @param apiKey  自定义密钥，可为 null
 */
public record ModelPreference(String modelId, String baseUrl, String apiKey) {

    /** 无偏好：使用全局默认模型 */
    public static final ModelPreference DEFAULT = new ModelPreference(null, null, null);

    public static ModelPreference of(String modelId) {
        return new ModelPreference(modelId, null, null);
    }

    /** 是否声明了有效的模型偏好 */
    public boolean hasPreference() {
        return modelId != null && !modelId.isBlank();
    }

    /** 避免误把密钥打进日志 */
    @Override
    public String toString() {
        return "ModelPreference{modelId=" + modelId
                + ", baseUrl=" + baseUrl
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "***") + "}";
    }
}