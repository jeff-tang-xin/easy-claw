package com.xinl.easyclaw.api;

import com.xinl.easyclaw.asr.AsrService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 语音转文字（ASR）API：移动端聊天页的语音输入入口。
 * <p>
 * 流程：前端录音（WAV 16kHz 单声道）→ 本接口离线转文字 → 前端把文字回填输入框，
 * 用户确认/编辑后再作为普通消息发给 LLM。语音数据只在本机推理，不出网。
 */
@Slf4j
@RestController
@RequestMapping("/api/asr")
public class AsrController {

    /** 音频大小上限：16kHz 16bit 单声道 WAV 约 32KB/s，15MB ≈ 8 分钟，足够语音输入场景 */
    private static final long MAX_AUDIO_BYTES = 15L * 1024 * 1024;

    private final AsrService asrService;

    public AsrController(AsrService asrService) {
        this.asrService = asrService;
    }

    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> transcribe(@RequestParam("file") MultipartFile file) {
        if (!asrService.enabled()) {
            throw new ApiExceptions.BadRequestException(
                    "语音转文字未启用：请在 ~/.easyClaw/application.yml 配置 easyclaw.asr.enabled=true 与模型路径（easyclaw.asr.model-path）");
        }
        if (file == null || file.isEmpty()) {
            throw new ApiExceptions.BadRequestException("音频文件为空");
        }
        if (file.getSize() > MAX_AUDIO_BYTES) {
            throw new ApiExceptions.BadRequestException("音频过大（上限 15MB），请缩短录音时长");
        }
        try {
            String text = asrService.transcribe(file.getBytes());
            return ResponseEntity.ok(Map.of("text", text == null ? "" : text));
        } catch (IllegalStateException e) {
            // 模型未配置/加载失败/无结果：业务性失败，400 带原因（前端直接展示）
            log.warn("[asr] 转写失败: {}", e.getMessage());
            throw new ApiExceptions.BadRequestException(e.getMessage());
        } catch (Exception e) {
            log.error("[asr] 转写异常", e);
            throw new ApiExceptions.BadRequestException("语音识别失败：" + e.getMessage());
        }
    }
}
