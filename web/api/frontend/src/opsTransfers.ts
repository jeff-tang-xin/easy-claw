// 运维文件传输任务管理：上传（XHR，upload.onprogress）/ 下载（fetch 流式读取，按
// content-length 计进度），任务状态驱动「文件任务中心」浮窗。
// 与 OpsFilePanel 配合：面板里的上传/下载全部经此模块，进度可见、失败可查。
import {useCallback, useRef, useState} from 'react';

export type TransferStatus = 'running' | 'done' | 'error';

export interface TransferTask {
  id: number;
  direction: 'upload' | 'download';
  name: string;
  size: number;
  loaded: number;
  status: TransferStatus;
  message?: string;
}

let seq = 0;

/** 浏览器落盘（下载完成） */
function saveBlob(blob: Blob, name: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = name;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export function useOpsTransfers() {
  const [tasks, setTasks] = useState<TransferTask[]>([]);
  const xhrsRef = useRef<Map<number, XMLHttpRequest>>(new Map());

  const patch = useCallback((id: number, p: Partial<TransferTask>) => {
    setTasks((prev) => prev.map((t) => (t.id === id ? {...t, ...p} : t)));
  }, []);

  const remove = useCallback((id: number) => {
    xhrsRef.current.get(id)?.abort();
    xhrsRef.current.delete(id);
    setTasks((prev) => prev.filter((t) => t.id !== id));
  }, []);

  /** 上传文件到远程目录（SFTP 端点；targetDir 空 = 家目录） */
  const upload = useCallback((workspaceId: string, connId: number, targetDir: string, file: File) => {
    const id = ++seq;
    setTasks((prev) => [...prev, {
      id, direction: 'upload', name: file.name, size: file.size,
      loaded: 0, status: 'running',
    }]);
    const qs = `workspaceId=${encodeURIComponent(workspaceId)}&connId=${connId}`
      + (targetDir ? `&targetDir=${encodeURIComponent(targetDir)}` : '');
    const fd = new FormData();
    fd.append('file', file);
    const xhr = new XMLHttpRequest();
    xhrsRef.current.set(id, xhr);
    xhr.open('POST', `/api/ops/upload?${qs}`);
    xhr.upload.onprogress = (e) => {
      if (e.lengthComputable) patch(id, {loaded: e.loaded});
    };
    xhr.onload = () => {
      xhrsRef.current.delete(id);
      if (xhr.status >= 200 && xhr.status < 300) {
        patch(id, {status: 'done', loaded: file.size});
      } else {
        patch(id, {status: 'error', message: xhr.responseText?.trim() || `HTTP ${xhr.status}`});
      }
    };
    xhr.onerror = () => {
      xhrsRef.current.delete(id);
      patch(id, {status: 'error', message: '网络错误'});
    };
    xhr.send(fd);
  }, [patch]);

  /** 下载远程文件（SFTP 流式端点；读流计进度，完成后浏览器落盘） */
  const download = useCallback(async (workspaceId: string, connId: number, path: string, name: string) => {
    const id = ++seq;
    setTasks((prev) => [...prev, {
      id, direction: 'download', name, size: 0, loaded: 0, status: 'running',
    }]);
    try {
      const qs = `workspaceId=${encodeURIComponent(workspaceId)}&connId=${connId}`
        + `&path=${encodeURIComponent(path)}`;
      const res = await fetch(`/api/ops/download?${qs}`);
      if (!res.ok) {
        const detail = (await res.text()).trim();
        patch(id, {status: 'error', message: detail || `HTTP ${res.status}`});
        return;
      }
      const total = Number(res.headers.get('content-length')) || 0;
      if (total) patch(id, {size: total});
      if (!res.body) {
        // 老浏览器兜底：整读 blob
        const blob = await res.blob();
        saveBlob(blob, name);
        patch(id, {status: 'done', size: blob.size, loaded: blob.size});
        return;
      }
      const reader = res.body.getReader();
      const chunks: Uint8Array[] = [];
      let received = 0;
      for (;;) {
        const {done, value} = await reader.read();
        if (done) break;
        if (value) {
          chunks.push(value);
          received += value.length;
          patch(id, {loaded: received, size: total || received});
        }
      }
      saveBlob(new Blob(chunks as BlobPart[]), name);
      patch(id, {status: 'done', size: received, loaded: received});
    } catch (e) {
      patch(id, {status: 'error', message: String(e)});
    }
  }, [patch]);

  return {tasks, upload, download, remove};
}
