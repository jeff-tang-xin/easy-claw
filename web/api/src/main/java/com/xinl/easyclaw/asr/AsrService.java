package com.xinl.easyclaw.asr;

import cn.smartjavaai.speech.asr.config.AsrModelConfig;
import cn.smartjavaai.speech.asr.enums.AsrModelEnum;
import cn.smartjavaai.speech.asr.factory.SpeechRecognizerFactory;
import cn.smartjavaai.speech.asr.model.SpeechRecognizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 语音转文字服务：封装 SmartJavaAI 离线 ASR 模型的懒加载与识别调用。
 * <p>
 * 模型加载耗时数秒且占内存，采用<b>懒加载</b>——首次识别请求才加载，之后常驻复用；
 * 加载失败不重试（模型文件问题重试也不会好），错误信息保留给后续请求直接返回。
 * <p>
 * 识别调用整体串行（synchronized）：whisper 推理是 CPU 密集型，并发只会互相拖慢，
 * 单用户场景串行的吞吐完全够用。
 */
@Slf4j
@Service
public class AsrService {

    private final AsrProperties props;

    /** 已加载的识别器（volatile：双检锁发布） */
    private volatile SpeechRecognizer recognizer;

    /** 懒加载失败后的标记与原因：失败不重试，后续请求直接拿到明确错误 */
    private volatile String loadError = "";

    public AsrService(AsrProperties props) {
        this.props = props;
    }

    /** 是否已启用（配置开关，与模型是否加载成功无关） */
    public boolean enabled() {
        return props.isEnabled();
    }

    /**
     * 识别音频，返回文本。
     *
     * @param audio 音频字节（前端录制的 WAV 16kHz 单声道；DJL Audio 按 wav 解码）
     * @return 识别出的文本（已 trim；可能为空串 = 没听清内容）
     * @throws IllegalStateException 未启用 / 模型未配置或加载失败 / 识别失败
     */
    public synchronized String transcribe(byte[] audio) {
        SpeechRecognizer r = ensureLoaded();
        cn.smartjavaai.common.entity.R<cn.smartjavaai.speech.asr.entity.AsrResult> result = r.recognize(audio);
        if (result == null || result.getData() == null || result.getData().getText() == null) {
            throw new IllegalStateException("语音识别无结果（音频可能没有可识别内容）");
        }
        return result.getData().getText().trim();
    }

    /** 懒加载识别器：成功后常驻；失败记录原因并不再重试 */
    private SpeechRecognizer ensureLoaded() {
        SpeechRecognizer r = recognizer;
        if (r != null) {
            return r;
        }
        if (!loadError.isEmpty()) {
            throw new IllegalStateException("语音识别不可用（此前加载失败，修复配置后需重启）：" + loadError);
        }
        synchronized (this) {
            if (recognizer != null) {
                return recognizer;
            }
            try {
                AsrModelEnum modelEnum = AsrModelEnum.fromName(props.getModelType());
                AsrModelConfig config = new AsrModelConfig();
                config.setModelEnum(modelEnum);
                config.setModelPath(props.getModelPath());
                // VOSK 等需要外部 native 库：显式加载，免改系统 PATH
                if (props.getLibPath() != null && !props.getLibPath().isBlank()) {
                    config.setLibPath(java.nio.file.Paths.get(props.getLibPath()));
                }
                if (props.getLanguage() != null && !props.getLanguage().isBlank()) {
                    config.putCustomParam("language", props.getLanguage());
                }
                long start = System.currentTimeMillis();
                SpeechRecognizer created = SpeechRecognizerFactory.getInstance().getModel(config);
                recognizer = created;
                log.info("[asr] 模型加载完成（{}，{} ms）", modelEnum, System.currentTimeMillis() - start);
                return created;
            } catch (Exception e) {
                loadError = e.getMessage() == null ? e.toString() : e.getMessage();
                log.error("[asr] 模型加载失败：{}", loadError, e);
                throw new IllegalStateException("语音识别模型加载失败：" + loadError, e);
            }
        }
    }
}
