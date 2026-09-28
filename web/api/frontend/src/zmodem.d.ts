/** zmodem.js 0.1.10 无官方类型声明：按实际用到的 API 面做最小 shim。
 * API 依据源码核实（黑板 #14）：入口含 Sentry/Session/Browser；
 * Sentry 四参全必填（第 2 参名是 sender）；accept() 的 Promise 直接 resolve Uint8Array[]
 * （README 的 xfer.get_payloads() 在源码中不存在）。
 * 用 declare module 显式绑定子路径导入（'zmodem.js/src/zmodem_browser'），
 * 否则 TS 只认包名根路径，TS7016 依旧。 */
declare module 'zmodem.js/src/zmodem_browser' {
  /** ZMODEM 传输会话（远端 sz → type='receive' 我们收；远端 rz → type='send' 我们发） */
  export interface ZmodemSession {
    type: 'send' | 'receive';
    /** receive 会话必须调用后才开始协议交互 */
    start(): void;
    /** send 会话文件发完后收尾 */
    close(): void;
    on(event: 'offer', handler: (xfer: ZmodemTransfer) => void): void;
    on(event: 'session_end', handler: () => void): void;
    on(event: string, handler: (...args: unknown[]) => void): void;
  }

  /** 一个待传输文件（receive 的 offer / send 的完成回调载体） */
  export interface ZmodemTransfer {
    /** 接收该文件：Promise resolve 原始字节分片（按序拼接即文件内容） */
    accept(): Promise<Uint8Array[]>;
    get_details(): { name: string; size: number };
    on(event: string, handler: (...args: unknown[]) => void): void;
  }

  /** 下行疑似 ZMODEM 帧头的检测结果：confirm() 进入会话 / deny() 拒绝 */
  export interface ZmodemDetection {
    confirm(): ZmodemSession;
    deny(): void;
  }

  /** 常驻哨兵：下行字节先过它——普通输出透传给 to_terminal，ZMODEM 帧头截获成会话 */
  export interface ZmodemSentry {
    consume(input: number[] | ArrayBuffer | Uint8Array): void;
  }

  const Zmodem: {
    Sentry: new (options: {
      to_terminal: (octets: ArrayLike<number>) => void;
      sender: (octets: ArrayLike<number>) => void;
      on_detect: (detection: ZmodemDetection) => void;
      on_retract: () => void;
    }) => ZmodemSentry;
    Browser: {
      save_to_disk(payloads: Uint8Array[], filename: string): void;
      send_files(
        session: ZmodemSession,
        files: File[],
        options?: {
          on_offer_response?(xfer: ZmodemTransfer, offerResponse: unknown): void;
          on_progress?(xfer: ZmodemTransfer, offerResponse: unknown): void;
          on_file_complete?(xfer: ZmodemTransfer): void;
        },
      ): Promise<void>;
    };
  };

  export default Zmodem;
}

