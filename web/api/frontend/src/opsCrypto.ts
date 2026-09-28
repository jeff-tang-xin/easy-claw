// 运维连接密码的应用层加密（与后端 OpsCryptoService 对齐）：
// GET /api/ops/public-key 取 RSA-2048 公钥（X.509 SPKI，DER，Base64），
// WebCrypto RSA-OAEP(SHA-256) 加密密码后 Base64 上送 connect——网络路径不出现明文。
// spoke 重启即换钥（密钥仅存内存）：每次连接前现取公钥，不做任何缓存。
// 注意 OAEP 参数：WebCrypto 的 RSA-OAEP(SHA-256) 对主摘要与 MGF1 都用 SHA-256，
// 后端已用 OAEPParameterSpec 显式对齐（OpsCryptoService.decrypt）。
import {getJson} from './api';

function b64ToBytes(b64: string): Uint8Array {
  const bin = atob(b64);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return bytes;
}

function bytesToB64(bytes: Uint8Array): string {
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin);
}

/** TS 5.7+ 的 Uint8Array<ArrayBufferLike> 与 WebCrypto BufferSource 不兼容：
 * 拷贝出确定归属的 ArrayBuffer（运行时零风险，仅类型层收窄） */
function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

/** 取后端当次启动的 RSA 公钥（SPKI DER，Base64）；失败由调用方提示 */
export function getOpsPublicKey(): Promise<string> {
  return getJson<{publicKey: string}>('/api/ops/public-key').then((r) => r.publicKey);
}

/** RSA-OAEP(SHA-256) 加密密码，返回 Base64 密文（即 connect 请求的 encryptedPassword） */
export async function encryptPassword(publicKeySpkiBase64: string, password: string): Promise<string> {
  const key = await crypto.subtle.importKey(
    'spki', toArrayBuffer(b64ToBytes(publicKeySpkiBase64)),
    {name: 'RSA-OAEP', hash: 'SHA-256'}, false, ['encrypt']);
  const ct = await crypto.subtle.encrypt({name: 'RSA-OAEP'}, key, toArrayBuffer(new TextEncoder().encode(password)));
  return bytesToB64(new Uint8Array(ct));
}
