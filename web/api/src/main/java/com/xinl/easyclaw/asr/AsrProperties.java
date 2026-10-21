package com.xinl.easyclaw.asr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 语音转文字（ASR）配置。
 * <p>
 * 基于 SmartJavaAI speech 模块做<b>离线</b>推理：模型文件需人工准备（不自动下载），
 * 因此默认关闭——未启用时接口返回明确提示，而不是让启动失败或报 500。
 * <p>
 * 配置分层：本文件给中立默认值，用户真实值（模型路径等）写在运行层
 * {@code ~/.easyClaw/application.yml} 的 {@code easyclaw.asr} 段覆盖。
 */
@Data
@ConfigurationProperties(prefix = "easyclaw.asr")
public class AsrProperties {

    /** 是否启用语音转文字接口（模型文件就绪后再打开） */
    private boolean enabled = false;

    /**
     * 识别模型类型（SmartJavaAI {@code AsrModelEnum} 名称）。
     * WHISPER 的 native 库由 whisper-jni 自带（零额外部署），推荐首选；
     * SHERPA_PARAFORMER 等还需额外配置 native 库目录（libPath），暂不暴露。
     */
    private String modelType = "WHISPER";

    /**
     * 模型文件路径。WHISPER = ggml 格式模型文件（如 ggml-base.bin，
     * 从 whisper.cpp / sherpa-onnx 的模型发布页下载；base 约 148MB，中文建议 small 及以上）。
     * VOSK = 模型目录（解压后的目录，如 vosk-model-small-cn-0.22，含 am/ conf/ 子目录）。
     */
    private String modelPath = "";

    /**
     * native 库路径（可选，VOSK 需要）：vosk-win64 zip 解压出的 vosk.dll 所在目录或 dll 全路径。
     * 不配置时 JNA 从系统 PATH 找 vosk.dll；配置后显式 System.load，无需改环境变量。
     * WHISPER 不需要（whisper-jni 自带 native）。
     */
    private String libPath = "";

    /** 识别语言提示（whisper 参数）：zh / en 等，空 = 自动检测 */
    private String language = "zh";
}
