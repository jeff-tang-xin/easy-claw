/**
 * 语音录制：WebAudio 16kHz 采集 PCM → 标准 WAV Blob。
 *
 * 为什么不用 MediaRecorder：其输出格式因平台而异（Chrome/Android = webm/opus，
 * iOS Safari = mp4/aac），后端离线 ASR 模型只吃 wav/pcm。WebAudio 采集时把
 * AudioContext 采样率指定为 16kHz（浏览器自动把麦克风流重采样到该速率），
 * 前端直接产出 16kHz 单声道 PCM16 WAV——后端零转码、格式确定。
 *
 * 注意：getUserMedia 是浏览器硬性安全要求，仅 HTTPS 或 localhost 可用；
 * HTTP 部署（如内网穿透）下 mediaDevices 为 undefined，start() 会抛出明确错误。
 */

/** 采集目标采样率：whisper 系模型的标准输入速率 */
const TARGET_SAMPLE_RATE = 16000;

export interface VoiceRecorder {
  /** 请求麦克风并开始采集（失败抛错，调用方展示原因） */
  start(): Promise<void>;
  /** 停止采集并编码为 WAV；释放麦克风与音频资源 */
  stop(): Blob;
}

export function createVoiceRecorder(): VoiceRecorder {
  let ctx: AudioContext | null = null;
  let stream: MediaStream | null = null;
  let source: MediaStreamAudioSourceNode | null = null;
  let processor: ScriptProcessorNode | null = null;
  /** 各回调周期的 PCM 原样累积（Float32 -1..1） */
  const chunks: Float32Array[] = [];
  let totalFrames = 0;

  return {
    async start() {
      if (!navigator.mediaDevices?.getUserMedia) {
        throw new Error('当前环境不支持录音（浏览器要求 HTTPS 或 localhost）');
      }
      stream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
      });
      ctx = new AudioContext({ sampleRate: TARGET_SAMPLE_RATE });
      const actualRate = ctx.sampleRate;
      if (actualRate !== TARGET_SAMPLE_RATE) {
        void ctx.close();
        ctx = null;
        stream.getTracks().forEach((t) => t.stop());
        stream = null;
        throw new Error(`浏览器不支持 ${TARGET_SAMPLE_RATE}Hz 采集（实际 ${actualRate}Hz）`);
      }
      source = ctx.createMediaStreamSource(stream);
      // ScriptProcessor 虽已标记 deprecated，但全平台支持且行为确定，移动端兼容性最稳
      processor = ctx.createScriptProcessor(4096, 1, 1);
      processor.onaudioprocess = (e) => {
        const input = e.inputBuffer.getChannelData(0);
        chunks.push(new Float32Array(input));
        totalFrames += input.length;
      };
      source.connect(processor);
      // ScriptProcessor 必须连到 destination 才会被驱动；经零增益节点避免麦克风回放
      const silent = ctx.createGain();
      silent.gain.value = 0;
      processor.connect(silent);
      silent.connect(ctx.destination);
      chunks.length = 0;
      totalFrames = 0;
    },

    stop(): Blob {
      const sampleRate = ctx?.sampleRate ?? TARGET_SAMPLE_RATE;
      try { if (processor) processor.onaudioprocess = null; } catch { /* 已断开 */ }
      try { processor?.disconnect(); } catch { /* 未连接 */ }
      try { source?.disconnect(); } catch { /* 未连接 */ }
      try { stream?.getTracks().forEach((t) => t.stop()); } catch { /* 已停止 */ }
      try { void ctx?.close(); } catch { /* 已关闭 */ }
      const blob = encodeWav(chunks, totalFrames, sampleRate);
      chunks.length = 0;
      totalFrames = 0;
      ctx = null;
      stream = null;
      source = null;
      processor = null;
      return blob;
    },
  };
}

/** 合并 PCM 分片并封装为 16bit 单声道 WAV（44 字节 RIFF 头 + 数据） */
function encodeWav(chunks: Float32Array[], totalFrames: number, sampleRate: number): Blob {
  const pcm = new Int16Array(totalFrames);
  let off = 0;
  for (const c of chunks) {
    for (let i = 0; i < c.length; i++) {
      const s = Math.max(-1, Math.min(1, c[i]));
      pcm[off++] = s < 0 ? s * 0x8000 : s * 0x7fff;
    }
  }
  const buf = new ArrayBuffer(44 + pcm.length * 2);
  const view = new DataView(buf);
  const writeStr = (offset: number, str: string) => {
    for (let i = 0; i < str.length; i++) view.setUint8(offset + i, str.charCodeAt(i));
  };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + pcm.length * 2, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);              // fmt 块长度
  view.setUint16(20, 1, true);               // PCM
  view.setUint16(22, 1, true);               // 单声道
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true);  // 字节率 = 采样率 × 声道 × 2 字节
  view.setUint16(32, 2, true);               // 块对齐
  view.setUint16(34, 16, true);              // 位深
  writeStr(36, 'data');
  view.setUint32(40, pcm.length * 2, true);
  new Int16Array(buf, 44).set(pcm);
  return new Blob([buf], { type: 'audio/wav' });
}
